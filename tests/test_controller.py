import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

from PySide6.QtCore import QRect

from app import AppConfig, AppController


def bare_controller() -> AppController:
    controller = AppController.__new__(AppController)
    controller.app = Mock()
    controller.config = AppConfig()
    controller.api_key = "test-key"
    controller.current_prompt = "Prompt"
    controller.overlay = Mock()
    controller.overlay.isVisible.return_value = False
    controller.pairing = Mock()
    controller.pairing.isVisible.return_value = False
    controller.mobile_server = SimpleNamespace(
        microphone_ready=False,
        paired_once=False,
        media_control_ready=False,
        media_state="none",
        send_media_command=Mock(return_value=None),
    )
    controller.state = "sin_celular"
    controller.active_jobs = []
    controller.session_id = None
    controller.image_data_url = ""
    controller.vision_done = False
    controller.vision_text = ""
    controller.vision_error = ""
    controller.audio_done = False
    controller.audio_text = ""
    controller.audio_incomplete = False
    controller.recording_acknowledged = False
    controller.session_subjects = {}
    controller.capture_rect = QRect()
    controller.question_source = "phone"
    controller.answer_started = False
    controller.answer_mode = ""
    controller.fallback_started = False
    controller.multimodal_error = ""
    controller.media_paused_by_cycle = False
    controller.media_source_id = ""
    controller.media_pause_cycle_id = None
    controller.phone_media_state = None
    controller.pending_media_commands = {}
    controller.media_controller = Mock()
    controller.rate_limit_waiting = False
    controller.tray = Mock()
    return controller


class ControllerKeyboardTests(unittest.TestCase):
    def test_phone_media_transitions_apply_asymmetric_policy(self) -> None:
        controller = bare_controller()
        controller.phone_media_state = "playing"

        controller.handle_phone_media_state("paused")
        controller.media_controller.ensure_current_state.assert_called_once()
        self.assertEqual(controller.media_controller.ensure_current_state.call_args.args[1], "playing")

        controller.media_controller.reset_mock()
        controller.handle_phone_media_state("playing")
        self.assertEqual(controller.media_controller.ensure_current_state.call_args.args[1], "paused")

        controller.media_controller.reset_mock()
        controller.handle_phone_media_state("none")
        controller.media_controller.ensure_current_state.assert_not_called()

    def test_connection_only_baselines_phone_media(self) -> None:
        controller = bare_controller()
        controller.mobile_server.microphone_ready = True
        controller.mobile_server.media_control_ready = True
        controller.mobile_server.media_state = "playing"

        controller.handle_mobile_connection(True, "conectado")

        self.assertEqual(controller.phone_media_state, "playing")
        controller.media_controller.ensure_current_state.assert_not_called()

    def test_windows_playing_requests_idempotent_phone_pause(self) -> None:
        controller = bare_controller()
        controller.mobile_server.media_control_ready = True
        controller.mobile_server.send_media_command = Mock(return_value="command")

        controller.handle_windows_media_state("paused")
        controller.mobile_server.send_media_command.assert_not_called()
        controller.handle_windows_media_state("playing")
        controller.mobile_server.send_media_command.assert_called_once_with("ensure_paused")

    def test_tray_click_toggles_only_when_media_control_is_ready(self) -> None:
        controller = bare_controller()
        controller.handle_tray_primary()
        controller.tray.show_message.assert_called_once()

        controller.tray.reset_mock()
        controller.mobile_server.media_control_ready = True
        controller.mobile_server.send_media_command = Mock(return_value="toggle-1")
        controller.handle_tray_primary()
        self.assertEqual(controller.pending_media_commands, {"toggle-1": "tray"})
        controller.tray.show_message.assert_not_called()

    @patch("app.PairingWindow")
    @patch("app.LocalMobileServer")
    @patch("app.resolve_runtime_dir")
    def test_deferred_mobile_server_uses_controller_config(
        self, runtime_dir, server_class, pairing_class
    ) -> None:
        controller = bare_controller()
        controller.mobile_server = None
        controller.config.lan_host = "192.168.1.20"
        server = Mock()
        server.pair_uri = "inscreen://pair"
        server_class.return_value = server

        controller.initialize_mobile_server()

        server_class.assert_called_once_with(
            runtime_dir=runtime_dir.return_value,
            lan_host="192.168.1.20",
            setup_port=controller.config.setup_port,
            https_port=controller.config.https_port,
            max_recording_seconds=controller.config.max_recording_seconds,
        )
        pairing_class.assert_called_once_with("inscreen://pair")
        server.start.assert_called_once_with()

    def test_pair_request_opens_window(self) -> None:
        controller = bare_controller()
        controller.mobile_server.paired_once = True
        controller.show_pairing()
        controller.pairing.show.assert_called_once_with()
        controller.pairing.raise_.assert_called_once_with()
        controller.pairing.activateWindow.assert_called_once_with()

    def test_recording_ack_timeout_reuses_capture_for_keyboard(self) -> None:
        controller = bare_controller()
        controller.state = "grabando"
        controller.session_id = "session-timeout"
        controller.image_data_url = "data:image/png;base64,captured"
        controller.capture_rect = QRect(10, 20, 800, 600)
        controller.mobile_server = Mock()
        controller._begin_keyboard_cycle = Mock()
        controller.resume_media_if_needed = Mock()

        controller.recording_start_timeout("session-timeout")

        controller.mobile_server.cancel_recording.assert_called_once_with("session-timeout")
        controller._begin_keyboard_cycle.assert_called_once_with(
            "session-timeout", controller.capture_rect, "data:image/png;base64,captured"
        )

    def test_disconnect_does_not_reopen_qr_after_pairing(self) -> None:
        controller = bare_controller()
        controller.state = "listo"
        controller.mobile_server.paired_once = True
        controller.handle_mobile_connection(False, "desconectado")
        self.assertEqual(controller.state, "sin_celular")
        controller.pairing.show.assert_not_called()

    def test_no_phone_dispatches_to_keyboard_cycle(self) -> None:
        controller = bare_controller()
        controller.start_keyboard_cycle = Mock()
        controller.start_cycle()
        controller.start_keyboard_cycle.assert_called_once_with()

    @patch("app.capture_window_to_data_url", return_value="data:image/png;base64,test")
    @patch("app.get_foreground_window_rect", return_value=QRect(10, 20, 800, 600))
    def test_keyboard_cycle_captures_without_starting_ocr(
        self,
        capture_rect,
        capture_image,
    ) -> None:
        controller = bare_controller()
        controller.start_worker = Mock()

        controller.start_keyboard_cycle()

        self.assertEqual(controller.state, "escribiendo")
        self.assertEqual(controller.question_source, "keyboard")
        self.assertEqual(controller.image_data_url, "data:image/png;base64,test")
        capture_rect.assert_called_once_with()
        capture_image.assert_called_once()
        controller.start_worker.assert_not_called()
        controller.overlay.show_input.assert_called_once_with()

    def test_enter_uses_captured_image_without_ocr(self) -> None:
        controller = bare_controller()
        controller.state = "escribiendo"
        controller.session_id = "session-1"
        controller.image_data_url = "data:image/png;base64,captured"
        controller.maybe_start_answer = Mock()

        controller.handle_keyboard_question("  ¿Qué significa esto?  ")

        self.assertEqual(controller.audio_text, "¿Qué significa esto?")
        self.assertEqual(controller.image_data_url, "data:image/png;base64,captured")
        self.assertEqual(controller.state, "transcribiendo")
        controller.overlay.show_status.assert_called_once_with("ANALIZANDO IMAGEN…")
        controller.maybe_start_answer.assert_called_once_with()

    def test_enter_without_image_uses_text_status(self) -> None:
        controller = bare_controller()
        controller.config.send_image = False
        controller.state = "escribiendo"
        controller.session_id = "session-text"
        controller.maybe_start_answer = Mock()

        controller.handle_keyboard_question("Pregunta sin foto")

        controller.overlay.show_status.assert_called_once_with("PENSANDO…")
        controller.maybe_start_answer.assert_called_once_with()

    @patch("app.MultimodalAnswerWorker")
    def test_answer_sends_image_and_question_to_multimodal_model(self, worker_class) -> None:
        controller = bare_controller()
        controller.session_id = "session-mm"
        controller.image_data_url = "data:image/png;base64,captured"
        controller.audio_done = True
        controller.audio_text = "Consulta directa"
        controller.start_worker = Mock()
        worker = Mock()
        worker_class.return_value = worker

        controller.maybe_start_answer()

        worker_class.assert_called_once_with(
            "session-mm",
            "Prompt",
            "data:image/png;base64,captured",
            "Consulta directa",
            controller.config,
            "test-key",
        )
        self.assertEqual(controller.answer_mode, "multimodal")
        controller.start_worker.assert_called_once_with(worker, controller.handle_answer_result)

    @patch("app.AnswerWorker")
    def test_answer_without_image_uses_text_model(self, worker_class) -> None:
        controller = bare_controller()
        controller.config.send_image = False
        controller.session_id = "session-text"
        controller.audio_done = True
        controller.audio_text = "Consulta directa"
        controller.start_worker = Mock()
        worker = Mock()
        worker_class.return_value = worker

        controller.maybe_start_answer()

        worker_class.assert_called_once_with(
            "session-text",
            "Prompt",
            None,
            "Consulta directa",
            controller.config,
            "test-key",
        )
        self.assertEqual(controller.answer_mode, "text_only")
        controller.start_worker.assert_called_once_with(worker, controller.handle_answer_result)

    def test_phone_transcription_is_kept_internal(self) -> None:
        controller = bare_controller()
        controller.session_id = "session-2"
        controller.vision_done = True
        controller.maybe_start_answer = Mock()

        controller.handle_audio_result("session-2", True, "Pregunta transcripta")

        self.assertEqual(controller.audio_text, "Pregunta transcripta")
        controller.overlay.show_status.assert_called_once_with("ANALIZANDO IMAGEN…")
        controller.overlay.show_question.assert_not_called()
        controller.overlay.begin_answer.assert_not_called()

    def test_phone_transcription_without_image_uses_text_status(self) -> None:
        controller = bare_controller()
        controller.config.send_image = False
        controller.session_id = "session-text"
        controller.maybe_start_answer = Mock()

        controller.handle_audio_result("session-text", True, "Pregunta transcripta")

        controller.overlay.show_status.assert_called_once_with("PENSANDO…")

    def test_media_paused_by_current_cycle_is_restored(self) -> None:
        controller = bare_controller()
        controller.session_id = "cycle"
        controller.media_pause_cycle_id = "cycle"
        controller.state = "grabando"

        controller.handle_media_pause_completed("cycle", "browser", True)
        self.assertTrue(controller.media_paused_by_cycle)
        controller.resume_media_if_needed()

        controller.media_controller.resume.assert_called_once_with("browser")

    def test_late_media_pause_is_immediately_restored(self) -> None:
        controller = bare_controller()
        controller.session_id = None
        controller.state = "resultado"

        controller.handle_media_pause_completed("old-cycle", "browser", True)

        controller.media_controller.resume.assert_called_once_with("browser")

    def test_rate_limit_failure_does_not_start_ocr(self) -> None:
        controller = bare_controller()
        controller.session_id = "session-rate"
        controller.answer_mode = "multimodal"
        controller.start_ocr_fallback = Mock()

        controller.handle_answer_result("session-rate", False, "__INSCREEN_RATE_LIMIT__:4")

        controller.start_ocr_fallback.assert_not_called()
        controller.overlay.show_error.assert_called_once()

    @patch("app.VisionWorker")
    def test_multimodal_failure_starts_ocr_fallback(self, vision_worker) -> None:
        controller = bare_controller()
        controller.session_id = "session-fallback"
        controller.image_data_url = "data:image/png;base64,captured"
        controller.answer_mode = "multimodal"
        controller.answer_started = True
        controller.start_worker = Mock()
        worker = Mock()
        vision_worker.return_value = worker

        controller.handle_answer_result("session-fallback", False, "fallo visual")

        self.assertTrue(controller.fallback_started)
        self.assertEqual(controller.answer_mode, "ocr_fallback")
        vision_worker.assert_called_once_with(
            "session-fallback",
            "data:image/png;base64,captured",
            controller.config,
            "test-key",
        )
        controller.start_worker.assert_called_once_with(worker, controller.handle_vision_result)

    def test_successful_ocr_fallback_starts_text_answer(self) -> None:
        controller = bare_controller()
        controller.session_id = "session-ocr"
        controller.fallback_started = True
        controller.answer_mode = "ocr_fallback"
        controller.start_text_fallback = Mock()

        controller.handle_vision_result("session-ocr", True, "Material recuperado")

        self.assertTrue(controller.vision_done)
        self.assertEqual(controller.vision_text, "Material recuperado")
        controller.start_text_fallback.assert_called_once_with()

    def test_ocr_fallback_timeout_ends_session(self) -> None:
        controller = bare_controller()
        controller.session_id = "session-timeout"
        controller.fallback_started = True
        controller.answer_mode = "ocr_fallback"

        controller.handle_fallback_ocr_timeout("session-timeout")

        self.assertEqual(controller.state, "resultado")
        self.assertEqual(controller.answer_mode, "fallback_failed")
        controller.overlay.show_error.assert_called_once()


if __name__ == "__main__":
    unittest.main()
