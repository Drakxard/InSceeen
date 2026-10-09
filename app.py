from __future__ import annotations

import base64
import asyncio
import ctypes
import io
import json
import logging
import math
import os
import re
import shutil
import signal
import sys
import threading
import time
import uuid
from dataclasses import dataclass, replace
from datetime import datetime
from logging.handlers import RotatingFileHandler
from pathlib import Path

from dotenv import dotenv_values, load_dotenv
from openai import OpenAI, RateLimitError
from PIL import Image, ImageGrab
from pynput import keyboard
from PySide6.QtCore import (
    QEvent,
    QEventLoop,
    QObject,
    QRect,
    Qt,
    QThread,
    QTimer,
    QUrl,
    Signal,
    Slot,
)
from PySide6.QtGui import (
    QAction,
    QColor,
    QFont,
    QFontMetrics,
    QGuiApplication,
    QImage,
    QIcon,
    QPixmap,
    QRegion,
)
from PySide6.QtWidgets import (
    QApplication,
    QDialog,
    QComboBox,
    QFormLayout,
    QFrame,
    QHBoxLayout,
    QLabel,
    QPlainTextEdit,
    QPushButton,
    QMenu,
    QStackedWidget,
    QSystemTrayIcon,
    QVBoxLayout,
    QWidget,
)
from PySide6.QtWebChannel import QWebChannel
from PySide6.QtWebEngineCore import QWebEnginePage, QWebEngineProfile, QWebEngineSettings
from PySide6.QtWebEngineWidgets import QWebEngineView
import qrcode

from mobile_server import LocalMobileServer


if getattr(sys, "frozen", False):
    APP_DIR = Path(sys.executable).resolve().parent
    RESOURCE_DIR = Path(getattr(sys, "_MEIPASS", APP_DIR))
else:
    APP_DIR = Path(__file__).resolve().parent
    RESOURCE_DIR = APP_DIR

GROQ_BASE_URL = "https://api.groq.com/openai/v1"
QWEN_MODEL = "qwen/qwen3.8-27b"
TEXT_MODEL = "qwen/qwen3.8-27b"
MAX_IMAGE_BYTES = 20 * 1024 * 1024
DWMWA_EXTENDED_FRAME_BOUNDS = 9
RATE_LIMIT_MARKER = "__INSCREEN_RATE_LIMIT__"
LOGGER = logging.getLogger("inscreen")
MODEL_FIELDS = {"vision_model": "Imagen / OCR", "text_model": "Texto", "audio_model": "Transcripción de audio"}
MODEL_FAILURE_MARKER = "__INSCREEN_MODEL__:"


def is_model_failure(exc: Exception) -> bool:
    status = getattr(exc, "status_code", None)
    if status in (401, 429) or (isinstance(status, int) and status >= 500):
        return False
    body = getattr(exc, "body", {})
    if isinstance(body, dict):
        error = body.get("error", body)
        code = str(error.get("code", "")) if isinstance(error, dict) else ""
        if code in {"model_decommissioned", "model_not_found", "model_not_available", "model_permission_denied", "model_access_denied", "model_permission_blocked_org", "model_permission_blocked_project"}:
            return True
    message = str(exc).lower()
    return status in (400, 403, 404) and "model" in message and any(
        phrase in message for phrase in ("decommissioned", "deprecated", "does not exist", "not found", "do not have access", "does not have access", "no access", "not permitted", "access denied")
    )


def encode_model_failure(exc: Exception, role: str, model: str) -> str:
    if not is_model_failure(exc):
        return str(exc)
    return MODEL_FAILURE_MARKER + json.dumps({"role": role, "model": model, "detail": str(exc)})


def decode_model_failure(content: str) -> dict | None:
    if not content.startswith(MODEL_FAILURE_MARKER):
        return None
    try:
        value = json.loads(content[len(MODEL_FAILURE_MARKER):])
        if isinstance(value, dict) and all(isinstance(value.get(key), str) for key in ("role", "model", "detail")):
            return value
    except (ValueError, TypeError):
        pass
    return None
RUNTIME_IDENTITY_FILES = (
    "state.json",
    "ca-key.pem",
    "ca-cert.pem",
    "server-key.pem",
    "server-cert.pem",
)


class SingleInstanceGuard:
    ERROR_ALREADY_EXISTS = 183

    def __init__(self, name: str = r"Local\InScreen.Application.Singleton") -> None:
        self.name = name
        self.handle: int | None = None

    def acquire(self) -> bool:
        if sys.platform != "win32":
            return True
        kernel32 = ctypes.windll.kernel32
        create_mutex = kernel32.CreateMutexW
        create_mutex.argtypes = [ctypes.c_void_p, ctypes.c_bool, ctypes.c_wchar_p]
        create_mutex.restype = ctypes.c_void_p
        close_handle = kernel32.CloseHandle
        close_handle.argtypes = [ctypes.c_void_p]
        close_handle.restype = ctypes.c_bool

        handle = create_mutex(None, False, self.name)
        if not handle:
            raise ctypes.WinError()
        if kernel32.GetLastError() == self.ERROR_ALREADY_EXISTS:
            close_handle(handle)
            return False
        self.handle = int(handle)
        return True

    def release(self) -> None:
        if sys.platform == "win32" and self.handle:
            ctypes.windll.kernel32.CloseHandle(ctypes.c_void_p(self.handle))
        self.handle = None


DEFAULT_PROMPT = """Responde en español de forma breve y directa.
Usa el material capturado como contexto principal para contestar la pregunta del usuario.
No repitas la pregunta, no describas tu proceso y no agregues encabezados.
Si la pregunta pide una explicación, incluye solamente lo necesario.
Si el material no alcanza para responder, indícalo claramente y no inventes.
Mantén el texto normal breve y utiliza notación matemática solo cuando aporte claridad.""".strip()


LATEX_OUTPUT_CONTRACT = r"""CONTRATO DE SALIDA OBLIGATORIO (tiene prioridad sobre cualquier instrucción anterior):
- Devuelve texto breve y fórmulas compatibles con KaTeX.
- Encierra cada fórmula en línea entre \( y \).
- Encierra cada fórmula centrada entre \[ y \].
- Puedes usar **texto** para negrita y *texto* para cursiva.
- No uses signos $, HTML, bloques de código, otro Markdown, paquetes, macros personalizadas ni documentos LaTeX completos.
- Fuera de esos delimitadores escribe solamente texto normal.""".strip()


@dataclass
class AppConfig:
    vision_model: str = QWEN_MODEL
    text_model: str = TEXT_MODEL
    audio_model: str = "whisper-large-v3-turbo"
    hotkey: str = "-"
    language: str = "es"
    font_size: int = 18
    prompt: str | None = None
    setup_port: int = 8764
    https_port: int = 8765
    lan_host: str | None = None
    max_recording_seconds: int = 300
    vision_wait_seconds: float = 8.0
    pause_media_during_recording: bool = True
    overlay_width_ratio: float = 0.76
    send_image: bool = True


def load_config() -> AppConfig:
    config = AppConfig()
    path = APP_DIR / "config.json"
    if not path.exists():
        return config
    with path.open("r", encoding="utf-8") as handle:
        raw = json.load(handle)
    if not isinstance(raw, dict):
        return config
    for field_name in config.__dataclass_fields__:
        if field_name in raw:
            setattr(config, field_name, raw[field_name])
    config.hotkey = "-"
    config.max_recording_seconds = max(5, int(config.max_recording_seconds))
    config.vision_wait_seconds = min(30.0, max(2.0, float(config.vision_wait_seconds)))
    config.overlay_width_ratio = min(0.95, max(0.35, float(config.overlay_width_ratio)))
    config.send_image = bool(config.send_image)
    for field in MODEL_FIELDS:
        value = getattr(config, field)
        setattr(config, field, value.strip() if isinstance(value, str) and value.strip()
                else getattr(AppConfig(), field))
    return config


def save_send_image_preference(enabled: bool, path: Path | None = None) -> None:
    save_preferences({"send_image": bool(enabled)}, path)


def save_preferences(values: dict, path: Path | None = None) -> None:
    target = path or (APP_DIR / "config.json")
    raw: dict = {}
    if target.exists():
        with target.open("r", encoding="utf-8") as handle:
            loaded = json.load(handle)
        if isinstance(loaded, dict):
            raw = loaded
    raw.update(values)
    temporary = target.with_suffix(target.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8") as handle:
        json.dump(raw, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    os.replace(temporary, target)


def resolve_runtime_dir(
    app_dir: Path = APP_DIR,
    local_app_data: str | None = None,
) -> Path:
    local_root = local_app_data if local_app_data is not None else os.environ.get("LOCALAPPDATA")
    if not local_root:
        return app_dir / "runtime"
    target = Path(local_root) / "InScreen" / "runtime"
    target.mkdir(parents=True, exist_ok=True)
    legacy = app_dir / "runtime"
    identity_exists = any((target / name).exists() for name in RUNTIME_IDENTITY_FILES[:3])
    if legacy != target and legacy.is_dir() and not identity_exists:
        for name in RUNTIME_IDENTITY_FILES:
            source = legacy / name
            destination = target / name
            if source.is_file() and not destination.exists():
                shutil.copy2(source, destination)
        LOGGER.info("Runtime: identidad anterior migrada desde %s", legacy)
    return target


def model_request_options(model: str, role: str) -> dict:
    lowered = model.lower()
    if role == "multimodal" and lowered.startswith("qwen/qwen3"):
        return {
            "reasoning_effort": "default",
            "extra_body": {"reasoning_format": "hidden"},
        }
    if role == "vision" and lowered.startswith("qwen/qwen3"):
        return {"reasoning_effort": "none"}
    if role == "text" and lowered.startswith("qwen/qwen3"):
        return {"reasoning_effort": "none"}
    return {}


def rate_limit_delay_seconds(exc: Exception, default: int = 5) -> int:
    response = getattr(exc, "response", None)
    headers = getattr(response, "headers", None)
    if headers:
        raw_header = headers.get("retry-after") or headers.get("Retry-After")
        try:
            return max(1, min(60, math.ceil(float(raw_header))))
        except (TypeError, ValueError):
            pass
    match = re.search(
        r"(?:try again in|retry after)\s+([0-9]+(?:\.[0-9]+)?)\s*(ms|s)?",
        str(exc),
        flags=re.IGNORECASE,
    )
    if not match:
        return default
    value = float(match.group(1))
    if (match.group(2) or "s").lower() == "ms":
        value /= 1000.0
    return max(1, min(60, math.ceil(value)))


def is_rate_limit_error(exc: Exception) -> bool:
    return isinstance(exc, RateLimitError) or getattr(exc, "status_code", None) == 429


def encode_rate_limit_failure(delay: int) -> str:
    return f"{RATE_LIMIT_MARKER}:{max(1, int(delay))}"


def decode_rate_limit_failure(value: str) -> int | None:
    if not value.startswith(f"{RATE_LIMIT_MARKER}:"):
        return None
    try:
        return max(1, int(value.rsplit(":", 1)[1]))
    except (TypeError, ValueError):
        return 1


class RequestCancelled(RuntimeError):
    pass


class RateLimitExhausted(RuntimeError):
    def __init__(self, delay: int) -> None:
        super().__init__(f"Límite de solicitudes; vuelve a intentar en {delay} segundos.")
        self.delay = delay


def run_with_rate_limit_retry(operation, cancelled: threading.Event, countdown) -> object:
    for attempt in range(2):
        if cancelled.is_set():
            raise RequestCancelled("Cancelado")
        try:
            return operation()
        except Exception as exc:
            if not is_rate_limit_error(exc):
                raise
            delay = rate_limit_delay_seconds(exc)
            if attempt == 1:
                raise RateLimitExhausted(delay) from exc
            deadline = time.monotonic() + delay
            last_remaining = None
            while True:
                remaining = math.ceil(deadline - time.monotonic())
                if remaining <= 0:
                    break
                if remaining != last_remaining:
                    countdown(remaining)
                    last_remaining = remaining
                if cancelled.wait(min(0.1, max(0.0, deadline - time.monotonic()))):
                    raise RequestCancelled("Cancelado")
            countdown(0)
    raise AssertionError("El bucle de reintento terminó sin resultado")


def setup_logging() -> None:
    runtime_dir = resolve_runtime_dir()
    runtime_dir.mkdir(parents=True, exist_ok=True)
    log_path = runtime_dir / "inscreen.log"
    legacy_info_entries = False
    if log_path.is_file():
        try:
            legacy_info_entries = " INFO " in log_path.read_text(
                encoding="utf-8", errors="replace"
            )
        except OSError:
            pass
    handler = RotatingFileHandler(
        log_path,
        maxBytes=1_000_000,
        backupCount=2,
        encoding="utf-8",
    )
    handler.setFormatter(
        logging.Formatter("%(asctime)s %(levelname)s %(threadName)s %(message)s")
    )
    handler.setLevel(logging.WARNING)
    if legacy_info_entries:
        handler.doRollover()
    LOGGER.setLevel(logging.INFO)
    LOGGER.handlers.clear()
    LOGGER.addHandler(handler)
    LOGGER.propagate = False


def install_exception_logging() -> None:
    def log_unhandled(exc_type, exc_value, exc_traceback) -> None:
        if issubclass(exc_type, KeyboardInterrupt):
            return
        LOGGER.critical(
            "Excepción no controlada",
            exc_info=(exc_type, exc_value, exc_traceback),
        )

    def log_thread_unhandled(args) -> None:
        LOGGER.critical(
            "Excepción no controlada en hilo %s",
            getattr(args.thread, "name", "desconocido"),
            exc_info=(args.exc_type, args.exc_value, args.exc_traceback),
        )

    sys.excepthook = log_unhandled
    threading.excepthook = log_thread_unhandled


def load_api_key() -> str | None:
    env_path = APP_DIR / ".env"
    env_values = dotenv_values(env_path) if env_path.exists() else {}
    load_dotenv(env_path, override=True)
    key = os.getenv("GROQ_API_KEY")
    if key:
        source = ".env" if env_values.get("GROQ_API_KEY") == key else "variable de entorno"
        print(f"GROQ_API_KEY cargada desde {source}.")
    else:
        print("GROQ_API_KEY no configurada.")
    return key


def build_vision_prompt(language: str) -> str:
    return f"""Transcribe fielmente en {language} el material principal visible en esta captura.

Reglas:
- Ignora barras de herramientas, menús, botones, números de página aislados y controles de la aplicación.
- Conserva títulos, enunciados, definiciones, fórmulas, símbolos y el orden de lectura.
- Devuelve solo la transcripción, sin análisis, solución ni encabezado.
- Conserva las fórmulas en LaTeX compatible con KaTeX: usa \( ... \) en línea y \[ ... \] para una fórmula centrada.
- Puedes usar **texto** para negrita y *texto* para cursiva.
- No uses signos $, HTML, bloques de código, otro Markdown, paquetes ni documentos LaTeX completos.""".strip()


def conversation_context_block(context: str) -> str:
    context = context.strip()
    if not context:
        return ""
    return (
        "CONTEXTO DE LA CONVERSACION ANTERIOR:\n"
        f"{context}\n\n"
        "Usa este texto solo como contexto para continuar la conversacion.\n\n"
    )


def build_answer_input(prompt: str, material: str, question: str, context: str = "") -> str:
    visual_context = material.strip() or "[No fue posible extraer el material de la ventana.]"
    return (
        f"{prompt.strip()}\n\n"
        f"MATERIAL CAPTURADO:\n{visual_context}\n\n"
        f"{conversation_context_block(context)}"
        f"PREGUNTA DEL USUARIO:\n{question.strip()}\n\n"
        f"{LATEX_OUTPUT_CONTRACT}"
    )


def build_multimodal_answer_input(prompt: str, question: str, context: str = "") -> str:
    return (
        f"{prompt.strip()}\n\n"
        "CONTEXTO VISUAL:\n"
        "Analiza directamente la imagen adjunta. Usa el material principal visible "
        "para responder y evita describir barras, botones o controles irrelevantes.\n\n"
        f"{conversation_context_block(context)}"
        f"PREGUNTA DEL USUARIO:\n{question.strip()}\n\n"
        f"{LATEX_OUTPUT_CONTRACT}"
    )


def build_question_only_input(prompt: str, question: str, context: str = "") -> str:
    return (
        f"{prompt.strip()}\n\n"
        "CONTEXTO:\n"
        "No se adjunta una imagen. Responde usando solamente la pregunta del usuario.\n\n"
        f"{conversation_context_block(context)}"
        f"PREGUNTA DEL USUARIO:\n{question.strip()}\n\n"
        f"{LATEX_OUTPUT_CONTRACT}"
    )


def get_foreground_window_rect() -> QRect:
    if sys.platform != "win32":
        raise RuntimeError("La captura de ventana activa requiere Windows.")
    user32 = ctypes.windll.user32
    hwnd = user32.GetForegroundWindow()
    if not hwnd or not user32.IsWindowVisible(hwnd) or user32.IsIconic(hwnd):
        raise RuntimeError("No hay una ventana activa visible para capturar.")

    class RECT(ctypes.Structure):
        _fields_ = [
            ("left", ctypes.c_long),
            ("top", ctypes.c_long),
            ("right", ctypes.c_long),
            ("bottom", ctypes.c_long),
        ]

    bounds = RECT()
    captured = False
    try:
        dwmapi = ctypes.windll.dwmapi
        captured = (
            dwmapi.DwmGetWindowAttribute(
                hwnd,
                DWMWA_EXTENDED_FRAME_BOUNDS,
                ctypes.byref(bounds),
                ctypes.sizeof(bounds),
            )
            == 0
        )
    except Exception:
        captured = False
    if not captured and not user32.GetWindowRect(hwnd, ctypes.byref(bounds)):
        raise RuntimeError("Windows no devolvió los límites de la ventana activa.")

    width = bounds.right - bounds.left
    height = bounds.bottom - bounds.top
    if width < 40 or height < 40:
        raise RuntimeError("La ventana activa es demasiado pequeña para capturarla.")
    title_length = user32.GetWindowTextLengthW(hwnd)
    title_buffer = ctypes.create_unicode_buffer(title_length + 1)
    user32.GetWindowTextW(hwnd, title_buffer, len(title_buffer))
    LOGGER.info(
        "Captura: hwnd=%s título=%r rect=(%s,%s %sx%s)",
        hwnd,
        title_buffer.value,
        bounds.left,
        bounds.top,
        width,
        height,
    )
    return QRect(bounds.left, bounds.top, width, height)


def capture_window_to_data_url(rect: QRect) -> str:
    bbox = (rect.left(), rect.top(), rect.right() + 1, rect.bottom() + 1)
    image = ImageGrab.grab(bbox=bbox, all_screens=True)
    buffer = io.BytesIO()
    image.save(buffer, "PNG", optimize=True)
    data = buffer.getvalue()
    mime = "image/png"

    if len(data) > MAX_IMAGE_BYTES:
        image = image.convert("RGB")
        quality = 90
        while quality >= 60:
            buffer = io.BytesIO()
            image.save(buffer, "JPEG", quality=quality, optimize=True)
            data = buffer.getvalue()
            mime = "image/jpeg"
            if len(data) <= MAX_IMAGE_BYTES:
                break
            quality -= 10

    while len(data) > MAX_IMAGE_BYTES:
        width, height = image.size
        image = image.resize(
            (max(1, int(width * 0.85)), max(1, int(height * 0.85))),
            Image.Resampling.LANCZOS,
        )
        buffer = io.BytesIO()
        image.convert("RGB").save(buffer, "JPEG", quality=80, optimize=True)
        data = buffer.getvalue()
        mime = "image/jpeg"

    LOGGER.info(
        "Captura: imagen preparada mime=%s tamaño=%sx%s bytes=%s",
        mime,
        image.width,
        image.height,
        len(data),
    )
    return f"data:{mime};base64,{base64.b64encode(data).decode('ascii')}"


def should_pause_playback(status: object, playing_status: object) -> bool:
    return status == playing_status


def media_transition_action(previous: str | None, current: str) -> str | None:
    if previous is None or previous == current:
        return None
    if previous == "playing" and current == "paused":
        return "play_pc"
    if current == "playing":
        return "pause_pc"
    return None


class MediaSessionController(QObject):
    pause_completed = Signal(str, str, bool)
    playback_state_changed = Signal(str)
    command_completed = Signal(str, str, bool, str)

    def __init__(self) -> None:
        super().__init__()
        self._monitor_stop = threading.Event()
        self._monitor_thread_handle: threading.Thread | None = None

    def start_monitoring(self) -> None:
        if sys.platform != "win32":
            return
        if self._monitor_thread_handle and self._monitor_thread_handle.is_alive():
            return
        self._monitor_stop.clear()
        self._monitor_thread_handle = threading.Thread(
            target=self._monitor_thread,
            name="InScreenMediaMonitor",
            daemon=True,
        )
        self._monitor_thread_handle.start()

    def stop_monitoring(self) -> None:
        self._monitor_stop.set()
        if self._monitor_thread_handle:
            self._monitor_thread_handle.join(timeout=2)
        self._monitor_thread_handle = None

    def ensure_current_state(self, request_id: str, desired: str) -> None:
        if desired not in {"playing", "paused"}:
            return
        threading.Thread(
            target=self._ensure_thread,
            args=(request_id, desired),
            name="InScreenMediaEnsure",
            daemon=True,
        ).start()

    def pause_if_playing(self, cycle_id: str) -> None:
        threading.Thread(
            target=self._pause_thread,
            args=(cycle_id,),
            name="InScreenMediaPause",
            daemon=True,
        ).start()

    def resume(self, source_id: str) -> None:
        if not source_id:
            return
        threading.Thread(
            target=self._resume_thread,
            args=(source_id,),
            name="InScreenMediaResume",
            daemon=True,
        ).start()

    def _pause_thread(self, cycle_id: str) -> None:
        source_id = ""
        paused = False
        try:
            source_id, paused = asyncio.run(self._pause_current_session())
        except Exception:
            LOGGER.exception("Multimedia: no se pudo consultar o pausar la sesión")
        self.pause_completed.emit(cycle_id, source_id, paused)

    def _resume_thread(self, source_id: str) -> None:
        try:
            asyncio.run(self._resume_session(source_id))
        except Exception:
            LOGGER.exception("Multimedia: no se pudo reanudar la sesión")

    def _monitor_thread(self) -> None:
        try:
            asyncio.run(self._monitor_current_session())
        except Exception:
            if not self._monitor_stop.is_set():
                LOGGER.exception("Multimedia: no se pudo observar la sesión actual")

    async def _monitor_current_session(self) -> None:
        from winrt.windows.media.control import (
            GlobalSystemMediaTransportControlsSessionManager,
            GlobalSystemMediaTransportControlsSessionPlaybackStatus,
        )

        manager = await GlobalSystemMediaTransportControlsSessionManager.request_async()
        previous: tuple[str, str] | None = None
        while not self._monitor_stop.is_set():
            session = manager.get_current_session()
            source_id = str(session.source_app_user_model_id or "") if session else ""
            state = "none"
            if session is not None:
                status = session.get_playback_info().playback_status
                if status == GlobalSystemMediaTransportControlsSessionPlaybackStatus.PLAYING:
                    state = "playing"
                elif status == GlobalSystemMediaTransportControlsSessionPlaybackStatus.PAUSED:
                    state = "paused"
            current = (source_id, state)
            if previous is not None and current != previous:
                self.playback_state_changed.emit(state)
            previous = current
            await asyncio.sleep(0.4)

    def _ensure_thread(self, request_id: str, desired: str) -> None:
        ok = False
        detail = ""
        try:
            ok, detail = asyncio.run(self._ensure_current_state(desired))
        except Exception as exc:
            detail = str(exc)
            LOGGER.exception("Multimedia: no se pudo asegurar estado=%s", desired)
        self.command_completed.emit(request_id, desired, ok, detail)

    @staticmethod
    async def _ensure_current_state(desired: str) -> tuple[bool, str]:
        if sys.platform != "win32":
            return False, "Windows no está disponible."
        from winrt.windows.media.control import (
            GlobalSystemMediaTransportControlsSessionManager,
            GlobalSystemMediaTransportControlsSessionPlaybackStatus,
        )

        manager = await GlobalSystemMediaTransportControlsSessionManager.request_async()
        session = manager.get_current_session()
        if session is None:
            return False, "Windows no tiene una sesión multimedia actual."
        status = session.get_playback_info().playback_status
        playing = GlobalSystemMediaTransportControlsSessionPlaybackStatus.PLAYING
        paused = GlobalSystemMediaTransportControlsSessionPlaybackStatus.PAUSED
        if desired == "playing":
            if status == playing:
                return True, ""
            if status != paused:
                return False, "La sesión de Windows no está pausada."
            return bool(await session.try_play_async()), ""
        if status == paused:
            return True, ""
        if status != playing:
            return False, "La sesión de Windows no está reproduciendo."
        return bool(await session.try_pause_async()), ""

    @staticmethod
    async def _pause_current_session() -> tuple[str, bool]:
        if sys.platform != "win32":
            return "", False
        from winrt.windows.media.control import (
            GlobalSystemMediaTransportControlsSessionManager,
            GlobalSystemMediaTransportControlsSessionPlaybackStatus,
        )

        manager = await GlobalSystemMediaTransportControlsSessionManager.request_async()
        session = manager.get_current_session()
        if session is None:
            return "", False
        status = session.get_playback_info().playback_status
        playing = GlobalSystemMediaTransportControlsSessionPlaybackStatus.PLAYING
        if not should_pause_playback(status, playing):
            return "", False
        source_id = str(session.source_app_user_model_id or "")
        paused = bool(await session.try_pause_async())
        return source_id, paused

    @staticmethod
    async def _resume_session(source_id: str) -> bool:
        if sys.platform != "win32":
            return False
        from winrt.windows.media.control import (
            GlobalSystemMediaTransportControlsSessionManager,
            GlobalSystemMediaTransportControlsSessionPlaybackStatus,
        )

        manager = await GlobalSystemMediaTransportControlsSessionManager.request_async()
        paused_status = GlobalSystemMediaTransportControlsSessionPlaybackStatus.PAUSED
        for session in manager.get_sessions():
            if str(session.source_app_user_model_id or "") != source_id:
                continue
            if session.get_playback_info().playback_status != paused_status:
                return False
            return bool(await session.try_play_async())
        return False


def audio_extension(mime_type: str) -> str:
    lowered = mime_type.lower()
    if "ogg" in lowered:
        return ".ogg"
    if "mp4" in lowered or "m4a" in lowered:
        return ".m4a"
    return ".webm"


def resolve_documents_dir() -> Path:
    if sys.platform == "win32":
        try:
            guid_type = ctypes.c_byte * 16
            guid = guid_type.from_buffer_copy(
                uuid.UUID("FDD39AD0-238F-46AF-ADB4-6C85480369C7").bytes_le
            )
            result = ctypes.c_wchar_p()
            status = ctypes.windll.shell32.SHGetKnownFolderPath(
                ctypes.byref(guid), 0, None, ctypes.byref(result)
            )
            if status == 0 and result.value:
                path = Path(result.value)
                ctypes.windll.ole32.CoTaskMemFree(result)
                return path
        except Exception:
            LOGGER.exception("Transcripciones: no se pudo resolver Documentos")
    return Path.home() / "Documents"


def sanitize_windows_name(value: str | None) -> str:
    source = " ".join((value or "").strip().split()) or "Sin materia"
    invalid = '<>:"/\\|?*'
    cleaned = "".join("_" if char in invalid or ord(char) < 32 else char for char in source)
    cleaned = cleaned.rstrip(" .")[:100].rstrip(" .") or "Sin materia"
    reserved = {"CON", "PRN", "AUX", "NUL"}
    reserved.update(f"COM{index}" for index in range(1, 10))
    reserved.update(f"LPT{index}" for index in range(1, 10))
    if cleaned.split(".", 1)[0].upper() in reserved:
        cleaned = f"_{cleaned}"
    return cleaned


def save_transcription(
    transcription: str,
    subject: str | None,
    session_id: str,
    documents_dir: Path | None = None,
) -> Path:
    target_dir = (
        (documents_dir or resolve_documents_dir())
        / "InScreen"
        / "Transcripciones"
        / sanitize_windows_name(subject)
    )
    target_dir.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    target = target_dir / f"{timestamp}_{session_id[:8]}.txt"
    target.write_text(transcription.rstrip("\r\n") + "\n", encoding="utf-8")
    return target


class HotkeyListener(QObject):
    pressed = Signal()
    text_key_pressed = Signal(str)
    dismiss_requested = Signal()
    quit_requested = Signal()

    def __init__(self, hotkey: str) -> None:
        super().__init__()
        self.hotkey = hotkey
        self._listener = None
        self._pressed_keys: set[object] = set()
        self._hotkey_down = False
        self._dismiss_down = False
        self._last_emit = 0.0

    def start(self) -> None:
        self._listener = keyboard.Listener(on_press=self._on_press, on_release=self._on_release)
        self._listener.daemon = True
        self._listener.start()

    def stop(self) -> None:
        if self._listener:
            self._listener.stop()

    def _on_press(self, key: object) -> None:
        self._pressed_keys.add(key)
        if self._is_quit_combo():
            self.quit_requested.emit()
            return
        if self._is_dismiss(key):
            if not self._dismiss_down:
                self._dismiss_down = True
                self.dismiss_requested.emit()
            return
        if not self._matches(key):
            char = getattr(key, "char", None)
            blocked = any(
                modifier in self._pressed_keys
                for modifier in (
                    keyboard.Key.ctrl_l,
                    keyboard.Key.ctrl_r,
                    keyboard.Key.alt_l,
                    keyboard.Key.alt_r,
                    keyboard.Key.cmd,
                )
            )
            if char and not blocked:
                self.text_key_pressed.emit(str(char))
            return
        if self._hotkey_down:
            return
        now = time.monotonic()
        if now - self._last_emit < 0.25:
            return
        self._hotkey_down = True
        self._last_emit = now
        self.pressed.emit()

    def _on_release(self, key: object) -> None:
        self._pressed_keys.discard(key)
        if self._is_dismiss(key):
            self._dismiss_down = False
        if self._matches(key):
            self._hotkey_down = False

    def _is_quit_combo(self) -> bool:
        ctrl = keyboard.Key.ctrl_l in self._pressed_keys or keyboard.Key.ctrl_r in self._pressed_keys
        shift = keyboard.Key.shift_l in self._pressed_keys or keyboard.Key.shift_r in self._pressed_keys
        q = any(
            getattr(pressed, "char", None)
            and str(getattr(pressed, "char")).lower() == "q"
            for pressed in self._pressed_keys
        )
        return ctrl and shift and q

    @staticmethod
    def _is_dismiss(key: object) -> bool:
        return key == keyboard.Key.esc or getattr(key, "char", None) == "|"

    def _matches(self, key: object) -> bool:
        if self.hotkey == "-":
            char = getattr(key, "char", None)
            vk = getattr(key, "vk", None)
            return char == "-" or vk in (109, 189)
        return str(getattr(key, "char", "")).lower() == self.hotkey.lower()


class VisionWorker(QObject):
    completed = Signal(str, bool, str)
    countdown = Signal(str, int)

    def __init__(
        self,
        session_id: str,
        image_data_url: str,
        config: AppConfig,
        api_key: str,
    ) -> None:
        super().__init__()
        self.session_id = session_id
        self.image_data_url = image_data_url
        self.config = replace(config)
        self.api_key = api_key
        self._cancelled = threading.Event()

    def cancel(self) -> None:
        self._cancelled.set()

    def run(self) -> None:
        started = time.monotonic()
        LOGGER.info("Vision: inicio de OCR")
        try:
            def request() -> str:
                client = OpenAI(
                    api_key=self.api_key,
                    base_url=GROQ_BASE_URL,
                    timeout=25.0,
                    max_retries=0,
                )
                response = client.chat.completions.create(
                    model=self.config.vision_model,
                    messages=[
                        {
                            "role": "user",
                            "content": [
                                {"type": "text", "text": build_vision_prompt(self.config.language)},
                                {"type": "image_url", "image_url": {"url": self.image_data_url}},
                            ],
                        }
                    ],
                    temperature=0.0,
                    max_tokens=1800,
                    **model_request_options(self.config.vision_model, "vision"),
                )
                return (response.choices[0].message.content or "").strip()

            content = str(
                run_with_rate_limit_retry(
                    request,
                    self._cancelled,
                    lambda value: self.countdown.emit(self.session_id, value),
                )
            )
            if self._cancelled.is_set():
                self.completed.emit(self.session_id, False, "Cancelado")
                return
            if not content:
                raise RuntimeError("El modelo de visión devolvió una transcripción vacía.")
            LOGGER.info("Vision: OCR listo en %.2fs", time.monotonic() - started)
            self.completed.emit(self.session_id, True, content)
        except RequestCancelled:
            self.completed.emit(self.session_id, False, "Cancelado")
        except RateLimitExhausted as exc:
            LOGGER.error("Vision: límite de solicitudes agotado", exc_info=True)
            self.completed.emit(self.session_id, False, encode_rate_limit_failure(exc.delay))
        except Exception as exc:
            LOGGER.warning(
                "Vision: fallo después de %.2fs: %s",
                time.monotonic() - started,
                exc,
            )
            LOGGER.exception("Vision: detalle del fallo")
            self.completed.emit(self.session_id, False, encode_model_failure(exc, "vision", self.config.vision_model))


class AudioTranscriptionWorker(QObject):
    completed = Signal(str, bool, str)

    def __init__(
        self,
        session_id: str,
        audio_path: Path,
        mime_type: str,
        config: AppConfig,
        api_key: str,
    ) -> None:
        super().__init__()
        self.session_id = session_id
        self.audio_path = audio_path
        self.mime_type = mime_type
        self.config = replace(config)
        self.api_key = api_key
        self._cancelled = threading.Event()

    def cancel(self) -> None:
        self._cancelled.set()

    def run(self) -> None:
        retain_audio = False
        started = time.monotonic()
        LOGGER.info("Audio: inicio de transcripción")
        try:
            if self._cancelled.is_set():
                self.completed.emit(self.session_id, False, "Cancelado")
                return
            data = self.audio_path.read_bytes()
            if not data:
                raise RuntimeError("No se recibió audio.")
            client = OpenAI(
                api_key=self.api_key,
                base_url=GROQ_BASE_URL,
                timeout=30.0,
                max_retries=0,
            )
            response = client.audio.transcriptions.create(
                model=self.config.audio_model,
                file=(f"pregunta{audio_extension(self.mime_type)}", data),
                language=self.config.language,
                response_format="json",
                temperature=0.0,
            )
            content = str(getattr(response, "text", "") or "").strip()
            if self._cancelled.is_set():
                self.completed.emit(self.session_id, False, "Cancelado")
                return
            if not content:
                raise RuntimeError("No se detectó voz en la grabación.")
            LOGGER.info("Audio: transcripción lista en %.2fs", time.monotonic() - started)
            self.completed.emit(self.session_id, True, content)
        except Exception as exc:
            retain_audio = is_model_failure(exc) and not self._cancelled.is_set()
            LOGGER.warning(
                "Audio: fallo después de %.2fs: %s",
                time.monotonic() - started,
                exc,
            )
            LOGGER.exception("Audio: detalle del fallo")
            self.completed.emit(self.session_id, False, encode_model_failure(exc, "audio", self.config.audio_model))
        finally:
            if not retain_audio:
                self.audio_path.unlink(missing_ok=True)


class MultimodalAnswerWorker(QObject):
    delta = Signal(str, str)
    completed = Signal(str, bool, str)
    countdown = Signal(str, int)

    def __init__(
        self,
        session_id: str,
        prompt: str,
        image_data_url: str,
        question: str,
        config: AppConfig,
        api_key: str,
        context: str = "",
    ) -> None:
        super().__init__()
        self.session_id = session_id
        self.prompt = prompt
        self.image_data_url = image_data_url
        self.question = question
        self.config = replace(config)
        self.api_key = api_key
        self.context = context
        self._cancelled = threading.Event()

    def cancel(self) -> None:
        self._cancelled.set()

    def run(self) -> None:
        collected: list[str] = []
        started = time.monotonic()
        LOGGER.info("Respuesta multimodal: inicio imagen + consulta")
        try:
            def request() -> str:
                collected.clear()
                client = OpenAI(
                    api_key=self.api_key,
                    base_url=GROQ_BASE_URL,
                    timeout=75.0,
                    max_retries=0,
                )
                stream = client.chat.completions.create(
                    model=self.config.vision_model,
                    messages=[
                        {
                            "role": "user",
                            "content": [
                                {
                                    "type": "text",
                                    "text": build_multimodal_answer_input(
                                        self.prompt,
                                        self.question,
                                        self.context,
                                    ),
                                },
                                {
                                    "type": "image_url",
                                    "image_url": {"url": self.image_data_url},
                                },
                            ],
                        }
                    ],
                    temperature=0.6,
                    max_tokens=1200,
                    stream=True,
                    **model_request_options(self.config.vision_model, "multimodal"),
                )
                for chunk in stream:
                    if self._cancelled.is_set():
                        close = getattr(stream, "close", None)
                        if callable(close):
                            close()
                        raise RequestCancelled("Cancelado")
                    piece = chunk.choices[0].delta.content or ""
                    if piece:
                        collected.append(piece)
                        self.delta.emit(self.session_id, piece)
                return "".join(collected).strip()

            result = str(
                run_with_rate_limit_retry(
                    request,
                    self._cancelled,
                    lambda value: self.countdown.emit(self.session_id, value),
                )
            )
            if not result:
                raise RuntimeError("El modelo multimodal no devolvió una respuesta.")
            LOGGER.info(
                "Respuesta multimodal: completada en %.2fs",
                time.monotonic() - started,
            )
            self.completed.emit(self.session_id, True, result)
        except RequestCancelled:
            self.completed.emit(self.session_id, False, "Cancelado")
        except RateLimitExhausted as exc:
            LOGGER.error("Respuesta multimodal: límite de solicitudes agotado", exc_info=True)
            self.completed.emit(self.session_id, False, encode_rate_limit_failure(exc.delay))
        except Exception as exc:
            LOGGER.warning(
                "Respuesta multimodal: fallo después de %.2fs: %s",
                time.monotonic() - started,
                exc,
            )
            LOGGER.exception("Respuesta multimodal: detalle del fallo")
            self.completed.emit(self.session_id, False, encode_model_failure(exc, "multimodal", self.config.vision_model))


class AnswerWorker(QObject):
    delta = Signal(str, str)
    completed = Signal(str, bool, str)
    countdown = Signal(str, int)

    def __init__(
        self,
        session_id: str,
        prompt: str,
        material: str | None,
        question: str,
        config: AppConfig,
        api_key: str,
        context: str = "",
    ) -> None:
        super().__init__()
        self.session_id = session_id
        self.prompt = prompt
        self.material = material
        self.question = question
        self.config = replace(config)
        self.api_key = api_key
        self.context = context
        self._cancelled = threading.Event()

    def cancel(self) -> None:
        self._cancelled.set()

    def run(self) -> None:
        collected: list[str] = []
        started = time.monotonic()
        LOGGER.info("Respuesta: inicio del modelo de texto")
        try:
            def request() -> str:
                collected.clear()
                client = OpenAI(
                    api_key=self.api_key,
                    base_url=GROQ_BASE_URL,
                    timeout=60.0,
                    max_retries=0,
                )
                stream = client.chat.completions.create(
                    model=self.config.text_model,
                    messages=[
                        {
                            "role": "user",
                            "content": (
                                build_answer_input(self.prompt, self.material, self.question, self.context)
                                if self.material is not None
                                else build_question_only_input(self.prompt, self.question, self.context)
                            ),
                        }
                    ],
                    temperature=0.1,
                    max_tokens=1200,
                    stream=True,
                    **model_request_options(self.config.text_model, "text"),
                )
                for chunk in stream:
                    if self._cancelled.is_set():
                        close = getattr(stream, "close", None)
                        if callable(close):
                            close()
                        raise RequestCancelled("Cancelado")
                    piece = chunk.choices[0].delta.content or ""
                    if piece:
                        collected.append(piece)
                        self.delta.emit(self.session_id, piece)
                return "".join(collected).strip()

            result = str(
                run_with_rate_limit_retry(
                    request,
                    self._cancelled,
                    lambda value: self.countdown.emit(self.session_id, value),
                )
            )
            if not result:
                raise RuntimeError("El modelo no devolvió una respuesta.")
            LOGGER.info("Respuesta: completada en %.2fs", time.monotonic() - started)
            self.completed.emit(self.session_id, True, result)
        except RequestCancelled:
            self.completed.emit(self.session_id, False, "Cancelado")
        except RateLimitExhausted as exc:
            LOGGER.error("Respuesta: límite de solicitudes agotado", exc_info=True)
            self.completed.emit(self.session_id, False, encode_rate_limit_failure(exc.delay))
        except Exception as exc:
            LOGGER.warning(
                "Respuesta: fallo después de %.2fs: %s",
                time.monotonic() - started,
                exc,
            )
            LOGGER.exception("Respuesta: detalle del fallo")
            self.completed.emit(self.session_id, False, encode_model_failure(exc, "text", self.config.text_model))


class MathRenderBridge(QObject):
    height_reported = Signal(int)

    @Slot(int)
    def reportHeight(self, height: int) -> None:
        self.height_reported.emit(max(1, int(height)))


class LocalMathPage(QWebEnginePage):
    def __init__(self, profile: QWebEngineProfile, local_page: Path, parent=None) -> None:
        super().__init__(profile, parent)
        self.local_page = local_page.resolve()

    def acceptNavigationRequest(self, url, navigation_type, is_main_frame) -> bool:
        del navigation_type
        if not is_main_frame:
            return True
        if url.isLocalFile() and Path(url.toLocalFile()).resolve() == self.local_page:
            return True
        LOGGER.warning("Overlay matemático: navegación bloqueada a %s", url.toString())
        return False


class MathAnswerView(QWidget):
    content_height_changed = Signal(int)
    rendered = Signal(str)
    RENDER_INTERVAL_MS = 75

    def __init__(self, font: QFont, parent=None, page_path: Path | None = None) -> None:
        super().__init__(parent)
        self.raw_text = ""
        self.content_height = max(24, QFontMetrics(font).lineSpacing())
        self._ready = False
        self._using_fallback = False
        self._pending_text: str | None = None
        self._font_size = max(10, font.pointSize())
        self._page_path = (page_path or (RESOURCE_DIR / "web" / "math.html")).resolve()
        self._renderer_started = False

        self.stack = QStackedWidget(self)
        self.fallback = QPlainTextEdit()
        self.fallback.setFont(font)
        self.fallback.setReadOnly(True)
        self.fallback.setFocusPolicy(Qt.NoFocus)
        self.fallback.setFrameShape(QFrame.NoFrame)
        self.fallback.setLineWrapMode(QPlainTextEdit.WidgetWidth)
        self.fallback.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        self.fallback.setVerticalScrollBarPolicy(Qt.ScrollBarAsNeeded)
        self.stack.addWidget(self.fallback)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.addWidget(self.stack)

        self._render_timer = QTimer(self)
        self._render_timer.setSingleShot(True)
        self._render_timer.setInterval(self.RENDER_INTERVAL_MS)
        self._render_timer.timeout.connect(self._flush_render)

        self._load_timeout = QTimer(self)
        self._load_timeout.setSingleShot(True)
        self._load_timeout.setInterval(6000)
        self._load_timeout.timeout.connect(
            lambda: self.activate_fallback("tiempo de carga agotado")
        )

    def _ensure_renderer(self) -> None:
        if self._renderer_started:
            return
        self._renderer_started = True
        if not self._page_path.is_file():
            self.activate_fallback(f"no existe {self._page_path}")
            return

        try:
            self.profile = QWebEngineProfile(self)
            self.profile.setHttpCacheType(QWebEngineProfile.HttpCacheType.MemoryHttpCache)
            self.profile.setPersistentCookiesPolicy(
                QWebEngineProfile.PersistentCookiesPolicy.NoPersistentCookies
            )
            self.profile.setSpellCheckEnabled(False)

            self.web = QWebEngineView()
            self.web.setContextMenuPolicy(Qt.NoContextMenu)
            self.web.setFocusPolicy(Qt.NoFocus)
            self.web.installEventFilter(self)
            self.page = LocalMathPage(self.profile, self._page_path, self.web)
            settings = self.page.settings()
            settings.setAttribute(
                QWebEngineSettings.WebAttribute.LocalContentCanAccessRemoteUrls, False
            )
            settings.setAttribute(
                QWebEngineSettings.WebAttribute.LocalContentCanAccessFileUrls, True
            )
            settings.setAttribute(
                QWebEngineSettings.WebAttribute.JavascriptCanOpenWindows, False
            )
            settings.setAttribute(QWebEngineSettings.WebAttribute.PluginsEnabled, False)
            settings.setAttribute(
                QWebEngineSettings.WebAttribute.LocalStorageEnabled, False
            )
            self.web.setPage(self.page)

            self.bridge = MathRenderBridge(self)
            self.bridge.height_reported.connect(self._update_content_height)
            self.channel = QWebChannel(self.page)
            self.channel.registerObject("bridge", self.bridge)
            self.page.setWebChannel(self.channel)

            self.stack.insertWidget(0, self.web)
            self.stack.setCurrentWidget(self.web)
            self.web.loadFinished.connect(self._on_load_finished)
            self._load_timeout.start()
            self.web.setUrl(QUrl.fromLocalFile(str(self._page_path)))
        except Exception as exc:
            self.activate_fallback(str(exc))

    @property
    def is_ready(self) -> bool:
        return self._ready

    @property
    def is_fallback(self) -> bool:
        return self._using_fallback

    def _on_load_finished(self, success: bool) -> None:
        self._load_timeout.stop()
        if not success:
            self.activate_fallback("la página local no pudo cargarse")
            return
        self.page.runJavaScript(
            "Boolean(window.inscreenReady && window.renderMathInElement && window.katex)",
            self._on_runtime_checked,
        )

    def _on_runtime_checked(self, available) -> None:
        if not available:
            self.activate_fallback("KaTeX o el puente JavaScript no están disponibles")
            return
        self._ready = True
        self.page.runJavaScript(f"window.inscreenSetFontSize({self._font_size});")
        self._flush_render()

    def activate_fallback(self, reason: str) -> None:
        if self._using_fallback:
            return
        self._load_timeout.stop()
        self._render_timer.stop()
        self._ready = False
        self._using_fallback = True
        self.fallback.setPlainText(self.raw_text)
        self.stack.setCurrentWidget(self.fallback)
        self._report_fallback_height()
        self.rendered.emit(self.raw_text)
        LOGGER.error("Overlay matemático: se usa visor de texto de respaldo: %s", reason)

    def set_text(self, value: str, *, immediate: bool = False) -> None:
        self._ensure_renderer()
        self.raw_text = value.replace("\r\n", "\n")
        self._pending_text = self.raw_text
        if self._using_fallback:
            scrollbar = self.fallback.verticalScrollBar()
            was_at_bottom = scrollbar.value() >= scrollbar.maximum() - 2
            previous_value = scrollbar.value()
            self.fallback.setPlainText(self.raw_text)
            self._report_fallback_height()
            self.rendered.emit(self.raw_text)
            QTimer.singleShot(
                0,
                lambda bar=scrollbar, follow=was_at_bottom, position=previous_value: bar.setValue(
                    bar.maximum() if follow else min(position, bar.maximum())
                ),
            )
            return
        if not self._ready:
            return
        if immediate:
            self._render_timer.stop()
            self._flush_render()
        elif not self._render_timer.isActive():
            self._render_timer.start()

    def _flush_render(self) -> None:
        if self._using_fallback or not self._ready or self._pending_text is None:
            return
        value = self._pending_text
        self._pending_text = None
        payload = json.dumps(value, ensure_ascii=False)
        self.page.runJavaScript(
            f"window.inscreenSetContent({payload}, false);",
            lambda _result, rendered=value: self.rendered.emit(rendered),
        )

    def _update_content_height(self, height: int) -> None:
        height = max(24, height)
        if height == self.content_height:
            return
        self.content_height = height
        self.content_height_changed.emit(height)

    def _report_fallback_height(self) -> None:
        metrics = QFontMetrics(self.fallback.font())
        lines = max(1, self.fallback.document().blockCount())
        self._update_content_height(lines * metrics.lineSpacing() + 4)

    def scroll_by(self, delta: int) -> None:
        if self._using_fallback:
            bar = self.fallback.verticalScrollBar()
            bar.setValue(bar.value() + delta)
        elif self._ready:
            self.page.runJavaScript(f"window.inscreenScrollBy({int(delta)});")

    def run_javascript(self, script: str, callback=None) -> None:
        if self._using_fallback or not hasattr(self, "page"):
            if callback:
                callback(None)
            return
        if callback is None:
            self.page.runJavaScript(script)
        else:
            self.page.runJavaScript(script, callback)

    def eventFilter(self, watched, event) -> bool:
        if watched is getattr(self, "web", None) and event.type() == QEvent.Wheel:
            self.scroll_by(-event.angleDelta().y())
            return True
        return super().eventFilter(watched, event)


class KeyboardQuestionEdit(QPlainTextEdit):
    submitted = Signal(str)

    def keyPressEvent(self, event) -> None:
        if event.key() in (Qt.Key_Return, Qt.Key_Enter):
            if self.isReadOnly():
                event.accept()
                return
            question = self.toPlainText().strip()
            if question:
                self.submitted.emit(question)
            event.accept()
            return
        super().keyPressEvent(event)


class TerminalOverlay(QWidget):
    question_submitted = Signal(str)
    keyboard_ready = Signal()
    send_image_changed = Signal(bool)

    def __init__(self, font_size: int, width_ratio: float, send_image: bool = True) -> None:
        super().__init__()
        self.font_size = font_size
        self.width_ratio = width_ratio
        self.target_geometry = QGuiApplication.primaryScreen().availableGeometry()
        self.answer_text = ""
        self.input_active = False
        self.pending_answer = ""
        self._awaiting_presentation: str | None = None
        self._keyboard_height = 0
        self.recording_active = False
        self.countdown_active = False

        self.setWindowFlags(
            Qt.FramelessWindowHint
            | Qt.WindowStaysOnTopHint
            | Qt.Tool
            | Qt.WindowDoesNotAcceptFocus
        )
        self.setAttribute(Qt.WA_ShowWithoutActivating, True)
        self.setAttribute(Qt.WA_TranslucentBackground, True)

        terminal_font = QFont("Consolas", font_size)
        terminal_font.setStyleHint(QFont.Monospace)

        self.question_frame = QFrame()
        self.question_frame.setObjectName("questionFrame")
        question_layout = QHBoxLayout(self.question_frame)
        question_layout.setContentsMargins(13, 7, 13, 7)
        self.question = QLabel()
        self.question.setFont(terminal_font)
        self.question.setWordWrap(True)
        self.question.setTextInteractionFlags(Qt.NoTextInteraction)
        question_layout.addWidget(self.question)

        self.answer_frame = QFrame()
        self.answer_frame.setObjectName("answerFrame")
        answer_layout = QVBoxLayout(self.answer_frame)
        answer_layout.setContentsMargins(16, 13, 16, 13)
        self.answer = MathAnswerView(terminal_font)
        self.answer.content_height_changed.connect(lambda _height: self._fit_and_show())
        self.answer.rendered.connect(self._queue_keyboard_presentation_finish)
        self.question_input = KeyboardQuestionEdit()
        self.question_input.setFont(terminal_font)
        self.question_input.setPlaceholderText("")
        self.question_input.setAccessibleName("Escribe una consulta; Enter envía, Esc sale")
        self.question_input.setCursorWidth(2)
        self.question_input.setFrameShape(QFrame.NoFrame)
        self.question_input.setLineWrapMode(QPlainTextEdit.WidgetWidth)
        self.question_input.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        self.question_input.setVerticalScrollBarPolicy(Qt.ScrollBarAsNeeded)
        self.question_input.submitted.connect(self.question_submitted.emit)
        self.question_input.textChanged.connect(self._fit_and_show)
        self.photo_switch = QPushButton("●")
        self.photo_switch.setObjectName("photoSwitch")
        self.photo_switch.setCheckable(True)
        self.photo_switch.setChecked(send_image)
        self.photo_switch.setFixedSize(46, 26)
        self.photo_switch.setCursor(Qt.PointingHandCursor)
        self.photo_switch.setToolTip("Enviar foto")
        self.photo_switch.setAccessibleName("Enviar foto")
        self.photo_switch.toggled.connect(self.send_image_changed.emit)
        self.keyboard_prompt_frame = QFrame()
        self.keyboard_prompt_frame.setObjectName("keyboardPromptFrame")
        keyboard_input_layout = QVBoxLayout(self.keyboard_prompt_frame)
        keyboard_input_layout.setContentsMargins(13, 7, 13, 7)
        keyboard_input_layout.setSpacing(4)
        self.keyboard_question_row = QWidget()
        keyboard_question_layout = QHBoxLayout(self.keyboard_question_row)
        keyboard_question_layout.setContentsMargins(0, 0, 0, 0)
        self.prompt_marker = QLabel(">")
        self.prompt_marker.setFont(terminal_font)
        self.prompt_marker.setContentsMargins(0, 4, 0, 0)
        keyboard_question_layout.addWidget(self.prompt_marker, 0, Qt.AlignTop)
        keyboard_question_layout.addWidget(self.question_input, 1)
        keyboard_input_layout.addWidget(self.keyboard_question_row, 0)
        self.countdown_panel = QWidget()
        countdown_layout = QHBoxLayout(self.countdown_panel)
        countdown_layout.setContentsMargins(0, 0, 0, 0)
        self.countdown_label = QLabel()
        countdown_font = QFont(terminal_font)
        countdown_font.setPointSize(max(8, font_size - 7))
        self.countdown_label.setFont(countdown_font)
        self.countdown_label.setAlignment(Qt.AlignCenter)
        self.countdown_label.setObjectName("countdownLabel")
        countdown_layout.addWidget(self.countdown_label)
        self.content_stack = QStackedWidget()
        self.content_stack.addWidget(self.answer)
        self.content_stack.addWidget(self.countdown_panel)
        answer_layout.addWidget(self.content_stack)
        self.switch_row = QWidget()
        switch_layout = QHBoxLayout(self.switch_row)
        switch_layout.setContentsMargins(0, 0, 0, 0)
        switch_layout.addStretch(1)
        switch_layout.addWidget(self.photo_switch, 0, Qt.AlignRight | Qt.AlignVCenter)
        keyboard_input_layout.addWidget(self.switch_row)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(7)
        layout.addWidget(self.question_frame)
        layout.addWidget(self.answer_frame)
        layout.addWidget(self.keyboard_prompt_frame)

        self.setStyleSheet(
            """
            QFrame#questionFrame, QFrame#answerFrame, QFrame#keyboardPromptFrame {
                background: #000000;
                border: 2px solid #d6d6d6;
                border-radius: 7px;
            }
            QLabel, QPlainTextEdit {
                color: #45ff1a;
                background: transparent;
                font-weight: 700;
                border: none;
                selection-background-color: #176d0d;
            }
            QScrollBar:vertical {
                background: #071607;
                width: 11px;
                margin: 0;
            }
            QScrollBar::handle:vertical {
                background: #45ff1a;
                min-height: 24px;
                border-radius: 4px;
            }
            QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {
                height: 0;
            }
            QPushButton#photoSwitch {
                color: #bcbcbc;
                background: #313131;
                border: 2px solid #777777;
                border-radius: 13px;
                font-size: 18px;
                padding: 0 17px 2px 1px;
            }
            QPushButton#photoSwitch:checked {
                color: #45ff1a;
                background: #12380d;
                border-color: #45ff1a;
                padding: 0 1px 2px 17px;
            }
            QPushButton#photoSwitch:focus {
                border-color: #ffffff;
            }
            QLabel#countdownLabel {
                color: #45ff1a;
                font-weight: 400;
            }
            """
        )
        self.answer_frame.hide()
        self.keyboard_prompt_frame.hide()
        self.keyboard_question_row.hide()
        self.switch_row.hide()

    def set_target(self, captured_rect: QRect) -> None:
        center = captured_rect.center()
        screen = QGuiApplication.screenAt(center)
        if screen:
            self.target_geometry = screen.availableGeometry()

    def show_status(self, text: str, answer: str | None = None) -> None:
        if self.input_active:
            self.question_input.setReadOnly(True)
            self.question_input.setToolTip("")
            self.prompt_marker.setText(">")
            return
        self._leave_input_mode()
        if answer:
            self.question_frame.hide()
            self.answer_text = f"{text.upper()}\n\n{answer}"
            self._set_answer_display(self.answer_text, immediate=True)
            self.answer_frame.show()
        else:
            self.question.setText(text.upper())
            self.question_frame.show()
            self.answer_text = ""
            self.answer_frame.hide()
        self._fit_and_show()

    def show_recording(self, text: str) -> None:
        self._leave_input_mode()
        self.recording_active = True
        self.question.setText(text.upper())
        self.question_frame.show()
        self.answer_frame.hide()
        self.keyboard_prompt_frame.show()
        self.keyboard_question_row.hide()
        self.switch_row.show()
        self._fit_and_show()

    def show_countdown(self, seconds: int) -> None:
        if self.input_active:
            return
        if seconds <= 0:
            self.end_countdown()
            return
        if not self.countdown_active:
            self.answer_text = ""
        self._leave_input_mode()
        self.countdown_active = True
        self.question_frame.hide()
        self.switch_row.hide()
        self.content_stack.show()
        self.content_stack.setCurrentWidget(self.countdown_panel)
        self.countdown_label.setText(str(seconds))
        self.answer_frame.show()
        self._fit_and_show()

    def end_countdown(self) -> None:
        if not self.countdown_active:
            return
        self.countdown_active = False
        self.countdown_label.clear()
        self.content_stack.show()
        self.content_stack.setCurrentWidget(self.answer)
        self.keyboard_question_row.hide()
        self.answer.show()
        self._set_answer_display(self.answer_text or "…", immediate=True)
        self._fit_and_show()

    def show_question(self, text: str, warning: str | None = None) -> None:
        self._leave_input_mode()
        if warning:
            self.question_frame.hide()
            self.answer_text = f"{text.upper()}\n\n{warning}"
            self._set_answer_display(self.answer_text, immediate=True)
            self.answer_frame.show()
        else:
            self.question.setText(text.upper())
            self.question_frame.show()
            self.answer_text = ""
            self.answer_frame.hide()
        self._fit_and_show()

    def begin_answer(self, warning: str | None = None) -> None:
        if self.input_active:
            self.pending_answer = f"{warning}\n\n" if warning else ""
            self.question_input.setReadOnly(True)
            return
        self._leave_input_mode()
        self.question_frame.hide()
        self.answer_text = f"{warning}\n\n" if warning else ""
        self._set_answer_display(self.answer_text or "…", immediate=True)
        self.answer_frame.show()
        self._fit_and_show()

    def show_input(self) -> None:
        if self.input_active:
            self._restore_keyboard_editor_focus()
            return
        self.input_active = True
        self.recording_active = False
        self.countdown_active = False
        self.question_frame.hide()
        self.answer_frame.setVisible(bool(self.answer_text.strip()))
        self.keyboard_prompt_frame.show()
        self.content_stack.show()
        self.switch_row.show()
        self.content_stack.setCurrentWidget(self.answer)
        self.answer.setVisible(bool(self.answer_text.strip()))
        self.keyboard_question_row.show()
        self.question_input.clear()
        self.question_input.setReadOnly(False)
        self.setAttribute(Qt.WA_ShowWithoutActivating, False)
        self.setWindowFlag(Qt.WindowDoesNotAcceptFocus, False)
        self._fit_and_show()
        self.show()
        self.raise_()
        self.activateWindow()
        QTimer.singleShot(0, self._focus_input)
        QTimer.singleShot(100, self._focus_input)

    def mark_keyboard_question_submitted(self) -> None:
        if not self.input_active:
            return
        self.pending_answer = ""
        self._awaiting_presentation = None
        self.question_input.setReadOnly(True)
        self.question_input.setToolTip("")
        self.prompt_marker.setText(">")

    def present_keyboard_answer(self, result: str) -> None:
        if not self.input_active:
            self.finish_answer(result)
            return
        self.answer_text = result
        self.pending_answer = ""
        self._awaiting_presentation = result.replace("\r\n", "\n")
        self.answer.show()
        self.answer.set_text(result, immediate=True)

    def present_keyboard_error(self, title: str, detail: str) -> None:
        if not self.input_active:
            self.show_error(title, detail)
            return
        self.pending_answer = ""
        self._awaiting_presentation = None
        self.question_input.setToolTip(f"{title}: {detail}")
        self.prompt_marker.setText("!")
        self.question_input.setReadOnly(False)
        self._restore_keyboard_editor_focus()

    def _focus_input(self) -> None:
        if not self.input_active or not self.isVisible():
            return
        if self.question_input.hasFocus() and self.isActiveWindow():
            return
        if sys.platform == "win32" and QGuiApplication.platformName() == "windows":
            # The global hotkey runs outside Qt's activation path. Temporarily
            # share the foreground input queue so Windows can focus this tool.
            user32 = ctypes.WinDLL("user32", use_last_error=True)
            user32.GetForegroundWindow.restype = ctypes.c_void_p
            foreground = user32.GetForegroundWindow()
            current_thread = ctypes.windll.kernel32.GetCurrentThreadId()
            foreground_thread = user32.GetWindowThreadProcessId(ctypes.c_void_p(foreground), None)
            attached = False
            try:
                if foreground_thread and foreground_thread != current_thread:
                    attached = bool(user32.AttachThreadInput(current_thread, foreground_thread, True))
                user32.SetForegroundWindow(ctypes.c_void_p(int(self.winId())))
            finally:
                if attached:
                    user32.AttachThreadInput(current_thread, foreground_thread, False)
        self.activateWindow()
        if self.windowHandle():
            self.windowHandle().requestActivate()
        self.question_input.setFocus(Qt.ActiveWindowFocusReason)

    def append_answer(self, piece: str) -> None:
        if self.input_active:
            self.pending_answer += piece
            return
        if self.countdown_active:
            self.end_countdown()
        self.answer_text += piece
        self._set_answer_display(self.answer_text)
        self._fit_and_show()

    def finish_answer(self, result: str) -> None:
        if self.input_active:
            self.present_keyboard_answer(result or self.pending_answer)
            return
        if not self.answer_text.strip():
            self.answer_text = result
        self._set_answer_display(self.answer_text, immediate=True)
        self._fit_and_show()

    def _queue_keyboard_presentation_finish(self, text: str) -> None:
        if not self.input_active or self._awaiting_presentation != text:
            return
        QTimer.singleShot(50, lambda rendered=text: self._finish_keyboard_presentation(rendered))

    def _finish_keyboard_presentation(self, text: str) -> None:
        if not self.input_active or self._awaiting_presentation != text:
            return
        self._awaiting_presentation = None
        self.answer_frame.show()
        self.question_input.clear()
        self.question_input.setReadOnly(False)
        self.question_input.setToolTip("")
        self.prompt_marker.setText(">")
        self._fit_and_show()
        self._restore_keyboard_editor_focus()
        self.keyboard_ready.emit()

    def _restore_keyboard_editor_focus(self) -> None:
        if not self.input_active:
            return
        QTimer.singleShot(0, self._focus_input)
        QTimer.singleShot(100, self._focus_input)

    def accept_external_text_key(self, text: str) -> None:
        if not self.input_active or self.question_input.isReadOnly() or not text:
            return
        self._focus_input()
        self.question_input.insertPlainText(text)

    def show_error(self, title: str, detail: str) -> None:
        if self.input_active:
            self.present_keyboard_error(title, detail)
            return
        self._leave_input_mode()
        self.question_frame.hide()
        self.answer_text = f"{title.upper()}\n\n{detail}"
        self._set_answer_display(self.answer_text, immediate=True)
        self.answer_frame.show()
        self._fit_and_show()

    def leave_input_mode(self) -> None:
        self._leave_input_mode()
        self.answer_text = ""
        self.pending_answer = ""

    def _leave_input_mode(self) -> None:
        self._awaiting_presentation = None
        self._keyboard_height = 0
        self.keyboard_prompt_frame.hide()
        self.keyboard_question_row.hide()
        self.recording_active = False
        self.countdown_active = False
        self.countdown_label.clear()
        self.switch_row.hide()
        self.content_stack.show()
        if not self.input_active:
            self.content_stack.setCurrentWidget(self.answer)
            self.answer.show()
            return
        self.input_active = False
        self.question_input.clearFocus()
        self.content_stack.setCurrentWidget(self.answer)
        self.answer.show()
        self.setWindowFlag(Qt.WindowDoesNotAcceptFocus, True)
        self.setAttribute(Qt.WA_ShowWithoutActivating, True)

    def _set_answer_display(self, value: str, *, immediate: bool = False) -> None:
        self.content_stack.setCurrentWidget(self.answer)
        self.keyboard_question_row.hide()
        self.answer.show()
        self.answer.set_text(value, immediate=immediate)

    def _fit_and_show(self) -> None:
        geometry = self.target_geometry
        width = min(1100, max(480, int(geometry.width() * self.width_ratio)))
        max_height = max(240, int(geometry.height() * 0.85))
        content_width = width - 32
        self.setFixedWidth(width)
        self.layout().activate()

        question_height = self._text_height(
            self.question.font(),
            self.question.text(),
            content_width,
        ) + 18
        question_limit = max(70, int(geometry.height() * 0.25))
        self.question_frame.setFixedHeight(max(42, min(question_height, question_limit)))

        prompt_height = 0
        if not self.keyboard_prompt_frame.isHidden():
            row_height = 0
            if self.keyboard_question_row.isVisible():
                input_height = self._text_height(
                    self.question_input.font(),
                    self.question_input.toPlainText(),
                    content_width - 24,
                )
                input_height = max(34, min(input_height + 14, 150))
                self.question_input.setFixedHeight(input_height)
                row_height = input_height
            switch_height = self.switch_row.sizeHint().height() if self.switch_row.isVisible() else 0
            spacing = 4 if row_height and switch_height else 0
            prompt_height = max(42, row_height + switch_height + spacing + 14)
            self.keyboard_prompt_frame.setFixedHeight(prompt_height)

        if not self.answer_frame.isHidden():
            question_space = self.question_frame.height() + 7 if self.question_frame.isVisible() else 0
            prompt_space = prompt_height + 7 if prompt_height else 0
            available = max(68, max_height - question_space - prompt_space)
            if self.countdown_active:
                answer_height = 38
            else:
                self.answer.setFixedHeight(
                    min(self.answer.content_height, max(24, available - 30))
                )
                answer_height = self.answer.content_height + 30
            self.answer_frame.setFixedHeight(max(68, min(answer_height, available)))

        self.adjustSize()
        self.layout().activate()
        interactive_region = QRegion()
        if self.question_frame.isVisible():
            interactive_region = interactive_region.united(QRegion(self.question_frame.geometry()))
        if self.answer_frame.isVisible():
            interactive_region = interactive_region.united(QRegion(self.answer_frame.geometry()))
        if self.keyboard_prompt_frame.isVisible():
            interactive_region = interactive_region.united(QRegion(self.keyboard_prompt_frame.geometry()))
        self.setMask(interactive_region)
        x = geometry.left() + (geometry.width() - width) // 2
        y = geometry.bottom() - self.height() - 18
        self.move(x, max(geometry.top() + 10, y))
        if not self.isVisible():
            self.show()
        self.raise_()

    def wheelEvent(self, event) -> None:
        if self.input_active:
            event.ignore()
            return
        if self.answer_frame.isVisible() and self.answer.content_height > self.answer.height():
            direction = event.angleDelta().y()
            step = 72
            self.answer.scroll_by(-(step if direction > 0 else -step))
            event.accept()
            return
        event.ignore()

    @staticmethod
    def _text_height(font: QFont, text: str, width: int) -> int:
        metrics = QFontMetrics(font)
        text = text or " "
        return metrics.boundingRect(QRect(0, 0, max(80, width), 10000), Qt.TextWordWrap, text).height()


class PairingWindow(QDialog):
    def __init__(self, pair_uri: str) -> None:
        super().__init__()
        self.pair_uri = pair_uri
        self.setWindowTitle("InScreen")
        self.setWindowFlag(Qt.WindowStaysOnTopHint, True)

        qr = qrcode.QRCode(box_size=7, border=2)
        qr.add_data(pair_uri)
        qr.make(fit=True)
        qr_image = qr.make_image(fill_color="black", back_color="white").convert("RGB")
        raw = io.BytesIO()
        qr_image.save(raw, "PNG")
        image = QImage.fromData(raw.getvalue(), "PNG")
        qr_label = QLabel()
        qr_label.setAlignment(Qt.AlignCenter)
        qr_label.setPixmap(QPixmap.fromImage(image))

        layout = QVBoxLayout(self)
        layout.setContentsMargins(10, 10, 10, 10)
        layout.addWidget(qr_label)
        self.setStyleSheet("QDialog { background: white; }")


class ModelCatalogWorker(QObject):
    completed = Signal(str, bool, str)

    def __init__(self, session_id: str, api_key: str) -> None:
        super().__init__()
        self.session_id = session_id
        self.api_key = api_key

    def run(self) -> None:
        try:
            with OpenAI(api_key=self.api_key, base_url=GROQ_BASE_URL, timeout=15, max_retries=0) as client:
                models = sorted(
                    (
                        {
                            "id": model.id,
                            "name": str(getattr(model, "name", "") or model.id),
                            "input_modalities": list(getattr(model, "input_modalities", None) or []),
                            "output_modalities": list(getattr(model, "output_modalities", None) or []),
                        }
                        for model in client.models.list()
                        if getattr(model, "active", True) is not False
                    ),
                    key=lambda item: item["id"],
                )
            self.completed.emit(self.session_id, True, json.dumps(models))
        except Exception as exc:
            self.completed.emit(self.session_id, False, str(exc))


class ModelSettingsDialog(QDialog):
    saved = Signal(object)

    def __init__(self, controller, field: str | None = None, detail: str = "") -> None:
        super().__init__()
        self.controller = controller
        self.request_id = ""
        self.setWindowTitle("Cambiar modelo" if field else "Configuración de modelos")
        self.setMinimumWidth(560)
        self.setWindowFlag(Qt.WindowStaysOnTopHint, True)
        layout = QVBoxLayout(self)
        if detail:
            label = QLabel(detail)
            label.setWordWrap(True)
            label.setTextFormat(Qt.PlainText)
            layout.addWidget(label)
        form = QFormLayout()
        self.fields = {}
        for name, title in MODEL_FIELDS.items():
            if field and name != field:
                continue
            combo = QComboBox()
            combo.setEditable(True)
            combo.setInsertPolicy(QComboBox.NoInsert)
            combo.setEditText(getattr(controller.config, name))
            combo.completer().setFilterMode(Qt.MatchContains)
            combo.completer().setCaseSensitivity(Qt.CaseInsensitive)
            self.fields[name] = combo
            form.addRow(title, combo)
        layout.addLayout(form)
        hint = QLabel("Escribe para buscar o introducir un identificador. El catálogo no garantiza acceso ni compatibilidad.\n"
                      "Qwen 3.6/3.8 27B: imagen y texto; Llama 3.3 70B: texto; Whisper: transcripción.\n"
                      "Otros modelos: capacidades sin verificar.")
        hint.setWordWrap(True)
        layout.addWidget(hint)
        self.status = QLabel()
        self.status.setWordWrap(True)
        self.status.setTextFormat(Qt.PlainText)
        layout.addWidget(self.status)
        buttons = QHBoxLayout()
        refresh = QPushButton("Actualizar modelos")
        refresh.clicked.connect(self.refresh)
        save = QPushButton("Guardar y reintentar" if field else "Guardar")
        save.clicked.connect(self.save)
        cancel = QPushButton("Cancelar")
        cancel.clicked.connect(self.reject)
        for button in (refresh, save, cancel):
            buttons.addWidget(button)
        layout.addLayout(buttons)

    def refresh(self) -> None:
        self.request_id = uuid.uuid4().hex
        if not self.controller.api_key:
            self.status.setText("Falta GROQ_API_KEY. Puedes introducir el modelo manualmente.")
            return
        self.status.setText("Buscando modelos…")
        self.controller.start_worker(ModelCatalogWorker(self.request_id, self.controller.api_key), self.loaded)

    def loaded(self, request_id: str, success: bool, content: str) -> None:
        if request_id != self.request_id or not self.isVisible():
            return
        if not success:
            self.status.setText("No se pudo consultar el catálogo. Puedes introducir el modelo manualmente.\n" + content)
            return
        models = json.loads(content)
        for field, combo in self.fields.items():
            current = combo.currentText()
            combo.clear()
            compatible = []
            for model in models:
                if isinstance(model, str):
                    compatible.append(model)
                    continue
                inputs = set(model.get("input_modalities") or [])
                outputs = set(model.get("output_modalities") or [])
                if field == "audio_model":
                    accepted = "audio" in inputs and "transcription" in outputs
                elif field == "vision_model":
                    accepted = "image" in inputs and "text" in outputs
                else:
                    accepted = "text" in inputs and "text" in outputs
                if accepted:
                    compatible.append(model["id"])
            combo.addItems(compatible)
            combo.setEditText(current)
        available = sum(combo.count() for combo in self.fields.values())
        self.status.setText(
            f"{len(models)} modelos activos consultados; {available} opciones compatibles."
            if models else "Catálogo vacío. Puedes introducir el modelo manualmente."
        )

    def save(self) -> None:
        values = {name: combo.currentText().strip() for name, combo in self.fields.items()}
        if not all(values.values()):
            self.status.setText("El identificador del modelo no puede estar vacío.")
            return
        try:
            save_preferences(values)
        except Exception as exc:
            self.status.setText(f"No se pudo guardar la configuración: {exc}")
            return
        for name, value in values.items():
            setattr(self.controller.config, name, value)
        self.accept()
        self.saved.emit(values)

    def done(self, result: int) -> None:
        self.request_id = ""
        super().done(result)


class ApplicationTray(QObject):
    primary_activated = Signal()
    settings_requested = Signal()

    def __init__(self, app: QApplication, quit_callback) -> None:
        super().__init__()
        icon_path = RESOURCE_DIR / "assets" / "inscreen.xpm"
        self.icon = QIcon(str(icon_path)) if icon_path.is_file() else QIcon()
        app.setWindowIcon(self.icon)
        self.tray = QSystemTrayIcon(self.icon, self)
        self.tray.setToolTip("InScreen · iniciando")
        self.menu = QMenu()
        self.status_action = QAction("Iniciando", self.menu)
        self.status_action.setEnabled(False)
        self.open_log_action = QAction("Abrir registro de errores", self.menu)
        self.settings_action = QAction("Configuración", self.menu)
        self.settings_action.triggered.connect(self.settings_requested.emit)
        self.quit_action = QAction("Salir", self.menu)
        self.open_log_action.triggered.connect(self.open_log)
        self.quit_action.triggered.connect(quit_callback)
        self.menu.addAction(self.status_action)
        self.menu.addSeparator()
        self.menu.addAction(self.settings_action)
        self.menu.addAction(self.open_log_action)
        self.menu.addAction(self.quit_action)
        self.tray.setContextMenu(self.menu)
        self.tray.activated.connect(self._handle_activation)

    def show(self) -> None:
        self.tray.show()

    def hide(self) -> None:
        self.tray.hide()

    def set_status(self, text: str) -> None:
        self.status_action.setText(text)
        self.tray.setToolTip(f"InScreen · {text}")

    def show_message(self, title: str, detail: str) -> None:
        self.tray.showMessage(title, detail, self.icon, 3500)

    def _handle_activation(self, reason: QSystemTrayIcon.ActivationReason) -> None:
        if reason == QSystemTrayIcon.Trigger:
            self.primary_activated.emit()

    @staticmethod
    def open_log() -> None:
        path = resolve_runtime_dir() / "inscreen.log"
        try:
            if sys.platform == "win32":
                os.startfile(path)  # type: ignore[attr-defined]
        except Exception:
            LOGGER.exception("No se pudo abrir el registro de errores")


class AppController(QObject):
    quit_requested = Signal()

    def __init__(self, app: QApplication, config: AppConfig, api_key: str | None) -> None:
        super().__init__()
        self.app = app
        self.config = config
        self.api_key = api_key
        self.state = "sin_celular"
        self.current_prompt = config.prompt or DEFAULT_PROMPT
        self.overlay = TerminalOverlay(
            config.font_size,
            config.overlay_width_ratio,
            config.send_image,
        )
        self.hotkey = HotkeyListener(config.hotkey)
        self.active_jobs: list[tuple[QThread, QObject]] = []
        self.worker_attempts = {}
        self.settings_dialog = None
        self.recovery_dialog = None
        self.retained_audio = None

        self.session_id: str | None = None
        self.image_data_url = ""
        self.capture_rect = QRect()
        self.vision_done = False
        self.vision_text = ""
        self.vision_error = ""
        self.audio_done = False
        self.audio_text = ""
        self.previous_response = ""
        self.audio_incomplete = False
        self.recording_acknowledged = False
        self.session_subjects: dict[str, str] = {}
        self.question_source = "phone"
        self.answer_started = False
        self.answer_mode = ""
        self.fallback_started = False
        self.multimodal_error = ""
        self.media_paused_by_cycle = False
        self.media_source_id = ""
        self.media_pause_cycle_id: str | None = None
        self.phone_media_state: str | None = None
        self.pending_media_commands: dict[str, str] = {}
        self.rate_limit_waiting = False
        self.mobile_server: LocalMobileServer | None = None
        self.pairing: PairingWindow | None = None
        self.media_controller = MediaSessionController()
        self.tray = ApplicationTray(app, self.quit_requested.emit)

        self.hotkey.pressed.connect(self.handle_hotkey)
        self.hotkey.text_key_pressed.connect(self.handle_global_text_key)
        self.hotkey.dismiss_requested.connect(self.handle_dismiss)
        self.hotkey.quit_requested.connect(self.quit_requested.emit)
        self.overlay.question_submitted.connect(self.handle_keyboard_question)
        self.overlay.keyboard_ready.connect(self.handle_keyboard_ready)
        self.overlay.send_image_changed.connect(self.handle_send_image_changed)
        self.media_controller.pause_completed.connect(self.handle_media_pause_completed)
        self.media_controller.playback_state_changed.connect(self.handle_windows_media_state)
        self.media_controller.command_completed.connect(self.handle_windows_media_command)
        self.tray.primary_activated.connect(self.handle_tray_primary)
        self.tray.settings_requested.connect(self.show_settings)

    def show_settings(self) -> None:
        if self.recovery_dialog and self.recovery_dialog.isVisible():
            self.recovery_dialog.raise_()
            self.recovery_dialog.activateWindow()
            return
        if self.settings_dialog and self.settings_dialog.isVisible():
            self.settings_dialog.raise_()
            self.settings_dialog.activateWindow()
            return
        self.settings_dialog = ModelSettingsDialog(self)
        self.settings_dialog.show()
        self.settings_dialog.refresh()

    def handle_dismiss(self) -> None:
        dialog = getattr(self, "settings_dialog", None)
        if dialog and dialog.isVisible():
            dialog.reject()
        self.cancel_and_hide()

    def clear_retained_audio(self) -> None:
        path = getattr(self, "retained_audio", None)
        if path:
            path.unlink(missing_ok=True)
            self.retained_audio = None

    def recover_model(self, worker, handler, failure: dict, session_id: str) -> None:
        if self.settings_dialog and self.settings_dialog.isVisible():
            self.settings_dialog.reject()
        self.state = "modelo_no_disponible"
        self.answer_mode_before_recovery = self.answer_mode
        self.answer_mode = "model_recovery"
        self.rate_limit_waiting = False
        self.update_tray_status()
        self.overlay.show_error("MODELO NO DISPONIBLE", failure["detail"])
        role = failure["role"]
        field = "audio_model" if role == "audio" else "text_model" if role == "text" else "vision_model"
        config = replace(worker.config)
        if role == "audio":
            self.retained_audio = worker.audio_path
            retry = AudioTranscriptionWorker(session_id, worker.audio_path, worker.mime_type, config, worker.api_key)
        elif role == "vision":
            retry = VisionWorker(session_id, worker.image_data_url, config, worker.api_key)
        elif role == "multimodal":
            retry = MultimodalAnswerWorker(session_id, worker.prompt, worker.image_data_url, worker.question, config, worker.api_key, worker.context)
        else:
            retry = AnswerWorker(session_id, worker.prompt, worker.material, worker.question, config, worker.api_key, worker.context)
        dialog = ModelSettingsDialog(self, field, f"El modelo {failure['model']} no está disponible. Selecciona un reemplazo.")
        self.recovery_dialog = dialog

        def retry_selected(values):
            if self.session_id != session_id or self.recovery_dialog is not dialog:
                return
            self.recovery_dialog = None
            setattr(retry.config, field, values[field])
            self.answer_mode = self.answer_mode_before_recovery
            self.state = "transcribiendo" if role in ("audio", "vision") else "respondiendo"
            self.update_tray_status()
            if role in ("text", "multimodal"):
                self.overlay.begin_answer()
                retry.delta.connect(self.handle_answer_delta)
            else:
                self.overlay.show_status("REINTENTANDO…")
            self.start_worker(retry, handler)
            if role == "vision":
                attempt = retry.session_id
                QTimer.singleShot(int(retry.config.vision_wait_seconds * 1000),
                                  lambda: self.handle_attempt_ocr_timeout(session_id, attempt))

        dialog.saved.connect(retry_selected)
        dialog.rejected.connect(self.cancel_and_hide)
        dialog.show()
        dialog.activateWindow()
        dialog.refresh()

    @Slot(str, bool, str)
    def dispatch_worker_result(self, attempt: str, success: bool, content: str) -> None:
        context = self.worker_attempts.pop(attempt, None)
        if context is None:
            return
        session_id, worker, handler = context
        if isinstance(worker, ModelCatalogWorker):
            handler(session_id, success, content)
            return
        failure = decode_model_failure(content) if not success else None
        if session_id != self.session_id:
            if isinstance(worker, AudioTranscriptionWorker):
                worker.audio_path.unlink(missing_ok=True)
            return
        if failure:
            self.recover_model(worker, handler, failure, session_id)
            return
        if isinstance(worker, AudioTranscriptionWorker):
            self.clear_retained_audio()
        handler(session_id, success, content)

    def handle_attempt_ocr_timeout(self, session_id: str, attempt: str) -> None:
        if attempt not in getattr(self, "worker_attempts", {}):
            return
        if self.rate_limit_waiting:
            QTimer.singleShot(1000, lambda: self.handle_attempt_ocr_timeout(session_id, attempt))
            return
        self.handle_fallback_ocr_timeout(session_id)
        if self.answer_mode == "fallback_failed":
            context = self.worker_attempts.pop(attempt, None)
            if context:
                context[1].cancel()

    def initialize_mobile_server(self) -> None:
        if self.mobile_server is not None:
            return
        try:
            runtime_dir = resolve_runtime_dir()
            self.mobile_server = LocalMobileServer(
                runtime_dir=runtime_dir,
                lan_host=self.config.lan_host,
                setup_port=self.config.setup_port,
                https_port=self.config.https_port,
                max_recording_seconds=self.config.max_recording_seconds,
            )
            self.mobile_server.connection_changed.connect(self.handle_mobile_connection)
            self.mobile_server.pairing_requested.connect(self.show_pairing)
            self.mobile_server.recording_started.connect(self.handle_recording_started)
            self.mobile_server.audio_completed.connect(self.handle_audio_file)
            self.mobile_server.recording_failed.connect(self.handle_recording_failed)
            self.mobile_server.media_state_changed.connect(self.handle_phone_media_state)
            self.mobile_server.media_command_result.connect(self.handle_phone_media_result)
            self.mobile_server.server_error.connect(self.handle_server_error)
            self.pairing = PairingWindow(self.mobile_server.pair_uri)
            self.mobile_server.start()
        except Exception as exc:
            LOGGER.exception("Servidor móvil no disponible")
            self.overlay.show_error("SERVIDOR MÓVIL NO DISPONIBLE", str(exc))
        self.update_tray_status()

    def handle_send_image_changed(self, enabled: bool) -> None:
        self.config.send_image = bool(enabled)
        try:
            save_send_image_preference(self.config.send_image)
        except Exception:
            LOGGER.exception("No se pudo guardar la preferencia de envío de imagen")

    def start(self) -> None:
        LOGGER.info(
            "Configuración: vision=%s texto=%s audio=%s espera_vision=%.1fs",
            self.config.vision_model,
            self.config.text_model,
            self.config.audio_model,
            self.config.vision_wait_seconds,
        )
        self.hotkey.start()
        self.media_controller.start_monitoring()
        self.tray.show()
        self.update_tray_status()
        QTimer.singleShot(100, self.initialize_mobile_server)
        if not self.api_key:
            QTimer.singleShot(
                250,
                lambda: self.overlay.show_error(
                    "FALTA GROQ_API_KEY",
                    "Crea .env junto a la aplicación con GROQ_API_KEY=tu_clave.",
                ),
            )

    def show_pairing(self) -> None:
        if self.pairing:
            self.pairing.show()
            self.pairing.raise_()
            self.pairing.activateWindow()

    def shutdown(self) -> None:
        LOGGER.info("Aplicación: cierre solicitado")
        self.cancel_and_hide()
        self.resume_media_if_needed()
        self.media_controller.stop_monitoring()
        self.hotkey.stop()
        self.tray.hide()
        if self.mobile_server:
            self.mobile_server.stop()
        for thread, _worker in list(self.active_jobs):
            thread.quit()
            thread.wait(2500)

    def handle_mobile_connection(self, ready: bool, detail: str) -> None:
        LOGGER.info("Celular: ready=%s detalle=%s", ready, detail)
        if ready:
            self.phone_media_state = (
                self.mobile_server.media_state
                if self.mobile_server and self.mobile_server.media_control_ready
                else None
            )
            if self.state in ("sin_celular", "listo"):
                self.state = "listo"
            if self.pairing:
                self.pairing.hide()
            self.update_tray_status()
            return
        self.phone_media_state = (
            self.mobile_server.media_state
            if self.mobile_server and self.mobile_server.media_control_ready
            else None
        )
        self.pending_media_commands.clear()
        if self.state in ("sin_celular", "listo"):
            self.state = "sin_celular"
        self.update_tray_status()

    def update_tray_status(self) -> None:
        tray = getattr(self, "tray", None)
        if tray is None:
            return
        labels = {
            "sin_celular": "Listo · teclado",
            "listo": "Listo · celular conectado",
            "grabando": "Grabando",
            "escribiendo": "Escribiendo",
            "transcribiendo": "Procesando",
            "respondiendo": "Respondiendo",
            "resultado": "Listo",
            "modelo_no_disponible": "Selecciona otro modelo",
        }
        tray.set_status(labels.get(self.state, "Listo"))

    def handle_tray_primary(self) -> None:
        server = self.mobile_server
        if server is None or not server.media_control_ready:
            self.tray.show_message(
                "Control multimedia no disponible",
                "Conecta el celular y habilita Control multimedia en la app Android.",
            )
            return
        command_id = server.send_media_command("toggle")
        if command_id is None:
            self.tray.show_message(
                "No se pudo controlar el celular",
                "La conexión multimedia no está lista.",
            )
            return
        self.pending_media_commands[command_id] = "tray"

    def handle_phone_media_state(self, current: str) -> None:
        previous = self.phone_media_state
        self.phone_media_state = current
        action = media_transition_action(previous, current)
        if action == "play_pc":
            self.media_controller.ensure_current_state(uuid.uuid4().hex, "playing")
        elif action == "pause_pc":
            self.media_controller.ensure_current_state(uuid.uuid4().hex, "paused")

    def handle_windows_media_state(self, current: str) -> None:
        if current != "playing":
            return
        server = self.mobile_server
        if server is None or not server.media_control_ready:
            return
        server.send_media_command("ensure_paused")

    def handle_windows_media_command(
        self,
        request_id: str,
        desired: str,
        ok: bool,
        detail: str,
    ) -> None:
        if not ok and detail:
            LOGGER.info(
                "Multimedia Windows: estado=%s no aplicado: %s",
                desired,
                detail,
            )

    def handle_phone_media_result(
        self,
        command_id: str,
        ok: bool,
        state: str,
        error: str,
    ) -> None:
        origin = self.pending_media_commands.pop(command_id, None)
        if origin == "tray" and not ok:
            self.tray.show_message(
                "No se pudo controlar la música",
                error or "No hay una sesión multimedia reproducible en el celular.",
            )

    def handle_hotkey(self) -> None:
        LOGGER.info("Hotkey '-' recibida en estado=%s", self.state)
        if any(dialog and dialog.isVisible() for dialog in (
            getattr(self, "settings_dialog", None), getattr(self, "recovery_dialog", None)
        )):
            return
        if self.state == "grabando":
            self.stop_cycle()
            return
        if self.state in ("escribiendo", "transcribiendo", "respondiendo"):
            return
        self.start_cycle()

    def handle_global_text_key(self, text: str) -> None:
        if (
            self.state != "escribiendo"
            or self.question_source != "keyboard"
            or not self.overlay.isVisible()
            or not self.overlay.input_active
            or self.overlay.question_input.hasFocus()
        ):
            return
        self.overlay.accept_external_text_key(text)

    def handle_keyboard_ready(self) -> None:
        if self.question_source == "keyboard" and self.state == "resultado":
            self._prepare_keyboard_request()

    def cancel_and_hide(self) -> None:
        dialog = getattr(self, "recovery_dialog", None)
        self.recovery_dialog = None
        if dialog and dialog.isVisible():
            dialog.reject()
        self.clear_retained_audio()
        cancelled_session = self.session_id
        LOGGER.info(
            "Cancelando sesión=%s estado=%s",
            (cancelled_session or "")[:8],
            self.state,
        )
        if cancelled_session:
            for _thread, worker in list(self.active_jobs):
                if getattr(worker, "original_session_id", getattr(worker, "session_id", None)) != cancelled_session:
                    continue
                cancel = getattr(worker, "cancel", None)
                if callable(cancel):
                    cancel()
            if self.state == "grabando" and self.mobile_server:
                self.mobile_server.stop_recording(cancelled_session)

        self.resume_media_if_needed()
        self.session_id = None
        self.image_data_url = ""
        self.previous_response = ""
        self.vision_done = False
        self.vision_text = ""
        self.vision_error = ""
        self.audio_done = False
        self.audio_text = ""
        self.audio_incomplete = False
        self.recording_acknowledged = False
        self.question_source = "phone"
        self.answer_started = False
        self.answer_mode = ""
        self.fallback_started = False
        self.multimodal_error = ""
        self.rate_limit_waiting = False
        self.overlay.leave_input_mode()
        self.overlay.hide()
        self.state = (
            "listo"
            if self.mobile_server and self.mobile_server.microphone_ready
            else "sin_celular"
        )
        self.update_tray_status()

    def start_cycle(self) -> None:
        if not self.api_key:
            self.overlay.show_error(
                "FALTA GROQ_API_KEY",
                "Crea .env junto a la aplicación con GROQ_API_KEY=tu_clave.",
            )
            return
        if not self.mobile_server or not self.mobile_server.microphone_ready:
            self.start_keyboard_cycle()
            return

        self.overlay.hide()
        self.app.processEvents(QEventLoop.ExcludeUserInputEvents)
        try:
            rect = get_foreground_window_rect()
            image_data_url = capture_window_to_data_url(rect)
        except Exception as exc:
            self.state = "listo"
            self.update_tray_status()
            self.overlay.show_error("NO SE PUDO CAPTURAR", str(exc))
            return

        session_id = uuid.uuid4().hex
        if not self.mobile_server.start_recording(session_id):
            self._begin_keyboard_cycle(session_id, rect, image_data_url)
            return

        self.session_id = session_id
        self.image_data_url = image_data_url
        self.capture_rect = rect
        self.state = "grabando"
        self.update_tray_status()
        self.recording_acknowledged = False
        self.vision_done = False
        self.vision_text = ""
        self.vision_error = ""
        self.audio_done = False
        self.audio_text = ""
        self.audio_incomplete = False
        self.question_source = "phone"
        self.answer_started = False
        self.answer_mode = ""
        self.fallback_started = False
        self.multimodal_error = ""
        self.media_paused_by_cycle = False
        self.media_source_id = ""
        self.media_pause_cycle_id = session_id
        self.overlay.set_target(rect)
        self.overlay.show_recording("● GRABANDO DESDE EL CELULAR")
        LOGGER.info("Sesión %s: captura lista y grabación iniciada", session_id[:8])
        if self.config.pause_media_during_recording:
            self.media_controller.pause_if_playing(session_id)

        QTimer.singleShot(
            self.config.max_recording_seconds * 1000,
            lambda sid=session_id: self.enforce_recording_limit(sid),
        )
        QTimer.singleShot(2000, lambda sid=session_id: self.recording_start_timeout(sid))

    def start_keyboard_cycle(self) -> None:
        if self.pairing:
            self.pairing.hide()
        self.overlay.hide()
        self.app.processEvents(QEventLoop.ExcludeUserInputEvents)
        try:
            rect = get_foreground_window_rect()
            image_data_url = capture_window_to_data_url(rect)
        except Exception as exc:
            self.state = "sin_celular"
            self.update_tray_status()
            self.overlay.show_error("NO SE PUDO CAPTURAR", str(exc))
            return

        self._begin_keyboard_cycle(uuid.uuid4().hex, rect, image_data_url)

    def _begin_keyboard_cycle(self, session_id: str, rect: QRect, image_data_url: str) -> None:
        self.capture_rect = rect
        self.image_data_url = image_data_url
        self.question_source = "keyboard"
        self.previous_response = ""
        self._prepare_keyboard_request(session_id)
        self.overlay.set_target(rect)
        self.overlay.show_input()
        LOGGER.info("Sesión %s: captura lista y entrada por teclado iniciada", session_id[:8])

    def _prepare_keyboard_request(self, session_id: str | None = None) -> None:
        self.session_id = session_id or uuid.uuid4().hex
        self.state = "escribiendo"
        self.update_tray_status()
        self.vision_done = False
        self.vision_text = ""
        self.vision_error = ""
        self.audio_done = False
        self.audio_text = ""
        self.audio_incomplete = False
        self.recording_acknowledged = False
        self.answer_started = False
        self.answer_mode = ""
        self.fallback_started = False
        self.multimodal_error = ""
        self.media_paused_by_cycle = False
        self.media_source_id = ""
        self.media_pause_cycle_id = None

    def handle_recording_started(self, session_id: str) -> None:
        if self.session_id == session_id and self.state == "grabando":
            self.recording_acknowledged = True

    def recording_start_timeout(self, session_id: str) -> None:
        if (
            self.session_id != session_id
            or self.state != "grabando"
            or self.recording_acknowledged
        ):
            return
        LOGGER.warning("Sesión %s: grabación móvil sin confirmar", session_id[:8])
        if self.mobile_server:
            self.mobile_server.cancel_recording(session_id)
        self.resume_media_if_needed()
        self._begin_keyboard_cycle(session_id, self.capture_rect, self.image_data_url)

    def handle_keyboard_question(self, question: str) -> None:
        if self.state == "resultado" and self.question_source == "keyboard" and question.strip():
            self._prepare_keyboard_request()
        if self.state != "escribiendo" or not self.session_id:
            return
        question = question.strip()
        if not question:
            return
        self.audio_text = question
        self.audio_done = True
        self.state = "transcribiendo"
        self.update_tray_status()
        self.overlay.mark_keyboard_question_submitted()
        LOGGER.info(
            "Sesión %s: pregunta de teclado lista chars=%s",
            self.session_id[:8],
            len(question),
        )
        self.maybe_start_answer()

    def enforce_recording_limit(self, session_id: str) -> None:
        if self.state == "grabando" and self.session_id == session_id:
            self.stop_cycle()

    def stop_cycle(self) -> None:
        if self.state != "grabando" or not self.session_id or not self.mobile_server:
            return
        self.state = "transcribiendo"
        self.update_tray_status()
        self.overlay.show_status("TRANSCRIBIENDO…")
        LOGGER.info("Sesión %s: grabación detenida", self.session_id[:8])
        self.mobile_server.stop_recording(self.session_id)
        self.resume_media_if_needed()
        QTimer.singleShot(20000, lambda sid=self.session_id: self.audio_receive_timeout(sid))

    def resume_media_if_needed(self) -> None:
        self.media_pause_cycle_id = None
        if self.media_paused_by_cycle and self.media_source_id:
            self.media_controller.resume(self.media_source_id)
        self.media_paused_by_cycle = False
        self.media_source_id = ""

    def handle_media_pause_completed(
        self,
        cycle_id: str,
        source_id: str,
        paused: bool,
    ) -> None:
        if not paused or not source_id:
            return
        if self.media_pause_cycle_id == cycle_id and self.session_id == cycle_id and self.state == "grabando":
            self.media_paused_by_cycle = True
            self.media_source_id = source_id
            return
        self.media_controller.resume(source_id)

    def audio_receive_timeout(self, session_id: str) -> None:
        if (
            self.session_id == session_id
            and self.state == "transcribiendo"
            and not self.audio_done
            and session_id not in self.session_subjects
        ):
            self.state = "resultado"
            self.update_tray_status()
            self.overlay.show_error(
                "NO LLEGÓ EL AUDIO",
                "Revisa la conexión del celular y vuelve a intentarlo.",
            )

    def handle_audio_file(
        self,
        session_id: str,
        path: str,
        mime_type: str,
        interrupted: bool,
        active_subject: str,
    ) -> None:
        if session_id != self.session_id:
            Path(path).unlink(missing_ok=True)
            return
        if self.state == "grabando":
            self.state = "transcribiendo"
            self.update_tray_status()
            self.overlay.show_status("CONEXIÓN INTERRUMPIDA · TRANSCRIBIENDO…")
            self.resume_media_if_needed()
        self.audio_incomplete = interrupted
        self.session_subjects[session_id] = active_subject
        LOGGER.info(
            "Sesión %s: audio recibido mime=%s incompleto=%s",
            session_id[:8],
            mime_type,
            interrupted,
        )
        worker = AudioTranscriptionWorker(
            session_id,
            Path(path),
            mime_type,
            self.config,
            self.api_key or "",
        )
        self.start_worker(worker, self.handle_audio_result)

    def handle_recording_failed(self, session_id: str, detail: str) -> None:
        if session_id != self.session_id:
            return
        LOGGER.error("Sesión %s: el celular no pudo grabar: %s", session_id[:8], detail)
        self.resume_media_if_needed()
        self.session_id = None
        self.image_data_url = ""
        self.vision_done = False
        self.vision_text = ""
        self.vision_error = ""
        self.audio_done = False
        self.audio_text = ""
        self.audio_incomplete = False
        self.question_source = "phone"
        self.answer_started = False
        self.answer_mode = ""
        self.fallback_started = False
        self.multimodal_error = ""
        self.state = (
            "listo"
            if self.mobile_server and self.mobile_server.microphone_ready
            else "sin_celular"
        )
        self.update_tray_status()
        self.overlay.show_error("NO SE PUDO ABRIR EL MICRÓFONO", detail)

    def handle_vision_result(self, session_id: str, success: bool, content: str) -> None:
        LOGGER.info(
            "Sesión %s: OCR de respaldo recibido success=%s chars=%s sesión_actual=%s",
            session_id[:8],
            success,
            len(content),
            (self.session_id or "")[:8],
        )
        if (
            session_id != self.session_id
            or not self.fallback_started
            or self.answer_mode != "ocr_fallback"
        ):
            return
        self.vision_done = True
        if success:
            self.rate_limit_waiting = False
            self.vision_text = content
            self.start_text_fallback()
        else:
            self.vision_error = content
            self.state = "resultado"
            self.update_tray_status()
            retry_delay = decode_rate_limit_failure(content)
            if retry_delay is not None:
                self._show_request_error(
                    "LÍMITE DE SOLICITUDES",
                    f"Qwen sigue ocupado. Vuelve a intentarlo en {retry_delay} segundos.",
                )
            else:
                self._show_request_error(
                    "NO SE PUDO RESPONDER",
                    "Falló la respuesta visual y tampoco se pudo extraer el material por OCR.",
                )

    def _show_request_error(self, title: str, detail: str) -> None:
        if self.question_source == "keyboard":
            self.overlay.present_keyboard_error(title, detail)
        else:
            self.overlay.show_error(title, detail)

    def handle_audio_result(self, session_id: str, success: bool, content: str) -> None:
        LOGGER.info(
            "Sesión %s: señal Audio recibida success=%s chars=%s sesión_actual=%s",
            session_id[:8],
            success,
            len(content),
            (self.session_id or "")[:8],
        )
        if session_id != self.session_id:
            return
        self.audio_done = True
        if not success:
            self.state = "resultado"
            self.update_tray_status()
            self.overlay.show_error("NO SE DETECTÓ UNA PREGUNTA", content)
            return
        self.audio_text = content
        try:
            saved = save_transcription(
                content,
                getattr(self, "session_subjects", {}).pop(session_id, ""),
                session_id,
            )
            LOGGER.info("Sesión %s: transcripción guardada en %s", session_id[:8], saved)
        except Exception:
            LOGGER.exception("Sesión %s: no se pudo guardar la transcripción", session_id[:8])
        self.overlay.show_status("ANALIZANDO IMAGEN…" if self.config.send_image else "PENSANDO…")
        self.maybe_start_answer()

    def maybe_start_answer(self) -> None:
        if (
            self.answer_started
            or not self.audio_done
            or not self.audio_text
        ):
            return
        if self.config.send_image and not self.image_data_url:
            return
        warning_parts: list[str] = []
        if self.audio_incomplete:
            warning_parts.append("⚠ El audio se recuperó después de una desconexión.")
        warning = "\n".join(warning_parts) or None

        self.answer_started = True
        self.answer_mode = "multimodal" if self.config.send_image else "text_only"
        self.fallback_started = False
        self.state = "respondiendo"
        self.update_tray_status()
        if self.question_source == "keyboard":
            self.overlay.mark_keyboard_question_submitted()
        else:
            self.overlay.begin_answer(warning)
        LOGGER.info(
            "Sesión %s: inicio de respuesta %s pregunta_chars=%s",
            (self.session_id or "")[:8],
            self.answer_mode,
            len(self.audio_text),
        )
        if self.config.send_image:
            worker = MultimodalAnswerWorker(
                self.session_id or "",
                self.current_prompt,
                self.image_data_url,
                self.audio_text,
                self.config,
                self.api_key or "",
                context=getattr(self, "previous_response", "") if self.question_source == "keyboard" else "",
            )
        else:
            worker = AnswerWorker(
                self.session_id or "",
                self.current_prompt,
                None,
                self.audio_text,
                self.config,
                self.api_key or "",
                context=getattr(self, "previous_response", "") if self.question_source == "keyboard" else "",
            )
        worker.session_id = self.session_id
        worker.delta.connect(self.handle_answer_delta)
        self.start_worker(worker, self.handle_answer_result)

    def start_ocr_fallback(self, multimodal_error: str) -> None:
        if not self.session_id or not self.image_data_url or self.fallback_started:
            return
        self.fallback_started = True
        self.multimodal_error = multimodal_error
        self.answer_mode = "ocr_fallback"
        self.vision_done = False
        self.vision_text = ""
        self.vision_error = ""
        self.state = "transcribiendo"
        self.update_tray_status()
        if self.question_source != "keyboard":
            self.overlay.show_status("REINTENTANDO CON OCR…")
        LOGGER.warning(
            "Sesión %s: se activa OCR de respaldo tras fallo multimodal: %s",
            self.session_id[:8],
            multimodal_error,
        )
        worker = VisionWorker(
            self.session_id,
            self.image_data_url,
            self.config,
            self.api_key or "",
        )
        self.start_worker(worker, self.handle_vision_result)
        QTimer.singleShot(
            int(self.config.vision_wait_seconds * 1000),
            lambda sid=self.session_id, attempt=worker.session_id: self.handle_attempt_ocr_timeout(sid, attempt),
        )

    def handle_fallback_ocr_timeout(self, session_id: str) -> None:
        if (
            session_id != self.session_id
            or self.answer_mode != "ocr_fallback"
            or self.vision_done
        ):
            return
        if getattr(self, "rate_limit_waiting", False):
            QTimer.singleShot(1000, lambda sid=session_id: self.handle_fallback_ocr_timeout(sid))
            return
        self.answer_mode = "fallback_failed"
        self.state = "resultado"
        self.update_tray_status()
        LOGGER.warning("Sesión %s: OCR de respaldo agotó el tiempo", session_id[:8])
        self._show_request_error(
            "NO SE PUDO RESPONDER",
            "La respuesta visual falló y el OCR de respaldo excedió el tiempo disponible.",
        )

    def start_text_fallback(self) -> None:
        if not self.session_id or not self.vision_text:
            return
        self.answer_mode = "text_fallback"
        self.state = "respondiendo"
        self.update_tray_status()
        if self.question_source != "keyboard":
            self.overlay.begin_answer("⚠ Se utilizó OCR de respaldo.")
        LOGGER.info(
            "Sesión %s: inicio de respuesta de respaldo material_chars=%s",
            self.session_id[:8],
            len(self.vision_text),
        )
        worker = AnswerWorker(
            self.session_id,
            self.current_prompt,
            self.vision_text,
            self.audio_text,
            self.config,
            self.api_key or "",
            context=getattr(self, "previous_response", "") if self.question_source == "keyboard" else "",
        )
        worker.delta.connect(self.handle_answer_delta)
        self.start_worker(worker, self.handle_answer_result)

    def handle_answer_delta(self, session_id: str, piece: str) -> None:
        context = getattr(self, "worker_attempts", {}).get(session_id)
        if context:
            session_id = context[0]
        if (
            session_id == self.session_id
            and self.state == "respondiendo"
            and self.question_source != "keyboard"
        ):
            self.overlay.append_answer(piece)

    def handle_answer_result(self, session_id: str, success: bool, content: str) -> None:
        LOGGER.info(
            "Sesión %s: señal Respuesta recibida success=%s chars=%s",
            session_id[:8],
            success,
            len(content),
        )
        if session_id != self.session_id:
            return
        if success:
            self.rate_limit_waiting = False
            self.previous_response = content
            self.state = "resultado"
            self.update_tray_status()
            if self.question_source == "keyboard":
                self.overlay.present_keyboard_answer(content)
            else:
                self.overlay.finish_answer(content)
            return
        retry_delay = decode_rate_limit_failure(content)
        if retry_delay is not None:
            self.rate_limit_waiting = False
            self.state = "resultado"
            self.update_tray_status()
            title = "LÍMITE DE SOLICITUDES"
            detail = f"El modelo sigue ocupado. Vuelve a intentarlo en {retry_delay} segundos."
            self._show_request_error(title, detail)
            return
        if self.answer_mode == "multimodal" and not self.fallback_started:
            self.start_ocr_fallback(content)
            return
        self.state = "resultado"
        self.update_tray_status()
        self._show_request_error("NO SE PUDO RESPONDER", content)

    def handle_server_error(self, detail: str) -> None:
        LOGGER.error("Servidor móvil: %s", detail)
        if self.state == "grabando":
            self.overlay.show_error("PROBLEMA CON EL CELULAR", detail)
        elif self.state not in ("transcribiendo", "respondiendo"):
            self.overlay.show_error("SERVIDOR MÓVIL", detail)

    def start_worker(self, worker: QObject, handler) -> None:
        if not hasattr(self, "worker_attempts"):
            self.worker_attempts = {}
        original_session = worker.session_id
        worker.original_session_id = original_session
        worker.session_id = uuid.uuid4().hex
        self.worker_attempts[worker.session_id] = (original_session, worker, handler)
        thread = QThread()
        worker.moveToThread(thread)
        self.active_jobs.append((thread, worker))
        thread.started.connect(worker.run)
        worker.completed.connect(self.dispatch_worker_result)
        countdown = getattr(worker, "countdown", None)
        if countdown is not None:
            countdown.connect(self.handle_rate_limit_countdown)
        worker.completed.connect(thread.quit)
        worker.completed.connect(worker.deleteLater)
        thread.finished.connect(thread.deleteLater)
        thread.finished.connect(lambda t=thread, w=worker: self.cleanup_worker(t, w))
        thread.start()

    def handle_rate_limit_countdown(self, session_id: str, seconds: int) -> None:
        context = getattr(self, "worker_attempts", {}).get(session_id)
        if context:
            session_id = context[0]
        if session_id != self.session_id:
            return
        self.rate_limit_waiting = seconds > 0
        if self.question_source != "keyboard":
            self.overlay.show_countdown(seconds)

    def cleanup_worker(self, thread: QThread, worker: QObject) -> None:
        self.active_jobs = [
            (active_thread, active_worker)
            for active_thread, active_worker in self.active_jobs
            if active_thread is not thread and active_worker is not worker
        ]


def main() -> int:
    instance = SingleInstanceGuard()
    if not instance.acquire():
        return 0
    try:
        setup_logging()
        install_exception_logging()
        config = load_config()
        api_key = load_api_key()
        app = QApplication(sys.argv)
        app.setQuitOnLastWindowClosed(False)

        controller = AppController(app, config, api_key)
        controller.quit_requested.connect(app.quit)
        app.aboutToQuit.connect(controller.shutdown)

        signal.signal(signal.SIGINT, lambda _sig, _frame: app.quit())
        signal_timer = QTimer()
        signal_timer.start(200)
        signal_timer.timeout.connect(lambda: None)

        controller.start()
        return app.exec()
    finally:
        instance.release()


if __name__ == "__main__":
    raise SystemExit(main())
