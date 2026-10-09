import json
import os
import time
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import Mock, patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QObject, Qt
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication

from app import (
    AnswerWorker, AppConfig, AudioTranscriptionWorker, ModelCatalogWorker,
    ModelSettingsDialog, MultimodalAnswerWorker, VisionWorker,
    decode_model_failure, encode_model_failure, is_model_failure,
    load_config, save_preferences,
)
from test_controller import bare_controller


def model_error(code="model_decommissioned", status=400):
    error = RuntimeError("The model has been decommissioned")
    error.status_code = status
    error.body = {"error": {"code": code}}
    return error


class ModelTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def controller(self):
        controller = bare_controller()
        QObject.__init__(controller)
        controller.worker_attempts = {}
        controller.settings_dialog = None
        controller.recovery_dialog = None
        controller.retained_audio = None
        controller.session_id = "session"
        return controller

    def wait_for(self, predicate):
        deadline = time.monotonic() + 5
        while not predicate() and time.monotonic() < deadline:
            self.app.processEvents()
            time.sleep(0.005)
        self.assertTrue(predicate())

    def test_failure_classification(self):
        for code in ("model_decommissioned", "model_not_found", "model_permission_denied"):
            self.assertTrue(is_model_failure(model_error(code)))
        for status in (401, 429, 500, 503):
            self.assertFalse(is_model_failure(model_error(status=status)))
        for message in ("Connection failed", "model does not support image inputs", "Invalid API key"):
            error = RuntimeError(message)
            error.status_code = 400
            self.assertFalse(is_model_failure(error))
        encoded = encode_model_failure(model_error(), "vision", "old")
        self.assertEqual(decode_model_failure(encoded)["model"], "old")
        self.assertIsNone(decode_model_failure("ordinary error"))

    def test_config_backward_compatibility_and_atomic_merge(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "config.json"
            path.write_text(json.dumps({"language": "en", "custom": 42}), encoding="utf-8")
            with patch("app.APP_DIR", root):
                self.assertEqual(load_config().vision_model, AppConfig().vision_model)
                save_preferences({"vision_model": "new-vision", "text_model": "new-text"})
                self.assertEqual(load_config().text_model, "new-text")
            self.assertEqual(json.loads(path.read_text())["custom"], 42)
            self.assertFalse(path.with_suffix(".json.tmp").exists())

    def test_workers_use_config_snapshot(self):
        config = AppConfig(vision_model="custom-vision", text_model="custom-text")
        workers = [VisionWorker("s", "image", config, "key"),
                   MultimodalAnswerWorker("s", "prompt", "image", "question", config, "key"),
                   AnswerWorker("s", "prompt", None, "question", config, "key")]
        config.vision_model = "changed"
        config.text_model = "changed"
        for worker in workers:
            client = Mock()
            client.chat.completions.create.side_effect = model_error()
            results = []
            worker.completed.connect(lambda sid, ok, result: results.append(result))
            with patch("app.OpenAI", return_value=client):
                worker.run()
            expected = "custom-text" if isinstance(worker, AnswerWorker) else "custom-vision"
            self.assertEqual(client.chat.completions.create.call_args.kwargs["model"], expected)
            self.assertEqual(decode_model_failure(results[0])["model"], expected)

    def test_audio_retained_only_for_model_failure(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "audio.m4a"
            for error, retained in ((model_error(), True), (RuntimeError("network"), False)):
                path.write_bytes(b"audio")
                client = Mock()
                client.audio.transcriptions.create.side_effect = error
                with patch("app.OpenAI", return_value=client):
                    AudioTranscriptionWorker("s", path, "audio/mp4", AppConfig(audio_model="custom-audio"), "key").run()
                self.assertEqual(path.exists(), retained)
                self.assertEqual(client.audio.transcriptions.create.call_args.kwargs["model"], "custom-audio")

    def test_catalog_uses_provider_and_returns_sorted_models(self):
        client = Mock()
        client.models.list.return_value = [
            SimpleNamespace(id="z", active=True, name="Z", input_modalities=["text"], output_modalities=["text"]),
            SimpleNamespace(id="a", active=True, name="A", input_modalities=["text", "image"], output_modalities=["text"]),
        ]
        factory = Mock()
        factory.return_value.__enter__ = Mock(return_value=client)
        factory.return_value.__exit__ = Mock(return_value=False)
        worker = ModelCatalogWorker("request", "key")
        results = []
        worker.completed.connect(lambda sid, ok, value: results.append((ok, [item["id"] for item in json.loads(value)])))
        with patch("app.OpenAI", factory):
            worker.run()
        self.assertEqual(results, [(True, ["a", "z"])])

    def test_dialog_catalog_errors_empty_and_stale_results(self):
        controller = self.controller()
        dialog = ModelSettingsDialog(controller)
        dialog.show()
        dialog.request_id = "new"
        dialog.loaded("old", True, '["stale"]')
        self.assertEqual(dialog.fields["vision_model"].count(), 0)
        dialog.loaded("new", True, '[]')
        self.assertIn("vacío", dialog.status.text())
        dialog.loaded("new", False, "sin conexión")
        self.assertIn("manualmente", dialog.status.text())
        dialog.fields["vision_model"].setEditText("manual-model")
        with patch("app.save_preferences") as save:
            dialog.save()
            self.assertEqual(save.call_args.args[0]["vision_model"], "manual-model")
        dialog.loaded("new", True, '[{"id":"late","input_modalities":["text"],"output_modalities":["text"]}]')
        self.assertEqual(dialog.fields["vision_model"].count(), 0)

    def test_dialog_escape_does_not_save(self):
        controller = self.controller()
        dialog = ModelSettingsDialog(controller)
        dialog.show()
        dialog.fields["text_model"].setEditText("unsaved")
        with patch("app.save_preferences") as save:
            QTest.keyClick(dialog, Qt.Key_Escape)
            save.assert_not_called()
        self.assertFalse(dialog.isVisible())
        self.assertNotEqual(controller.config.text_model, "unsaved")

    def test_dialog_filters_catalog_by_model_modalities(self):
        controller = self.controller()
        dialog = ModelSettingsDialog(controller)
        dialog.show()
        dialog.request_id = "catalog"
        models = [
            {"id": "vision", "input_modalities": ["text", "image"], "output_modalities": ["text"]},
            {"id": "text", "input_modalities": ["text"], "output_modalities": ["text"]},
            {"id": "audio", "input_modalities": ["audio"], "output_modalities": ["transcription"]},
        ]
        dialog.loaded("catalog", True, json.dumps(models))
        self.assertEqual([dialog.fields["vision_model"].itemText(i) for i in range(dialog.fields["vision_model"].count())], ["vision"])
        self.assertEqual([dialog.fields["text_model"].itemText(i) for i in range(dialog.fields["text_model"].count())], ["vision", "text"])
        self.assertEqual([dialog.fields["audio_model"].itemText(i) for i in range(dialog.fields["audio_model"].count())], ["audio"])
        dialog.reject()

    def test_recovery_retries_original_question_and_ignores_old_attempt(self):
        controller = self.controller()
        controller.answer_mode = "multimodal"
        worker = MultimodalAnswerWorker("session", "prompt", "original-image", "original-question", controller.config, "key")
        handler = Mock()
        controller.worker_attempts["attempt"] = ("session", worker, handler)
        with patch.object(ModelSettingsDialog, "refresh"), patch.object(controller, "start_worker") as start:
            controller.dispatch_worker_result("attempt", False, encode_model_failure(model_error(), "multimodal", "old"))
            handler.assert_not_called()
            dialog = controller.recovery_dialog
            dialog.fields["vision_model"].setEditText("replacement")
            with patch("app.save_preferences"):
                dialog.save()
            retry = start.call_args.args[0]
            self.assertEqual((retry.question, retry.image_data_url), ("original-question", "original-image"))
            self.assertEqual(retry.config.vision_model, "replacement")
            controller.dispatch_worker_result("attempt", True, "late")
            handler.assert_not_called()

    def test_cancel_recovery_cleans_audio_and_ignores_catalog(self):
        controller = self.controller()
        with TemporaryDirectory() as directory:
            path = Path(directory) / "audio.m4a"
            path.write_bytes(b"audio")
            worker = AudioTranscriptionWorker("session", path, "audio/mp4", controller.config, "key")
            with patch.object(ModelSettingsDialog, "refresh"):
                controller.recover_model(worker, Mock(), {"role": "audio", "model": "old", "detail": "retired"}, "session")
            dialog = controller.recovery_dialog
            dialog.request_id = "catalog"
            controller.handle_dismiss()
            self.assertFalse(path.exists())
            self.assertIsNone(controller.session_id)
            self.assertFalse(dialog.isVisible())
            dialog.loaded("catalog", True, '["new"]')
            self.assertFalse(dialog.isVisible())

    def test_threaded_failure_and_successful_retry(self):
        controller = self.controller()
        controller.state = "respondiendo"
        controller.answer_mode = "text_only"
        client = Mock()
        client.chat.completions.create.side_effect = [model_error(), [SimpleNamespace(choices=[SimpleNamespace(delta=SimpleNamespace(content="ok"))])]]
        worker = AnswerWorker("session", "prompt", None, "question", controller.config, "key")
        with patch("app.OpenAI", return_value=client), patch.object(ModelSettingsDialog, "refresh"), patch("app.save_preferences"):
            controller.start_worker(worker, controller.handle_answer_result)
            self.wait_for(lambda: controller.recovery_dialog is not None)
            controller.recovery_dialog.fields["text_model"].setEditText("replacement")
            controller.recovery_dialog.save()
            self.wait_for(lambda: controller.state == "resultado" and not controller.active_jobs)
        controller.overlay.finish_answer.assert_called_once_with("ok")
        self.assertFalse(controller.worker_attempts)

    def test_cancel_states_and_ignore_late_results(self):
        for state in ("escribiendo", "grabando", "respondiendo", "transcribiendo", "resultado"):
            controller = self.controller()
            controller.state = state
            controller.mobile_server.stop_recording = Mock()
            worker = Mock(session_id="attempt", original_session_id="session")
            controller.active_jobs = [(Mock(), worker)]
            controller.handle_dismiss()
            worker.cancel.assert_called_once()
            controller.overlay.hide.assert_called_once()
            controller.handle_answer_result("session", True, "late")
            controller.overlay.finish_answer.assert_not_called()

    def test_failed_replacement_waits_for_user_and_ocr_timer_is_stale(self):
        controller = self.controller()
        controller.answer_mode = "ocr_fallback"
        controller.fallback_started = True
        worker = VisionWorker("session", "image", controller.config, "key")
        controller.worker_attempts["attempt"] = ("session", worker, controller.handle_vision_result)
        with patch.object(ModelSettingsDialog, "refresh"), patch.object(controller, "start_worker") as start:
            controller.dispatch_worker_result("attempt", False, encode_model_failure(model_error(), "vision", "old"))
            controller.handle_attempt_ocr_timeout("session", "attempt")
            self.assertEqual(controller.state, "modelo_no_disponible")
            controller.recovery_dialog.fields["vision_model"].setEditText("replacement")
            with patch("app.save_preferences"):
                controller.recovery_dialog.save()
            retry = start.call_args.args[0]
            start.reset_mock()
            controller.worker_attempts["retry"] = ("session", retry, controller.handle_vision_result)
            controller.dispatch_worker_result("retry", False, encode_model_failure(model_error(), "vision", "replacement"))
            start.assert_not_called()
            self.assertTrue(controller.recovery_dialog.isVisible())
            controller.cancel_and_hide()

    def test_non_model_failure_uses_normal_handler(self):
        controller = self.controller()
        for message in ("invalid API key", "rate limited", "connection failed", "model does not support images"):
            handler = Mock()
            worker = AnswerWorker("session", "prompt", None, "question", controller.config, "key")
            controller.worker_attempts["attempt"] = ("session", worker, handler)
            controller.dispatch_worker_result("attempt", False, message)
            handler.assert_called_once_with("session", False, message)
            self.assertIsNone(controller.recovery_dialog)

    def test_audio_receive_timeout_does_not_override_received_audio(self):
        controller = self.controller()
        controller.state = "transcribiendo"
        controller.session_subjects["session"] = "subject"
        controller.audio_receive_timeout("session")
        controller.overlay.show_error.assert_not_called()

    def test_typing_model_id_does_not_trigger_capture(self):
        controller = self.controller()
        dialog = ModelSettingsDialog(controller)
        controller.settings_dialog = dialog
        dialog.show()
        with patch.object(controller, "start_cycle") as start:
            controller.handle_hotkey()
            start.assert_not_called()
        dialog.reject()


if __name__ == "__main__":
    unittest.main()
