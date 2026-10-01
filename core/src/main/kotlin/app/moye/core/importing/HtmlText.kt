package app.moye.core.importing

data class HtmlExtract(
    val text: String,
    val anchors: Map<String, Int>,
    val firstHeading: String?,
)

object HtmlText {
    private val blockClosers = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "tr", "blockquote",
        "section", "article", "header", "br",
    )
    private val headingTags = setOf("h1", "h2", "h3")
    private val idPattern = Regex("""\bid\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    fun extract(html: String): HtmlExtract {
        val stripped = stripComments(stripTagBlocks(stripTagBlocks(html, "script"), "style"))
        val anchors = linkedMapOf<String, Int>()
        val sb = StringBuilder()
        var headingStart: Int? = null
        var firstHeading: String? = null
        var index = 0
        while (index < stripped.length) {
            val ch = stripped[index]
            if (ch == '<') {
                val close = stripped.indexOf('>', index)
                if (close == -1) break
                val raw = stripped.substring(index + 1, close).trim()
                val isClose = raw.startsWith("/")
                val name = raw.trimStart('/').substringBefore(' ').substringBefore('\t')
                    .substringBefore('/').lowercase()
                idPattern.find(raw)?.groupValues?.getOrNull(1)?.let { id ->
                    if (id.isNotBlank()) anchors.putIfAbsent(id, sb.length)
                }
                if (!isClose && name in headingTags && headingStart == null && firstHeading == null) {
                    headingStart = sb.length
                }
                if (isClose && name in headingTags && headingStart != null && firstHeading == null) {
                    firstHeading = sb.substring(headingStart).trim().ifBlank { null }
                    headingStart = null
                }
                if (name == "br" || (isClose && name in blockClosers)) sb.append('\n')
                index = close + 1
            } else if (ch == '&') {
                val semi = stripped.indexOf(';', index)
                if (semi == -1 || semi - index > 12) {
                    sb.append(ch)
                    index++
                } else {
                    sb.append(decodeEntity(stripped.substring(index, semi + 1)))
                    index = semi + 1
                }
            } else {
                sb.append(ch)
                index++
            }
        }
        return HtmlExtract(
            text = normalizeWhitespace(sb.toString()),
            anchors = anchors,
            firstHeading = firstHeading?.let { normalizeWhitespace(it).lineSequence().firstOrNull()?.trim() },
        )
    }

    private fun stripTagBlocks(html: String, tag: String): String {
        val lower = html.lowercase()
        val openToken = "<$tag"
        val closeToken = "</$tag"
        val sb = StringBuilder()
        var index = 0
        while (index < html.length) {
            val open = lower.indexOf(openToken, index)
            if (open < 0) {
                sb.append(html, index, html.length)
                break
            }
            sb.append(html, index, open)
            val close = lower.indexOf(closeToken, open + openToken.length)
            if (close < 0) break
            val after = lower.indexOf('>', close)
            index = if (after < 0) html.length else after + 1
        }
        return sb.toString()
    }

    private fun stripComments(html: String): String {
        val sb = StringBuilder()
        var index = 0
        while (index < html.length) {
            val open = html.indexOf("<!--", index)
            if (open < 0) {
                sb.append(html, index, html.length)
                break
            }
            sb.append(html, index, open)
            val close = html.indexOf("-->", open + 4)
            index = if (close < 0) html.length else close + 3
        }
        return sb.toString()
    }

    private fun decodeEntity(entity: String): String {
        val body = entity.removePrefix("&").removeSuffix(";")
        return when (body.lowercase()) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> " "
            "hellip" -> "…"
            "mdash" -> "—"
            "ndash" -> "–"
            "lsquo", "rsquo" -> "’"
            "ldquo", "rdquo" -> "”"
            else -> when {
                body.startsWith("#x") || body.startsWith("#X") -> body.drop(2).toIntOrNull(16)?.toChar()?.toString()
                body.startsWith("#") -> body.drop(1).toIntOrNull()?.toChar()?.toString()
                else -> null
            } ?: entity
        }
    }

    private fun normalizeWhitespace(text: String): String {
        val lines = text.replace('\u00A0', ' ').split('\n').map { line ->
            line.replace(Regex("[ \\t]+"), " ").trim()
        }
        val collapsed = StringBuilder()
        var blank = 0
        for (line in lines) {
            if (line.isEmpty()) {
                blank++
                if (blank <= 1 && collapsed.isNotEmpty()) collapsed.append('\n')
            } else {
                if (collapsed.isNotEmpty() && !collapsed.endsWith('\n')) collapsed.append('\n')
                collapsed.append(line)
                blank = 0
            }
        }
        return collapsed.toString().trim()
    }
}
