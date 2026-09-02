package com.inscreen.mic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.json.JSONObject

internal object ModuleClipboard {
    fun read(context: Context): String = payload {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = clipboard?.primaryClip
        if (clip == null || clip.itemCount == 0) "" else clip.getItemAt(0).coerceToText(context)
    }

    fun write(context: Context, text: String): String = writePayload {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: error("clipboard_unavailable")
        clipboard.setPrimaryClip(ClipData.newPlainText("Ruta de Síntesis", text))
    }

    internal fun payload(readText: () -> CharSequence?): String = try {
        JSONObject().put("ok", true).put("texto", readText()?.toString().orEmpty()).toString()
    } catch (_: Exception) {
        JSONObject().put("ok", false).put("texto", "").put("error", "clipboard_read_failed").toString()
    }

    internal fun writePayload(writeText: () -> Unit): String = try {
        writeText()
        JSONObject().put("ok", true).toString()
    } catch (_: Exception) {
        JSONObject().put("ok", false).put("error", "clipboard_write_failed").toString()
    }
}
