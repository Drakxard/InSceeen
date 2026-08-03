from __future__ import annotations

import asyncio
import hashlib
import hmac
import ipaddress
import json
import logging
import secrets
import socket
import ssl
import sys
import tempfile
import threading
import urllib.parse
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from aiohttp import WSMsgType, web
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID
from PySide6.QtCore import QObject, Signal


PROTOCOL_VERSION = 1
PAIR_REQUEST_PORT = 8763
MAX_AUDIO_BYTES = 24 * 1024 * 1024
DISCOVERY_MAX_BYTES = 2048
LOGGER = logging.getLogger("inscreen")
MEDIA_STATES = {"playing", "paused", "none"}


def normalize_media_state(value: object) -> str:
    state = str(value or "").strip().lower()
    return state if state in MEDIA_STATES else "none"


def resource_dir() -> Path:
    if getattr(sys, "frozen", False) and hasattr(sys, "_MEIPASS"):
        return Path(sys._MEIPASS)
    return Path(__file__).resolve().parent


def detect_lan_ip(override: str | None = None) -> str:
    if override:
        ipaddress.ip_address(override)
        return override
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("1.1.1.1", 80))
        candidate = probe.getsockname()[0]
        if not candidate.startswith("127."):
            return candidate
    except OSError:
        pass
    finally:
        probe.close()
    candidate = socket.gethostbyname(socket.gethostname())
    if candidate.startswith("127."):
        raise RuntimeError("No se pudo detectar una dirección IPv4 de la red local.")
    return candidate


@dataclass
class CertificatePaths:
    ca_cert: Path
    server_cert: Path
    server_key: Path


def ensure_certificates(runtime_dir: Path, lan_ip: str) -> CertificatePaths:
    runtime_dir.mkdir(parents=True, exist_ok=True)
    ca_key_path = runtime_dir / "ca-key.pem"
    ca_cert_path = runtime_dir / "ca-cert.pem"
    server_key_path = runtime_dir / "server-key.pem"
    server_cert_path = runtime_dir / "server-cert.pem"

    if not ca_key_path.exists() or not ca_cert_path.exists():
        ca_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        subject = x509.Name(
            [
                x509.NameAttribute(NameOID.ORGANIZATION_NAME, "InScreen Local"),
                x509.NameAttribute(NameOID.COMMON_NAME, "InScreen Local CA"),
            ]
        )
        now = datetime.now(timezone.utc)
        ca_cert = (
            x509.CertificateBuilder()
            .subject_name(subject)
            .issuer_name(subject)
            .public_key(ca_key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - timedelta(days=1))
            .not_valid_after(now + timedelta(days=3650))
            .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
            .add_extension(
                x509.KeyUsage(
                    digital_signature=True,
                    content_commitment=False,
                    key_encipherment=False,
                    data_encipherment=False,
                    key_agreement=False,
                    key_cert_sign=True,
                    crl_sign=True,
                    encipher_only=False,
                    decipher_only=False,
                ),
                critical=True,
            )
            .sign(ca_key, hashes.SHA256())
        )
        ca_key_path.write_bytes(
            ca_key.private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption(),
            )
        )
        ca_cert_path.write_bytes(ca_cert.public_bytes(serialization.Encoding.PEM))

    regenerate_server = True
    if server_cert_path.exists() and server_key_path.exists():
        try:
            existing = x509.load_pem_x509_certificate(server_cert_path.read_bytes())
            sans = existing.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
            regenerate_server = (
                ipaddress.ip_address(lan_ip) not in sans.get_values_for_type(x509.IPAddress)
                or existing.not_valid_after_utc < datetime.now(timezone.utc) + timedelta(days=7)
            )
        except Exception:
            regenerate_server = True

    if regenerate_server:
        ca_key = serialization.load_pem_private_key(ca_key_path.read_bytes(), password=None)
        ca_cert = x509.load_pem_x509_certificate(ca_cert_path.read_bytes())
        server_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        now = datetime.now(timezone.utc)
        server_cert = (
            x509.CertificateBuilder()
            .subject_name(
                x509.Name(
                    [
                        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "InScreen Local"),
                        x509.NameAttribute(NameOID.COMMON_NAME, lan_ip),
                    ]
                )
            )
            .issuer_name(ca_cert.subject)
            .public_key(server_key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - timedelta(days=1))
            .not_valid_after(now + timedelta(days=825))
            .add_extension(
                x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address(lan_ip))]),
                critical=False,
            )
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .add_extension(
                x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]),
                critical=False,
            )
            .sign(ca_key, hashes.SHA256())
        )
        server_key_path.write_bytes(
            server_key.private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption(),
            )
        )
        server_cert_path.write_bytes(server_cert.public_bytes(serialization.Encoding.PEM))

    return CertificatePaths(ca_cert_path, server_cert_path, server_key_path)


def load_runtime_state(runtime_dir: Path) -> dict[str, Any]:
    state_path = runtime_dir / "state.json"
    if state_path.exists():
        try:
            state = json.loads(state_path.read_text(encoding="utf-8"))
            if isinstance(state, dict):
                return state
        except (OSError, json.JSONDecodeError):
            pass
    return {}


def save_runtime_state(runtime_dir: Path, state: dict[str, Any]) -> None:
    runtime_dir.mkdir(parents=True, exist_ok=True)
    state_path = runtime_dir / "state.json"
    temporary = state_path.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
    os_replace(temporary, state_path)


def os_replace(source: Path, destination: Path) -> None:
    source.replace(destination)


def load_or_create_token(runtime_dir: Path) -> str:
    state = load_runtime_state(runtime_dir)
    token = state.get("token")
    if isinstance(token, str) and len(token) >= 32:
        return token
    token = secrets.token_urlsafe(32)
    state["token"] = token
    state.setdefault("paired_once", False)
    save_runtime_state(runtime_dir, state)
    return token


def has_paired_once(runtime_dir: Path) -> bool:
    return load_runtime_state(runtime_dir).get("paired_once") is True


def mark_paired_once(runtime_dir: Path) -> None:
    state = load_runtime_state(runtime_dir)
    if state.get("paired_once") is True:
        return
    state["paired_once"] = True
    save_runtime_state(runtime_dir, state)


def discovery_proof(
    token: str,
    kind: str,
    nonce: str,
    ca_sha256: str,
    https_port: int | None = None,
) -> str:
    parts = [kind, str(PROTOCOL_VERSION), nonce, ca_sha256.lower()]
    if https_port is not None:
        parts.append(str(https_port))
    canonical = "|".join(parts)
    return hmac.new(token.encode("utf-8"), canonical.encode("ascii"), hashlib.sha256).hexdigest()


def valid_discovery_nonce(nonce: object) -> bool:
    if not isinstance(nonce, str) or len(nonce) != 32:
        return False
    try:
        return len(bytes.fromhex(nonce)) == 16
    except ValueError:
        return False


def build_discovery_response(
    token: str,
    nonce: str,
    ca_sha256: str,
    https_port: int,
) -> dict[str, Any]:
    return {
        "v": PROTOCOL_VERSION,
        "type": "discover.response",
        "nonce": nonce,
        "ca_sha256": ca_sha256,
        "https_port": https_port,
        "proof": discovery_proof(
            token,
            "discover.response",
            nonce,
            ca_sha256,
            https_port,
        ),
    }


def validate_discovery_request(
    message: object,
    token: str,
    ca_sha256: str,
) -> bool:
    if not isinstance(message, dict):
        return False
    nonce = message.get("nonce")
    fingerprint = message.get("ca_sha256")
    proof = message.get("proof")
    if (
        message.get("v") != PROTOCOL_VERSION
        or message.get("type") != "discover"
        or not valid_discovery_nonce(nonce)
        or not isinstance(fingerprint, str)
        or not isinstance(proof, str)
        or not secrets.compare_digest(fingerprint.lower(), ca_sha256.lower())
    ):
        return False
    expected = discovery_proof(token, "discover", nonce, fingerprint)
    return secrets.compare_digest(proof, expected)


class DiscoveryDatagramProtocol(asyncio.DatagramProtocol):
    def __init__(self, token: str, ca_sha256: str, https_port: int) -> None:
        self.token = token
        self.ca_sha256 = ca_sha256
        self.https_port = https_port
        self.transport: asyncio.DatagramTransport | None = None

    def connection_made(self, transport: asyncio.BaseTransport) -> None:
        self.transport = transport  # type: ignore[assignment]

    def datagram_received(self, data: bytes, addr: tuple[str, int]) -> None:
        if len(data) > DISCOVERY_MAX_BYTES:
            return
        try:
            message = json.loads(data.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return
        if not validate_discovery_request(message, self.token, self.ca_sha256):
            return
        response = build_discovery_response(
            self.token,
            message["nonce"],
            self.ca_sha256,
            self.https_port,
        )
        if self.transport:
            self.transport.sendto(
                json.dumps(response, separators=(",", ":")).encode("utf-8"),
                addr,
            )


def validate_pair_request(message: object, source_ip: str) -> bool:
    if not isinstance(message, dict):
        return False
    try:
        address = ipaddress.ip_address(source_ip)
    except ValueError:
        return False
    private_networks = (
        ipaddress.ip_network("10.0.0.0/8"),
        ipaddress.ip_network("172.16.0.0/12"),
        ipaddress.ip_network("192.168.0.0/16"),
    )
    return bool(
        address.version == 4
        and any(address in network for network in private_networks)
        and not address.is_loopback
        and message.get("v") == PROTOCOL_VERSION
        and message.get("type") == "pair.request"
        and valid_discovery_nonce(message.get("nonce"))
    )


class PairRequestDatagramProtocol(asyncio.DatagramProtocol):
    def __init__(self, callback: Any, clock: Any | None = None) -> None:
        self.callback = callback
        self.clock = clock or __import__("time").monotonic
        self.last_opened_at = float("-inf")

    def datagram_received(self, data: bytes, addr: tuple[str, int]) -> None:
        if len(data) > DISCOVERY_MAX_BYTES:
            return
        try:
            message = json.loads(data.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return
        if not validate_pair_request(message, addr[0]):
            return
        now = float(self.clock())
        if now - self.last_opened_at < 3.0:
            return
        self.last_opened_at = now
        self.callback(addr[0], message["nonce"])


def certificate_sha256(certificate_path: Path) -> str:
    certificate = x509.load_pem_x509_certificate(certificate_path.read_bytes())
    der = certificate.public_bytes(serialization.Encoding.DER)
    return hashlib.sha256(der).hexdigest()


def build_pair_uri(
    host: str,
    setup_port: int,
    https_port: int,
    token: str,
    ca_sha256: str,
) -> str:
    query = urllib.parse.urlencode(
        {
            "host": host,
            "setup_port": setup_port,
            "https_port": https_port,
            "token": token,
            "ca_sha256": ca_sha256,
        }
    )
    return f"inscreen://pair?{query}"


def normalize_active_subject(value: object) -> str:
    if not isinstance(value, str):
        return ""
    return " ".join(value.strip().split())[:180]


@dataclass
class AudioSession:
    session_id: str
    path: Path
    handle: Any
    mime_type: str = "audio/webm"
    received: set[int] = field(default_factory=set)
    byte_count: int = 0
    interrupted: bool = False
    active_subject: str = ""


class LocalMobileServer(QObject):
    connection_changed = Signal(bool, str)
    pairing_requested = Signal()
    recording_started = Signal(str)
    audio_completed = Signal(str, str, str, bool, str)
    recording_failed = Signal(str, str)
    media_state_changed = Signal(str)
    media_command_result = Signal(str, bool, str, str)
    server_error = Signal(str)

    def __init__(
        self,
        runtime_dir: Path,
        lan_host: str | None,
        setup_port: int,
        https_port: int,
        max_recording_seconds: int,
        apk_path: Path | None = None,
    ) -> None:
        super().__init__()
        self.runtime_dir = runtime_dir
        self.lan_ip = detect_lan_ip(lan_host)
        self.setup_port = setup_port
        self.https_port = https_port
        self.max_recording_seconds = max_recording_seconds
        self.paths = ensure_certificates(runtime_dir, self.lan_ip)
        self.token = load_or_create_token(runtime_dir)
        self._paired_once = has_paired_once(runtime_dir)
        self.setup_url = f"http://{self.lan_ip}:{self.setup_port}/?token={self.token}"
        self.ca_sha256 = certificate_sha256(self.paths.ca_cert)
        self.pair_uri = build_pair_uri(
            self.lan_ip,
            self.setup_port,
            self.https_port,
            self.token,
            self.ca_sha256,
        )
        self.apk_path = apk_path or (resource_dir() / "mobile" / "InScreenMic.apk")

        self._thread: threading.Thread | None = None
        self._loop: asyncio.AbstractEventLoop | None = None
        self._stop_event: asyncio.Event | None = None
        self._client: web.WebSocketResponse | None = None
        self._microphone_ready = False
        self._session: AudioSession | None = None
        self._pending_meta: dict[str, Any] | None = None
        self._completed_sessions: set[str] = set()
        self._active_subject = ""
        self._media_control_ready = False
        self._media_state = "none"
        self._state_lock = threading.RLock()

    @property
    def microphone_ready(self) -> bool:
        with self._state_lock:
            return bool(
                self._microphone_ready
                and self._client is not None
                and not self._client.closed
            )

    @property
    def paired_once(self) -> bool:
        with self._state_lock:
            return self._paired_once

    @property
    def media_control_ready(self) -> bool:
        with self._state_lock:
            return bool(
                self._media_control_ready
                and self._client is not None
                and not self._client.closed
            )

    @property
    def media_state(self) -> str:
        with self._state_lock:
            return self._media_state

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._thread = threading.Thread(target=self._thread_main, name="InScreenMobileServer", daemon=True)
        self._thread.start()
        LOGGER.info(
            "Servidor móvil: iniciando HTTP=%s:%s HTTPS=%s:%s",
            self.lan_ip,
            self.setup_port,
            self.lan_ip,
            self.https_port,
        )

    def stop(self) -> None:
        LOGGER.info("Servidor móvil: deteniendo")
        if self._loop and self._stop_event:
            self._loop.call_soon_threadsafe(self._stop_event.set)
        if self._thread:
            self._thread.join(timeout=4)
        self._close_session(delete=True)

    def start_recording(self, session_id: str) -> bool:
        if not self.microphone_ready:
            return False
        with self._state_lock:
            self._close_session(delete=True)
            audio_dir = Path(tempfile.gettempdir()) / "InScreenAudio"
            audio_dir.mkdir(parents=True, exist_ok=True)
            path = audio_dir / f"{session_id}.audio"
            handle = path.open("wb")
            self._session = AudioSession(
                session_id=session_id,
                path=path,
                handle=handle,
                active_subject=self._active_subject,
            )
        self._send(
            {
                "type": "recording.start",
                "session_id": session_id,
                "max_seconds": self.max_recording_seconds,
            }
        )
        LOGGER.info("Servidor móvil: sesión %s creada", session_id[:8])
        return True

    def stop_recording(self, session_id: str) -> None:
        LOGGER.info("Servidor móvil: solicitando fin de sesión %s", session_id[:8])
        self._send({"type": "recording.stop", "session_id": session_id})

    def cancel_recording(self, session_id: str) -> None:
        LOGGER.info("Servidor mÃ³vil: cancelando sesiÃ³n %s", session_id[:8])
        self._send({"type": "recording.cancel", "session_id": session_id})
        with self._state_lock:
            if self._session and self._session.session_id == session_id:
                self._close_session(delete=True)

    def send_media_command(self, action: str) -> str | None:
        if action not in {"toggle", "ensure_paused"} or not self.media_control_ready:
            return None
        command_id = uuid.uuid4().hex
        self._send(
            {
                "type": "media.command",
                "command_id": command_id,
                "action": action,
            }
        )
        return command_id

    def _send(self, payload: dict[str, Any]) -> None:
        if not self._loop:
            return
        message = {"v": PROTOCOL_VERSION, **payload}
        asyncio.run_coroutine_threadsafe(self._send_async(message), self._loop)

    async def _send_async(self, message: dict[str, Any]) -> None:
        client = self._client
        if client is not None and not client.closed:
            await client.send_json(message)

    def _thread_main(self) -> None:
        try:
            asyncio.run(self._serve())
        except Exception as exc:
            self.server_error.emit(f"No se pudo iniciar el servidor móvil: {exc}")

    async def _serve(self) -> None:
        self._loop = asyncio.get_running_loop()
        self._stop_event = asyncio.Event()

        setup_app = web.Application()
        setup_app.router.add_get("/", self._setup_page)
        setup_app.router.add_get("/ca.crt", self._ca_download)
        setup_app.router.add_get("/inscreen.apk", self._apk_download)
        setup_runner = web.AppRunner(setup_app)
        await setup_runner.setup()

        secure_app = web.Application()
        secure_app.router.add_get("/ws", self._websocket)
        secure_runner = web.AppRunner(secure_app)
        await secure_runner.setup()

        ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ssl_context.load_cert_chain(self.paths.server_cert, self.paths.server_key)
        setup_site = web.TCPSite(setup_runner, "0.0.0.0", self.setup_port)
        secure_site = web.TCPSite(secure_runner, "0.0.0.0", self.https_port, ssl_context=ssl_context)
        await setup_site.start()
        await secure_site.start()
        discovery_transport, _ = await self._loop.create_datagram_endpoint(
            lambda: DiscoveryDatagramProtocol(self.token, self.ca_sha256, self.https_port),
            local_addr=("0.0.0.0", self.setup_port),
            allow_broadcast=True,
        )
        pair_transport, _ = await self._loop.create_datagram_endpoint(
            lambda: PairRequestDatagramProtocol(
                lambda _host, _nonce: self.pairing_requested.emit()
            ),
            local_addr=("0.0.0.0", PAIR_REQUEST_PORT),
            allow_broadcast=True,
        )
        LOGGER.info("Servidor móvil: HTTP, HTTPS y descubrimiento UDP escuchando")

        try:
            await self._stop_event.wait()
        finally:
            client = self._client
            if client is not None and not client.closed:
                await client.close(code=1001, message=b"InScreen closing")
            discovery_transport.close()
            pair_transport.close()
            await secure_runner.cleanup()
            await setup_runner.cleanup()
            self._loop = None

    def _authorized(self, request: web.Request) -> bool:
        return secrets.compare_digest(request.query.get("token", ""), self.token)

    async def _setup_page(self, request: web.Request) -> web.Response:
        if not self._authorized(request):
            raise web.HTTPForbidden(text="Enlace de InScreen inválido.")
        apk_link = f"/inscreen.apk?token={urllib.parse.quote(self.token)}"
        download = (
            f'<a href="{apk_link}" download="InScreenMic.apk">1. DESCARGAR APK</a>'
            if self.apk_path.is_file()
            else '<p class="warning">El APK todavía no fue compilado en esta PC.</p>'
        )
        page = f"""<!doctype html>
<html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body{{background:#020402;color:#45ff1a;font:16px Consolas,monospace;margin:0;padding:24px;line-height:1.5}}
main{{max-width:620px;margin:auto;border:2px solid #45ff1a;padding:22px;background:#000}}
a{{display:block;margin:18px 0;padding:15px;border:1px solid #45ff1a;color:#45ff1a;text-align:center;text-decoration:none}}
code{{color:#fff}} li{{margin:.7em 0}} .warning{{color:#ffcf45;border:1px solid #ffcf45;padding:12px}}
</style><title>Configurar InScreen</title></head>
<body><main><h1>&gt;_ INSCREEN</h1>{download}</main></body></html>"""
        return web.Response(
            text=page,
            content_type="text/html",
            headers={"Cache-Control": "no-store"},
        )

    async def _ca_download(self, request: web.Request) -> web.Response:
        if not self._authorized(request):
            raise web.HTTPForbidden()
        cert = x509.load_pem_x509_certificate(self.paths.ca_cert.read_bytes())
        der = cert.public_bytes(serialization.Encoding.DER)
        return web.Response(
            body=der,
            content_type="application/x-x509-ca-cert",
            headers={
                "Content-Disposition": 'attachment; filename="inscreen-ca.crt"',
                "Cache-Control": "no-store",
            },
        )

    async def _apk_download(self, request: web.Request) -> web.Response:
        if not self._authorized(request):
            raise web.HTTPForbidden(text="Enlace de InScreen inválido.")
        if not self.apk_path.is_file():
            raise web.HTTPNotFound(text="El APK de InScreen todavía no fue compilado.")
        return web.FileResponse(
            self.apk_path,
            headers={
                "Content-Type": "application/vnd.android.package-archive",
                "Content-Disposition": 'attachment; filename="InScreenMic.apk"',
                "Cache-Control": "no-store",
            },
        )

    async def _websocket(self, request: web.Request) -> web.StreamResponse:
        if not self._authorized(request):
            raise web.HTTPForbidden(text="Token inválido.")
        if self._client is not None and not self._client.closed:
            raise web.HTTPConflict(text="Ya hay un celular conectado.")

        ws = web.WebSocketResponse(heartbeat=15)
        await ws.prepare(request)
        LOGGER.info("Celular: WebSocket conectado desde %s", request.remote)
        self._client = ws
        self._microphone_ready = False
        self._media_control_ready = False
        self._media_state = "none"
        self.connection_changed.emit(False, "Aplicación Android conectada; verificando permisos.")

        try:
            async for message in ws:
                if message.type == WSMsgType.TEXT:
                    await self._handle_json(ws, message.data)
                elif message.type == WSMsgType.BINARY:
                    await self._handle_binary(ws, message.data)
                elif message.type == WSMsgType.ERROR:
                    break
        finally:
            if self._client is ws:
                self._client = None
                self._microphone_ready = False
                self._media_control_ready = False
                self._media_state = "none"
                if self._session:
                    self._session.interrupted = True
                self.connection_changed.emit(False, "El celular se desconectó.")
                LOGGER.warning("Celular: WebSocket desconectado")
        return ws

    async def _handle_json(self, ws: web.WebSocketResponse, raw: str) -> None:
        try:
            message = json.loads(raw)
        except json.JSONDecodeError:
            await self._error(ws, None, "invalid_json", "Mensaje JSON inválido.")
            return
        if message.get("v") != PROTOCOL_VERSION:
            await self._error(ws, message.get("session_id"), "protocol", "Versión de protocolo incompatible.")
            return

        kind = message.get("type")
        if kind == "ready":
            is_android = message.get("client") == "android"
            self._microphone_ready = bool(message.get("microphone_ready")) and is_android
            self._media_control_ready = bool(message.get("media_control_ready")) and is_android
            self._media_state = normalize_media_state(message.get("media_state"))
            self._active_subject = normalize_active_subject(message.get("active_subject"))
            if self._microphone_ready and not self._paired_once:
                mark_paired_once(self.runtime_dir)
                with self._state_lock:
                    self._paired_once = True
            detail = "Celular listo; micrófono cerrado." if self._microphone_ready else "Abre la app Android y concede los permisos."
            self.connection_changed.emit(self._microphone_ready, detail)
            LOGGER.info(
                "Celular: ready cliente=%s versión=%s preparado=%s grabando=%s",
                message.get("client"),
                message.get("client_version"),
                self._microphone_ready,
                bool(message.get("recording")),
            )
        elif kind == "media.state":
            state = normalize_media_state(message.get("state"))
            self._media_state = state
            self.media_state_changed.emit(state)
        elif kind == "media.result":
            command_id = str(message.get("command_id") or "")
            if not command_id:
                return
            state = normalize_media_state(message.get("state"))
            self._media_state = state
            self.media_command_result.emit(
                command_id,
                bool(message.get("ok")),
                state,
                str(message.get("error") or ""),
            )
        elif kind == "recording.started":
            session = self._session
            if session and message.get("session_id") == session.session_id:
                session.mime_type = str(message.get("mime_type") or "audio/webm")
                supplied_subject = normalize_active_subject(message.get("active_subject"))
                if supplied_subject:
                    session.active_subject = supplied_subject
                self.recording_started.emit(session.session_id)
                LOGGER.info(
                    "Celular: grabación iniciada sesión=%s mime=%s",
                    session.session_id[:8],
                    session.mime_type,
                )
        elif kind == "queue.state":
            self._active_subject = normalize_active_subject(message.get("active_subject"))
        elif kind == "audio.chunk":
            session = self._session
            sequence = message.get("sequence")
            if not session or message.get("session_id") != session.session_id or not isinstance(sequence, int):
                await self._error(ws, message.get("session_id"), "session", "Sesión de audio desconocida.")
                return
            self._pending_meta = message
        elif kind == "audio.complete":
            await self._complete_audio(ws, message)
        elif kind == "error":
            detail = str(message.get("message") or "Error informado por el celular.")
            session_id = str(message.get("session_id") or "")
            session = self._session
            if session and session_id == session.session_id:
                self._close_session(delete=True)
                self.recording_failed.emit(session_id, detail)
            else:
                self.server_error.emit(detail)

    async def _handle_binary(self, ws: web.WebSocketResponse, data: bytes) -> None:
        meta = self._pending_meta
        self._pending_meta = None
        session = self._session
        if not meta or not session or meta.get("session_id") != session.session_id:
            await self._error(ws, None, "chunk_without_metadata", "Fragmento de audio sin metadatos.")
            return
        sequence = int(meta["sequence"])
        if sequence not in session.received:
            if session.byte_count + len(data) > MAX_AUDIO_BYTES:
                await self._error(ws, session.session_id, "audio_too_large", "El audio superó el límite de 24 MB.")
                self._close_session(delete=True)
                return
            session.handle.write(data)
            session.handle.flush()
            session.received.add(sequence)
            session.byte_count += len(data)
        await ws.send_json(
            {
                "v": PROTOCOL_VERSION,
                "type": "audio.ack",
                "session_id": session.session_id,
                "sequence": sequence,
            }
        )

    async def _complete_audio(self, ws: web.WebSocketResponse, message: dict[str, Any]) -> None:
        session = self._session
        requested_id = str(message.get("session_id") or "")
        if requested_id in self._completed_sessions:
            await self._send_complete_ack(ws, requested_id)
            return
        if not session or requested_id != session.session_id:
            return
        session.interrupted = session.interrupted or bool(message.get("interrupted"))
        session.handle.close()
        path = session.path
        mime_type = session.mime_type
        interrupted = session.interrupted
        active_subject = session.active_subject
        session_id = session.session_id
        byte_count = session.byte_count
        self._session = None
        if byte_count <= 0:
            path.unlink(missing_ok=True)
            await self._error(ws, session_id, "empty_audio", "No se recibió audio.")
            return
        self._completed_sessions.add(session_id)
        if len(self._completed_sessions) > 20:
            self._completed_sessions.pop()
        self.audio_completed.emit(session_id, str(path), mime_type, interrupted, active_subject)
        LOGGER.info(
            "Celular: audio completo sesión=%s bytes=%s fragmentos=%s interrumpido=%s",
            session_id[:8],
            byte_count,
            len(session.received),
            interrupted,
        )
        await self._send_complete_ack(ws, session_id)

    @staticmethod
    async def _send_complete_ack(ws: web.WebSocketResponse, session_id: str) -> None:
        await ws.send_json(
            {
                "v": PROTOCOL_VERSION,
                "type": "audio.complete_ack",
                "session_id": session_id,
            }
        )

    async def _error(
        self,
        ws: web.WebSocketResponse,
        session_id: str | None,
        code: str,
        message: str,
    ) -> None:
        await ws.send_json(
            {
                "v": PROTOCOL_VERSION,
                "type": "error",
                "session_id": session_id,
                "code": code,
                "message": message,
            }
        )
        self.server_error.emit(message)

    def _close_session(self, delete: bool) -> None:
        with self._state_lock:
            session = self._session
            self._session = None
        if not session:
            return
        try:
            if not session.handle.closed:
                session.handle.close()
        finally:
            if delete:
                session.path.unlink(missing_ok=True)
