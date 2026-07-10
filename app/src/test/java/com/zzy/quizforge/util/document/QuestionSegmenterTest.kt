package com.zzy.quizforge.util.document

import org.junit.Assert.*
import org.junit.Test
import java.io.Reader

class QuestionSegmenterTest {

    private val reader = OoXmlDocumentReader { r: Reader -> TestXmlPullParser(r) }

    private fun parse(bodyXml: String): StructuredDocument {
        val b = DocxFixtureBuilder().documentXml(bodyXml)
        return reader.read(b.build())
    }
    private fun seg(bodyXml: String): SegmentationResult =
        QuestionSegmenter.segment(parse(bodyXml))
    private fun opt(key: String, text: String) = "<w:p><w:r><w:t xml:space=\"preserve\">$key. $text</w:t></w:r></w:p>"
    private fun answer(key: String) = "<w:p><w:r><w:t xml:space=\"preserve\">答案：$key</w:t></w:r></w:p>"
    private fun explain(t: String) = "<w:p><w:r><w:t xml:space=\"preserve\">解析：$t</w:t></w:r></w:p>"
    private fun plain(t: String) = "<w:p><w:r><w:t xml:space=\"preserve\">$t</w:t></w:r></w:p>"
    private fun q(t: String) = "<w:p><w:r><w:t xml:space=\"preserve\">$t</w:t></w:r></w:p>"
    private fun j(vararg xs: String) = xs.joinToString("\n")

    // ═══════════════════════════════════════════════════════
    // 1. Standard two questions
    // ═══════════════════════════════════════════════════════

    @Test fun `two explicit numbered choice questions`() {
        val r = seg(j(q("1.Q1"), opt("A","x"), opt("B","y"), answer("B"), q("2.Q2"), opt("A","z"), answer("A")))
        assertEquals(2, r.segments.size); assertEquals(0, r.unassignedSourceIds.size)
    }

    @Test fun `three consecutive numbered questions`() {
        val r = seg(j(q("1.Q1"), opt("A","x"), answer("A"), q("2.Q2"), opt("A","y"), answer("A"), q("3.Q3"), opt("A","z"), answer("A")))
        assertEquals(3, r.segments.size); assertEquals(0, r.unassignedSourceIds.size)
    }

    // ═══════════════════════════════════════════════════════
    // 2. Word numbering (decimal level 0 only)
    // ═══════════════════════════════════════════════════════

    @Test fun `word numbering questions decimal level 0`() {
        val b = DocxFixtureBuilder()
            .numberingXml(decimalNumberingXml(numId = 1, abstractNumId = 0))
            .documentXml(j(numberedParagraph("WQ1", numId = 1), opt("A","x"), answer("A"),
                           numberedParagraph("WQ2", numId = 1), opt("A","y"), answer("A")))
        val r = QuestionSegmenter.segment(reader.read(b.build()))
        assertEquals(2, r.segments.size)
    }

    // ═══════════════════════════════════════════════════════
    // 3. Numbering format variants
    // ═══════════════════════════════════════════════════════

    @Test fun `1 dot`() = assertEquals(1, seg(j(q("1. Q"),opt("A","x"),answer("A"))).segments.size)
    @Test fun `1 dun`() = assertEquals(1, seg(j(q("1、Q"),opt("A","x"),answer("A"))).segments.size)
    @Test fun `paren`() = assertEquals(1, seg(j(q("（1）Q"),opt("A","x"),answer("A"))).segments.size)
    @Test fun `1 paren`() = assertEquals(1, seg(j(q("1）Q"),opt("A","x"),answer("A"))).segments.size)

    // ═══════════════════════════════════════════════════════
    // 4. Stem + options + answer
    // ═══════════════════════════════════════════════════════

    @Test fun `stem options answer belong to same segment`() {
        val r = seg(j(q("1. stem"), opt("A","a"), opt("B","b"), opt("C","c"), opt("D","d"), answer("C")))
        assertEquals(1, r.segments.size); assertEquals(6, r.segments[0].sourceIds.size)
    }

    @Test fun `inline multi options`() {
        val r = seg(j(q("1. stem"), plain("A. a B. b C. c D. d"), answer("D")))
        assertEquals(1, r.segments.size); assertEquals(3, r.segments[0].sourceIds.size)
    }

    // ═══════════════════════════════════════════════════════
    // 5. Option alone does NOT open question
    // ═══════════════════════════════════════════════════════

    @Test fun `option marker alone does not open new question`() {
        val r = seg(j(q("1. Q"), opt("A","x"), answer("A"), opt("B","orphan")))
        assertEquals(1, r.segments.size)
    }

    // ═══════════════════════════════════════════════════════
    // 6. Answer / Explanation
    // ═══════════════════════════════════════════════════════

    @Test fun `answer belongs to preceding question`() {
        val r = seg(j(q("1.Q"), opt("A","x"), opt("B","y"), answer("A"), q("2.Q")))
        assertEquals(2, r.segments.size)
        assertTrue(r.segments[0].signals.any { it is SegmentSignal.AnswerMarker })
    }

    @Test fun `explanation belongs to preceding question`() {
        val r = seg(j(q("1.Q"), opt("A","x"), answer("A"), explain("xxx"), q("2.Q")))
        assertEquals(2, r.segments.size)
        assertTrue(r.segments[0].signals.any { it is SegmentSignal.ExplanationMarker })
    }

    // ═══════════════════════════════════════════════════════
    // 7. Table
    // ═══════════════════════════════════════════════════════

    @Test fun `table belongs to current question`() {
        val b = DocxFixtureBuilder().documentXml(j(
            q("1. see table"), simpleTable(listOf("A","TCP","B","UDP")), answer("B")
        ))
        val r = QuestionSegmenter.segment(reader.read(b.build()))
        assertEquals(1, r.segments.size)
        assertTrue(r.segments[0].sourceIds.size >= 3)
    }

    @Test fun `table cell paragraph not independent`() {
        val b = DocxFixtureBuilder().documentXml(j(
            q("1.Q"), opt("A","x"), answer("A"),
            simpleTable(listOf("A","B","C","D")),
            q("2.Q"), opt("A","y"), answer("A")
        ))
        val r = QuestionSegmenter.segment(reader.read(b.build()))
        assertEquals(2, r.segments.size)
    }

    // ═══════════════════════════════════════════════════════
    // 8. Image
    // ═══════════════════════════════════════════════════════

    @Test fun `image paragraph retained in segment`() {
        val b = DocxFixtureBuilder()
            .imageRels(imageRelationshipXml("rId1", "media/img1.png"))
            .media("word/media/img1.png", minimalPngBytes())
            .documentXml(j(q("1.Q"), paragraphWithImage("rId1","前","后"), opt("A","x"), answer("A")))
        val r = QuestionSegmenter.segment(reader.read(b.build()))
        assertEquals(1, r.segments.size)
    }

    @Test fun `unresolved ImageContent does not affect segmentation`() {
        val b = DocxFixtureBuilder()
            .imageRels(imageRelationshipXml("rId7", "media/missing.png"))
            .documentXml(j(q("1.Q"), paragraphWithImage("rId7","前","后"), opt("A","x"), answer("A")))
        val r = QuestionSegmenter.segment(reader.read(b.build()))
        assertEquals(1, r.segments.size)
    }

    // ═══════════════════════════════════════════════════════
    // 9. No-number stem binding (FIX 1)
    // ═══════════════════════════════════════════════════════

    @Test fun `no number stem binds to implicit question`() {
        val r = seg(j(plain("以下哪个正确？"), opt("A","x"), opt("B","y"), answer("A")))
        assertEquals(1, r.segments.size)
        assertEquals(0, r.unassignedSourceIds.size)
        // Stem sourceId must be in the segment
        assertTrue(r.segments[0].sourceIds.size >= 4) // stem + A + B + answer
        // stem is first
        val stemId = r.segments[0].sourceIds.first()
        // verify segment includes both start and end orders
        assertEquals(r.segments[0].startSourceOrder, r.segments[0].sourceOrders.first())
        assertEquals(r.segments[0].endSourceOrder, r.segments[0].sourceOrders.last())
    }

    @Test fun `title then stem then options title unassigned stem in segment`() {
        val r = seg(j(plain("操作系统复习题"), plain("以下哪个正确？"), opt("A","x"), opt("B","y"), answer("A")))
        assertEquals(1, r.segments.size)
        assertEquals(1, r.unassignedSourceIds.size)
        // The unassigned should be the title, not the stem
        assertEquals(4, r.segments[0].sourceIds.size) // stem + A + B + answer
    }

    @Test fun `answer only block does not create segment`() {
        val r = seg(answer("A"))
        assertEquals(0, r.segments.size)
        assertEquals(1, r.unassignedSourceIds.size)
    }

    // ═══════════════════════════════════════════════════════
    // 10. endSourceOrder invariant (FIX 2)
    // ═══════════════════════════════════════════════════════

    @Test fun `endSourceOrder equals sourceOrders last`() {
        val b = DocxFixtureBuilder().documentXml(j(
            q("1.Q"), opt("A","x"), answer("A"),
            simpleTable(listOf("X","Y","Z","W")),
            q("2.Q"), opt("A","y"), answer("A")
        ))
        val r = QuestionSegmenter.segment(reader.read(b.build()))
        assertEquals(2, r.segments.size)
        for (s in r.segments) {
            assertEquals(s.sourceOrders.first(), s.startSourceOrder)
            assertEquals(s.sourceOrders.last(), s.endSourceOrder)
        }
        // Table internal cell paragraphs create DFS gaps between top-level orders
        val s0 = r.segments[0]
        val s1 = r.segments[1]
        assertTrue(s0.endSourceOrder < s1.startSourceOrder)
    }

    // ═══════════════════════════════════════════════════════
    // 11. NumberingRef allowlist (FIX 3)
    // ═══════════════════════════════════════════════════════

    @Test fun `upperLetter numbering NOT a question start`() {
        val combinedXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:abstractNum w:abstractNumId="10">
                <w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/><w:start w:val="1"/></w:lvl>
              </w:abstractNum>
              <w:num w:numId="1"><w:abstractNumId w:val="10"/></w:num>
              <w:abstractNum w:abstractNumId="20">
                <w:lvl w:ilvl="0"><w:numFmt w:val="upperLetter"/><w:lvlText w:val="%1."/><w:start w:val="1"/></w:lvl>
              </w:abstractNum>
              <w:num w:numId="2"><w:abstractNumId w:val="20"/></w:num>
            </w:numbering>
        """.trimIndent()
        val b = DocxFixtureBuilder()
            .numberingXml(combinedXml)
            .documentXml(j(
                numberedParagraph("Q1 stem", numId = 1),
                numberedParagraph("A. opt1", numId = 2),
                numberedParagraph("B. opt2", numId = 2),
                answer("A"),
                numberedParagraph("Q2 stem", numId = 1)
            ))
        val r = QuestionSegmenter.segment(reader.read(b.build()))
        assertEquals("upperLetter options should not split into separate questions, got ${r.segments.size}", 2, r.segments.size)
    }

    @Test fun `unresolved numbering definition does not start question`() {
        // numId=99 has no definition — must NOT be a question start
        val numXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:abstractNum w:abstractNumId="10">
                <w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/><w:start w:val="1"/></w:lvl>
              </w:abstractNum>
              <w:num w:numId="1"><w:abstractNumId w:val="10"/></w:num>
            </w:numbering>
        """.trimIndent()
        val b = DocxFixtureBuilder()
            .numberingXml(numXml)
            .documentXml(j(
                numberedParagraph("orphan numId=99", numId = 99),
                q("1. real Q"), opt("A","x"), answer("A")
            ))
        val doc = reader.read(b.build())
        val r = QuestionSegmenter.segment(doc)
        assertEquals(1, r.segments.size)
        assertTrue(r.unassignedSourceIds.isNotEmpty())
        assertTrue(r.warnings.any { it.contains("numId=99") })
    }

    @Test fun `decimal numbering level gt 0 not strong start`() {
        val b = DocxFixtureBuilder()
            .numberingXml(decimalNumberingXml(numId = 1, abstractNumId = 0))
            .documentXml(j(
                numberedParagraph("Q stem", numId = 1), // level 0 → strong start
                numberedParagraph("sub item", numId = 1, level = 1), // level 1 → NOT strong start
                opt("A","x"), answer("A")
            ))
        val r = QuestionSegmenter.segment(reader.read(b.build()))
        assertEquals(1, r.segments.size)
        assertTrue(r.segments[0].sourceIds.size >= 4) // stem + sub + A + answer
    }

    // ═══════════════════════════════════════════════════════
    // 12. Same-block QuestionStart + OptionMarker (FIX 4)
    // ═══════════════════════════════════════════════════════

    @Test fun `same paragraph question start and option markers`() {
        val r = seg(j(plain("1. stem A. x B. y"), answer("A")))
        assertEquals(1, r.segments.size)
        assertTrue(r.segments[0].signals.any { it is SegmentSignal.QuestionStart })
        assertTrue(r.segments[0].signals.any { it is SegmentSignal.OptionMarker })
    }

    // ═══════════════════════════════════════════════════════
    // 13. Title unassigned
    // ═══════════════════════════════════════════════════════

    @Test fun `document title goes to unassigned not silently lost`() {
        val r = seg(j(plain("Title"), q("1.Q"), opt("A","x"), answer("A")))
        assertEquals(1, r.unassignedSourceIds.size); assertEquals(1, r.segments.size)
    }

    // ═══════════════════════════════════════════════════════
    // 14. Inter-question text
    // ═══════════════════════════════════════════════════════

    @Test fun `plain text between questions absorbed`() {
        val r = seg(j(q("1.Q"), opt("A","x"), answer("A"), plain("note"), q("2.Q"), opt("A","y"), answer("A")))
        assertEquals(2, r.segments.size)
    }

    // ═══════════════════════════════════════════════════════
    // 15. No double consumption + monotonic
    // ═══════════════════════════════════════════════════════

    @Test fun `source nodes not double consumed`() {
        val r = seg(j(q("1.Q"), opt("A","x"), answer("A"), q("2.Q"), opt("A","y"), answer("A")))
        val all = r.segments.flatMap { it.sourceIds }
        assertEquals(all.size, all.toSet().size)
        assertTrue(r.segments[0].sourceIds.toSet().intersect(r.segments[1].sourceIds.toSet()).isEmpty())
    }

    @Test fun `segment sourceOrders strictly increasing`() {
        val r = seg(j(q("1.Q"), opt("A","x"), answer("A"), q("2.Q"), opt("A","y"), answer("A")))
        for (s in r.segments) for (i in 1 until s.sourceOrders.size)
            assertTrue(s.sourceOrders[i] > s.sourceOrders[i-1])
    }

    // ═══════════════════════════════════════════════════════
    // 16. originalQuestionNumber independence
    // ═══════════════════════════════════════════════════════

    @Test fun `originalQuestionNumber independent of sourceId and sourceOrder`() {
        val r = seg(j(q("3.Q"), opt("A","x"), answer("A"), q("5.Q"), opt("A","y"), answer("A")))
        assertEquals(2, r.segments.size)
        assertEquals(3, r.segments[0].originalQuestionNumber)
        assertEquals(0, r.segments[0].startSourceOrder)
        assertEquals(5, r.segments[1].originalQuestionNumber)
    }

    // ═══════════════════════════════════════════════════════
    // 17. Mixed fixture
    // ═══════════════════════════════════════════════════════

    @Test fun `mixed fixture numbered image table answer next question`() {
        val b = DocxFixtureBuilder()
            .numberingXml(decimalNumberingXml(numId = 1, abstractNumId = 0))
            .imageRels(imageRelationshipXml("rId1", "media/img1.png"))
            .media("word/media/img1.png", minimalPngBytes())
            .documentXml(j(
                numberedParagraph("WQ", numId = 1),
                paragraphWithImage("rId1","前","后"),
                simpleTable(listOf("A","TCP","B","UDP")),
                answer("B"),
                q("2. Q2"), opt("A","x"), answer("A")
            ))
        val r = QuestionSegmenter.segment(reader.read(b.build()))
        assertEquals(2, r.segments.size)
        val q0 = r.segments[0]
        assertTrue(q0.sourceIds.size >= 4)
        assertTrue(q0.signals.any { it is SegmentSignal.QuestionStart })
        assertEquals(2, r.segments[1].originalQuestionNumber)
        val all = r.segments.flatMap { it.sourceIds }
        assertEquals(all.size, all.toSet().size)
    }

    // ═══════════════════════════════════════════════════════
    // 18. Debug dump
    // ═══════════════════════════════════════════════════════

    @Test fun `debug dump contains segment info`() {
        val r = seg(j(q("1.Q"), opt("A","x"), answer("A")))
        val json = QuestionSegmentDebugDump.toJson(r)
        assertTrue(json.contains("segmentId"))
        assertTrue(json.contains("sourceIds"))
        assertTrue(json.contains("QuestionStart"))
        assertTrue(json.contains("OptionMarker"))
        assertTrue(json.contains("AnswerMarker"))
    }

    @Test fun `debug dump summary`() {
        val r = seg(j(q("1.Q"), opt("A","x"), answer("A")))
        assertTrue(QuestionSegmentDebugDump.summary(r).contains("1 segments"))
    }
}
