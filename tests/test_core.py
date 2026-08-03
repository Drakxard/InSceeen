import unittest
import threading
import sys
import uuid
from types import SimpleNamespace
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import Mock, patch

from pynput import keyboard

from app import (
    AppConfig,
    AnswerWorker,
    DEFAULT_PROMPT,
    HotkeyListener,
    LATEX_OUTPUT_CONTRACT,
    LOGGER,
    QWEN_MODEL,
    TEXT_MODEL,
    audio_extension,
    build_answer_input,
    build_multimodal_answer_input,
    build_question_only_input,
    build_vision_prompt,
    model_request_options,
    media_transition_action,
    rate_limit_delay_seconds,
    RequestCancelled,
    SingleInstanceGuard,
    run_with_rate_limit_retry,
    resolve_runtime_dir,
    save_send_image_preference,
    sanitize_windows_name,
    save_transcription,
    setup_logging,
    should_pause_playback,
)


class CoreTests(unittest.TestCase):
    def test_media_transition_policy_distinguishes_pause_from_stop(self) -> None:
        self.assertEqual(media_transition_action("playing", "paused"), "play_pc")
        self.assertEqual(media_transition_action("paused", "playing"), "pause_pc")
        self.assertEqual(media_transition_action("none", "playing"), "pause_pc")
        self.assertIsNone(media_transition_action("playing", "none"))
        self.assertIsNone(media_transition_action("paused", "paused"))
        self.assertIsNone(media_transition_action(None, "playing"))

    def test_default_hotkey_is_minus(self) -> None:
        self.assertEqual(AppConfig().hotkey, "-")

    @unittest.skipUnless(sys.platform == "win32", "Mutex disponible solamente en Windows")
    def test_second_instance_is_rejected_until_first_releases_mutex(self) -> None:
        name = rf"Local\InScreen.Test.{uuid.uuid4().hex}"
        first = SingleInstanceGuard(name)
        second = SingleInstanceGuard(name)
        third = SingleInstanceGuard(name)
        try:
            self.assertTrue(first.acquire())
            self.assertFalse(second.acquire())
            first.release()
            self.assertTrue(third.acquire())
        finally:
            first.release()
            second.release()
            third.release()

    def test_minus_matches_main_and_numpad_keys(self) -> None:
        listener = HotkeyListener("-")
        self.assertTrue(listener._matches(keyboard.KeyCode.from_char("-")))
        self.assertTrue(listener._matches(keyboard.KeyCode.from_vk(109)))
        self.assertTrue(listener._matches(keyboard.KeyCode.from_vk(189)))
        self.assertFalse(listener._matches(keyboard.KeyCode.from_char("a")))

    def test_answer_input_separates_material_and_question(self) -> None:
        result = build_answer_input(DEFAULT_PROMPT, "Definición del material", "¿Qué implica?")
        self.assertIn("MATERIAL CAPTURADO:\nDefinición del material", result)
        self.assertIn("PREGUNTA DEL USUARIO:\n¿Qué implica?", result)

    def test_answer_input_has_explicit_visual_fallback(self) -> None:
        result = build_answer_input(DEFAULT_PROMPT, "", "Pregunta")
        self.assertIn("No fue posible extraer", result)

    def test_multimodal_input_uses_image_and_keeps_contract_last(self) -> None:
        result = build_multimodal_answer_input("Prompt propio", "¿Qué muestra?")
        self.assertIn("Analiza directamente la imagen adjunta", result)
        self.assertIn("PREGUNTA DEL USUARIO:\n¿Qué muestra?", result)
        self.assertTrue(result.endswith(LATEX_OUTPUT_CONTRACT))

    def test_question_only_input_explicitly_omits_image(self) -> None:
        result = build_question_only_input("Prompt propio", "Pregunta")
        self.assertIn("No se adjunta una imagen", result)
        self.assertIn("PREGUNTA DEL USUARIO:\nPregunta", result)
        self.assertTrue(result.endswith(LATEX_OUTPUT_CONTRACT))

    def test_send_image_preference_is_saved_without_losing_config(self) -> None:
        with TemporaryDirectory() as directory:
            path = Path(directory) / "config.json"
            path.write_text('{"language":"es"}', encoding="utf-8")
            save_send_image_preference(False, path)
            saved = __import__("json").loads(path.read_text(encoding="utf-8"))
            self.assertEqual(saved, {"language": "es", "send_image": False})

    def test_latex_contract_is_after_any_custom_prompt(self) -> None:
        custom = "Usa $x$ y devuelve Markdown si quieres."
        result = build_answer_input(custom, "Material", "Pregunta")
        self.assertTrue(result.endswith(LATEX_OUTPUT_CONTRACT))
        self.assertLess(result.index(custom), result.index("CONTRATO DE SALIDA OBLIGATORIO"))
        self.assertIn(r"\( y \)", result)
        self.assertIn(r"\[ y \]", result)
        self.assertIn("No uses signos $", result)

    def test_vision_prompt_preserves_katex_delimiters(self) -> None:
        result = build_vision_prompt("español")
        self.assertIn(r"\( ... \)", result)
        self.assertIn(r"\[ ... \]", result)
        self.assertIn("No uses signos $", result)

    def test_audio_extensions(self) -> None:
        self.assertEqual(audio_extension("audio/webm;codecs=opus"), ".webm")
        self.assertEqual(audio_extension("audio/ogg;codecs=opus"), ".ogg")
        self.assertEqual(audio_extension("audio/mp4"), ".m4a")

    def test_visual_and_text_models_are_separate(self) -> None:
        self.assertEqual(QWEN_MODEL, "qwen/qwen3.6-27b")
        self.assertEqual(TEXT_MODEL, "llama-3.3-70b-versatile")
        self.assertFalse(hasattr(AppConfig(), "vision_model"))
        self.assertFalse(hasattr(AppConfig(), "text_model"))
        self.assertEqual(model_request_options(TEXT_MODEL, "text"), {})

    def test_text_worker_sends_llama_model(self) -> None:
        client = Mock()
        client.chat.completions.create.return_value = [
            SimpleNamespace(
                choices=[SimpleNamespace(delta=SimpleNamespace(content="respuesta"))]
            )
        ]
        worker = AnswerWorker("session", "Prompt", None, "Pregunta", AppConfig(), "key")
        completed = []
        worker.completed.connect(lambda _sid, success, content: completed.append((success, content)))
        with patch("app.OpenAI", return_value=client):
            worker.run()
        self.assertEqual(client.chat.completions.create.call_args.kwargs["model"], TEXT_MODEL)
        self.assertEqual(completed, [(True, "respuesta")])

    def test_rate_limit_delay_reads_groq_message(self) -> None:
        exc = RuntimeError("Please try again in 2.125s")
        self.assertEqual(rate_limit_delay_seconds(exc), 3)

    def test_rate_limit_counts_down_and_retries_once(self) -> None:
        attempts = []
        countdown = []

        def operation():
            attempts.append(True)
            if len(attempts) == 1:
                exc = RuntimeError("Please try again in 0.01s")
                exc.status_code = 429
                raise exc
            return "ok"

        result = run_with_rate_limit_retry(operation, threading.Event(), countdown.append)
        self.assertEqual(result, "ok")
        self.assertEqual(len(attempts), 2)
        self.assertEqual(countdown, [1, 0])

    def test_rate_limit_can_be_cancelled_during_countdown(self) -> None:
        cancelled = Mock()
        cancelled.is_set.return_value = False
        cancelled.wait.return_value = True
        exc = RuntimeError("Please try again in 3s")
        exc.status_code = 429

        with self.assertRaises(RequestCancelled):
            run_with_rate_limit_retry(lambda: (_ for _ in ()).throw(exc), cancelled, Mock())

    def test_media_is_paused_only_for_playing_status(self) -> None:
        self.assertTrue(should_pause_playback("playing", "playing"))
        self.assertFalse(should_pause_playback("paused", "playing"))
        self.assertFalse(should_pause_playback("stopped", "playing"))
        self.assertFalse(should_pause_playback(None, "playing"))

    def test_log_file_omits_info_and_keeps_warnings(self) -> None:
        original_handlers = list(LOGGER.handlers)
        with TemporaryDirectory() as directory:
            target = Path(directory)
            try:
                with patch("app.resolve_runtime_dir", return_value=target):
                    setup_logging()
                LOGGER.info("actividad normal")
                LOGGER.warning("problema visible")
                for handler in LOGGER.handlers:
                    handler.flush()
                content = (target / "inscreen.log").read_text(encoding="utf-8")
                self.assertNotIn("actividad normal", content)
                self.assertIn("problema visible", content)
            finally:
                for handler in LOGGER.handlers:
                    handler.close()
                LOGGER.handlers = original_handlers

    def test_legacy_info_log_is_rotated_once(self) -> None:
        original_handlers = list(LOGGER.handlers)
        with TemporaryDirectory() as directory:
            target = Path(directory)
            (target / "inscreen.log").write_text(
                "2026-01-01 10:00:00 INFO MainThread actividad anterior\n",
                encoding="utf-8",
            )
            try:
                with patch("app.resolve_runtime_dir", return_value=target):
                    setup_logging()
                self.assertEqual((target / "inscreen.log").read_text(encoding="utf-8"), "")
                self.assertIn(
                    "actividad anterior",
                    (target / "inscreen.log.1").read_text(encoding="utf-8"),
                )
            finally:
                for handler in LOGGER.handlers:
                    handler.close()
                LOGGER.handlers = original_handlers

    def test_runtime_identity_migrates_once_without_overwriting(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            app_dir = root / "portable"
            legacy = app_dir / "runtime"
            legacy.mkdir(parents=True)
            (legacy / "state.json").write_text('{"token":"legacy"}', encoding="utf-8")
            (legacy / "ca-cert.pem").write_text("legacy-ca", encoding="utf-8")

            target = resolve_runtime_dir(app_dir, str(root / "local"))
            self.assertEqual((target / "state.json").read_text(encoding="utf-8"), '{"token":"legacy"}')
            (target / "state.json").write_text('{"token":"new"}', encoding="utf-8")
            resolve_runtime_dir(app_dir, str(root / "local"))
            self.assertEqual((target / "state.json").read_text(encoding="utf-8"), '{"token":"new"}')

    def test_pipe_emits_dismiss_once_until_released(self) -> None:
        listener = HotkeyListener("-")
        events = []
        listener.dismiss_requested.connect(lambda: events.append("dismiss"))
        pipe = keyboard.KeyCode.from_char("|")
        listener._on_press(pipe)
        listener._on_press(pipe)
        listener._on_release(pipe)
        listener._on_press(pipe)
        self.assertEqual(events, ["dismiss", "dismiss"])

    def test_escape_does_not_dismiss(self) -> None:
        listener = HotkeyListener("-")
        events = []
        listener.dismiss_requested.connect(lambda: events.append("dismiss"))
        listener._on_press(keyboard.Key.esc)
        listener._on_release(keyboard.Key.esc)
        self.assertEqual(events, [])

    def test_ctrl_shift_p_does_not_request_pairing(self) -> None:
        listener = HotkeyListener("-")
        listener._on_press(keyboard.Key.ctrl_l)
        listener._on_press(keyboard.Key.shift_l)
        listener._on_press(keyboard.KeyCode.from_char("p"))
        self.assertFalse(hasattr(listener, "pairing_requested"))

    def test_transcription_uses_safe_subject_directory(self) -> None:
        with TemporaryDirectory() as directory:
            path = save_transcription("Texto transcripto", "CON", "abcdef123456", Path(directory))
            self.assertEqual(path.parent.name, "_CON")
            self.assertTrue(path.name.endswith("_abcdef12.txt"))
            self.assertEqual(path.read_text(encoding="utf-8"), "Texto transcripto\n")
        self.assertEqual(sanitize_windows_name("A/B:*?"), "A_B___")
        self.assertEqual(sanitize_windows_name(""), "Sin materia")


if __name__ == "__main__":
    unittest.main()
