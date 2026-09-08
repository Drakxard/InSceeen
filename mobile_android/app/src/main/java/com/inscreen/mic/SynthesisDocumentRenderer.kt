package com.inscreen.mic

import org.json.JSONObject

internal object SynthesisDocumentRenderer {
    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    fun render(workspace: JSONObject): String {
        require(workspace.optInt("version") == 2) { "Versión de síntesis no compatible." }
        val document = workspace.optJSONObject("document") ?: error("Síntesis sin contenido.")
        val fontSize = workspace.optInt("editorFontSize", 16).coerceIn(12, 32)
        return """<!doctype html><html lang="es"><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https://synthesis.local; style-src 'unsafe-inline'">
            <style>body{font-family:Georgia,serif;font-size:${fontSize}px;line-height:1.6;color:#302318;background:#fff8ec;padding:16px;overflow-wrap:anywhere}
            img{max-width:100%;height:auto}table{border-collapse:collapse;display:block;overflow-x:auto}td,th{border:1px solid #ad9982;padding:8px;min-width:40px}
            blockquote{border-left:3px solid #ad9982;padding-left:14px}pre{white-space:pre-wrap}h1,h2,h3,h4,h5,h6{line-height:1.25}p:empty{min-height:1em}</style></head>
            <body>${node(document)}</body></html>"""
    }

    private fun node(value: JSONObject, depth: Int = 0): String {
        if (depth > 100) return ""
        val content = value.optJSONArray("content")
        val children by lazy { (0 until (content?.length() ?: 0)).joinToString("") { content?.optJSONObject(it)?.let { child -> node(child, depth + 1) }.orEmpty() } }
        val attrs = value.optJSONObject("attrs") ?: JSONObject()
        fun tag(name: String, attributes: String = "") = "<$name$attributes>$children</$name>"
        return when (value.optString("type")) {
            "text" -> {
                var text = escape(value.optString("text"))
                val marks = value.optJSONArray("marks")
                for (i in 0 until (marks?.length() ?: 0)) {
                    val mark = marks?.optJSONObject(i) ?: continue
                    val name = when (mark.optString("type")) { "bold" -> "strong"; "italic" -> "em"; "underline" -> "u"; "strike" -> "s"; "code" -> "code"; "subscript" -> "sub"; "superscript" -> "sup"; "highlight" -> "mark"; else -> "" }
                    if (name.isNotEmpty()) text = "<$name>$text</$name>"
                    if (mark.optString("type") == "link") {
                        val href = mark.optJSONObject("attrs")?.optString("href").orEmpty()
                        if (href.startsWith("https://") || href.startsWith("http://")) text = "<a href=\"${escape(href)}\">$text</a>"
                    }
                }
                text
            }
            "heading" -> tag("h" + attrs.optInt("level", 1).coerceIn(1, 6))
            "paragraph" -> tag("p")
            "bulletList", "taskList" -> tag("ul")
            "orderedList" -> tag("ol", " start=\"${attrs.optInt("start", 1).coerceAtLeast(1)}\"")
            "listItem" -> tag("li")
            "taskItem" -> "<li>" + (if (attrs.optBoolean("checked")) "☑ " else "☐ ") + children + "</li>"
            "blockquote" -> tag("blockquote")
            "codeBlock" -> tag("pre")
            "hardBreak" -> "<br>"
            "horizontalRule" -> "<hr>"
            "table" -> tag("table")
            "tableRow" -> tag("tr")
            "tableCell", "tableHeader" -> tag(if (value.optString("type") == "tableCell") "td" else "th",
                " colspan=\"${attrs.optInt("colspan", 1).coerceIn(1, 100)}\" rowspan=\"${attrs.optInt("rowspan", 1).coerceIn(1, 100)}\"")
            "image" -> {
                val src = attrs.optString("src")
                val id = src.removePrefix("synthesis-local-image:")
                if (src.startsWith("synthesis-local-image:") && id.matches(Regex("[a-zA-Z0-9-]{1,100}")))
                    "<img src=\"https://synthesis.local/images/$id\" alt=\"${escape(attrs.optString("alt", "Imagen"))}\">"
                else "<p>[Imagen no disponible]</p>"
            }
            else -> children
        }
    }
}
