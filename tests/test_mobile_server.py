import asyncio
import hashlib
import ipaddress
import json
import tempfile
import unittest
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from cryptography import x509
from cryptography.hazmat.primitives import serialization
from aiohttp import web
from aiohttp.test_utils import make_mocked_request

from mobile_server import (
    AudioSession,
    PairRequestDatagramProtocol,
    LocalMobileServer,
    build_pair_uri,
    build_discovery_response,
    certificate_sha256,
    discovery_proof,
    ensure_certificates,
    has_paired_once,
    load_or_create_token,
    mark_paired_once,
    normalize_media_state,
    validate_pair_request,
    validate_discovery_request,
)


class MobileServerTests(unittest.TestCase):
    def test_media_state_normalization_is_closed(self) -> None:
        self.assertEqual(normalize_media_state("PLAYING"), "playing")
        self.assertEqual(normalize_media_state("paused"), "paused")
        self.assertEqual(normalize_media_state("stopped"), "none")
        self.assertEqual(normalize_media_state(None), "none")

    def test_ready_negotiates_optional_media_capability(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            server = LocalMobileServer(Path(directory), "192.168.1.8", 8764, 8765, 300)
            asyncio.run(server._handle_json(object(), json.dumps({
                "v": 1,
                "type": "ready",
                "client": "android",
                "microphone_ready": True,
                "media_control_ready": True,
                "media_state": "playing",
            })))
            self.assertTrue(server._media_control_ready)
            self.assertEqual(server._media_state, "playing")

    def test_media_messages_emit_state_and_command_result(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            server = LocalMobileServer(Path(directory), "192.168.1.8", 8764, 8765, 300)
            states = []
            results = []
            server.media_state_changed.connect(states.append)
            server.media_command_result.connect(
                lambda command_id, ok, state, error: results.append((command_id, ok, state, error))
            )
            asyncio.run(server._handle_json(object(), json.dumps({
                "v": 1, "type": "media.state", "state": "paused",
            })))
            asyncio.run(server._handle_json(object(), json.dumps({
                "v": 1, "type": "media.result", "command_id": "abc",
                "ok": False, "state": "none", "error": "sin sesión",
            })))
            self.assertEqual(states, ["paused"])
            self.assertEqual(results, [("abc", False, "none", "sin sesión")])

    def test_token_is_random_and_persistent(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            runtime = Path(directory)
            first = load_or_create_token(runtime)
            second = load_or_create_token(runtime)
            self.assertEqual(first, second)
            self.assertGreaterEqual(len(first), 32)
            state = json.loads((runtime / "state.json").read_text(encoding="utf-8"))
            self.assertEqual(state["token"], first)
            self.assertFalse(has_paired_once(runtime))
            mark_paired_once(runtime)
            self.assertTrue(has_paired_once(runtime))
            self.assertEqual(load_or_create_token(runtime), first)

    def test_discovery_hmac_matches_android_vector(self) -> None:
        token = "test-token-abcdefghijklmnopqrstuvwxyz012345"
        nonce = "00112233445566778899aabbccddeeff"
        fingerprint = "ab" * 32
        self.assertEqual(
            discovery_proof(token, "discover", nonce, fingerprint),
            "4b4fe73eda090bbd4af911007d61d43fba662a70e308a14f9ac90364dfc4f597",
        )
        self.assertEqual(
            discovery_proof(token, "discover.response", nonce, fingerprint, 8765),
            "44730c3a84ae7b4a6a7a30da153651f7fdb9b6a000af139505bb91d70afcac80",
        )

    def test_discovery_rejects_invalid_identity_and_proof(self) -> None:
        token = "t" * 43
        nonce = "00112233445566778899aabbccddeeff"
        fingerprint = "ab" * 32
        request = {
            "v": 1,
            "type": "discover",
            "nonce": nonce,
            "ca_sha256": fingerprint,
            "proof": discovery_proof(token, "discover", nonce, fingerprint),
        }
        self.assertTrue(validate_discovery_request(request, token, fingerprint))
        self.assertFalse(validate_discovery_request({**request, "proof": "0" * 64}, token, fingerprint))
        self.assertFalse(validate_discovery_request(request, token, "cd" * 32))
        response = build_discovery_response(token, nonce, fingerprint, 8765)
        self.assertEqual(response["https_port"], 8765)
        self.assertEqual(response["nonce"], nonce)

    def test_certificate_contains_lan_ip_and_reuses_ca(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            runtime = Path(directory)
            first = ensure_certificates(runtime, "192.168.50.10")
            ca_before = first.ca_cert.read_bytes()
            first_leaf = x509.load_pem_x509_certificate(first.server_cert.read_bytes())
            first_sans = first_leaf.extensions.get_extension_for_class(
                x509.SubjectAlternativeName
            ).value
            self.assertIn(
                ipaddress.ip_address("192.168.50.10"),
                first_sans.get_values_for_type(x509.IPAddress),
            )

            second = ensure_certificates(runtime, "192.168.50.11")
            self.assertEqual(ca_before, second.ca_cert.read_bytes())
            second_leaf = x509.load_pem_x509_certificate(second.server_cert.read_bytes())
            second_sans = second_leaf.extensions.get_extension_for_class(
                x509.SubjectAlternativeName
            ).value
            self.assertIn(
                ipaddress.ip_address("192.168.50.11"),
                second_sans.get_values_for_type(x509.IPAddress),
            )

    def test_pair_uri_contains_connection_and_ca_fingerprint(self) -> None:
        token = "a" * 43
        fingerprint = "bc" * 32
        uri = build_pair_uri("192.168.1.8", 8764, 8765, token, fingerprint)
        parsed = urlparse(uri)
        values = parse_qs(parsed.query)
        self.assertEqual(parsed.scheme, "inscreen")
        self.assertEqual(parsed.netloc, "pair")
        self.assertEqual(values["host"], ["192.168.1.8"])
        self.assertEqual(values["setup_port"], ["8764"])
        self.assertEqual(values["https_port"], ["8765"])
        self.assertEqual(values["token"], [token])
        self.assertEqual(values["ca_sha256"], [fingerprint])

    def test_ca_fingerprint_matches_downloaded_der(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            paths = ensure_certificates(Path(directory), "192.168.1.8")
            certificate = x509.load_pem_x509_certificate(paths.ca_cert.read_bytes())
            der = certificate.public_bytes(serialization.Encoding.DER)
            self.assertEqual(certificate_sha256(paths.ca_cert), hashlib.sha256(der).hexdigest())

    def test_setup_page_only_serves_apk(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "InScreenMic.apk"
            apk.write_bytes(b"fake-apk")
            server = LocalMobileServer(root / "runtime", "192.168.1.8", 8764, 8765, 300, apk)
            request = make_mocked_request("GET", f"/?token={server.token}")
            response = asyncio.run(server._setup_page(request))
            self.assertIn("DESCARGAR APK", response.text)
            self.assertNotIn("VINCULAR CON ESTA PC", response.text)
            self.assertNotIn("inscreen://pair", response.text)

            apk_request = make_mocked_request("GET", f"/inscreen.apk?token={server.token}")
            apk_response = asyncio.run(server._apk_download(apk_request))
            self.assertEqual(apk_response.headers["Content-Type"], "application/vnd.android.package-archive")

    def test_pair_request_requires_rfc1918_source_and_rate_limits(self) -> None:
        nonce = "00112233445566778899aabbccddeeff"
        message = {"v": 1, "type": "pair.request", "nonce": nonce}
        self.assertTrue(validate_pair_request(message, "192.168.1.20"))
        self.assertTrue(validate_pair_request(message, "172.16.4.2"))
        self.assertFalse(validate_pair_request(message, "8.8.8.8"))
        self.assertFalse(validate_pair_request(message, "169.254.1.2"))
        self.assertFalse(validate_pair_request({**message, "nonce": "bad"}, "10.0.0.2"))

        calls = []
        times = iter((10.0, 11.0, 13.1))
        protocol = PairRequestDatagramProtocol(
            lambda host, received: calls.append((host, received)),
            clock=lambda: next(times),
        )
        raw = json.dumps(message).encode()
        protocol.datagram_received(raw, ("192.168.1.20", 4444))
        protocol.datagram_received(raw, ("192.168.1.20", 4444))
        protocol.datagram_received(raw, ("192.168.1.20", 4444))
        self.assertEqual(calls, [("192.168.1.20", nonce), ("192.168.1.20", nonce)])

    def test_apk_download_rejects_invalid_token(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "InScreenMic.apk"
            apk.write_bytes(b"fake-apk")
            server = LocalMobileServer(root / "runtime", "192.168.1.8", 8764, 8765, 300, apk)
            request = make_mocked_request("GET", "/inscreen.apk?token=invalid")
            with self.assertRaises(web.HTTPForbidden):
                asyncio.run(server._apk_download(request))

    def test_ready_only_accepts_native_android_client(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            server = LocalMobileServer(Path(directory), "192.168.1.8", 8764, 8765, 300)
            asyncio.run(
                server._handle_json(
                    object(),
                    json.dumps({"v": 1, "type": "ready", "microphone_ready": True}),
                )
            )
            self.assertFalse(server._microphone_ready)
            asyncio.run(
                server._handle_json(
                    object(),
                    json.dumps(
                        {
                            "v": 1,
                            "type": "ready",
                            "client": "android",
                            "client_version": "1.0.0",
                            "microphone_ready": True,
                            "recording": False,
                        }
                    ),
                )
            )
            self.assertTrue(server._microphone_ready)

    def test_client_recording_error_closes_session_and_emits_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            server = LocalMobileServer(root / "runtime", "192.168.1.8", 8764, 8765, 300)
            audio_path = root / "session.audio"
            session = AudioSession("session-1", audio_path, audio_path.open("wb"))
            server._session = session
            failures = []
            server.recording_failed.connect(lambda session_id, detail: failures.append((session_id, detail)))
            asyncio.run(
                server._handle_json(
                    object(),
                    json.dumps(
                        {
                            "v": 1,
                            "type": "error",
                            "session_id": "session-1",
                            "code": "microphone_failed",
                            "message": "sin micrófono",
                        }
                    ),
                )
            )
            self.assertEqual(failures, [("session-1", "sin micrófono")])
            self.assertIsNone(server._session)
            self.assertFalse(audio_path.exists())

    def test_recording_keeps_subject_when_queue_changes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            server = LocalMobileServer(root / "runtime", "192.168.1.8", 8764, 8765, 300)
            server._active_subject = "Física"
            audio_path = root / "session.audio"
            server._session = AudioSession(
                "session-1",
                audio_path,
                audio_path.open("wb"),
                active_subject=server._active_subject,
            )
            asyncio.run(
                server._handle_json(
                    object(),
                    json.dumps(
                        {"v": 1, "type": "queue.state", "active_subject": "Álgebra"}
                    ),
                )
            )
            self.assertEqual(server._active_subject, "Álgebra")
            self.assertEqual(server._session.active_subject, "Física")
            server._close_session(delete=True)

    def test_recording_started_ack_includes_subject(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            server = LocalMobileServer(root / "runtime", "192.168.1.8", 8764, 8765, 300)
            audio_path = root / "session.audio"
            server._session = AudioSession("session-1", audio_path, audio_path.open("wb"))
            acknowledgements = []
            server.recording_started.connect(acknowledgements.append)
            asyncio.run(
                server._handle_json(
                    object(),
                    json.dumps(
                        {
                            "v": 1,
                            "type": "recording.started",
                            "session_id": "session-1",
                            "mime_type": "audio/mp4",
                            "active_subject": "  Física 1  ",
                        }
                    ),
                )
            )
            self.assertEqual(acknowledgements, ["session-1"])
            self.assertEqual(server._session.active_subject, "Física 1")
            server._close_session(delete=True)


if __name__ == "__main__":
    unittest.main()
