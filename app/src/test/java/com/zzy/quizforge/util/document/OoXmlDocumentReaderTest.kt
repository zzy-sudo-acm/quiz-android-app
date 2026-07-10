package com.zzy.quizforge.util.document

import org.junit.Assert.*
import org.junit.Test
import java.io.Reader

class OoXmlDocumentReaderTest {

    private fun makeReader() = OoXmlDocumentReader { reader: Reader -> TestXmlPullParser(reader) }

    private fun read(builder: DocxFixtureBuilder): StructuredDocument =
        makeReader().read(builder.build())

    // ═══════════════════════════════════════════════════════════
    // 1. Three paragraphs
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `three paragraphs produce three ParagraphBlocks with incrementing sourceOrder`() {
        val doc = read(
            DocxFixtureBuilder().documentXml(
                simpleParagraph("P1") + simpleParagraph("P2") + simpleParagraph("P3")
            )
        )
        assertEquals(3, doc.blocks.size)
        for (i in 0..2) {
            assertTrue(doc.blocks[i] is ParagraphBlock)
            assertEquals(i, doc.blocks[i].sourceOrder)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 2. Multiple runs merged
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `multiple runs merged in order`() {
        val doc = read(DocxFixtureBuilder().documentXml(paragraphWithRuns("TCP", "/", "IP")))
        val p = doc.blocks[0] as ParagraphBlock
        assertEquals(1, p.content.size)
        assertEquals("TCP/IP", (p.content[0] as TextContent).text)
    }

    // ═══════════════════════════════════════════════════════════
    // 3. Text → Image → Text
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `text image text inline order preserved`() {
        val doc = read(
            DocxFixtureBuilder()
                .imageRels(imageRelationshipXml("rId7", "media/img1.png"))
                .media("word/media/img1.png", minimalPngBytes())
                .documentXml(paragraphWithImage("rId7", "观察下图", "回答问题"))
        )
        val p = doc.blocks[0] as ParagraphBlock
        assertEquals(3, p.content.size)
        assertTrue(p.content[0] is TextContent)
        assertEquals("观察下图", (p.content[0] as TextContent).text)
        assertTrue(p.content[1] is ImageContent)
        assertNotNull((p.content[1] as ImageContent).mediaId)
        assertTrue(p.content[2] is TextContent)
        assertEquals("回答问题", (p.content[2] as TextContent).text)
    }

    // ═══════════════════════════════════════════════════════════
    // 4. Same paragraph two images
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `same paragraph two images both present in strict order`() {
        val doc = read(
            DocxFixtureBuilder()
                .imageRels(
                    imageRelationshipXml("rId1", "media/img1.png") +
                        imageRelationshipXml("rId2", "media/img2.png")
                )
                .media("word/media/img1.png", minimalPngBytes())
                .media("word/media/img2.png", byteArrayOf(0x01, 0x02, 0x03))
                .documentXml(paragraphWithTwoImages("rId1", "rId2"))
        )
        val p = doc.blocks[0] as ParagraphBlock
        assertEquals(5, p.content.size)
        assertEquals("比较", (p.content[0] as TextContent).text)
        assertTrue(p.content[1] is ImageContent)
        assertEquals("与", (p.content[2] as TextContent).text)
        assertTrue(p.content[3] is ImageContent)
        assertEquals("的区别", (p.content[4] as TextContent).text)

        val img1 = p.content[1] as ImageContent
        val img2 = p.content[3] as ImageContent
        assertNotEquals(img1.mediaId, img2.mediaId)
        assertEquals("rId1", img1.relationshipId)
        assertEquals("rId2", img2.relationshipId)
    }

    // ═══════════════════════════════════════════════════════════
    // 5. Unresolved image — always creates ImageContent node
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `unresolved ImageContent preserves source structure Text Image Text`() {
        // rId7 is declared in rels but media file is NOT provided
        val doc = read(
            DocxFixtureBuilder()
                .imageRels(imageRelationshipXml("rId7", "media/missing.png"))
                .documentXml(paragraphWithImage("rId7", "观察下图", "回答问题"))
        )
        val p = doc.blocks[0] as ParagraphBlock
        // Must have 3 items: Text, ImageContent(unresolved), Text
        assertEquals(3, p.content.size)
        assertTrue(p.content[0] is TextContent)
        assertEquals("观察下图", (p.content[0] as TextContent).text)

        assertTrue("content[1] must be ImageContent even if unresolved", p.content[1] is ImageContent)
        val img = p.content[1] as ImageContent
        assertNull("mediaId should be null for unresolved image", img.mediaId)
        assertEquals("rId7", img.relationshipId)

        assertTrue(p.content[2] is TextContent)
        assertEquals("回答问题", (p.content[2] as TextContent).text)

        // Texts must NOT merge across unresolved ImageContent — two separate TextContent items
        val texts = p.content.filterIsInstance<TextContent>()
        assertEquals("Should have 2 separate TextContent items, not merged", 2, texts.size)
        assertEquals("观察下图", texts[0].text)
        assertEquals("回答问题", texts[1].text)
    }

    // ═══════════════════════════════════════════════════════════
    // 6. Word numPr
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `numbered paragraph has NumberingRef`() {
        val doc = read(DocxFixtureBuilder().documentXml(numberedParagraph("X", numId = 3, level = 0)))
        val p = doc.blocks[0] as ParagraphBlock
        assertNotNull(p.numbering)
        assertEquals("3", p.numbering!!.numId)
        assertEquals(0, p.numbering.level)
    }

    // ═══════════════════════════════════════════════════════════
    // 7. Numbering definitions
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `numbering definitions link numId to level details`() {
        val doc = read(
            DocxFixtureBuilder()
                .numberingXml(decimalNumberingXml(numId = 3, abstractNumId = 7))
                .documentXml(numberedParagraph("X", numId = 3, level = 0))
        )
        assertEquals(1, doc.numberingDefinitions.size)
        val def = doc.numberingDefinitions["3"]
        assertNotNull(def)
        assertEquals("7", def!!.abstractNumId)
        val lvl = def.levels[0]
        assertNotNull(lvl)
        assertEquals("decimal", lvl!!.numFmt)
        assertEquals("%1.", lvl.lvlText)
        assertEquals(1, lvl.start)
    }

    // ═══════════════════════════════════════════════════════════
    // 8. Table 2×2
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `table 2x2 preserves structure`() {
        val doc = read(DocxFixtureBuilder().documentXml(simpleTable(listOf("A", "TCP", "B", "UDP"))))
        val table = doc.blocks[0] as TableBlock
        assertEquals(2, table.rows.size)
        assertEquals(2, table.rows[0].cells.size)
        assertEquals(2, table.rows[1].cells.size)
        assertEquals("A", cellText(table, 0, 0))
        assertEquals("TCP", cellText(table, 1, 0))
        assertEquals("B", cellText(table, 0, 1))
        assertEquals("UDP", cellText(table, 1, 1))
    }

    // ═══════════════════════════════════════════════════════════
    // 9. Line break
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `line break produces LineBreakContent`() {
        val doc = read(DocxFixtureBuilder().documentXml(paragraphWithLineBreak("L1", "L2")))
        val p = doc.blocks[0] as ParagraphBlock
        assertEquals(3, p.content.size)
        assertEquals("L1", (p.content[0] as TextContent).text)
        assertTrue(p.content[1] is LineBreakContent)
        assertEquals("L2", (p.content[2] as TextContent).text)
    }

    // ═══════════════════════════════════════════════════════════
    // 10. Duplicate image bytes → single DocumentMedia
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `duplicate image bytes produce single DocumentMedia entry`() {
        val imgBytes = minimalPngBytes()
        val doc = read(
            DocxFixtureBuilder()
                .imageRels(imageRelationshipXml("rId1", "media/img1.png") + imageRelationshipXml("rId2", "media/img2.png"))
                .media("word/media/img1.png", imgBytes)
                .media("word/media/img2.png", imgBytes)
                .documentXml(paragraphWithImage("rId1") + paragraphWithImage("rId2"))
        )
        assertEquals(1, doc.media.size)
        val p0 = doc.blocks[0] as ParagraphBlock
        val p1 = doc.blocks[1] as ParagraphBlock
        val img0 = p0.content.first { it is ImageContent } as ImageContent
        val img1 = p1.content.first { it is ImageContent } as ImageContent
        assertEquals(img0.mediaId, img1.mediaId)
        assertEquals(doc.media[0].mediaId, img0.mediaId)
    }

    // ═══════════════════════════════════════════════════════════
    // 11. No [图片N] placeholder
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `no image placeholder strings in text content`() {
        val doc = read(
            DocxFixtureBuilder()
                .imageRels(imageRelationshipXml("rId1", "media/img1.png"))
                .media("word/media/img1.png", minimalPngBytes())
                .documentXml(paragraphWithImage("rId1", "题干", "结束"))
        )
        for (block in doc.blocks) {
            if (block is ParagraphBlock) {
                for (inline in block.content) {
                    if (inline is TextContent) assertFalse(inline.text.contains("[图片"))
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 12. EXACT DFS sourceOrder sequence
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `exact DFS sourceOrder sequence matches traversal`() {
        val doc = read(
            DocxFixtureBuilder().documentXml(
                simpleParagraph("P1") + simpleTable(listOf("A", "B", "C", "D")) + simpleParagraph("P2")
            )
        )
        val allBlocks = collectAllBlocks(doc.blocks)
        val orders = allBlocks.map { it.sourceOrder }

        // Exact sequence: 0, 1, 2, 3, ... (not sorted!)
        assertEquals(
            (0 until allBlocks.size).toList(),
            orders,
        )
    }

    // ═══════════════════════════════════════════════════════════
    // 13. Global sourceOrder uniqueness and >= 0
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `all sourceOrders globally unique and nonnegative`() {
        val doc = read(
            DocxFixtureBuilder().documentXml(
                simpleParagraph("P1") + simpleTable(listOf("A", "B", "C", "D")) + simpleParagraph("P2")
            )
        )
        val orders = collectAllSourceOrders(doc.blocks)
        assertEquals(orders.size, orders.toSet().size)
        orders.forEach { assertTrue("sourceOrder must be >= 0, got $it", it >= 0) }
    }

    // ═══════════════════════════════════════════════════════════
    // 14. Global sourceId uniqueness (including cell blocks)
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `all sourceIds globally unique including cell blocks`() {
        val doc = read(
            DocxFixtureBuilder().documentXml(
                simpleParagraph("P1") + simpleTable(listOf("A", "B", "C", "D")) + simpleParagraph("P2")
            )
        )
        val ids = collectAllSourceIds(doc.blocks)
        assertEquals("All sourceIds must be unique: $ids", ids.size, ids.toSet().size)
    }

    // ═══════════════════════════════════════════════════════════
    // 15. Mixed body blocks
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `mixed body blocks numbered image table normal`() {
        val doc = read(
            DocxFixtureBuilder()
                .numberingXml(decimalNumberingXml(numId = 1, abstractNumId = 0))
                .imageRels(imageRelationshipXml("rId1", "media/img1.png"))
                .media("word/media/img1.png", minimalPngBytes())
                .documentXml(
                    numberedParagraph("编号段落", numId = 1) +
                        paragraphWithImage("rId1", "图文前", "图文后") +
                        simpleTable(listOf("A", "B", "C", "D")) +
                        simpleParagraph("尾段")
                )
        )
        assertEquals(4, doc.blocks.size)
        assertTrue(doc.blocks[0] is ParagraphBlock)
        assertTrue(doc.blocks[1] is ParagraphBlock)
        assertTrue(doc.blocks[2] is TableBlock)
        assertTrue(doc.blocks[3] is ParagraphBlock)

        val b0 = doc.blocks[0] as ParagraphBlock
        assertNotNull(b0.numbering)
        assertEquals("1", b0.numbering!!.numId)

        val b1 = doc.blocks[1] as ParagraphBlock
        assertTrue(b1.content.any { it is ImageContent })

        val b2 = doc.blocks[2] as TableBlock
        assertEquals(2, b2.rows.size)

        val b3 = doc.blocks[3] as ParagraphBlock
        assertEquals("尾段", b3.content.filterIsInstance<TextContent>().joinToString("") { it.text })

        // No cell blocks promoted to top-level
        assertTrue(doc.blocks.none { it.sourceId.startsWith("c") })
    }

    // ═══════════════════════════════════════════════════════════
    // 16. Consecutive reads — independent parse sessions
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `consecutive reads produce independent sourceOrder sequences`() {
        val reader = makeReader()

        val doc1 = reader.read(
            DocxFixtureBuilder().documentXml(simpleParagraph("A") + simpleParagraph("B")).build()
        )
        val doc2 = reader.read(
            DocxFixtureBuilder().documentXml(simpleParagraph("C") + simpleParagraph("D")).build()
        )

        // Both documents should independently start from sourceOrder 0
        assertEquals(listOf(0, 1), doc1.blocks.map { it.sourceOrder })
        assertEquals(listOf(0, 1), doc2.blocks.map { it.sourceOrder })

        // sourceIds should also restart
        assertEquals("p0", doc1.blocks[0].sourceId)
        assertEquals("p0", doc2.blocks[0].sourceId)
    }

    // ═══════════════════════════════════════════════════════════
    // 17. Debug dump
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `debug dump contains key structural info`() {
        val doc = read(
            DocxFixtureBuilder()
                .numberingXml(decimalNumberingXml(numId = 1, abstractNumId = 0))
                .imageRels(imageRelationshipXml("rId1", "media/img1.png"))
                .media("word/media/img1.png", minimalPngBytes())
                .documentXml(
                    numberedParagraph("编号段落", numId = 1) +
                        paragraphWithImage("rId1", "图文") +
                        simpleTable(listOf("A", "B", "C", "D"))
                )
        )
        val json = DocumentDebugDump.toJson(doc)
        assertTrue(json.contains("paragraph"))
        assertTrue(json.contains("numbering"))
        assertTrue(json.contains("numId"))
        assertTrue(json.contains("image"))
    }

    // ═══════════════════════════════════════════════════════════
    // 18. Summary
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `summary contains block and media counts`() {
        val doc = read(DocxFixtureBuilder().documentXml(simpleParagraph("P1") + simpleParagraph("P2")))
        val s = DocumentDebugDump.summary(doc)
        assertTrue(s.contains("2 blocks"))
    }

    // ═══════════════════════════════════════════════════════════
    // 19–24. pPrDepth targeted regressions — must not lose blocks after numbered paragraph
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `numbered paragraph followed by simple paragraph`() {
        val doc = read(
            DocxFixtureBuilder()
                .numberingXml(decimalNumberingXml(numId = 1, abstractNumId = 0))
                .documentXml(numberedParagraph("编号", numId = 1) + simpleParagraph("普通"))
        )
        assertEquals(2, doc.blocks.size)
    }

    @Test
    fun `numbered paragraph followed by image paragraph`() {
        val doc = read(
            DocxFixtureBuilder()
                .numberingXml(decimalNumberingXml(numId = 1, abstractNumId = 0))
                .imageRels(imageRelationshipXml("rId1", "media/img1.png"))
                .media("word/media/img1.png", minimalPngBytes())
                .documentXml(numberedParagraph("编号", numId = 1) + paragraphWithImage("rId1", "图", "文"))
        )
        assertEquals(2, doc.blocks.size)
    }

    @Test
    fun `image paragraph followed by table`() {
        val doc = read(
            DocxFixtureBuilder()
                .imageRels(imageRelationshipXml("rId1", "media/img1.png"))
                .media("word/media/img1.png", minimalPngBytes())
                .documentXml(paragraphWithImage("rId1", "图", "文") + simpleTable(listOf("A", "B", "C", "D")))
        )
        assertEquals(2, doc.blocks.size)
    }

    @Test
    fun `numbered plus simple paragraph diagnostic`() {
        val doc = read(
            DocxFixtureBuilder()
                .numberingXml(decimalNumberingXml(numId = 1, abstractNumId = 0))
                .documentXml(numberedParagraph("编号", numId = 1) + simpleParagraph("普通"))
        )
        val msg = "Got ${doc.blocks.size} blocks: ${doc.blocks.map { "${it::class.simpleName}(${it.sourceId}, order=${it.sourceOrder})" }}"
        assertEquals(msg, 2, doc.blocks.size)
    }

    @Test
    fun `paragraph with empty pPr followed by simple paragraph`() {
        val xml = """<w:p><w:pPr></w:pPr><w:r><w:t xml:space="preserve">有属性</w:t></w:r></w:p>"""
        val doc = read(DocxFixtureBuilder().documentXml(xml + simpleParagraph("普通")))
        assertEquals("Got ${doc.blocks.size} blocks", 2, doc.blocks.size)
    }

    @Test
    fun `paragraph with pPr and rPr followed by simple paragraph`() {
        val xml = """<w:p><w:pPr><w:rPr><w:b></w:b></w:rPr></w:pPr><w:r><w:t xml:space="preserve">粗体</w:t></w:r></w:p>"""
        val doc = read(DocxFixtureBuilder().documentXml(xml + simpleParagraph("普通")))
        assertEquals(2, doc.blocks.size)
    }

    // ═══════════════════════════════════════════════════════════
    // 25–27. Image warning classification — non-duplicating
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `blip without embed or link preserves ImageContent and produces warning`() {
        // Raw paragraph XML with a:blip that has no r:embed attribute
        val xml = """<w:p><w:r><w:t xml:space="preserve">前</w:t></w:r><w:r><w:drawing><wp:inline><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:blipFill><a:blip/></pic:blipFill></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r><w:r><w:t xml:space="preserve">后</w:t></w:r></w:p>"""
        val doc = read(DocxFixtureBuilder().documentXml(xml))

        val p = doc.blocks[0] as ParagraphBlock
        assertEquals(3, p.content.size)
        val img = p.content[1] as ImageContent
        assertNull(img.mediaId)
        assertNull(img.relationshipId)

        // Must produce warning about missing embed/link
        assertTrue(doc.warnings.any { it.message.contains("embed") || it.message.contains("link") })
    }

    @Test
    fun `undeclared image relationship preserves ImageContent and produces warning`() {
        // rId99 is NOT declared in rels — the .imageRels() call is intentionally absent
        val doc = read(
            DocxFixtureBuilder()
                .documentXml(paragraphWithImage("rId99", "前", "后"))
        )
        val p = doc.blocks[0] as ParagraphBlock
        assertEquals(3, p.content.size)
        val img = p.content[1] as ImageContent
        assertNull(img.mediaId)
        assertEquals("rId99", img.relationshipId)

        // Must produce warning about undeclared relationship
        assertTrue(doc.warnings.any { it.message.contains("rId99") && it.message.contains("rels") })
    }

    @Test
    fun `missing media bytes produces exactly one non-duplicated warning`() {
        // rId7 IS declared in rels but media file is NOT provided
        val doc = read(
            DocxFixtureBuilder()
                .imageRels(imageRelationshipXml("rId7", "media/missing.png"))
                .documentXml(paragraphWithImage("rId7", "前", "后"))
        )
        val p = doc.blocks[0] as ParagraphBlock
        assertEquals(3, p.content.size)
        val img = p.content[1] as ImageContent
        assertNull(img.mediaId)
        assertEquals("rId7", img.relationshipId)

        // buildMediaList warns once. parseParagraph must NOT add a duplicate.
        val mediaWarnings = doc.warnings.filter { it.message.contains("rId7") }
        assertEquals("Should have exactly 1 warning for rId7, got ${mediaWarnings.map { it.message }}", 1, mediaWarnings.size)
    }
}

// ═══════════════════════════════════════════════════════════════
// Helpers
// ═══════════════════════════════════════════════════════════════

private fun collectAllSourceOrders(blocks: List<DocumentBlock>): List<Int> {
    val orders = mutableListOf<Int>()
    for (b in blocks) {
        orders += b.sourceOrder
        if (b is TableBlock) {
            for (row in b.rows) for (cell in row.cells) {
                orders += collectAllSourceOrders(cell.blocks)
            }
        }
    }
    return orders
}

private fun collectAllBlocks(blocks: List<DocumentBlock>): List<DocumentBlock> {
    val all = mutableListOf<DocumentBlock>()
    for (b in blocks) {
        all += b
        if (b is TableBlock) {
            for (row in b.rows) for (cell in row.cells) {
                all += collectAllBlocks(cell.blocks)
            }
        }
    }
    return all
}

private fun collectAllSourceIds(blocks: List<DocumentBlock>): List<String> {
    val ids = mutableListOf<String>()
    for (b in blocks) {
        ids += b.sourceId
        if (b is TableBlock) {
            for (row in b.rows) for (cell in row.cells) {
                ids += collectAllSourceIds(cell.blocks)
            }
        }
    }
    return ids
}

private fun cellText(table: TableBlock, cellIndex: Int, rowIndex: Int): String {
    val cell = table.rows[rowIndex].cells[cellIndex]
    val p = cell.blocks.firstOrNull { it is ParagraphBlock } as? ParagraphBlock
    return p?.content?.filterIsInstance<TextContent>()?.joinToString("") { it.text } ?: ""
}
