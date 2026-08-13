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
        apriori = (ROOT / "Apriori" / "app.js").read_text(encoding="utf-8")
        main_activity = (ROOT / "mobile_android" / "app" / "src" / "main" / "java" / "com" / "inscreen" / "mic" / "MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn('id="headerEditor"', html)
        self.assertNotIn('id="editorOverlay"', html)
        self.assertIn("visualViewport", script)
        self.assertIn("InScreenApriori?.removeModule", apriori)
        bridge = main_activity.split("private inner class AprioriBridge", 1)[1].split("private fun createConnectionPage", 1)[0]
        self.assertIn("@JavascriptInterface fun removeModule", bridge)


if __name__ == "__main__":
    unittest.main()
