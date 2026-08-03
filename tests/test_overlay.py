import json
import os
import time
import unittest
from pathlib import Path

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
os.environ.setdefault("QTWEBENGINE_CHROMIUM_FLAGS", "--disable-gpu")

from PySide6.QtCore import QRect, Qt
from PySide6.QtGui import QFont
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication

from app import MathAnswerView, TerminalOverlay


class OverlayTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.app = QApplication.instance() or QApplication([])

    def wait_for(self, predicate, timeout: float = 8.0) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            self.app.processEvents()
            if predicate():
                return
            time.sleep(0.01)
        self.fail("Se agotó el tiempo esperando a Qt WebEngine")

    def javascript(self, view: MathAnswerView, script: str):
        results = []
        view.run_javascript(script, results.append)
        self.wait_for(lambda: bool(results), timeout=3.0)
        return results[0]

    def process_for(self, seconds: float) -> None:
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            self.app.processEvents()
            time.sleep(0.01)

    def ready_overlay(self, height: int = 800) -> TerminalOverlay:
        overlay = TerminalOverlay(18, 0.76)
        overlay.target_geometry = QRect(0, 0, 1200, height)
        overlay.show_question("Pregunta")
        overlay.begin_answer()
        self.wait_for(lambda: overlay.answer.is_ready or overlay.answer.is_fallback)
        self.assertTrue(overlay.answer.is_ready, "KaTeX debe cargar en las pruebas")
        self.assertTrue(overlay.answer.profile.isOffTheRecord())
        return overlay

    def test_katex_renders_supported_math_nodes(self) -> None:
        overlay = self.ready_overlay()
        answer = (
            r"Pendiente \(m=2\), raíz \(\sqrt{x}\), potencia \(x^2\), "
            r"desigualdad \(x\le y\), griega \(\alpha\) e integral \(\int_0^1 x\,dx\). "
            r"\[\frac{a+b}{c}\] \[\begin{matrix}1&2\\3&4\end{matrix}\]"
        )
        overlay.append_answer(answer)
        self.wait_for(
            lambda: self.javascript(
                overlay.answer, 'document.querySelectorAll(".katex").length'
            )
            >= 8
        )
        self.assertGreaterEqual(
            self.javascript(overlay.answer, 'document.querySelectorAll(".katex-display").length'),
            2,
        )
        overlay.close()

    def test_incomplete_and_invalid_latex_remain_visible(self) -> None:
        overlay = self.ready_overlay()
        raw = r"Incompleta \(\frac{a}{b} y no compatible \(\comandoInexistente{x}\)."
        overlay.append_answer(raw)
        self.wait_for(lambda: "Incompleta" in self.javascript(overlay.answer, "document.body.innerText"))
        visible = self.javascript(overlay.answer, "document.body.innerText")
        self.assertIn(r"\frac{a}{b}", visible)
        self.assertIn(r"\comandoInexistente", visible)
        overlay.append_answer(r" Luego llega una válida \(x^2\).")
        self.wait_for(
            lambda: self.javascript(
                overlay.answer, 'document.querySelectorAll(".katex").length'
            )
            >= 1
        )
        overlay.close()

    def test_html_is_literal_and_cannot_execute(self) -> None:
        overlay = self.ready_overlay()
        payload = '<img src="https://example.invalid/x" onerror="window.hacked=1"><script>window.hacked=2</script>'
        overlay.append_answer(payload)
        self.wait_for(lambda: "example.invalid" in self.javascript(overlay.answer, "document.body.innerText"))
        result = self.javascript(
            overlay.answer,
            "JSON.stringify({images:document.querySelectorAll('img').length,"
            "scripts:document.querySelectorAll('script').length,hacked:Boolean(window.hacked)})",
        )
        metrics = json.loads(result)
        self.assertEqual(metrics["images"], 0)
        self.assertEqual(metrics["scripts"], 4)
        self.assertFalse(metrics["hacked"])
        overlay.close()

    def test_long_answer_is_scrollable_and_height_is_capped(self) -> None:
        overlay = self.ready_overlay(height=800)
        overlay.append_answer("\n".join(f"Línea extensa número {index}" for index in range(200)))
        self.wait_for(lambda: overlay.answer.content_height > 1000)
        metrics = json.loads(
            self.javascript(overlay.answer, "JSON.stringify(window.inscreenMetrics())")
        )
        self.assertLessEqual(overlay.height(), int(800 * 0.85) + 2)
        self.assertGreater(metrics["scrollHeight"], metrics["clientHeight"])
        overlay.close()

    def test_stream_follows_bottom_and_preserves_manual_position(self) -> None:
        overlay = self.ready_overlay(height=500)
        overlay.append_answer("\n".join(f"Línea {index}" for index in range(160)))
        self.wait_for(lambda: overlay.answer.content_height > 1000)
        self.process_for(0.25)

        self.javascript(overlay.answer, "window.scrollTo(0, 120); true")
        self.process_for(0.1)
        before = json.loads(
            self.javascript(overlay.answer, "JSON.stringify(window.inscreenMetrics())")
        )["scrollTop"]
        overlay.append_answer("\nLínea nueva mientras se lee arriba")
        self.process_for(0.25)
        after = json.loads(
            self.javascript(overlay.answer, "JSON.stringify(window.inscreenMetrics())")
        )["scrollTop"]
        self.assertAlmostEqual(after, before, delta=2)

        self.javascript(overlay.answer, "window.scrollTo(0, document.documentElement.scrollHeight); true")
        self.wait_for(
            lambda: (
                (metrics := json.loads(
                    self.javascript(
                        overlay.answer,
                        "JSON.stringify(window.inscreenMetrics())",
                    )
                ))["scrollTop"]
                >= metrics["scrollHeight"] - metrics["clientHeight"] - 2
            )
        )
        overlay.append_answer("\nÚltima línea")
        self.process_for(0.25)
        self.wait_for(
            lambda: (
                (metrics := json.loads(
                    self.javascript(
                        overlay.answer,
                        "JSON.stringify(window.inscreenMetrics())",
                    )
                ))["scrollTop"]
                >= metrics["scrollHeight"] - metrics["clientHeight"] - 2
            )
        )
        final = json.loads(
            self.javascript(overlay.answer, "JSON.stringify(window.inscreenMetrics())")
        )
        self.assertAlmostEqual(
            final["scrollTop"],
            final["scrollHeight"] - final["clientHeight"],
            delta=2,
        )
        overlay.close()

    def test_fallback_preserves_unmodified_latex(self) -> None:
        missing = Path(__file__).with_name("missing-math-page.html")
        view = MathAnswerView(QFont("Consolas", 18), page_path=missing)
        value = r"Sea \(x \leq y\) y \[\frac{a}{b}\]."
        view.set_text(value)
        self.assertTrue(view.is_fallback)
        self.assertEqual(view.fallback.toPlainText(), value)
        view.close()

    def test_keyboard_input_replaces_answer_and_enter_submits(self) -> None:
        overlay = self.ready_overlay()
        overlay.append_answer("Respuesta anterior")
        self.process_for(0.2)

        submitted = []
        overlay.question_submitted.connect(submitted.append)
        overlay.show_input()
        self.process_for(0.1)
        self.assertTrue(overlay.input_active)
        self.assertTrue(overlay.question_frame.isHidden())
        self.assertTrue(overlay.answer_frame.isVisible())
        self.assertIs(overlay.content_stack.currentWidget(), overlay.keyboard_input_panel)

        QTest.keyClicks(overlay.question_input, "Nueva pregunta")
        QTest.keyClick(overlay.question_input, Qt.Key_Return)
        self.assertEqual(submitted, ["Nueva pregunta"])

        overlay.begin_answer()
        self.assertFalse(overlay.input_active)
        self.assertTrue(overlay.question_frame.isHidden())
        self.assertIs(overlay.content_stack.currentWidget(), overlay.answer)
        overlay.close()

    def test_photo_switch_is_compact_and_emits_changes(self) -> None:
        overlay = TerminalOverlay(18, 0.76, send_image=True)
        changes = []
        overlay.send_image_changed.connect(changes.append)

        self.assertTrue(overlay.photo_switch.isChecked())
        self.assertEqual(overlay.photo_switch.text(), "●")
        self.assertEqual(overlay.photo_switch.toolTip(), "Enviar foto")
        overlay.photo_switch.click()

        self.assertEqual(changes, [False])
        overlay.close()

    def test_renderer_is_lazy_until_answer_content_is_needed(self) -> None:
        overlay = TerminalOverlay(18, 0.76)
        self.assertFalse(overlay.answer._renderer_started)
        overlay.show_input()
        self.assertFalse(overlay.answer._renderer_started)
        overlay.close()

    def test_recording_shows_photo_switch(self) -> None:
        overlay = TerminalOverlay(18, 0.76)
        overlay.show_recording("Grabando")
        self.process_for(0.05)
        self.assertTrue(overlay.recording_active)
        self.assertTrue(overlay.photo_switch.isVisible())
        self.assertTrue(overlay.question_frame.isVisible())
        overlay.close()

    def test_countdown_contains_only_small_number(self) -> None:
        overlay = TerminalOverlay(18, 0.76)
        overlay.show_countdown(3)
        self.process_for(0.05)
        self.assertEqual(overlay.countdown_label.text(), "3")
        self.assertEqual(overlay.question.text(), "")
        self.assertLess(overlay.countdown_label.font().pointSize(), overlay.question.font().pointSize())
        overlay.show_countdown(2)
        self.assertEqual(overlay.countdown_label.text(), "2")
        overlay.show_countdown(1)
        self.assertEqual(overlay.countdown_label.text(), "1")
        overlay.show_countdown(0)
        self.assertEqual(overlay.countdown_label.text(), "")
        overlay.close()

    def test_status_error_and_answer_use_only_one_visible_block(self) -> None:
        overlay = self.ready_overlay()
        overlay.show_status("Procesando")
        self.assertTrue(overlay.question_frame.isVisible())
        self.assertTrue(overlay.answer_frame.isHidden())

        overlay.show_error("Error", "Detalle")
        self.assertTrue(overlay.question_frame.isHidden())
        self.assertTrue(overlay.answer_frame.isVisible())

        overlay.begin_answer()
        self.assertTrue(overlay.question_frame.isHidden())
        self.assertTrue(overlay.answer_frame.isVisible())
        overlay.close()


if __name__ == "__main__":
    unittest.main()
