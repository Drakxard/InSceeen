package com.inscreen.mic

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.pdf.PdfDocument
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.viewpager2.widget.ViewPager2
import com.github.barteksc.pdfviewer.PDFView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class WorkspacePdfTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun pdf(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val document = PdfDocument()
        try {
            listOf(Color.BLUE, Color.GREEN).forEachIndexed { index, color ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(300, 600, index + 1).create())
                page.canvas.drawColor(color)
                document.finishPage(page)
            }
            document.writeTo(output)
        } finally { document.close() }
        return output.toByteArray()
    }

    private fun <T : View> find(view: View, type: Class<T>): T? {
        if (type.isInstance(view)) return type.cast(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            find(view.getChildAt(index), type)?.let { return it }
        }
        return null
    }
    private fun reader(activity: WorkspacePdfActivity) = find(activity.window.decorView, PDFView::class.java)!!

    private fun withSession(bytes: ByteArray, block: (Intent) -> Unit) {
        val hash = WorkspaceLibraryStore.sha256(bytes)
        val file = File(context.filesDir, "workspace-library/pdf-instrumentation/blobs/$hash").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val session = File(context.cacheDir, "library-pdf/instrumentation.json").apply {
            parentFile!!.mkdirs()
            writeText(JSONObject().put("path", file.absolutePath).put("fileId", "pdf-test")
                .put("manifest", JSONObject().put("files", JSONArray().put(JSONObject().put("id", "pdf-test").put("hash", hash).put("mime", "application/pdf").put("path", "teoria/test.pdf"))).put("items", JSONArray())).toString())
        }
        // Each test starts at the first page even if the emulator previously ran it.
        context.getSharedPreferences("WorkspacePdfActivity", Context.MODE_PRIVATE).edit().remove(hash).commit()
        try { block(Intent(context, WorkspacePdfActivity::class.java).putExtra("session", session.absolutePath)) }
        finally { session.delete(); file.delete() }
    }

    private fun awaitState(scenario: ActivityScenario<WorkspacePdfActivity>, message: String, predicate: (WorkspacePdfActivity) -> Boolean) {
        repeat(150) {
            var ready = false
            scenario.onActivity { ready = predicate(it) }
            if (ready) return
            Thread.sleep(100)
        }
        fail(message)
    }

    private fun hasColor(view: PDFView, expected: Int): Boolean {
        if (view.width <= 0 || view.height <= 0) return false
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return try {
            view.draw(Canvas(bitmap))
            bitmap.getPixel(view.width / 2, view.height / 3) == expected
        } finally { bitmap.recycle() }
    }

    @Test fun rendersPdfOfflineWithContinuousZoomAndRestoresAfterRecreation() = withSession(pdf()) { intent ->
        ActivityScenario.launch<WorkspacePdfActivity>(intent).use { scenario ->
            awaitState(scenario, "PDFium must render the first PDF page") {
                reader(it).pageCount == 2 && reader(it).tag == "rendered" && hasColor(reader(it), Color.BLUE)
            }
            var horizontal = 0f
            scenario.onActivity {
                val view = reader(it)
                val root = view.parent as ViewGroup
                val buttons = (0 until root.childCount).map { index -> root.getChildAt(index) }.filterIsInstance<Button>()
                assertEquals(listOf("Maximizar PDF"), buttons.filter { button -> button.visibility == View.VISIBLE }.map { button -> button.contentDescription.toString() })
                val maximize = buttons.first { button -> button.contentDescription == "Maximizar PDF" }
                maximize.performClick()
                assertEquals("Salir de pantalla completa", maximize.contentDescription)
                maximize.performClick()
                assertEquals("Maximizar PDF", maximize.contentDescription)
                view.zoomCenteredTo(2f, PointF(view.width / 2f, view.height / 2f))
                horizontal = view.currentXOffset
                assertTrue(horizontal < 0)
                view.jumpTo(1, false)
                assertEquals(2f, view.zoom, 0.001f)
                assertEquals(horizontal, view.currentXOffset, 0.1f)
            }
            awaitState(scenario, "The next page must render at the same document zoom") {
                reader(it).currentPage == 1 && hasColor(reader(it), Color.GREEN)
            }
            scenario.recreate()
            awaitState(scenario, "Recreation must restore the page, zoom and horizontal position") {
                val view = reader(it)
                view.tag == "rendered" && view.currentPage == 1 &&
                    kotlin.math.abs(view.zoom - 2f) < 0.001f &&
                    kotlin.math.abs(view.currentXOffset - horizontal) < 1f && hasColor(view, Color.GREEN)
            }
            scenario.onActivity {
                val view = reader(it)
                view.zoomCenteredTo(1f, PointF(view.width / 2f, view.height / 2f))
                assertEquals(0f, view.currentXOffset, 0.1f)
            }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            repeat(100) {
                if (scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED) return@withSession
                Thread.sleep(50)
            }
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
        }
    }

    @Test fun rejectsInvalidPdfAndDeviceBackClosesReader() = withSession("not a PDF".toByteArray()) { intent ->
        ActivityScenario.launch<WorkspacePdfActivity>(intent).use { scenario ->
            awaitState(scenario, "The reader must report an invalid PDF") {
                val root = reader(it).parent as ViewGroup
                (0 until root.childCount).map { index -> root.getChildAt(index) }.filterIsInstance<TextView>()
                    .any { label -> label.visibility == View.VISIBLE && label.text.contains("No se pudo abrir") }
            }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            repeat(100) {
                if (scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED) return@withSession
                Thread.sleep(50)
            }
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
        }
    }

    @Test fun repeatedDeviceBackReturnsToTheSameVisibleHost() = withSession(pdf()) { intent ->
        val session = File(intent.getStringExtra("session")!!)
        val sessionText = session.readText()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { host ->
            host.onActivity { find(it.window.decorView, ViewPager2::class.java)!!.setCurrentItem(0, false) }
            var booted = false
            for (poll in 0 until 150) {
                host.onActivity { booted = find(it.window.decorView, WorkspaceLibraryView::class.java)?.url != null }
                if (booted) break
                Thread.sleep(100)
            }
            assertTrue("The host must finish selecting its startup package", booted)
            // Exercise Android lifecycle independently of the emulator's old system
            // WebView. The production React screens are covered by browser tests.
            host.onActivity {
                find(it.window.decorView, WorkspaceLibraryView::class.java)!!.loadDataWithBaseURL(
                    "https://workspace.inscreen.local/", "<button>Host de lectura</button><script>window.pdfHostProbe='same-screen'</script>",
                    "text/html", "UTF-8", null,
                )
            }
            fun evaluate(script: String): String {
                val done = CountDownLatch(1)
                val result = AtomicReference("")
                host.onActivity { activity ->
                    find(activity.window.decorView, WorkspaceLibraryView::class.java)!!.evaluateJavascript(script) {
                        result.set(it); done.countDown()
                    }
                }
                assertTrue(done.await(5, TimeUnit.SECONDS))
                return result.get()
            }
            for (poll in 0 until 150) {
                if (evaluate("Boolean(document.body && document.body.innerText.trim())") == "true") break
                Thread.sleep(100)
            }
            assertEquals("true", evaluate("window.pdfHostProbe='same-screen'; Boolean(document.body.innerText.trim())"))
            repeat(3) { attempt ->
                val nextSession = File(session.parentFile, "repeat-$attempt.json").apply { writeText(sessionText) }
                host.onActivity { it.startActivity(Intent(intent).putExtra("session", nextSession.absolutePath)) }
                var rendered = false
                for (poll in 0 until 150) {
                    instrumentation.runOnMainSync {
                        val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<WorkspacePdfActivity>().firstOrNull()
                        rendered = activity != null && (attempt == 1 || reader(activity).tag == "rendered")
                    }
                    if (rendered) break
                    Thread.sleep(100)
                }
                assertTrue("PDF must open before device Back", rendered)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                for (poll in 0 until 100) {
                    if (host.state == androidx.lifecycle.Lifecycle.State.RESUMED) break
                    Thread.sleep(50)
                }
                assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, host.state)
                assertEquals("true", evaluate("window.pdfHostProbe==='same-screen' && Boolean(document.body.innerText.trim()) && document.visibilityState==='visible'"))
            }
        }
    }
}
