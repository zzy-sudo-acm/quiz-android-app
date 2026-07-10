package com.zzy.quizforge.util.document

import org.junit.Assert.*
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import java.io.Reader

class OoXmlDocumentReaderTest {

    private val testReader = OoXmlDocumentReader { reader: Reader -> TestXmlPullParser(reader) }

    private fun read(builder: DocxFixtureBuilder): StructuredDocument =
        testReader.read(builder.build())

    // ═══════════════════════════════════════════════════════════════
    // 1. Three paragraphs → sourceOrder 0,1,2
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `three paragraphs produce three ParagraphBlocks with incrementing sourceOrder`() {
        val doc = read(
            DocxFixtureBuilder().documentXml(
                simpleParagraph("第一段") + simpleParagraph("第二段") + simpleParagraph("第三段")
            )
        )
        assertEquals(3, doc.blocks.size)
        for (i in 0..2) {
            assertTrue(doc.blocks[i] is ParagraphBlock)
            assertEquals(i, doc.blocks[i].sourceOrder)
            assertTrue(doc.blocks[i].sourceId.startsWith("p"))
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. Multiple runs merged
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `multiple runs in one paragraph merged in order`() {
        val doc = read(DocxFixtureBuilder().documentXml(paragraphWithRuns("TCP", "/", "IP")))
        assertEquals(1, doc.blocks.size)
        val p = doc.blocks[0] as ParagraphBlock
        assertEquals(1, p.content.size)
        assertEquals("TCP/IP", (p.content[0] as TextContent).text)
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. Text → Image → Text inline order
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `text image text inline order preserved`() {
        val doc = read(
            DocxFixtureBuilder()
                .imageRels(imageRelationshipXml("rId7", "media/img1.png"))
                .media("word/media/img1.png", minimalPngBytes())
                .documentXml(paragraphWithImage("rId7", "观察下图", "回答问题"))
        )
        assertEquals(1, doc.blocks.size)
        val p = doc.blocks[0] as ParagraphBlock
        assertEquals(3, p.content.size)
        assertTrue(p.content[0] is TextContent)
        assertEquals("观察下图", (p.content[0] as TextContent).text)
        assertTrue(p.content[1] is ImageContent)
        assertTrue(p.content[2] is TextContent)
        assertEquals("回答问题", (p.content[2] as TextContent).text)
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. Same paragraph TWO images — Text→Img→Text→Img→Text
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `same paragraph two images both present in strict order`() {
        val doc = read(
            DocxFixtureBuilder()
                .imageRels(
                    imageRelationshipXml("rId1", "media/img1.png") +
                        imageRelationshipXml("rId2", "media/img2.png")
                )
                .media("word/media/img1.png", minimalPngBytes())
                .media("word/media/img2.png", byteArrayOf(0x01, 0x02, 0x03)) // different bytes
                .documentXml(paragraphWithTwoImages("rId1", "rId2"))
        )

        assertEquals(1, doc.blocks.size)
        val p = doc.blocks[0] as ParagraphBlock
        assertEquals(5, p.content.size)

        assertTrue(p.content[0] is TextContent)
        assertEquals("比较", (p.content[0] as TextContent).text)

        assertTrue(p.content[1] is ImageContent)
        val img1 = p.content[1] as ImageContent
        assertEquals(64, img1.mediaId.length)

        assertTrue(p.content[2] is TextContent)
        assertEquals("与", (p.content[2] as TextContent).text)

        assertTrue(p.content[3] is ImageContent)
        val img2 = p.content[3] as ImageContent
        assertEquals(64, img2.mediaId.length)

        assertTrue(p.content[4] is TextContent)
        assertEquals("的区别", (p.content[4] as TextContent).text)

        // Two different images → two different mediaIds
        assertNotEquals(img1.mediaId, img2.mediaId)
    }

    // ═══════════════════════════════════════════════════════════════
    // 5. Word numPr
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `numbered paragraph has NumberingRef with correct numId and level`() {
        val doc = read(
            DocxFixtureBuilder().documentXml(numberedParagraph("第一项", numId = 3, level = 0))
        )
        val p = doc.blocks[0] as ParagraphBlock
        assertNotNull(p.numbering)
        assertEquals("3", p.numbering!!.numId)
        assertEquals(0, p.numbering.level)
    }

    // ═══════════════════════════════════════════════════════════════
    // 6. Numbering definitions
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `numbering definition links numId to abstractNumId and level details`() {
        val doc = read(
            DocxFixtureBuilder()
                .numberingXml(decimalNumberingXml(numId = 3, abstractNumId = 7))
                .documentXml(numberedParagraph("编号项", numId = 3, level = 0))
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

    // ═══════════════════════════════════════════════════════════════
    // 7. Table 2×2
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `table 2x2 preserves structure`() {
        val doc = read(DocxFixtureBuilder().documentXml(simpleTable(listOf("A", "TCP", "B", "UDP"))))
        assertEquals(1, doc.blocks.size)
        val table = doc.blocks[0] as TableBlock
        assertTrue(table.sourceId.startsWith("t"))
        assertEquals(2, table.rows.size)
        assertEquals(2, table.rows[0].cells.size)
        assertEquals(2, table.rows[1].cells.size)
        assertEquals("A", cellText(table, 0, 0))
        assertEquals("TCP", cellText(table, 1, 0))
        assertEquals("B", cellText(table, 0, 1))
        assertEquals("UDP", cellText(table, 1, 1))
    }

    // ═══════════════════════════════════════════════════════════════
    // 8. Line break
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `line break produces LineBreakContent`() {
        val doc = read(DocxFixtureBuilder().documentXml(paragraphWithLineBreak("第一行", "第二行")))
        val p = doc.blocks[0] as ParagraphBlock
        assertEquals(3, p.content.size)
        assertEquals("第一行", (p.content[0] as TextContent).text)
        assertTrue(p.content[1] is LineBreakContent)
        assertEquals("第二行", (p.content[2] as TextContent).text)
    }

    // ═══════════════════════════════════════════════════════════════
    // 9. Duplicate image bytes → dedup + same mediaId
    // ═══════════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════════
    // 10. No [图片N] placeholder strings
    // ═══════════════════════════════════════════════════════════════

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
                    if (inline is TextContent) {
                        assertFalse(inline.text.contains("[图片"))
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 11. All sourceOrders unique and >= 0
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `all sourceOrders globally unique and nonnegative`() {
        val doc = read(
            DocxFixtureBuilder().documentXml(
                simpleParagraph("P1") + simpleTable(listOf("A", "B", "C", "D")) + simpleParagraph("P2")
            )
        )
        val orders = collectAllSourceOrders(doc.blocks)
        assertTrue("Should have at least 3 blocks, got ${orders.size}", orders.size >= 3)
        // All sourceOrders must be unique
        assertEquals("All sourceOrders must be unique: $orders", orders.size, orders.toSet().size)
        // All sourceOrders must be >= 0
        orders.forEach { assertTrue("sourceOrder must be >= 0, got $it", it >= 0) }
    }

    // ═══════════════════════════════════════════════════════════════
    // 12. sourceOrder follows DFS/traversal order
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `sourceOrder follows document traversal order`() {
        val doc = read(
            DocxFixtureBuilder().documentXml(
                simpleParagraph("P1") + simpleTable(listOf("A", "B", "C", "D")) + simpleParagraph("P2")
            )
        )

        val all = collectAllBlocks(doc.blocks)
        assertTrue(all.size >= 6)

        // All sourceOrders >= 0 and unique
        val orders = all.map { it.sourceOrder }
        orders.forEach { assertTrue("sourceOrder $it should be >= 0", it >= 0) }
        assertEquals(orders.toSet().size, orders.size) // unique

        // Body-level blocks: p0, table, p2 should preserve sourceOrder ordering
        assertEquals(0, doc.blocks[0].sourceOrder) // P1 first
        assertTrue(doc.blocks[1].sourceOrder > 0) // Table after P1
        assertTrue(doc.blocks[2].sourceOrder > doc.blocks[1].sourceOrder) // P2 last
    }

    // ═══════════════════════════════════════════════════════════════
    // 13. sourceId globally unique
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `all sourceIds are globally unique`() {
        val doc = read(
            DocxFixtureBuilder().documentXml(
                simpleParagraph("P1") + simpleTable(listOf("A", "B", "C", "D")) + simpleParagraph("P2")
            )
        )
        val ids = collectAllSourceIds(doc.blocks)
        assertEquals(ids.size, ids.toSet().size)
    }

    // ═══════════════════════════════════════════════════════════════
    // 14. Mixed body blocks: numbered p + image p + table + p
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `mixed body blocks numbered paragraph image paragraph table normal paragraph`() {
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

        assertEquals("Should have 4 top-level blocks", 4, doc.blocks.size)

        // Block 0: numbered paragraph
        val b0 = doc.blocks[0] as ParagraphBlock
        assertEquals(0, b0.sourceOrder)
        assertNotNull(b0.numbering)
        assertEquals("1", b0.numbering!!.numId)

        // Block 1: image paragraph (Text → Image → Text, may include extra whitespace TextContent from nested XML)
        val b1 = doc.blocks[1] as ParagraphBlock
        assertEquals(1, b1.sourceOrder)
        assertTrue(b1.content.size >= 3)
        assertTrue(b1.content.any { it is ImageContent })
        val texts = b1.content.filterIsInstance<TextContent>().joinToString("") { it.text }
        assertTrue(texts.contains("图文前"))
        assertTrue(texts.contains("图文后"))

        // Block 2: table
        val b2 = doc.blocks[2] as TableBlock
        assertEquals(2, b2.sourceOrder)
        assertEquals(2, b2.rows.size)

        // Block 3: normal paragraph (sourceOrder reflects global traversal, may be > 3 due to cell blocks)
        val b3 = doc.blocks[3] as ParagraphBlock
        assertTrue(b3.sourceOrder > b2.sourceOrder)
        assertTrue(b3.sourceOrder >= 3)
        assertEquals("尾段", (b3.content.single() as TextContent).text)

        // No cell-level blocks promoted to top-level
        assertTrue(doc.blocks.none { it.sourceId.startsWith("c") })
    }

    // ═══════════════════════════════════════════════════════════════
    // 15. Missing media produces warning
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `missing media produces warning but does not fail`() {
        val doc = read(
            DocxFixtureBuilder()
                .imageRels(imageRelationshipXml("rId1", "media/missing.png"))
                .documentXml(paragraphWithImage("rId1"))
        )
        assertTrue(doc.warnings.isNotEmpty())
        assertEquals(1, doc.blocks.size)
    }

    // ═══════════════════════════════════════════════════════════════
    // 16. Text runs do not merge across line break
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `text runs do not merge across line break`() {
        val doc = read(DocxFixtureBuilder().documentXml(paragraphWithLineBreak("第一行", "第二行")))
        val p = doc.blocks[0] as ParagraphBlock
        assertEquals(3, p.content.size)
        assertTrue(p.content[0] is TextContent)
        assertTrue(p.content[1] is LineBreakContent)
        assertTrue(p.content[2] is TextContent)
    }

    // ═══════════════════════════════════════════════════════════════
    // 17. Debug dump
    // ═══════════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════════
    // 18. Summary
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `summary contains block and media counts`() {
        val doc = read(DocxFixtureBuilder().documentXml(simpleParagraph("P1") + simpleParagraph("P2")))
        val s = DocumentDebugDump.summary(doc)
        assertTrue(s.contains("2 blocks"))
    }

    // ═══════════════════════════════════════════════════════════════
    // 19. Two separate paragraphs (simplest reproducible mixed case)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `numbered paragraph followed by simple paragraph`() {
        val doc = read(
            DocxFixtureBuilder()
                .numberingXml(decimalNumberingXml(numId = 1, abstractNumId = 0))
                .documentXml(
                    numberedParagraph("编号", numId = 1) + simpleParagraph("普通")
                )
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
                .documentXml(
                    numberedParagraph("编号", numId = 1) + paragraphWithImage("rId1", "图", "文")
                )
        )
        assertEquals(2, doc.blocks.size)
    }

    @Test
    fun `image paragraph followed by table`() {
        val doc = read(
            DocxFixtureBuilder()
                .imageRels(imageRelationshipXml("rId1", "media/img1.png"))
                .media("word/media/img1.png", minimalPngBytes())
                .documentXml(
                    paragraphWithImage("rId1", "图", "文") + simpleTable(listOf("A", "B", "C", "D"))
                )
        )
        assertEquals(2, doc.blocks.size)
    }

    @Test
    fun `numbered plus simple paragraph parseBodyChildren diagnostic`() {
        val doc = read(
            DocxFixtureBuilder()
                .numberingXml(decimalNumberingXml(numId = 1, abstractNumId = 0))
                .documentXml(
                    numberedParagraph("编号", numId = 1) + simpleParagraph("普通")
                )
        )
        val msg = "Got ${doc.blocks.size} blocks: ${doc.blocks.map { "${it::class.simpleName}(${it.sourceId}, order=${it.sourceOrder})" }}"
        assertEquals(msg, 2, doc.blocks.size)
    }

    @Test
    fun `paragraph with empty pPr followed by simple paragraph`() {
        // w:pPr block without any children — just empty properties
        val xml = """<w:p><w:pPr></w:pPr><w:r><w:t xml:space="preserve">有属性</w:t></w:r></w:p>"""
        val doc = read(
            DocxFixtureBuilder().documentXml(
                xml + simpleParagraph("普通")
            )
        )
        assertEquals("Got ${doc.blocks.size} blocks", 2, doc.blocks.size)
    }

    @Test
    fun `paragraph with pPr and rPr followed by simple paragraph`() {
        // w:pPr with w:rPr (run properties, no numbering)
        val xml = """<w:p><w:pPr><w:rPr><w:b></w:b></w:rPr></w:pPr><w:r><w:t xml:space="preserve">粗体</w:t></w:r></w:p>"""
        val doc = read(
            DocxFixtureBuilder().documentXml(
                xml + simpleParagraph("普通")
            )
        )
        assertEquals(2, doc.blocks.size)
    }
}

// ═══════════════════════════════════════════════════════════════════
// Helpers
// ═══════════════════════════════════════════════════════════════════

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
