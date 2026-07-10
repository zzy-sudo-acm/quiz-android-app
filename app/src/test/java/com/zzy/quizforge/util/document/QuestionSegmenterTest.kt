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
    // 1. Two standard choice questions
    // ═══════════════════════════════════════════════════════

    @Test fun `two explicit numbered choice questions`() {
        val r = seg(j(
            q("1. 以下哪个是传输层协议？"), opt("A", "TCP"), opt("B", "UDP"), answer("B"),
            q("2. 以下哪个是网络层协议？"), opt("A", "TCP"), opt("B", "IP"), answer("B")
        ))
        assertEquals(2, r.segments.size)
        assertEquals(0, r.unassignedSourceIds.size)
    }

    // ═══════════════════════════════════════════════════════
    // 2. Three consecutive numbered questions
    // ═══════════════════════════════════════════════════════

    @Test fun `three consecutive numbered questions`() {
        val r = seg(j(
            q("1. Q1"), opt("A", "x1"), answer("A"),
            q("2. Q2"), opt("A", "x2"), answer("A"),
            q("3. Q3"), opt("A", "x3"), answer("A")
        ))
        assertEquals(3, r.segments.size)
        assertEquals(0, r.segments[0].startSourceOrder)
        assertEquals(3, r.segments[1].startSourceOrder)
        assertEquals(6, r.segments[2].startSourceOrder)
    }

    // ═══════════════════════════════════════════════════════
    // 3. Word numbering questions
    // ═══════════════════════════════════════════════════════

    @Test fun `word numbering questions`() {
        val b = DocxFixtureBuilder()
            .numberingXml(decimalNumberingXml(numId = 1, abstractNumId = 0))
            .documentXml(j(
                numberedParagraph("Word Q1", numId = 1), opt("A", "x"), answer("A"),
                numberedParagraph("Word Q2", numId = 1), opt("A", "y"), answer("A")
            ))
        val doc = reader.read(b.build())
        val r = QuestionSegmenter.segment(doc)
        assertEquals(2, r.segments.size)
    }

    // ═══════════════════════════════════════════════════════
    // 4. Numbering format variants
    // ═══════════════════════════════════════════════════════

    @Test fun `1 dot numbering`() {
        assertEquals(1, seg(j(q("1. Q"), opt("A", "x"), answer("A"))).segments.size)
    }
    @Test fun `1 dun comma numbering`() {
        assertEquals(1, seg(j(q("1、Q"), opt("A", "x"), answer("A"))).segments.size)
    }
    @Test fun `parentheses numbering`() {
        assertEquals(1, seg(j(q("（1）Q"), opt("A", "x"), answer("A"))).segments.size)
    }
    @Test fun `1 paren numbering`() {
        assertEquals(1, seg(j(q("1）Q"), opt("A", "x"), answer("A"))).segments.size)
    }

    // ═══════════════════════════════════════════════════════
    // 5. Stem + A/B/C/D + answer = 1 segment
    // ═══════════════════════════════════════════════════════

    @Test fun `stem options answer belong to same segment`() {
        val r = seg(j(
            q("1. 题干"), opt("A", "A"), opt("B", "B"), opt("C", "C"), opt("D", "D"), answer("C")
        ))
        assertEquals(1, r.segments.size)
        assertEquals(6, r.segments[0].sourceIds.size)
        assertTrue(r.segments[0].signals.any { it is SegmentSignal.OptionMarker })
        assertTrue(r.segments[0].signals.any { it is SegmentSignal.AnswerMarker })
    }

    // ═══════════════════════════════════════════════════════
    // 6. Inline multi-option
    // ═══════════════════════════════════════════════════════

    @Test fun `same paragraph inline multi options`() {
        val r = seg(j(q("1. 题干"), plain("A. optA B. optB C. optC D. optD"), answer("D")))
        assertEquals(1, r.segments.size)
        assertEquals(3, r.segments[0].sourceIds.size)
    }

    // ═══════════════════════════════════════════════════════
    // 7. Option marker does NOT open new question
    // ═══════════════════════════════════════════════════════

    @Test fun `option marker alone does not open new question`() {
        val r = seg(j(q("1. Q1"), opt("A", "x"), answer("A"), opt("B", "orphan")))
        assertEquals(1, r.segments.size)
    }

    // ═══════════════════════════════════════════════════════
    // 8. Answer belongs to preceding question
    // ═══════════════════════════════════════════════════════

    @Test fun `answer belongs to preceding question`() {
        val r = seg(j(q("1. Q1"), opt("A", "x"), opt("B", "y"), answer("A"), q("2. Q2")))
        assertEquals(2, r.segments.size)
        assertTrue(r.segments[0].signals.any { it is SegmentSignal.AnswerMarker })
    }

    // ═══════════════════════════════════════════════════════
    // 9. Explanation belongs to preceding question
    // ═══════════════════════════════════════════════════════

    @Test fun `explanation belongs to preceding question`() {
        val r = seg(j(q("1. Q1"), opt("A", "x"), answer("A"), explain("xxx"), q("2. Q2")))
        assertEquals(2, r.segments.size)
        assertTrue(r.segments[0].signals.any { it is SegmentSignal.ExplanationMarker })
    }

    // ═══════════════════════════════════════════════════════
    // 10. Table belongs to current question
    // ═══════════════════════════════════════════════════════

    @Test fun `table belongs to current question`() {
        val b = DocxFixtureBuilder().documentXml(j(
            q("1. 查看下表"), simpleTable(listOf("A", "TCP", "B", "UDP")), answer("B")
        ))
        val r = QuestionSegmenter.segment(reader.read(b.build()))
        assertEquals(1, r.segments.size)
        assertTrue(r.segments[0].sourceIds.size >= 3)
    }

    // ═══════════════════════════════════════════════════════
    // 11. Table cell paragraph not independent
    // ═══════════════════════════════════════════════════════

    @Test fun `table cell paragraph not independent question`() {
        val b = DocxFixtureBuilder().documentXml(j(
            q("1. Q1"), opt("A", "x"), answer("A"),
            simpleTable(listOf("A", "B", "C", "D")),
            q("2. Q2"), opt("A", "y"), answer("A")
        ))
        val doc = reader.read(b.build())
        val r = QuestionSegmenter.segment(doc)
        assertEquals(2, r.segments.size)
        // Table's cell paragraphs should NOT appear as independent segments
        assertTrue(r.segments[0].sourceIds.size >= 3)
    }

    // ═══════════════════════════════════════════════════════
    // 12. Image paragraph retained in segment
    // ═══════════════════════════════════════════════════════

    @Test fun `image paragraph retained in segment`() {
        val b = DocxFixtureBuilder()
            .imageRels(imageRelationshipXml("rId1", "media/img1.png"))
            .media("word/media/img1.png", minimalPngBytes())
            .documentXml(j(
                q("1. 题干"), paragraphWithImage("rId1", "图前", "图后"), opt("A", "x"), answer("A")
            ))
        val r = QuestionSegmenter.segment(reader.read(b.build()))
        assertEquals(1, r.segments.size)
        assertTrue(r.segments[0].sourceIds.size >= 4)
    }

    // ═══════════════════════════════════════════════════════
    // 13. Unresolved ImageContent does not affect segmentation
    // ═══════════════════════════════════════════════════════

    @Test fun `unresolved ImageContent does not affect segmentation`() {
        val b = DocxFixtureBuilder()
            .imageRels(imageRelationshipXml("rId7", "media/missing.png"))
            .documentXml(j(
                q("1. 题干"), paragraphWithImage("rId7", "图前", "图后"), opt("A", "x"), answer("A")
            ))
        val r = QuestionSegmenter.segment(reader.read(b.build()))
        assertEquals(1, r.segments.size)
    }

    // ═══════════════════════════════════════════════════════
    // 14. No numbering but full option/answer → question
    // ═══════════════════════════════════════════════════════

    @Test fun `no explicit numbering but has options and answer`() {
        val r = seg(j(q("以下哪个正确？"), opt("A", "x"), opt("B", "y"), answer("A")))
        assertEquals(1, r.segments.size)
    }

    // ═══════════════════════════════════════════════════════
    // 15. Title → unassigned
    // ═══════════════════════════════════════════════════════

    @Test fun `document title goes to unassigned not silently lost`() {
        val r = seg(j(plain("Title"), q("1. Q1"), opt("A", "x"), answer("A")))
        assertEquals(1, r.unassignedSourceIds.size)
        assertEquals(1, r.segments.size)
    }

    // ═══════════════════════════════════════════════════════
    // 16. Plain text between questions absorbed
    // ═══════════════════════════════════════════════════════

    @Test fun `plain explanatory text between questions absorbed`() {
        val r = seg(j(
            q("1. Q1"), opt("A", "x"), answer("A"),
            plain("下面是重点"),
            q("2. Q2"), opt("A", "y"), answer("A")
        ))
        assertEquals(2, r.segments.size)
    }

    // ═══════════════════════════════════════════════════════
    // 17. Source nodes not double-consumed
    // ═══════════════════════════════════════════════════════

    @Test fun `source nodes not double consumed`() {
        val r = seg(j(
            q("1. Q1"), opt("A", "x"), answer("A"),
            q("2. Q2"), opt("A", "y"), answer("A")
        ))
        val all = r.segments.flatMap { it.sourceIds }
        assertEquals(all.size, all.toSet().size)
        assertTrue(r.segments[0].sourceIds.toSet().intersect(r.segments[1].sourceIds.toSet()).isEmpty())
    }

    // ═══════════════════════════════════════════════════════
    // 18. sourceOrders monotonic within segment
    // ═══════════════════════════════════════════════════════

    @Test fun `segment sourceOrders strictly increasing`() {
        val r = seg(j(
            q("1. Q1"), opt("A", "x"), answer("A"),
            q("2. Q2"), opt("A", "y"), answer("A")
        ))
        for (seg in r.segments) {
            for (i in 1 until seg.sourceOrders.size) {
                assertTrue("${seg.sourceOrders}", seg.sourceOrders[i] > seg.sourceOrders[i - 1])
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // 19. originalQuestionNumber separate from sourceId/sourceOrder
    // ═══════════════════════════════════════════════════════

    @Test fun `originalQuestionNumber independent of sourceId and sourceOrder`() {
        val r = seg(j(
            q("3. Q3"), opt("A", "x"), answer("A"),
            q("5. Q5"), opt("A", "y"), answer("A")
        ))
        assertEquals(2, r.segments.size)
        assertEquals(3, r.segments[0].originalQuestionNumber)
        assertEquals(0, r.segments[0].startSourceOrder)
        assertEquals(5, r.segments[1].originalQuestionNumber)
    }

    // ═══════════════════════════════════════════════════════
    // 20. Mixed fixture: numbered + image + table + answer + next
    // ═══════════════════════════════════════════════════════

    @Test fun `mixed fixture numbered image table answer next question`() {
        val b = DocxFixtureBuilder()
            .numberingXml(decimalNumberingXml(numId = 1, abstractNumId = 0))
            .imageRels(imageRelationshipXml("rId1", "media/img1.png"))
            .media("word/media/img1.png", minimalPngBytes())
            .documentXml(j(
                numberedParagraph("编号题", numId = 1),
                paragraphWithImage("rId1", "图前", "图后"),
                simpleTable(listOf("A", "TCP", "B", "UDP")),
                answer("B"),
                q("2. 第二题"), opt("A", "x"), answer("A")
            ))
        val doc = reader.read(b.build())
        val r = QuestionSegmenter.segment(doc)
        assertEquals(2, r.segments.size)
        val q0 = r.segments[0]
        assertTrue(q0.sourceIds.size >= 4)
        assertTrue(q0.signals.any { it is SegmentSignal.QuestionStart })
        assertTrue(q0.signals.any { it is SegmentSignal.AnswerMarker })
        assertEquals(2, r.segments[1].originalQuestionNumber)
        val all = r.segments.flatMap { it.sourceIds }
        assertEquals(all.size, all.toSet().size)
    }

    // ═══════════════════════════════════════════════════════
    // 21-22. Debug dump
    // ═══════════════════════════════════════════════════════

    @Test fun `debug dump contains segment info`() {
        val r = seg(j(q("1. Q1"), opt("A", "x"), answer("A")))
        val json = QuestionSegmentDebugDump.toJson(r)
        assertTrue(json.contains("segmentId"))
        assertTrue(json.contains("sourceIds"))
        assertTrue(json.contains("QuestionStart"))
        assertTrue(json.contains("OptionMarker"))
        assertTrue(json.contains("AnswerMarker"))
    }

    @Test fun `debug dump summary`() {
        val r = seg(j(q("1. Q1"), opt("A", "x"), answer("A")))
        val s = QuestionSegmentDebugDump.summary(r)
        assertTrue(s.contains("1 segments"))
    }
}
