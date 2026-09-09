package com.inscreen.mic

import org.json.JSONArray
import org.json.JSONObject

internal object SynthesisDocumentRenderer {
    private data class ReaderNode(
        val id: String,
        val parent: Int?,
        val name: String,
        val body: MutableList<JSONObject> = mutableListOf(),
        val x: Double,
        val y: Double,
        val scale: Double,
    )

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    fun render(workspace: JSONObject): String {
        require(workspace.optInt("version") == 2) { "Versión de síntesis no compatible." }
        val document = workspace.optJSONObject("document") ?: error("Síntesis sin contenido.")
        val layout = workspace.optJSONObject("layout") ?: JSONObject()
        val nodes = deriveNodes(document, layout)
        val fontSize = workspace.optInt("editorFontSize", 16).coerceIn(12, 32)
        val payload = JSONArray()
        nodes.forEachIndexed { index, item ->
            payload.put(JSONObject().put("index", index).put("parent", item.parent ?: JSONObject.NULL)
                .put("name", item.name).put("body", item.body.joinToString("") { node(it) })
                .put("x", item.x).put("y", item.y).put("scale", item.scale))
        }
        val encodedPayload = payload.toString().replace("<", "\\u003c").replace(">", "\\u003e")
            .replace("&", "\\u0026").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        return """<!doctype html><html lang="es"><head><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https://synthesis.local; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
            <style>
            *{box-sizing:border-box}html,body{margin:0;width:100%;height:100%;overflow:hidden}body{color:#fff4dc;background:#a65d3b url('https://synthesis.local/assets/background') center/cover no-repeat;font-family:Georgia,'Times New Roman',serif}
            #board{position:absolute;inset:0;overflow-x:hidden;overflow-y:auto}.node{position:absolute;width:calc(139px * var(--s));height:calc(127px * var(--s));transform:translate(-50%,-50%)}
            .plaque{width:100%;height:100%;padding:25% 18% 20%;border:0;color:#fff4dc;background:transparent url('https://synthesis.local/assets/plaque') center/100% 100% no-repeat;filter:drop-shadow(0 10px 14px rgba(31,10,3,.38));font:700 clamp(14px,calc(18px * var(--s)),27px)/1.12 Georgia,serif;text-shadow:0 1px 4px #1e0903;overflow-wrap:anywhere}
            #read{position:fixed;z-index:8;top:12px;right:12px;min-width:48px;height:44px;padding:0 14px;border:1px solid rgba(255,244,220,.65);border-radius:999px;color:#fff4dc;background:rgba(53,19,8,.72);font:600 14px system-ui,sans-serif}
            #empty{position:absolute;top:40%;left:50%;width:min(88vw,440px);padding:20px;transform:translateX(-50%);border-radius:14px;background:rgba(53,19,8,.85);text-align:center;font:16px/1.5 system-ui,sans-serif}
            #sheet{position:absolute;inset:0;overflow:auto;padding:24px max(17px,calc((100vw - 920px)/2)) 60px;color:#281811;background:#f8f2e7;font:${fontSize}px/1.65 system-ui,sans-serif;overflow-wrap:anywhere}
            #sheet h1,#sheet h2,#sheet h3,#sheet h4,#sheet h5,#sheet h6{margin:1.3em 0 .55em;color:#6b311b;line-height:1.18}#sheet h1:first-child,#sheet h2:first-child{margin-top:0}#sheet img{max-width:100%;height:auto}#sheet table{width:100%;border-collapse:collapse;display:block;overflow-x:auto}#sheet td,#sheet th{min-width:40px;padding:8px;border:1px solid #ad9982}#sheet blockquote{padding-left:14px;border-left:3px solid #ad9982}#sheet pre{overflow:auto;padding:14px;border-radius:10px;color:#f8eee2;background:#2e221d;white-space:pre-wrap}#sheet code{padding:.1em .28em;border-radius:4px;background:#e8ded0}#sheet pre code{padding:0;background:transparent}#sheet a{color:#943c1c}
            @media(min-width:641px){.node{width:calc(164px * var(--s));height:calc(150px * var(--s))}}
            </style></head><body><main id="board" aria-label="Elementos de Síntesis"></main><button id="read" hidden>Leer</button><article id="sheet" hidden></article><div id="empty" hidden></div>
            <script>'use strict';const nodes=$encodedPayload;let parent=null,sheet=false;
            const board=document.getElementById('board'),read=document.getElementById('read'),article=document.getElementById('sheet'),empty=document.getElementById('empty');
            function children(id){return nodes.filter(n=>n.parent===id)} function current(){return parent===null?null:nodes[parent]}
            function render(){sheet=false;article.hidden=true;board.hidden=false;board.replaceChildren();const list=children(parent);const active=current();read.hidden=!active||!active.body;
              empty.hidden=list.length>0;empty.textContent=nodes.length?'No hay más subtemas en esta sección.':'No hay contenido cargado para esta semana.';
              list.forEach((n,i)=>{const wrap=document.createElement('div');wrap.className='node';wrap.style.setProperty('--s',n.scale);wrap.style.left=(n.x*100)+'%';wrap.style.top=(Math.max(.22,n.y)*100)+'vh';const b=document.createElement('button');b.className='plaque';b.textContent=n.name;b.setAttribute('aria-label','Abrir '+n.name);b.onclick=()=>{parent=n.index;if(children(parent).length)render();else showSheet()};wrap.appendChild(b);board.appendChild(wrap)});
              const max=Math.max(1.5,...list.map(n=>n.y+.35));board.style.paddingBottom=(max*100)+'vh'}
            function showSheet(){const n=current();if(!n)return;sheet=true;board.hidden=true;read.hidden=true;article.hidden=false;article.innerHTML='<h1>'+escapeHtml(n.name)+'</h1>'+n.body;article.scrollTop=0}
            function escapeHtml(s){const d=document.createElement('div');d.textContent=s;return d.innerHTML} read.onclick=showSheet;
            window.readerBack=function(){if(sheet){render();return true}if(parent!==null){parent=nodes[parent].parent;render();return true}Reader.close();return true};render();</script></body></html>"""
    }

    private fun deriveNodes(document: JSONObject, layout: JSONObject): List<ReaderNode> {
        val result = mutableListOf<ReaderNode>()
        val headingStack = mutableListOf<Pair<Int, Int>>()
        var active: ReaderNode? = null
        val blocks = document.optJSONArray("content") ?: JSONArray()
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            if (block.optString("type") == "heading") {
                val attrs = block.optJSONObject("attrs") ?: JSONObject()
                val level = attrs.optInt("level", 1).coerceIn(1, 3)
                while (headingStack.isNotEmpty() && headingStack.last().first >= level) headingStack.removeAt(headingStack.lastIndex)
                val parent = headingStack.lastOrNull()?.second
                val id = attrs.optString("synthesisId").ifBlank { "heading-$i" }
                val position = layout.optJSONObject(id) ?: JSONObject()
                val sibling = result.count { it.parent == parent }
                val fallbackX = .18 + (sibling % 3) * .32
                val fallbackY = .22 + (sibling / 3) * .24
                active = ReaderNode(id, parent, plainText(block).ifBlank { "Sin título" },
                    x = position.optDouble("x", fallbackX).coerceIn(0.08, .92),
                    y = position.optDouble("y", fallbackY).coerceAtLeast(.18),
                    scale = position.optDouble("scale", 1.0).coerceIn(.5, 2.0))
                result.add(active)
                headingStack.add(level to result.lastIndex)
            } else active?.body?.add(block)
        }
        return result
    }

    private fun plainText(value: JSONObject): String {
        if (value.has("text")) return value.optString("text")
        val content = value.optJSONArray("content") ?: return ""
        return (0 until content.length()).joinToString("") { content.optJSONObject(it)?.let(::plainText).orEmpty() }.trim()
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
                }; text
            }
            "heading" -> tag("h" + attrs.optInt("level", 1).coerceIn(1, 6)); "paragraph" -> tag("p")
            "bulletList", "taskList" -> tag("ul"); "orderedList" -> tag("ol", " start=\"${attrs.optInt("start", 1).coerceAtLeast(1)}\"")
            "listItem" -> tag("li"); "taskItem" -> "<li>" + (if (attrs.optBoolean("checked")) "☑ " else "☐ ") + children + "</li>"
            "blockquote" -> tag("blockquote"); "codeBlock" -> tag("pre"); "hardBreak" -> "<br>"; "horizontalRule" -> "<hr>"
            "table" -> tag("table"); "tableRow" -> tag("tr")
            "tableCell", "tableHeader" -> tag(if (value.optString("type") == "tableCell") "td" else "th", " colspan=\"${attrs.optInt("colspan", 1).coerceIn(1, 100)}\" rowspan=\"${attrs.optInt("rowspan", 1).coerceIn(1, 100)}\"")
            "image" -> { val src = attrs.optString("src"); val id = src.removePrefix("synthesis-local-image:"); if (src.startsWith("synthesis-local-image:") && id.matches(Regex("[a-zA-Z0-9-]{1,100}"))) "<img src=\"https://synthesis.local/images/$id\" alt=\"${escape(attrs.optString("alt", "Imagen"))}\">" else "<p>[Imagen no disponible]</p>" }
            else -> children
        }
    }
}
