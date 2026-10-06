package app.moye.core.importing

import app.moye.core.model.Chapter
import app.moye.core.model.EmbeddedCover
import app.moye.core.model.ImportError
import app.moye.core.model.ParsedBook
import app.moye.core.model.WritingMode
import app.moye.core.text.ChapterDetector
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

sealed class ParseResult {
    data class Ok(val book: ParsedBook) : ParseResult()

    data class Err(val error: ImportError) : ParseResult()
}

object EpubParser {
    private const val MAX_COVER_BYTES = 8 * 1024 * 1024

    fun parse(file: File): ParseResult {
        if (!file.isFile) return ParseResult.Err(ImportError.UNREADABLE)
        val zip = try {
            ZipFile(file)
        } catch (_: Exception) {
            return ParseResult.Err(ImportError.CORRUPT)
        }
        zip.use { archive ->
            return try {
                parseArchive(archive)
            } catch (_: Exception) {
                ParseResult.Err(ImportError.CORRUPT)
            }
        }
    }

    private fun parseArchive(zip: ZipFile): ParseResult {
        val containerBytes = readEntry(zip, "META-INF/container.xml")
            ?: return ParseResult.Err(ImportError.CORRUPT)
        val container = parseXml(containerBytes) ?: return ParseResult.Err(ImportError.CORRUPT)
        val opfPath = container.getElementsByTagName("*").let { nodes ->
            (0 until nodes.length).firstNotNullOfOrNull { index ->
                val node = nodes.item(index) as? Element
                if (node?.localName == "rootfile") node.getAttribute("full-path") else null
            }
        }?.takeIf { it.isNotBlank() } ?: return ParseResult.Err(ImportError.CORRUPT)
        val opfBytes = readEntry(zip, opfPath) ?: return ParseResult.Err(ImportError.CORRUPT)
        val opf = parseXml(opfBytes) ?: return ParseResult.Err(ImportError.CORRUPT)
        val opfDir = opfPath.substringBeforeLast('/', "")
        val title = firstText(opf, "title")
        val author = firstText(opf, "creator")
        val manifest = manifestItems(opf)
        val spine = spineRefs(opf)
        if (spine.isEmpty()) return ParseResult.Err(ImportError.CORRUPT)

        val styleBlobs = mutableListOf<String>()
        opfMetadataXml(opf)?.let(styleBlobs::add)
        manifest.filter { it.mediaType.contains("css") }.forEach { item ->
            readEntry(zip, resolvePath(opfDir, item.href))?.let { styleBlobs += TxtDecoder.decode(it) }
        }

        val body = StringBuilder()
        val docStarts = linkedMapOf<String, Int>()
        val anchors = linkedMapOf<String, Int>()
        val headings = linkedMapOf<String, String>()
        for (idref in spine) {
            val item = manifest.find { it.id == idref } ?: continue
            if (!isHtml(item.mediaType, item.href)) continue
            val href = resolvePath(opfDir, item.href)
            val bytes = readEntry(zip, href) ?: continue
            val html = TxtDecoder.decode(bytes)
            styleHints(html).takeIf { it.isNotBlank() }?.let(styleBlobs::add)
            val extracted = HtmlText.extract(html)
            if (body.isNotEmpty() && extracted.text.isNotEmpty()) body.append("\n\n")
            val start = body.length
            docStarts[href] = start
            docStarts[item.href] = start
            extracted.anchors.forEach { (anchor, relative) ->
                anchors["$href#$anchor"] = start + relative
            }
            extracted.firstHeading?.let { headings[href] = it }
            body.append(extracted.text)
        }
        val raw = body.toString()
        val leading = raw.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
        val text = raw.trim()
        if (text.isEmpty()) return ParseResult.Err(ImportError.EMPTY)
        val chapters = chapters(zip, opfDir, manifest, docStarts, anchors, headings, text)
            .map { chapter -> chapter.copy(startOffset = (chapter.startOffset - leading).coerceIn(0, text.length)) }
            .distinctBy { it.startOffset }
            .sortedBy { it.startOffset }
        return ParseResult.Ok(
            ParsedBook(
                title = title,
                author = author,
                text = text,
                chapters = chapters,
                declaredWritingMode = if (isVertical(styleBlobs)) WritingMode.VERTICAL else WritingMode.HORIZONTAL,
                cover = extractCover(zip, opf, opfDir, manifest),
            ),
        )
    }

    private fun extractCover(
        zip: ZipFile,
        opf: Element,
        opfDir: String,
        manifest: List<ManifestItem>,
    ): EmbeddedCover? {
        val byProperty = manifest.firstOrNull { item ->
            isImage(item.mediaType) &&
                item.properties.split(Regex("\\s+")).any { it.equals("cover-image", ignoreCase = true) }
        }
        val coverId = opf.descendants("meta").firstOrNull { meta ->
            meta.getAttribute("name").equals("cover", ignoreCase = true)
        }?.getAttribute("content")?.takeIf { it.isNotBlank() }
        val byMeta = coverId?.let { id -> manifest.firstOrNull { it.id == id && isImage(it.mediaType) } }
        val chosen = byProperty ?: byMeta ?: guideCoverItem(zip, opf, opfDir, manifest) ?: return null
        val bytes = readEntry(zip, resolvePath(opfDir, chosen.href)) ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) return null
        return EmbeddedCover(chosen.mediaType, bytes)
    }

    private fun guideCoverItem(
        zip: ZipFile,
        opf: Element,
        opfDir: String,
        manifest: List<ManifestItem>,
    ): ManifestItem? {
        val href = opf.descendants("reference").firstOrNull { reference ->
            reference.getAttribute("type").equals("cover", ignoreCase = true)
        }?.getAttribute("href")?.takeIf { it.isNotBlank() } ?: return null
        val resolved = resolvePath(opfDir, href.substringBefore('#'))
        manifest.firstOrNull { item ->
            isImage(item.mediaType) && resolvePath(opfDir, item.href) == resolved
        }?.let { return it }
        val html = readEntry(zip, resolved)?.let(TxtDecoder::decode) ?: return null
        val source = firstImageSource(html) ?: return null
        val imagePath = resolvePath(resolved.substringBeforeLast('/', ""), source)
        return manifest.firstOrNull { item ->
            isImage(item.mediaType) && resolvePath(opfDir, item.href) == imagePath
        }
    }

    private fun firstImageSource(html: String): String? {
        val tag = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE).find(html)?.value ?: return null
        return Regex("""\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(tag)
            ?.groupValues
            ?.get(1)
    }

    private fun isImage(mediaType: String): Boolean {
        return when (mediaType.lowercase().substringBefore(';').trim()) {
            "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp" -> true
            else -> false
        }
    }

    private fun chapters(
        zip: ZipFile,
        opfDir: String,
        manifest: List<ManifestItem>,
        docStarts: Map<String, Int>,
        anchors: Map<String, Int>,
        headings: Map<String, String>,
        text: String,
    ): List<Chapter> {
        val navItem = manifest.find { it.properties.contains("nav") }
        val ncxItem = manifest.find { it.mediaType.contains("dtbncx") || it.href.endsWith(".ncx") }
        val fromNav = navItem?.let { item ->
            readEntry(zip, resolvePath(opfDir, item.href))?.let { bytes ->
                navChapters(TxtDecoder.decode(bytes), opfDir, item.href, docStarts, anchors)
            }
        }.orEmpty()
        val fromNcx = if (fromNav.isNotEmpty()) {
            emptyList()
        } else {
            ncxItem?.let { item ->
                val resolved = resolvePath(opfDir, item.href)
                readEntry(zip, resolved)?.let { bytes ->
                    ncxChapters(bytes, resolved.substringBeforeLast('/', ""), docStarts, anchors)
                }
            }.orEmpty()
        }
        val chosen = (if (fromNav.isNotEmpty()) fromNav else fromNcx)
            .distinctBy { it.startOffset }
            .sortedBy { it.startOffset }
        if (chosen.isNotEmpty()) return chosen
        if (docStarts.size <= 1) return ChapterDetector.detect(text)
        return docStarts.entries
            .distinctBy { it.value }
            .sortedBy { it.value }
            .map { (href, offset) ->
                Chapter(headings[href] ?: href.substringAfterLast('/'), offset)
            }
    }

    private fun navChapters(
        html: String,
        opfDir: String,
        navHref: String,
        docStarts: Map<String, Int>,
        anchors: Map<String, Int>,
    ): List<Chapter> {
        val navBase = resolvePath(opfDir, navHref).substringBeforeLast('/', "")
        val pattern = Regex("""<a\b([^>]*)>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val hrefPattern = Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        return pattern.findAll(html).mapNotNull { match ->
            val href = hrefPattern.find(match.groupValues[1])?.groupValues?.get(1) ?: return@mapNotNull null
            val title = HtmlText.extract(match.groupValues[2]).text.ifBlank { return@mapNotNull null }
            val offset = locate(navBase, href, docStarts, anchors) ?: return@mapNotNull null
            Chapter(title, offset)
        }.toList()
    }

    private fun ncxChapters(
        bytes: ByteArray,
        opfDir: String,
        docStarts: Map<String, Int>,
        anchors: Map<String, Int>,
    ): List<Chapter> {
        val doc = parseXml(bytes) ?: return emptyList()
        val nodes = doc.getElementsByTagName("*")
        val chapters = mutableListOf<Chapter>()
        for (index in 0 until nodes.length) {
            val node = nodes.item(index) as? Element ?: continue
            if (node.localName != "navPoint") continue
            val label = node.childElements().firstOrNull { it.localName == "navLabel" }
                ?.childElements()?.firstOrNull { it.localName == "text" }?.textContent?.trim().orEmpty()
            val src = node.childElements().firstOrNull { it.localName == "content" }?.getAttribute("src").orEmpty()
            if (label.isBlank() || src.isBlank()) continue
            val offset = locate(opfDir, src, docStarts, anchors) ?: continue
            chapters += Chapter(label, offset)
        }
        return chapters
    }

    private fun locate(
        baseDir: String,
        href: String,
        docStarts: Map<String, Int>,
        anchors: Map<String, Int>,
    ): Int? {
        val path = href.substringBefore('#')
        val fragment = href.substringAfter('#', "")
        val resolved = resolvePath(baseDir, path.ifBlank { href })
        if (fragment.isNotEmpty()) {
            anchors["$resolved#$fragment"]?.let { return it }
        }
        return docStarts[resolved] ?: docStarts[path] ?: docStarts.entries.firstOrNull {
            it.key.endsWith(path) || path.endsWith(it.key)
        }?.value
    }

    private fun styleHints(html: String): String {
        val sb = StringBuilder()
        val lower = html.lowercase()
        var index = 0
        while (true) {
            val style = lower.indexOf("<style", index)
            if (style < 0) break
            val start = lower.indexOf('>', style)
            val end = if (start < 0) -1 else lower.indexOf("</style", start)
            if (start < 0 || end < 0) break
            sb.append(html, start + 1, end).append('\n')
            index = end + 7
        }
        Regex("""style\s*=\s*["'][^"']*writing-mode[^"']*["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { sb.append(it.value).append('\n') }
        return sb.toString()
    }

    private fun isVertical(blobs: List<String>): Boolean {
        val pattern = Regex("""writing-mode\s*:\s*vertical-(rl|lr)""", RegexOption.IGNORE_CASE)
        val meta = Regex("""primary-writing-mode["']?\s*(?:content\s*=\s*["']|\s*>\s*)vertical""", RegexOption.IGNORE_CASE)
        return blobs.any { blob -> pattern.containsMatchIn(blob) || meta.containsMatchIn(blob) || blob.contains("vertical-rl") && blob.contains("primary-writing-mode") }
    }

    private fun manifestItems(opf: Element): List<ManifestItem> {
        return opf.descendants("item").map { item ->
            ManifestItem(
                id = item.getAttribute("id"),
                href = item.getAttribute("href"),
                mediaType = item.getAttribute("media-type").lowercase(),
                properties = item.getAttribute("properties").lowercase(),
            )
        }
    }

    private fun spineRefs(opf: Element): List<String> {
        return opf.descendants("itemref").mapNotNull { it.getAttribute("idref").takeIf(String::isNotBlank) }
    }

    private fun firstText(root: Element, local: String): String? {
        return root.descendants(local).firstOrNull()?.textContent?.trim()?.ifBlank { null }
    }

    private fun opfMetadataXml(opf: Element): String? {
        val metadata = opf.descendants("metadata").firstOrNull() ?: return null
        val sb = StringBuilder()
        val nodes = metadata.getElementsByTagName("*")
        for (index in 0 until nodes.length) {
            val node = nodes.item(index) as? Element ?: continue
            sb.append(node.getAttribute("name")).append(' ')
            sb.append(node.getAttribute("content")).append(' ')
            sb.append(node.getAttribute("property")).append(' ')
            sb.append(node.textContent).append('\n')
        }
        return sb.toString()
    }

    private fun isHtml(mediaType: String, href: String): Boolean {
        return mediaType.contains("html") || href.endsWith(".xhtml") || href.endsWith(".html") || href.endsWith(".htm")
    }

    private fun resolvePath(baseDir: String, href: String): String {
        val clean = URLDecoder.decode(href.substringBefore('#').substringBefore('?'), StandardCharsets.UTF_8)
        val combined = when {
            clean.startsWith("/") -> clean.removePrefix("/")
            baseDir.isBlank() -> clean
            else -> "$baseDir/$clean"
        }
        val parts = ArrayDeque<String>()
        combined.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/")
    }

    private fun readEntry(zip: ZipFile, path: String): ByteArray? {
        val wanted = path.removePrefix("/").replace('\\', '/')
        val direct = zip.getEntry(wanted) ?: zip.entries().asSequence().firstOrNull {
            it.name.replace('\\', '/').equals(wanted, ignoreCase = true)
        } ?: return null
        return zip.getInputStream(direct).use { it.readBytes() }
    }

    private fun parseXml(bytes: ByteArray): Element? {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            factory.isExpandEntityReferences = false
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
        } catch (_: Exception) {
            null
        }
    }

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String,
    )
}

private fun Element.descendants(local: String): List<Element> {
    val nodes = getElementsByTagName("*")
    return (0 until nodes.length).mapNotNull { index ->
        (nodes.item(index) as? Element)?.takeIf { it.localName == local }
    }
}

private fun Element.childElements(): List<Element> {
    val nodes = childNodes
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
}
