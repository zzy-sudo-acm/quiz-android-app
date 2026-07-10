package com.zzy.quizforge.util.document

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.security.MessageDigest

/**
 * 纯 Kotlin OOXML 结构化读取器。
 *
 * @param createParser 创建 XmlPullParser 的工厂。默认为 Android XmlPullParserFactory。
 *   JVM 测试可注入替代实现。
 */
class OoXmlDocumentReader(
    private val createParser: (java.io.Reader) -> XmlPullParser = Companion.defaultFactory,
) {

    companion object {
        val defaultFactory: (java.io.Reader) -> XmlPullParser = { reader ->
            val f = XmlPullParserFactory.newInstance()
            f.newPullParser().also { it.setInput(reader) }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════

    fun read(
        entries: Map<String, ByteArray>,
        mediaDir: java.io.File? = null,
    ): StructuredDocument {
        sourceOrderCounter = 0
        nextGlobalId = 0
        val warnings = mutableListOf<DocumentWarning>()

        val documentXml = entries["word/document.xml"]?.toString(Charsets.UTF_8)
            ?: throw IllegalArgumentException("缺少 word/document.xml")

        val relsXml = entries["word/_rels/document.xml.rels"]?.toString(Charsets.UTF_8).orEmpty()
        val numberingXml = entries["word/numbering.xml"]?.toString(Charsets.UTF_8).orEmpty()

        val imageRelationships = parseImageRelationships(relsXml)
        val numberingDefinitions = parseNumberingDefinitions(numberingXml)

        val relIdToMediaId = mutableMapOf<String, String>()
        val media = buildMediaList(entries, imageRelationships, mediaDir, warnings, relIdToMediaId)

        val blocks = parseDocumentBlocks(documentXml, imageRelationships.keys, relIdToMediaId)

        return StructuredDocument(blocks, media, numberingDefinitions, warnings)
    }

    // ═══════════════════════════════════════════════════════════
    // Global counters (per read() call)
    // ═══════════════════════════════════════════════════════════

    /**
     * 全局 sourceOrder 计数器。
     * 所有 DocumentBlock（包括 TableCell 内嵌的）共用此计数器，
     * 保证整个 StructuredDocument 内 sourceOrder 唯一、>= 0、按 DFS 遍历顺序递增。
     */
    private var sourceOrderCounter = 0

    private var nextGlobalId = 0
    private fun allocId(prefix: String): String = "$prefix${nextGlobalId++}"
    private fun nextOrder(): Int = sourceOrderCounter++

    // ═══════════════════════════════════════════════════════════
    // Document body → blocks
    // ═══════════════════════════════════════════════════════════

    private fun parseDocumentBlocks(
        xml: String,
        imageRelIds: Set<String>,
        relIdToMediaId: Map<String, String>,
    ): List<DocumentBlock> {
        val parser = createParser(StringReader(xml))
        val blocks = mutableListOf<DocumentBlock>()
        var bodyDepth = 0 // track whether we're inside <w:body>

        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            val name = parser.name?.substringAfter(':') ?: ""

            when {
                event == XmlPullParser.START_TAG && name == "body" -> bodyDepth = 1
                event == XmlPullParser.END_TAG && name == "body" -> bodyDepth = 0
                event == XmlPullParser.START_TAG && bodyDepth > 0 && name == "p" ->
                    blocks += parseParagraph(parser, imageRelIds, relIdToMediaId)
                event == XmlPullParser.START_TAG && bodyDepth > 0 && name == "tbl" ->
                    blocks += parseTable(parser, imageRelIds, relIdToMediaId)
            }
        }
        return blocks
    }

    // ═══════════════════════════════════════════════════════════
    // Paragraph
    // ═══════════════════════════════════════════════════════════

    private fun parseParagraph(
        parser: XmlPullParser,
        imageRelIds: Set<String>,
        relIdToMediaId: Map<String, String>,
    ): ParagraphBlock {
        val order = nextOrder()
        val id = allocId("p")
        val content = mutableListOf<InlineContent>()
        var numId: String? = null
        var level: Int? = null
        var inPPr = false
        var pPrDepth = 0

        loop@ while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break

            val name = parser.name?.substringAfter(':') ?: ""

            when (event) {
                XmlPullParser.START_TAG -> {
                    when {
                        name == "pPr" -> { inPPr = true; pPrDepth++ }
                        inPPr && name == "numId" -> {
                            numId = parser.getAttributeValue(null, "val")
                            pPrDepth++ // balance with END_TAG
                        }
                        inPPr && name == "ilvl" -> {
                            level = parser.getAttributeValue(null, "val")?.toIntOrNull()
                            pPrDepth++ // balance with END_TAG
                        }
                        inPPr -> pPrDepth++
                        name == "t" -> content += TextContent(readTextContent(parser))
                        name == "br" || name == "cr" -> content += LineBreakContent
                        name == "blip" -> {
                            val relId = findRelId(parser)
                            val mediaId = if (relId != null) relIdToMediaId[relId] else null
                            if (mediaId != null) content += ImageContent(mediaId)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when {
                        name == "pPr" -> { pPrDepth--; if (pPrDepth <= 0) inPPr = false }
                        inPPr -> { pPrDepth--; if (pPrDepth <= 0) inPPr = false }
                        name == "p" -> break@loop
                    }
                }
            }
        }

        val numbering = if (numId != null && level != null) NumberingRef(numId, level) else null
        return ParagraphBlock(id, order, numbering, mergeAdjacentText(content))
    }

    private fun readTextContent(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.TEXT) sb.append(parser.text)
            else if (event == XmlPullParser.END_TAG) break
            event = parser.next()
        }
        return sb.toString()
    }

    private fun mergeAdjacentText(items: List<InlineContent>): List<InlineContent> {
        val merged = mutableListOf<InlineContent>()
        val buf = StringBuilder()
        for (item in items) {
            if (item is TextContent) buf.append(item.text)
            else { if (buf.isNotEmpty()) { merged += TextContent(buf.toString()); buf.clear() }; merged += item }
        }
        if (buf.isNotEmpty()) merged += TextContent(buf.toString())
        return merged
    }

    // ═══════════════════════════════════════════════════════════
    // Table
    // ═══════════════════════════════════════════════════════════

    private fun parseTable(
        parser: XmlPullParser,
        imageRelIds: Set<String>,
        relIdToMediaId: Map<String, String>,
    ): TableBlock {
        val order = nextOrder()
        val id = allocId("t")
        val rows = mutableListOf<TableRow>()
        var depth = 1
        var event = parser.next()

        while (depth > 0 && event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name?.substringAfter(':')
            when {
                event == XmlPullParser.START_TAG && name == "tr" ->
                    rows += parseTableRow(parser, imageRelIds, relIdToMediaId)
                event == XmlPullParser.START_TAG && name == "tbl" -> depth++
                event == XmlPullParser.END_TAG && name == "tbl" -> depth--
            }
            if (depth > 0) event = parser.next()
        }
        return TableBlock(id, order, rows)
    }

    private fun parseTableRow(
        parser: XmlPullParser,
        imageRelIds: Set<String>,
        relIdToMediaId: Map<String, String>,
    ): TableRow {
        val cells = mutableListOf<TableCell>()
        var depth = 1
        var event = parser.next()

        while (depth > 0 && event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name?.substringAfter(':')
            when {
                event == XmlPullParser.START_TAG && name == "tc" ->
                    cells += parseTableCell(parser, imageRelIds, relIdToMediaId)
                event == XmlPullParser.START_TAG && name == "tr" -> depth++
                event == XmlPullParser.END_TAG && name == "tr" -> depth--
            }
            if (depth > 0) event = parser.next()
        }
        return TableRow(cells)
    }

    private fun parseTableCell(
        parser: XmlPullParser,
        imageRelIds: Set<String>,
        relIdToMediaId: Map<String, String>,
    ): TableCell {
        val blocks = mutableListOf<DocumentBlock>()
        var depth = 1
        var event = parser.next()

        while (depth > 0 && event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name?.substringAfter(':')
            when {
                event == XmlPullParser.START_TAG && name == "p" ->
                    blocks += parseParagraph(parser, imageRelIds, relIdToMediaId)
                event == XmlPullParser.START_TAG && name == "tbl" ->
                    blocks += parseTable(parser, imageRelIds, relIdToMediaId)
                event == XmlPullParser.START_TAG && name == "tc" -> depth++
                event == XmlPullParser.END_TAG && name == "tc" -> depth--
            }
            if (depth > 0) event = parser.next()
        }
        return TableCell(blocks)
    }

    // ═══════════════════════════════════════════════════════════
    // Image relationships
    // ═══════════════════════════════════════════════════════════

    private fun parseImageRelationships(xml: String): Map<String, String> {
        if (xml.isBlank()) return emptyMap()
        val parser = createParser(StringReader(xml))
        val images = linkedMapOf<String, String>()
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name?.substringAfter(':') == "Relationship") {
                val id = parser.getAttributeValue(null, "Id")
                val target = parser.getAttributeValue(null, "Target")
                val type = parser.getAttributeValue(null, "Type").orEmpty()
                if (!id.isNullOrBlank() && !target.isNullOrBlank() && type.contains("/image"))
                    images[id] = target
            }
            event = parser.next()
        }
        return images
    }

    // ═══════════════════════════════════════════════════════════
    // Numbering
    // ═══════════════════════════════════════════════════════════

    private fun parseNumberingDefinitions(xml: String): Map<String, NumberingDefinition> {
        if (xml.isBlank()) return emptyMap()
        val parser = createParser(StringReader(xml))
        val abstractNums = mutableMapOf<String, MutableMap<Int, NumberingLevel>>()
        val numToAbstract = mutableMapOf<String, String>()
        var currentAbsId: String? = null
        var level: Int? = null
        var numFmt: String? = null
        var lvlText: String? = null
        var startVal: Int? = null
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name?.substringAfter(':') ?: ""
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (name) {
                        "abstractNum" -> currentAbsId = parser.getAttributeValue(null, "abstractNumId")
                        "lvl" -> { level = parser.getAttributeValue(null, "ilvl")?.toIntOrNull() ?: 0; numFmt = null; lvlText = null; startVal = null }
                        "numFmt" -> numFmt = parser.getAttributeValue(null, "val")
                        "lvlText" -> lvlText = parser.getAttributeValue(null, "val")
                        "start" -> startVal = parser.getAttributeValue(null, "val")?.toIntOrNull()
                        "num" -> {
                            val nid = parser.getAttributeValue(null, "numId")
                            if (nid != null) {
                                var inner = parser.next()
                                while (inner != XmlPullParser.END_DOCUMENT) {
                                    if (inner == XmlPullParser.START_TAG && parser.name?.substringAfter(':') == "abstractNumId") {
                                        val aid = parser.getAttributeValue(null, "val")
                                        if (aid != null) numToAbstract[nid] = aid
                                        break
                                    }
                                    if (inner == XmlPullParser.END_TAG && parser.name?.substringAfter(':') == "num") break
                                    inner = parser.next()
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (name) {
                        "abstractNum" -> currentAbsId = null
                        "lvl" -> {
                            if (currentAbsId != null && level != null)
                                abstractNums.getOrPut(currentAbsId) { mutableMapOf() }[level] =
                                    NumberingLevel(level, numFmt, lvlText, startVal)
                            level = null
                        }
                    }
                }
            }
            event = parser.next()
        }

        return numToAbstract.mapNotNull { (numId, absId) ->
            val levels = abstractNums[absId] ?: return@mapNotNull null
            numId to NumberingDefinition(numId, absId, levels)
        }.toMap()
    }

    // ═══════════════════════════════════════════════════════════
    // Media
    // ═══════════════════════════════════════════════════════════

    private fun buildMediaList(
        entries: Map<String, ByteArray>,
        imageRelationships: Map<String, String>,
        mediaDir: java.io.File?,
        warnings: MutableList<DocumentWarning>,
        relIdToMediaId: MutableMap<String, String> = mutableMapOf(),
    ): List<DocumentMedia> {
        val seen = mutableSetOf<String>()
        val media = mutableListOf<DocumentMedia>()

        for ((relId, target) in imageRelationships) {
            val normalized = normalizeMediaPath(target)
            val bytes = entries[normalized]
                ?: entries["word/$normalized"]
                ?: entries[normalized.removePrefix("word/")]

            if (bytes == null) {
                warnings += DocumentWarning(DocumentWarningLevel.WARN, "图片 rId=$relId 目标 $target 在 ZIP 中未找到")
                continue
            }

            val sha = sha256(bytes)
            val ext = target.substringAfterLast('.', "png").lowercase()
            val fileName = "$sha.$ext"

            var localPath: String? = null
            if (mediaDir != null) {
                mediaDir.mkdirs()
                val file = java.io.File(mediaDir, fileName)
                if (!file.exists()) file.writeBytes(bytes)
                localPath = file.absolutePath
            }

            relIdToMediaId[relId] = sha
            if (seen.add(sha)) {
                media += DocumentMedia(sha, relId, fileName, localPath, guessContentType(ext))
            }
        }
        return media
    }

    private fun normalizeMediaPath(target: String): String {
        val t = target.replace('\\', '/')
        return when {
            t.startsWith("word/media/") -> t
            t.startsWith("media/") -> "word/$t"
            else -> { val s = t.trimStart('/'); if (s.startsWith("word/")) s else "word/$s" }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Utilities
    // ═══════════════════════════════════════════════════════════

    private fun findRelId(parser: XmlPullParser): String? {
        for (i in 0 until parser.attributeCount) {
            val an = parser.getAttributeName(i)?.substringAfter(':')
            if (an == "embed" || an == "link") return parser.getAttributeValue(i)
        }
        return null
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun guessContentType(ext: String): String = when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        else -> "application/octet-stream"
    }
}
