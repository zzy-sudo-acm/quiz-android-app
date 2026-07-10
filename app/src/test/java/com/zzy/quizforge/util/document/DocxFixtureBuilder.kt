package com.zzy.quizforge.util.document

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 构建最小 DOCX ZIP fixture，用于 JVM 单元测试。
 *
 * DOCX 本质是一个 ZIP 文件，包含特定 XML 结构。
 * 此 builder 构造合法的最小 DOCX，无需真实文件系统。
 */
class DocxFixtureBuilder {

    private val entries = mutableMapOf<String, ByteArray>()

    // ═══════════════════════════════════════════════════════════════════
    // Required DOCX scaffolding
    // ═══════════════════════════════════════════════════════════════════

    init {
        entries["[Content_Types].xml"] = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Default Extension="png" ContentType="image/png"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            </Types>
        """.trimIndent().toByteArray()

        entries["_rels/.rels"] = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>
        """.trimIndent().toByteArray()
    }

    // ═══════════════════════════════════════════════════════════════════
    // Document XML
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 设置 word/document.xml 内容。
     *
     * @param bodyXml 直接插入到 <w:body> 内的 XML 片段
     */
    fun documentXml(bodyXml: String): DocxFixtureBuilder {
        entries["word/document.xml"] = wrapDocument(bodyXml)
        return this
    }

    private fun wrapDocument(bodyXml: String): ByteArray {
        return """
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
    }

    // ═══════════════════════════════════════════════════════════════════
    // Relationships
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 设置图片 relationships。
     *
     * @param relsXml 直接插入到 <Relationships> 内的 XML 片段
     */
    fun imageRels(relsXml: String): DocxFixtureBuilder {
        entries["word/_rels/document.xml.rels"] = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              $relsXml
            </Relationships>
        """.trimIndent().toByteArray()
        return this
    }

    // ═══════════════════════════════════════════════════════════════════
    // Numbering
    // ═══════════════════════════════════════════════════════════════════

    fun numberingXml(xml: String): DocxFixtureBuilder {
        entries["word/numbering.xml"] = xml.toByteArray()
        return this
    }

    // ═══════════════════════════════════════════════════════════════════
    // Media
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 添加媒体文件。
     *
     * @param path ZIP 内路径，如 "word/media/image1.png"
     * @param bytes 文件内容
     */
    fun media(path: String, bytes: ByteArray): DocxFixtureBuilder {
        entries[path] = bytes
        return this
    }

    // ═══════════════════════════════════════════════════════════════════
    // Build
    // ═══════════════════════════════════════════════════════════════════

    fun build(): Map<String, ByteArray> = entries.toMap()

    /**
     * 将 entries 打包为 ZIP 字节数组，模拟真实 DOCX 文件。
     */
    fun buildZip(): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((path, bytes) in entries) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Convenience functions for common XML snippets
// ═══════════════════════════════════════════════════════════════════════════

/** 简单段落：<w:p><w:r><w:t>text</w:t></w:r></w:p> */
fun simpleParagraph(text: String): String =
    """<w:p><w:r><w:t xml:space="preserve">$text</w:t></w:r></w:p>"""

/** 带编号的段落（使用显式开闭标签，避免 self-closing tag parser 兼容性问题）。 */
fun numberedParagraph(text: String, numId: Int, level: Int = 0): String =
    """<w:p><w:pPr><w:numPr><w:ilvl w:val="$level"></w:ilvl><w:numId w:val="$numId"></w:numId></w:numPr></w:pPr><w:r><w:t xml:space="preserve">$text</w:t></w:r></w:p>"""

/** 包含多段 text run 的段落。 */
fun paragraphWithRuns(vararg texts: String): String {
    val runs = texts.joinToString("") { """<w:r><w:t xml:space="preserve">$it</w:t></w:r>""" }
    return "<w:p>$runs</w:p>"
}

/** 包含图片的段落。 */
fun paragraphWithImage(relId: String, textBefore: String = "", textAfter: String = ""): String {
    val sb = StringBuilder("<w:p>")
    if (textBefore.isNotEmpty()) sb.append("""<w:r><w:t xml:space="preserve">$textBefore</w:t></w:r>""")
    sb.append("""
        <w:r>
          <w:drawing>
            <wp:inline>
              <a:graphic>
                <a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
                  <pic:pic>
                    <pic:blipFill>
                      <a:blip r:embed="$relId"/>
                    </pic:blipFill>
                  </pic:pic>
                </a:graphicData>
              </a:graphic>
            </wp:inline>
          </w:drawing>
        </w:r>
    """.trimIndent())
    if (textAfter.isNotEmpty()) sb.append("""<w:r><w:t xml:space="preserve">$textAfter</w:t></w:r>""")
    sb.append("</w:p>")
    return sb.toString()
}

/** 包含换行符的段落。 */
fun paragraphWithLineBreak(textBefore: String, textAfter: String): String =
    """<w:p><w:r><w:t xml:space="preserve">$textBefore</w:t></w:r><w:r><w:br/></w:r><w:r><w:t xml:space="preserve">$textAfter</w:t></w:r></w:p>"""

/** 同一段落内两张图片：Text → Image(rId1) → Text → Image(rId2) → Text */
fun paragraphWithTwoImages(relId1: String, relId2: String): String {
    return """
        <w:p>
          <w:r><w:t xml:space="preserve">比较</w:t></w:r>
          <w:r>
            <w:drawing>
              <wp:inline>
                <a:graphic>
                  <a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
                    <pic:pic>
                      <pic:blipFill>
                        <a:blip r:embed="$relId1"/>
                      </pic:blipFill>
                    </pic:pic>
                  </a:graphicData>
                </a:graphic>
              </wp:inline>
            </w:drawing>
          </w:r>
          <w:r><w:t xml:space="preserve">与</w:t></w:r>
          <w:r>
            <w:drawing>
              <wp:inline>
                <a:graphic>
                  <a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
                    <pic:pic>
                      <pic:blipFill>
                        <a:blip r:embed="$relId2"/>
                      </pic:blipFill>
                    </pic:pic>
                  </a:graphicData>
                </a:graphic>
              </wp:inline>
            </w:drawing>
          </w:r>
          <w:r><w:t xml:space="preserve">的区别</w:t></w:r>
        </w:p>
    """.trimIndent()
}

/** 2x2 简单表格。 */
fun simpleTable(cells: List<String>): String {
    require(cells.size == 4) { "simpleTable expects exactly 4 cells" }
    return """
        <w:tbl>
          <w:tr>
            <w:tc><w:p><w:r><w:t xml:space="preserve">${cells[0]}</w:t></w:r></w:p></w:tc>
            <w:tc><w:p><w:r><w:t xml:space="preserve">${cells[1]}</w:t></w:r></w:p></w:tc>
          </w:tr>
          <w:tr>
            <w:tc><w:p><w:r><w:t xml:space="preserve">${cells[2]}</w:t></w:r></w:p></w:tc>
            <w:tc><w:p><w:r><w:t xml:space="preserve">${cells[3]}</w:t></w:r></w:p></w:tc>
          </w:tr>
        </w:tbl>
    """.trimIndent()
}

/** 标准 numbering.xml：单个 decimal 编号定义。 */
fun decimalNumberingXml(numId: Int, abstractNumId: Int): String = """
    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
    <w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
      <w:abstractNum w:abstractNumId="$abstractNumId">
        <w:lvl w:ilvl="0">
          <w:numFmt w:val="decimal"/>
          <w:lvlText w:val="%1."/>
          <w:start w:val="1"/>
        </w:lvl>
      </w:abstractNum>
      <w:num w:numId="$numId">
        <w:abstractNumId w:val="$abstractNumId"/>
      </w:num>
    </w:numbering>
""".trimIndent()

/** 图片 relationship XML。 */
fun imageRelationshipXml(id: String, target: String): String =
    """<Relationship Id="$id" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="$target"/>"""

/** 简单 PNG 字节（1x1 像素，用于测试）。 */
fun minimalPngBytes(): ByteArray = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // PNG signature
    0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, // IHDR chunk
    0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
    0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53, 0xDE.toByte(),
    0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41, 0x54, // IDAT chunk
    0x08, 0xD7.toByte(), 0x63, 0x60, 0x60, 0x60, 0x00, 0x00,
    0x00, 0x04, 0x00, 0x01, 0x27.toByte(), 0x34, 0x09, 0x00.toByte(), // ...rest of IDAT
    0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(), // IEND
)
