package com.inscreen.mic

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
    @Test fun rendersRealPdfPagesOfflineWithBoundedMemoryAndRejectsInvalidDocuments() {
        val file = File(context.cacheDir, "native-pdf-engine-test.pdf")
        try {
            file.writeBytes(pdf())
            NativePdfDocument(file).use { document ->
                assertEquals(2, document.pageCount)
                val first = document.render(0, 600)
                assertEquals(600, first.width)
                assertEquals(1200, first.height)
                assertEquals(Color.BLUE, first.getPixel(100, 100))
                first.recycle()
                val second = document.render(1, 20000)
                assertTrue(second.width.toLong() * second.height <= 4_000_000)
                assertEquals(Color.GREEN, second.getPixel(100, 100))
                second.recycle()
                try { document.render(2, 600); fail("Debe rechazar páginas inexistentes") } catch (_: IllegalArgumentException) { }
            }
            file.writeText("not a PDF")
            var rejected = false
            try { NativePdfDocument(file).close() } catch (_: Exception) { rejected = true }
            assertTrue(rejected)
        } finally { file.delete() }
    }
    private fun hasPage(view: View): Boolean = (view is ZoomableImageView && view.contentDescription == "Página 1" && view.drawable != null) ||
        (view is ViewGroup && (0 until view.childCount).any { hasPage(view.getChildAt(it)) })

    @Test fun opensPrivatePdfRestoresAfterRecreationAndClosesWithDeviceBack() {
        val bytes = pdf()
        val hash = WorkspaceLibraryStore.sha256(bytes)
        val file = File(context.filesDir, "workspace-library/pdf-instrumentation/blobs/$hash").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val session = File(context.cacheDir, "library-pdf/instrumentation.json").apply {
            parentFile!!.mkdirs()
            writeText(JSONObject().put("path", file.absolutePath).put("fileId", "pdf-test")
                .put("manifest", JSONObject().put("files", JSONArray().put(JSONObject().put("id", "pdf-test").put("hash", hash).put("mime", "application/pdf").put("path", "teoria/test.pdf"))).put("items", JSONArray())).toString())
        }
        try {
            val intent = Intent(context, WorkspacePdfActivity::class.java).putExtra("session", session.absolutePath)
            ActivityScenario.launch<WorkspacePdfActivity>(intent).use { scenario ->
                fun awaitPage() {
                    var found = false
                    repeat(100) {
                        scenario.onActivity { found = hasPage(it.window.decorView) }
                        if (found) return
                        Thread.sleep(100)
                    }
                    assertTrue("Debe mostrar la primera página sin WebView ni red", found)
                }
                awaitPage()
                scenario.recreate()
                awaitPage()
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
            }
        } finally { session.delete(); file.delete() }
    }
}
