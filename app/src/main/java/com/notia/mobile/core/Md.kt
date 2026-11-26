package com.notia.mobile.core

/** Small Markdown / plain-text to note HTML converter, for files received from Notia Desktop (md and txt exports). */
object Md {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun inline(s0: String): String {
        var s = esc(s0)
        s = Regex("`([^`]+)`").replace(s) { "<code>${it.groupValues[1]}</code>" }
        s = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__").replace(s) { "<b>${it.groupValues[1].ifEmpty { it.groupValues[2] }}</b>" }
        s = Regex("(?<![*\\w])\\*([^*\\s][^*]*?)\\*(?![*\\w])").replace(s) { "<i>${it.groupValues[1]}</i>" }
        s = Regex("~~(.+?)~~").replace(s) { "<s>${it.groupValues[1]}</s>" }
        s = Regex("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)").replace(s) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
        return s
    }

    fun toHtml(md: String): String {
        val out = StringBuilder()
        var list: String? = null
        var code = false
        fun closeList() { if (list != null) { out.append("</$list>"); list = null } }
        for (raw in md.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) { closeList(); if (code) out.append("</pre>") else out.append("<pre>"); code = !code; continue }
            if (code) { out.append(esc(raw)).append('\n'); continue }
            val t = line.trim()
            val h = Regex("^(#{1,6})\\s+(.*)$").find(t)
            val ul = Regex("^[-*+]\\s+(?:\\[[ xX]]\\s+)?(.*)$").find(t)
            val ol = Regex("^\\d+[.)]\\s+(.*)$").find(t)
            when {
                t.isEmpty() -> closeList()
                h != null -> { closeList(); val n = if (h.groupValues[1].length <= 1) 1 else 2; out.append("<h$n>").append(inline(h.groupValues[2])).append("</h$n>") }
                Regex("^([-*_])(\\s*\\1){2,}$").matches(t) -> { closeList(); out.append("<hr>") }
                ul != null -> { if (list != "ul") { closeList(); out.append("<ul>"); list = "ul" }; out.append("<li>").append(inline(ul.groupValues[1])).append("</li>") }
                ol != null -> { if (list != "ol") { closeList(); out.append("<ol>"); list = "ol" }; out.append("<li>").append(inline(ol.groupValues[1])).append("</li>") }
                t.startsWith(">") -> { closeList(); out.append("<blockquote>").append(inline(t.removePrefix(">").trim())).append("</blockquote>") }
                else -> { closeList(); out.append("<div>").append(inline(t)).append("</div>") }
            }
        }
        closeList(); if (code) out.append("</pre>")
        return out.toString()
    }

    /** First "# heading" of a Markdown file, or null. */
    fun title(md: String): String? = Regex("^#\\s+(.+)$", RegexOption.MULTILINE).find(md)?.groupValues?.get(1)?.trim()
}
