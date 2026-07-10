package com.zzy.quizforge.util.document

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.security.MessageDigest

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
    // Per-read parse context — eliminates instance mutable state
    // ═══════════════════════════════════════════════════════════

    private class ParseContext {
        private var nextSourceOrder = 0
        private var nextSourceId = 0

        fun nextOrder(): Int = nextSourceOrder++
        fun allocId(prefix: String): String = "$prefix${nextSourceId++}"
    }

    // ═══════════════════════════════════════════════════════════
    // OOXML attribute helper — production reader resolves prefixed attributes itself
    // ═══════════════════════════════════════════════════════════

    /**
     * 按 local name（去掉命名空间前缀）查找 OOXML 属性值。
     *
     * OOXML 属性通常带前缀：w:val, w:numId, r:embed 等。
     * Android XmlPullParser 在不同 parser impl 下对这些属性的
     * getAttributeValue(null, "val") 行为不一致。
     *
     * 此方法显式按 local name 匹配，不依赖 parser 的 namespace 处理行为。
     */
    private fun attributeByLocalName(parser: XmlPullParser, localName: String): String? {
        for (i in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(i)?.substringAfter(':') ?: continue
            if (name == localName) return parser.getAttributeValue(i)
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════

    fun read(
        entries: Map<String, ByteArray>,
        mediaDir: java.io.File? = null,
    ): StructuredDocument {
        val ctx = ParseContext()
        val warnings = mutableListOf<DocumentWarning>()

        val documentXml = entries["word/document.xml"]?.toString(Charsets.UTF_8)
            ?: throw IllegalArgumentException("缺少 word/document.xml")

        val relsXml = entries["word/_rels/document.xml.rels"]?.toString(Charsets.UTF_8).orEmpty()
        val numberingXml = entries["word/numbering.xml"]?.toString(Charsets.UTF_8).orEmpty()

        val (declaredImageRelIds, resolvableImageRels) = parseImageRelationships(relsXml, warnings)
        val numberingDefinitions = parseNumberingDefinitions(numberingXml)

        val relIdToMediaId = mutableMapOf<String, String>()
        val media = buildMediaList(entries, resolvableImageRels, mediaDir, warnings, relIdToMediaId)

        val blocks = parseDocumentBlocks(documentXml, declaredImageRelIds, relIdToMediaId, warnings, ctx)

        return StructuredDocument(blocks, media, numberingDefinitions, warnings)
    }

    // ═══════════════════════════════════════════════════════════
    // Document body → blocks
    // ═══════════════════════════════════════════════════════════

    private fun parseDocumentBlocks(
        xml: String,
        imageRelIds: Set<String>,
        relIdToMediaId: Map<String, String>,
        warnings: MutableList<DocumentWarning>,
        ctx: ParseContext,
    ): List<DocumentBlock> {
        val parser = createParser(StringReader(xml))
        val blocks = mutableListOf<DocumentBlock>()
        var bodyDepth = 0

        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            val name = parser.name?.substringAfter(':') ?: ""

            when {
                event == XmlPullParser.START_TAG && name == "body" -> bodyDepth = 1
                event == XmlPullParser.END_TAG && name == "body" -> bodyDepth = 0
                event == XmlPullParser.START_TAG && bodyDepth > 0 && name == "p" ->
                    blocks += parseParagraph(parser, imageRelIds, relIdToMediaId, warnings, ctx)
                event == XmlPullParser.START_TAG && bodyDepth > 0 && name == "tbl" ->
                    blocks += parseTable(parser, imageRelIds, relIdToMediaId, warnings, ctx)
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
        warnings: MutableList<DocumentWarning>,
        ctx: ParseContext,
    ): ParagraphBlock {
        val order = ctx.nextOrder()
        val id = ctx.allocId("p")
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
                            numId = attributeByLocalName(parser, "val")
                            pPrDepth++
                        }
                        inPPr && name == "ilvl" -> {
                            level = attributeByLocalName(parser, "val")?.toIntOrNull()
                            pPrDepth++
                        }
                        inPPr -> pPrDepth++
                        name == "t" -> content += TextContent(readTextContent(parser))
                        name == "br" || name == "cr" -> content += LineBreakContent
                        name == "blip" -> {
                            val relId = attributeByLocalName(parser, "embed")
                                ?: attributeByLocalName(parser, "link")
                            val mediaId = if (relId != null) relIdToMediaId[relId] else null
                            // Always create ImageContent — source reference node
                            content += ImageContent(mediaId = mediaId, relationshipId = relId)
                            // Warning classification (non-duplicating):
                            when {
                                relId == null -> warnings += DocumentWarning(
                                    DocumentWarningLevel.WARN,
                                    "blip 缺少 r:embed / r:link 属性"
                                )
                                relId !in imageRelIds -> warnings += DocumentWarning(
                                    DocumentWarningLevel.WARN,
                                    "Image rId=$relId 未在 document.xml.rels 中声明"
                                )
                                // mediaId == null with valid relId: media bytes missing,
                                // already warned by buildMediaList — no duplicate warning here
                            }
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

    /** Merge adjacent TextContent. Does NOT merge across ImageContent or LineBreakContent. */
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
        warnings: MutableList<DocumentWarning>,
        ctx: ParseContext,
    ): TableBlock {
        val order = ctx.nextOrder()
        val id = ctx.allocId("t")
        val rows = mutableListOf<TableRow>()
        var depth = 1
        var event = parser.next()

        while (depth > 0 && event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name?.substringAfter(':')
            when {
                event == XmlPullParser.START_TAG && name == "tr" ->
                    rows += parseTableRow(parser, imageRelIds, relIdToMediaId, warnings, ctx)
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
        warnings: MutableList<DocumentWarning>,
        ctx: ParseContext,
    ): TableRow {
        val cells = mutableListOf<TableCell>()
        var depth = 1
        var event = parser.next()

        while (depth > 0 && event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name?.substringAfter(':')
            when {
                event == XmlPullParser.START_TAG && name == "tc" ->
                    cells += parseTableCell(parser, imageRelIds, relIdToMediaId, warnings, ctx)
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
        warnings: MutableList<DocumentWarning>,
        ctx: ParseContext,
    ): TableCell {
        val blocks = mutableListOf<DocumentBlock>()
        var depth = 1
        var event = parser.next()

        while (depth > 0 && event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name?.substringAfter(':')
            when {
                event == XmlPullParser.START_TAG && name == "p" ->
                    blocks += parseParagraph(parser, imageRelIds, relIdToMediaId, warnings, ctx)
                event == XmlPullParser.START_TAG && name == "tbl" ->
                    blocks += parseTable(parser, imageRelIds, relIdToMediaId, warnings, ctx)
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

    private fun parseImageRelationships(
        xml: String,
        warnings: MutableList<DocumentWarning>,
    ): Pair<Set<String>, Map<String, String>> {
        if (xml.isBlank()) return Pair(emptySet(), emptyMap())
        val parser = createParser(StringReader(xml))
        val declared = mutableSetOf<String>()
        val resolvable = linkedMapOf<String, String>()
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name?.substringAfter(':') == "Relationship") {
                val id = attributeByLocalName(parser, "Id")
                val target = attributeByLocalName(parser, "Target")
                val type = attributeByLocalName(parser, "Type").orEmpty()
                if (type.contains("/image") && !id.isNullOrBlank()) {
                    declared += id
                    if (!target.isNullOrBlank()) resolvable[id] = target
                    else warnings += DocumentWarning(DocumentWarningLevel.WARN, "Image relationship $id 缺少 Target")
                }
            }
            event = parser.next()
        }
        return Pair(declared, resolvable)
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
                        "abstractNum" -> currentAbsId = attributeByLocalName(parser, "abstractNumId")
                        "lvl" -> {
                            level = attributeByLocalName(parser, "ilvl")?.toIntOrNull() ?: 0
                            numFmt = null; lvlText = null; startVal = null
                        }
                        "numFmt" -> numFmt = attributeByLocalName(parser, "val")
                        "lvlText" -> lvlText = attributeByLocalName(parser, "val")
                        "start" -> startVal = attributeByLocalName(parser, "val")?.toIntOrNull()
                        "num" -> {
                            val nid = attributeByLocalName(parser, "numId")
                            if (nid != null) {
                                var inner = parser.next()
                                while (inner != XmlPullParser.END_DOCUMENT) {
                                    if (inner == XmlPullParser.START_TAG && parser.name?.substringAfter(':') == "abstractNumId") {
                                        val aid = attributeByLocalName(parser, "val")
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
