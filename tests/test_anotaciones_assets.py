from pathlib import Path
import unittest

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "modules" / "anotaciones"


class AnotacionesAssetsTest(unittest.TestCase):
    def test_mode_icons_are_matching_transparent_pngs(self):
        icons = [Image.open(MODULE / name) for name in ("mode-microphone.png", "mode-writing.png")]
        self.assertEqual([icon.size for icon in icons], [(256, 256), (256, 256)])
        for icon in icons:
            self.assertEqual(icon.mode, "RGBA")
            self.assertEqual(icon.getpixel((0, 0))[3], 0)
            self.assertIsNotNone(icon.getchannel("A").getbbox())

    def test_inline_editor_and_native_module_removal_are_wired(self):
        html = (MODULE / "index.html").read_text(encoding="utf-8")
        script = (MODULE / "app.js").read_text(encoding="utf-8")
        styles = (MODULE / "styles.css").read_text(encoding="utf-8")
        apriori = (ROOT / "Apriori" / "app.js").read_text(encoding="utf-8")
        main_activity = (ROOT / "mobile_android" / "app" / "src" / "main" / "java" / "com" / "inscreen" / "mic" / "MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn('id="headerEditor"', html)
        self.assertNotIn('id="editorOverlay"', html)
        self.assertNotIn('id="options"', html)
        self.assertNotIn('id="menu"', html)
        self.assertNotIn('<header>', html)
        self.assertNotIn('id="position"', html)
        self.assertNotIn('id="frontHint"', html)
        self.assertNotIn('id="backHint"', html)
        self.assertIn('<body>\n  <button id="modeToggle"', html)
        self.assertIn('id="undo"', html)
        self.assertIn('id="deleteIndicator"', html)
        self.assertIn('id="recordIndicator"', html)
        self.assertIn('id="fontControls"', html)
        self.assertIn('id="fontDecrease"', html)
        self.assertIn('id="fontIncrease"', html)
        self.assertIn('<svg viewBox="0 0 24 24"', html)
        self.assertIn("visualViewport", script)
        self.assertIn("removeUndoable", script)
        self.assertIn("SYSTEM_VOICE_KEY", script)
        self.assertIn("SWIPE_THRESHOLD=50", script)
        self.assertIn("setDeleteProgress", script)
        self.assertIn("heldAction='edit'", script)
        self.assertIn("setRecordProgress", script)
        self.assertIn("FONT_SCALE_KEY", script)
        self.assertIn("if(!editing)e.preventDefault()", script)
        self.assertIn("keyboardWasOpen&&!keyboard&&activeEditor", script)
        self.assertIn("$('listening').addEventListener('click'", script)
        self.assertIn("entrySign=exitX?-Math.sign(exitX):1", script)
        self.assertIn("#front::before", styles)
        self.assertIn("InScreenApriori?.removeModule", apriori)
        bridge = main_activity.split("private inner class AprioriBridge", 1)[1].split("private fun createConnectionPage", 1)[0]
        self.assertIn("@JavascriptInterface fun removeModule", bridge)
        self.assertIn("@JavascriptInterface fun openAssignedModule", bridge)
        self.assertIn("@JavascriptInterface fun removeSubjectData", bridge)
        self.assertNotIn("ModuleCache.from(this@MainActivity).reconcile(subjectIds)", bridge)


if __name__ == "__main__":
    unittest.main()
