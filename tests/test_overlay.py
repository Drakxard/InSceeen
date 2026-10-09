import json
import os
import time
import unittest
from pathlib import Path

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
os.environ.setdefault("QTWEBENGINE_CHROMIUM_FLAGS", "--disable-gpu")

from PySide6.QtCore import QPoint, QRect, Qt
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

    def test_bold_italic_and_math_are_rendered_without_visible_markers(self) -> None:
        overlay = self.ready_overlay()
        overlay.append_answer(r"Un **sustantivo** y *perro* con \(x^2\).")
        self.wait_for(
            lambda: (
                (metrics := json.loads(self.javascript(
                    overlay.answer, "JSON.stringify(window.inscreenMetrics())"
                )))["strongNodes"] == 1
                and metrics["emphasisNodes"] == 1
                and metrics["katexNodes"] >= 1
            )
        )
        metrics = json.loads(
            self.javascript(overlay.answer, "JSON.stringify(window.inscreenMetrics())")
        )
        self.assertNotIn("**", metrics["text"])
        self.assertNotIn("*perro*", metrics["text"])
        overlay.close()

    def test_markdown_renderer_does_not_execute_html(self) -> None:
        overlay = self.ready_overlay()
        overlay.append_answer('**seguro** <img src=x onerror="window.hacked=1">')
        self.process_for(0.2)
        metrics = json.loads(self.javascript(
            overlay.answer,
            "JSON.stringify({strong:document.querySelectorAll('strong').length,"
            "images:document.querySelectorAll('img').length,hacked:Boolean(window.hacked)})",
        ))
        self.assertEqual(metrics, {"strong": 1, "images": 0, "hacked": False})
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

    def test_keyboard_input_keeps_previous_answer_above_editor_then_replaces_it(self) -> None:
        overlay = self.ready_overlay()
        overlay.append_answer("Respuesta anterior")
        self.process_for(0.2)
        answer_position_before = overlay.answer_frame.mapToGlobal(QPoint(0, 0)).y()

        submitted = []
        overlay.question_submitted.connect(submitted.append)
        overlay.show_input()
        self.process_for(0.1)
        self.assertTrue(overlay.input_active)
        self.assertTrue(overlay.question_frame.isHidden())
        self.assertTrue(overlay.answer_frame.isVisible())
        self.assertIs(overlay.content_stack.currentWidget(), overlay.answer)
        self.assertTrue(overlay.answer.isVisible())
        self.assertEqual(overlay.answer.raw_text, "Respuesta anterior")
        self.assertEqual(overlay.prompt_marker.text(), ">")
        self.assertEqual(overlay.question_input.placeholderText(), "")
        self.assertTrue(overlay.keyboard_prompt_frame.isVisible())
        self.assertLess(
            overlay.answer_frame.mapToGlobal(QPoint(0, 0)).y(),
            answer_position_before,
        )
        self.assertGreater(
            overlay.keyboard_prompt_frame.geometry().top(),
            overlay.answer_frame.geometry().bottom(),
        )
        self.assertLessEqual(overlay.height(), int(800 * 0.85) + 2)

        QTest.keyClicks(overlay.question_input, "Nueva pregunta")
        QTest.keyClick(overlay.question_input, Qt.Key_Return)
        self.assertEqual(submitted, ["Nueva pregunta"])

        overlay.mark_keyboard_question_submitted()
        self.assertTrue(overlay.input_active)
        self.assertTrue(overlay.answer.isVisible())
        self.assertTrue(overlay.question_frame.isHidden())
        self.assertIs(overlay.content_stack.currentWidget(), overlay.answer)
        self.assertEqual(overlay.question_input.toPlainText(), "Nueva pregunta")
        self.assertEqual(overlay.answer.raw_text, "Respuesta anterior")
        self.assertTrue(overlay.question_input.isReadOnly())
        geometry = overlay.geometry()
        overlay.show_status("PENSANDO…")
        overlay.show_countdown(5)
        self.assertEqual(overlay.geometry(), geometry)
        self.assertEqual(overlay.answer.raw_text, "Respuesta anterior")
        self.assertEqual(overlay.question_input.toPlainText(), "Nueva pregunta")
        QTest.keyClick(overlay.question_input, Qt.Key_Return)
        self.assertEqual(submitted, ["Nueva pregunta"])

        overlay.present_keyboard_answer("Nueva respuesta")
        self.assertEqual(overlay.answer.raw_text, "Nueva respuesta")
        self.assertEqual(overlay.question_input.toPlainText(), "Nueva pregunta")
        self.assertTrue(overlay.question_input.isReadOnly())
        self.wait_for(lambda: not overlay.question_input.isReadOnly())
        self.assertEqual(overlay.question_input.toPlainText(), "")
        self.assertFalse(overlay.question_input.isReadOnly())
        QTest.keyClicks(overlay.question_input, "Otra consulta")
        overlay.show_input()
        self.assertEqual(overlay.question_input.toPlainText(), "Otra consulta")
        overlay.close()

    def test_replacing_answer_never_empties_visible_dom(self) -> None:
        overlay = self.ready_overlay()
        overlay.append_answer("Respuesta anterior")
        self.wait_for(lambda: self.javascript(overlay.answer, "document.body.innerText").strip() == "Respuesta anterior")
        self.javascript(overlay.answer, """
            window.emptyReplacements = 0;
            const content = document.getElementById('content');
            const replace = content.replaceChildren.bind(content);
            content.replaceChildren = (...nodes) => {
                if (!nodes.length) window.emptyReplacements++;
                return replace(...nodes);
            };
            true;
        """)
        overlay.answer.set_text(r"Nueva respuesta con \(x^2\)", immediate=True)
        self.wait_for(lambda: self.javascript(overlay.answer, "document.querySelectorAll('.katex').length") == 1)
        self.assertEqual(self.javascript(overlay.answer, "window.emptyReplacements"), 0)
        overlay.close()

    def test_shorter_followup_keeps_independent_prompt_below_answer(self) -> None:
        overlay = self.ready_overlay()
        overlay.append_answer("\n".join("Respuesta extensa" for _ in range(12)))
        overlay.show_input()
        self.wait_for(lambda: overlay.answer.content_height > 200)
        QTest.keyClicks(overlay.question_input, "Resume")
        overlay.mark_keyboard_question_submitted()
        overlay.present_keyboard_answer("Resumen breve")
        self.wait_for(lambda: not overlay.question_input.isReadOnly())
        self.assertEqual(overlay.answer.raw_text, "Resumen breve")
        self.assertTrue(overlay.answer_frame.isVisible())
        self.assertTrue(overlay.keyboard_prompt_frame.isVisible())
        self.assertGreater(
            overlay.keyboard_prompt_frame.mapToGlobal(QPoint(0, 0)).y(),
            overlay.answer_frame.mapToGlobal(QPoint(0, 0)).y(),
        )
        overlay.close()

    def test_first_keyboard_input_does_not_show_empty_previous_answer(self) -> None:
        overlay = TerminalOverlay(18, 0.76)
        overlay.show_input()
        self.process_for(0.05)
        self.assertFalse(overlay.answer.isVisible())
        self.assertFalse(overlay.answer._renderer_started)
        self.assertTrue(overlay.question_input.hasFocus())
        overlay.close()

    def test_first_answer_keeps_submitted_question_until_render_is_ready(self) -> None:
        overlay = TerminalOverlay(18, 0.76)
        overlay.show_input()
        self.process_for(0.05)
        QTest.keyClicks(overlay.question_input, "Primera consulta")
        overlay.mark_keyboard_question_submitted()
        current_widget = overlay.content_stack.currentWidget()

        overlay.present_keyboard_answer(r"Respuesta con \(x^2\)")

        self.assertIs(overlay.content_stack.currentWidget(), current_widget)
        self.assertEqual(overlay.question_input.toPlainText(), "Primera consulta")
        self.assertTrue(overlay.question_input.isReadOnly())
        self.wait_for(lambda: not overlay.question_input.isReadOnly())
        self.assertEqual(overlay.question_input.toPlainText(), "")
        self.assertTrue(overlay.answer.isVisible())
        self.assertEqual(overlay.answer.raw_text, r"Respuesta con \(x^2\)")
        overlay.close()

    def test_keyboard_error_preserves_prompt_for_retry(self) -> None:
        overlay = TerminalOverlay(18, 0.76)
        overlay.show_input()
        self.process_for(0.05)
        QTest.keyClicks(overlay.question_input, "Mi pregunta")
        overlay.mark_keyboard_question_submitted()
        overlay.present_keyboard_error("Error", "Vuelve a intentar")
        self.assertTrue(overlay.input_active)
        self.assertFalse(overlay.question_input.isReadOnly())
        self.assertEqual(overlay.question_input.toPlainText(), "Mi pregunta")
        self.assertEqual(overlay.prompt_marker.text(), "!")
        overlay.show_status("PENSANDO…")
        self.assertEqual(overlay.prompt_marker.text(), ">")
        overlay.close()

    def test_long_previous_answer_leaves_room_for_separate_prompt(self) -> None:
        overlay = self.ready_overlay(height=500)
        overlay.append_answer("\n".join(f"Respuesta {index}" for index in range(150)))
        overlay.show_input()
        self.wait_for(lambda: overlay.answer.content_height > 1000)
        self.process_for(0.1)
        self.assertLessEqual(overlay.height(), int(500 * 0.85) + 2)
        self.assertGreater(
            overlay.keyboard_prompt_frame.geometry().top(),
            overlay.answer_frame.geometry().bottom(),
        )
        self.assertTrue(overlay.question_input.isVisible())
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
