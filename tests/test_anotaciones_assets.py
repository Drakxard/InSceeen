from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "modules" / "anotaciones"


class AnotacionesAssetsTest(unittest.TestCase):
    def test_folder_ui_card_gestures_and_native_module_removal_are_wired(self):
        html = (MODULE / "index.html").read_text(encoding="utf-8")
        script = (MODULE / "app.js").read_text(encoding="utf-8")
        styles = (MODULE / "styles.css").read_text(encoding="utf-8")
        apriori = (ROOT / "Apriori" / "app.js").read_text(encoding="utf-8")
        main_activity = (ROOT / "mobile_android" / "app" / "src" / "main" / "java" / "com" / "inscreen" / "mic" / "MainActivity.kt").read_text(encoding="utf-8")
        module_host = (ROOT / "mobile_android" / "app" / "src" / "main" / "java" / "com" / "inscreen" / "mic" / "ModuleHostActivity.kt").read_text(encoding="utf-8")
        self.assertIn('id="headerText" class="mixed-editor', html)
        self.assertIn('id="mathToggle"', html)
        self.assertIn('id="mathField"', html)
        self.assertIn('vendor/mathlive/mathlive.min.js', html)
        self.assertNotIn('id="editorOverlay"', html)
        self.assertNotIn('id="options"', html)
        self.assertNotIn('id="menu"', html)
        self.assertNotIn('<header>', html)
        self.assertNotIn('id="position"', html)
        self.assertNotIn('id="frontHint"', html)
        self.assertNotIn('id="backHint"', html)
        self.assertIn('id="folderBack"', html)
        self.assertNotIn('id="modeToggle"', html)
        self.assertIn('id="foldersView"', html)
        self.assertIn('id="addFolder"', html)
        self.assertIn('id="scrubber"', html)
        self.assertIn('id="scrubBubble"', html)
        self.assertIn('id="scrubBubbleText"', html)
        self.assertIn('id="importDialog"', html)
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
        self.assertIn("HOLD_MS=550", script)
        self.assertIn("setRecordProgress", script)
        self.assertIn("FONT_SCALE_KEY", script)
        self.assertIn("if(!editing)e.preventDefault()", script)
        self.assertIn("keyboardWasOpen&&!keyboard", script)
        self.assertIn("inscreen:atras", script)
        self.assertIn("renderMixed", script)
        self.assertIn("$('listening').addEventListener('click'", script)
        self.assertIn("core.scrubPosition", script)
        self.assertIn("Math.abs(dx)<=DRAG_TOLERANCE", script)
        self.assertIn("portapapeles", script)
        self.assertIn("module?.salir", script)
        self.assertIn('salir:()=>native.exitModule()', module_host)
        self.assertIn('@JavascriptInterface fun exitModule()', module_host)
        self.assertIn('ModuleBackPolicy.DISPATCH_SCRIPT', module_host)
        self.assertIn("#front::before", styles)
        self.assertIn("InScreenApriori?.removeModule", apriori)
        bridge = main_activity.split("private inner class AprioriBridge", 1)[1].split("private fun createConnectionPage", 1)[0]
        self.assertIn("@JavascriptInterface fun removeModule", bridge)
        self.assertIn("@JavascriptInterface fun openAssignedModule", bridge)
        self.assertIn("@JavascriptInterface fun removeSubjectData", bridge)
        self.assertNotIn("ModuleCache.from(this@MainActivity).reconcile(subjectIds)", bridge)

    def test_mathlive_is_bundled_locally_with_fonts_and_license(self):
        vendor = MODULE / "vendor" / "mathlive"
        self.assertGreater((vendor / "mathlive.min.js").stat().st_size, 500_000)
        self.assertTrue((vendor / "mathlive-static.css").is_file())
        self.assertTrue((vendor / "LICENSE.txt").is_file())
        self.assertGreaterEqual(len(list((vendor / "fonts").glob("*.woff2"))), 20)


if __name__ == "__main__":
    unittest.main()
