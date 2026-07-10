package com.zzy.quizforge.util.document

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Android instrumentation test: 使用 production OoXmlDocumentReader() 读取真实 DOCX ZIP fixture。
 *
 * 验证 Android 实际 XmlPullParser 的 runtime semantics。
 */
@RunWith(AndroidJUnit4::class)
class OoXmlDocumentReaderAndroidTest {

    // ═══════════════════════════════════════════════════════════
    // Production parser path
    // ═══════════════════════════════════════════════════════════

    @Test
    fun reportParserClass() {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        val className = parser.javaClass.name
        // Record the actual parser class name used at runtime
        println("ANDROID_XPP_CLASS=$className")
        assertTrue("Parser class should be non-empty", className.isNotEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // Full mixed fixture read via production reader
    // ═══════════════════════════════════════════════════════════

    @Test
    fun productionReaderMixedFixture() {
        val reader = OoXmlDocumentReader() // default Android factory

        val entries = mutableMapOf<String, ByteArray>()

        // Content_Types
        entries["[Content_Types].xml"] = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Default Extension="png" ContentType="image/png"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            </Types>
        """.trimIndent().toByteArray()

        // _rels/.rels
        entries["_rels/.rels"] = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>
        """.trimIndent().toByteArray()

        // Image relationships
        entries["word/_rels/document.xml.rels"] = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/img1.png"/>
              <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/img2.png"/>
            </Relationships>
        """.trimIndent().toByteArray()

        // Numbering
        entries["word/numbering.xml"] = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:abstractNum w:abstractNumId="7">
                <w:lvl w:ilvl="0">
                  <w:numFmt w:val="decimal"/>
                  <w:lvlText w:val="%1."/>
                  <w:start w:val="1"/>
                </w:lvl>
              </w:abstractNum>
              <w:num w:numId="3">
                <w:abstractNumId w:val="7"/>
              </w:num>
            </w:numbering>
        """.trimIndent().toByteArray()

        // Media — two different PNG bytes
        entries["word/media/img1.png"] = minimalPng(0x01)
        entries["word/media/img2.png"] = minimalPng(0x02)

        // Document XML
        val bodyXml = numberedParagraph("编号段落", numId = 3) +
            paragraphWithTwoImages("rId1", "rId2") +
            simpleTable() +
            simpleParagraph("尾段正常段落")

        entries["word/document.xml"] = wrapDocument(bodyXml)

        // Read via PRODUCTION reader
        val doc = reader.read(entries)

        // ═══════════════════════════════════════════════
        // Assertions
        // ═══════════════════════════════════════════════

        // 1. Top-level blocks: P, P, Table, P
        assertEquals("Should have 4 top-level blocks", 4, doc.blocks.size)
        assertTrue("Block 0: ParagraphBlock", doc.blocks[0] is ParagraphBlock)
        assertTrue("Block 1: ParagraphBlock", doc.blocks[1] is ParagraphBlock)
        assertTrue("Block 2: TableBlock", doc.blocks[2] is TableBlock)
        assertTrue("Block 3: ParagraphBlock", doc.blocks[3] is ParagraphBlock)

        // 2. Numbered paragraph: NumberingRef
        val b0 = doc.blocks[0] as ParagraphBlock
        assertNotNull("Should have numbering", b0.numbering)
        assertEquals("numId", "3", b0.numbering!!.numId)
        assertEquals("level", 0, b0.numbering!!.level)

        // 3. Image paragraph: 5 inline items
        val b1 = doc.blocks[1] as ParagraphBlock
        val content = b1.content
        assertEquals("Image paragraph should have 5 inline items", 5, content.size)
        assertTrue(content[0] is TextContent)
        assertEquals("比较", (content[0] as TextContent).text)
        assertTrue(content[1] is ImageContent)
        assertTrue(content[2] is TextContent)
        assertEquals("与", (content[2] as TextContent).text)
        assertTrue(content[3] is ImageContent)
        assertTrue(content[4] is TextContent)
        assertEquals("的区别", (content[4] as TextContent).text)

        // Two different images → different mediaIds
        val img1 = content[1] as ImageContent
        val img2 = content[3] as ImageContent
        assertNotEquals("Two different images should have different mediaIds", img1.mediaId, img2.mediaId)
        assertEquals(64, img1.mediaId.length)
        assertEquals(64, img2.mediaId.length)

        // 4. Table: 2 rows × 2 cells
        val b2 = doc.blocks[2] as TableBlock
        assertEquals(2, b2.rows.size)
        assertEquals(2, b2.rows[0].cells.size)
        assertEquals(2, b2.rows[1].cells.size)

        // 5. Tail paragraph not swallowed
        val b3 = doc.blocks[3] as ParagraphBlock
        val tailText = b3.content.filterIsInstance<TextContent>().joinToString("") { it.text }
        assertEquals("尾段正常段落", tailText)

        // 6. Numbering definitions
        assertEquals(1, doc.numberingDefinitions.size)
        val numDef = doc.numberingDefinitions["3"]
        assertNotNull("Should have numbering def for numId=3", numDef)
        assertEquals("7", numDef!!.abstractNumId)
        val lvl = numDef.levels[0]
        assertNotNull("Should have level 0", lvl)
        assertEquals("decimal", lvl!!.numFmt)
        assertEquals("%1.", lvl.lvlText)
        assertEquals(1, lvl.start)

        // 7. All sourceOrders >= 0 and globally unique
        val allOrders = collectAllOrders(doc.blocks)
        assertTrue("Should have at least 6 blocks", allOrders.size >= 6)
        allOrders.forEach { assertTrue("sourceOrder >= 0: $it", it >= 0) }
        assertEquals("All sourceOrders must be unique", allOrders.size, allOrders.toSet().size)

        // 8. No [图片N] placeholders anywhere
        for (block in doc.blocks) {
            collectAllText(block).forEach { text ->
                assertFalse("Should not contain [图片N]: $text", text.contains("[图片"))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

    private fun collectAllOrders(blocks: List<DocumentBlock>): List<Int> {
        val orders = mutableListOf<Int>()
        for (b in blocks) {
            orders += b.sourceOrder
            if (b is TableBlock) {
                for (row in b.rows) for (cell in row.cells) {
                    orders += collectAllOrders(cell.blocks)
                }
            }
        }
        return orders
    }

    private fun collectAllText(block: DocumentBlock): List<String> {
        val texts = mutableListOf<String>()
        if (block is ParagraphBlock) {
            for (c in block.content) {
                if (c is TextContent) texts += c.text
            }
        }
        if (block is TableBlock) {
            for (row in block.rows) for (cell in row.cells) {
                for (cb in cell.blocks) texts += collectAllText(cb)
            }
        }
        return texts
    }

    // ═══════════════════════════════════════════════════════════
    // Minimal fixture builders
    // ═══════════════════════════════════════════════════════════

    private fun wrapDocument(bodyXml: String): ByteArray = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:document xmlns:wpc="http://schemas.microsoft.com/office/word/2010/wordprocessingCanvas"
                    xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
                    xmlns:o="urn:schemas-microsoft-com:office:office"
                    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                    xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math"
                    xmlns:v="urn:schemas-microsoft-com:vml"
                    xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
                    xmlns:w10="urn:schemas-microsoft-com:office:word"
                    xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                    xmlns:wne="http://schemas.microsoft.com/office/word/2006/wordml"
                    xmlns:sl="http://schemas.openxmlformats.org/schemaLibrary/2006/main"
                    xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                    xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
          <w:body>
            $bodyXml
          </w:body>
        </w:document>
    """.trimIndent().toByteArray()

    private fun simpleParagraph(text: String): String =
        """<w:p><w:r><w:t xml:space="preserve">$text</w:t></w:r></w:p>"""

    private fun numberedParagraph(text: String, numId: Int): String =
        """<w:p><w:pPr><w:numPr><w:ilvl w:val="0"></w:ilvl><w:numId w:val="$numId"></w:numId></w:numPr></w:pPr><w:r><w:t xml:space="preserve">$text</w:t></w:r></w:p>"""

    private fun paragraphWithTwoImages(relId1: String, relId2: String): String = """
        <w:p>
          <w:r><w:t xml:space="preserve">比较</w:t></w:r>
          <w:r><w:drawing><wp:inline><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:blipFill><a:blip r:embed="$relId1"/></pic:blipFill></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>
          <w:r><w:t xml:space="preserve">与</w:t></w:r>
          <w:r><w:drawing><wp:inline><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:blipFill><a:blip r:embed="$relId2"/></pic:blipFill></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>
          <w:r><w:t xml:space="preserve">的区别</w:t></w:r>
        </w:p>
    """.trimIndent()

    private fun simpleTable(): String = """
        <w:tbl>
          <w:tr>
            <w:tc><w:p><w:r><w:t xml:space="preserve">A</w:t></w:r></w:p></w:tc>
            <w:tc><w:p><w:r><w:t xml:space="preserve">TCP</w:t></w:r></w:p></w:tc>
          </w:tr>
          <w:tr>
            <w:tc><w:p><w:r><w:t xml:space="preserve">B</w:t></w:r></w:p></w:tc>
            <w:tc><w:p><w:r><w:t xml:space="preserve">UDP</w:t></w:r></w:p></w:tc>
          </w:tr>
        </w:tbl>
    """.trimIndent()

    private fun minimalPng(variant: Byte): ByteArray {
        // Minimal 1x1 PNG with variant byte embedded in IDAT
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // signature
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, // IHDR len
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, // 1x1
            0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53, 0xDE.toByte(), // IHDR + CRC
            0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41, 0x54, // IDAT len=12
            0x08, 0xD7.toByte(), 0x63, variant, 0x60, 0x60, 0x60, 0x00, // IDAT data
            0x00, 0x00, 0x04, 0x00, 0x01, 0x27.toByte(), 0x34, 0x09, // IDAT rest + CRC
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte() // IEND
        )
        return png
    }
}
