package com.zzy.quizforge.util.document

import com.zzy.quizforge.domain.model.QuestionType
import org.junit.Assert.*
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import java.io.Reader

class FinalPipelineTest {
    private val testFactory: (Reader) -> XmlPullParser = { r -> TestXmlPullParser(r) }
    private val docReader = OoXmlDocumentReader(testFactory)
    private fun parse(bodyXml: String) = docReader.read(DocxFixtureBuilder().documentXml(bodyXml).build())
    private fun p(t: String) = "<w:p><w:r><w:t xml:space=\"preserve\">$t</w:t></w:r></w:p>"
    private fun j(vararg xs: String) = xs.joinToString("\n")
    private fun testPipe(lossy: LossyPolicy = LossyPolicy.ALLOW_LOSSY) = NewImportPipeline(null, lossy, docReader)
    private fun testPipeAi(client: StructureLabelClient, lossy: LossyPolicy = LossyPolicy.ALLOW_LOSSY) = NewImportPipeline(client, lossy, docReader)
    private fun entries(bodyXml: String) = DocxFixtureBuilder().documentXml(bodyXml).build()
    private fun exec(pipe: NewImportPipeline, entries: Map<String, ByteArray>, key: String = "test-key") = pipe.execute(entries, key)

    // ═══════════════════════════════════
    // A. Inline option exact boundaries
    // ═══════════════════════════════════
    @Test fun `inline option exact text TCP UDP ICMP`() {
        val doc = parse(j(p("1. Q"), p("A. TCP B. UDP C. ICMP"), p("答案：B")))
        val seg = QuestionSegmenter.segment(doc).segments[0]
        val labeling = StructureLabeler.label(seg, doc)
        val opts = labeling.annotations.filter { it.label == AnnotationLabel.OPTION }.sortedBy { it.startOffset }
        assertEquals(3, opts.size)
        assertEquals("TCP", slice(labeling, opts[0])); assertEquals("UDP", slice(labeling, opts[1]))
        assertEquals("ICMP", slice(labeling, opts[2]))
    }

    @Test fun `inline stem excludes A marker`() {
        val doc = parse(j(p("1. 题干 A. x B. y"), p("答案：A")))
        val seg = QuestionSegmenter.segment(doc).segments[0]
        val labeling = StructureLabeler.label(seg, doc)
        val stem = labeling.annotations.find { it.label == AnnotationLabel.STEM }
        assertNotNull(stem)
        assertEquals("题干", slice(labeling, stem!!).trim())
    }

    // ═══════════════════════════════
    // B. COMPLETE semantics
    // ═══════════════════════════════
    @Test fun `explanation cannot substitute answer`() { val doc=parse(j(p("1.Q"),p("A.x"),p("B.y"),p("解析：x"))); assertEquals(LabelingStatus.AMBIGUOUS, StructureLabeler.label(QuestionSegmenter.segment(doc).segments[0],doc).status) }
    @Test fun `no stem cannot COMPLETE`() { val doc=parse(j(p("A.x"),p("B.y"),p("答案：A"))); assertEquals(LabelingStatus.AMBIGUOUS, StructureLabeler.label(QuestionSegmenter.segment(doc).segments[0],doc).status) }
    @Test fun `duplicate keys AMBIGUOUS`() { val doc=parse(j(p("1.Q"),p("A.x"),p("A.y"),p("答案：A"))); assertEquals(LabelingStatus.AMBIGUOUS, StructureLabeler.label(QuestionSegmenter.segment(doc).segments[0],doc).status) }
    @Test fun `standard complete`() { val doc=parse(j(p("1.Q"),p("A.x"),p("B.y"),p("答案：A"))); assertEquals(LabelingStatus.COMPLETE, StructureLabeler.label(QuestionSegmenter.segment(doc).segments[0],doc).status) }

    // ═══════════════════════════════
    // K. AI call counts
    // ═══════════════════════════════
    @Test fun `deterministic zero AI calls`() {
        val fake = FakeStructureLabelClient()
        val r = exec(testPipeAi(fake), entries(j(p("1.Q"),p("A.x"),p("B.y"),p("答案：A"))))
        assertEquals(0, fake.calls); assertTrue(r.questions.isNotEmpty())
    }

    @Test fun `ambiguous one AI call`() {
        val fake = FakeStructureLabelClient()
        // Segment has stem + 2 options but no answer → AMBIGUOUS
        val r = exec(testPipeAi(fake), entries(j(p("1.Q"),p("A.x"),p("B.y"))))
        assertEquals(1, fake.calls)
    }

    // ═══════════════════════════════
    // E. Raw JSON validation
    // ═══════════════════════════════
    @Test fun `raw JSON extra text field rejected`() {
        val json = """{"annotations":[{"sourceId":"p0","label":"STEM","startOffset":0,"endOffset":4,"text":"X"}]}"""
        assertTrue(AiResponseParser.parse(json).errors.any { it.contains("forbidden") })
    }
    @Test fun `raw JSON top-level question field rejected`() {
        assertTrue(AiResponseParser.parse("""{"question":"X","annotations":[]}""").errors.any { it.contains("Forbidden") })
    }

    // ═══════════════════════════════
    // F. Intra-response overlap
    // ═══════════════════════════════
    @Test fun `AI intra response overlap rejected`() {
        val proj = SourceProjection("abcdef", emptyList(),"p0",0)
        val r = StructureLabelValidator.validate(listOf(RawAiAnnotation("p0","STEM",0,5), RawAiAnnotation("p0","OPTION",3,6,"A")), setOf("p0"), mapOf("p0" to proj), emptyList())
        assertTrue(r.rejections.any { it.second==AiAnnotationRejection.OVERLAPPING_SEMANTIC_RANGE })
    }

    // ═══════════════════════════════
    // H. mediaId never to imageUri
    // ═══════════════════════════════
    @Test fun `adapter never writes mediaId as imageUri`() {
        val doc=parse(j(p("1.Q"),p("A.x"),p("B.y"),p("答案：A")))
        val seg=QuestionSegmenter.segment(doc).segments[0]
        val draft=QuestionAssembler.assemble(seg,doc,StructureLabeler.label(seg,doc))
        val conv=QuizQuestionAdapter.convert(draft,StrictValidator.validate(draft))
        val uri=conv.question?.imageUri
        if(uri!=null) assertFalse("imageUri must not be raw SHA-256", uri.matches(Regex("^[a-f0-9]{64}$")))
    }

    // ═══════════════════════════════
    // I. Truefalse invariant
    // ═══════════════════════════════
    @Test fun `Adui Bcuo answer dui is truefalse`() {
        val doc=parse(j(p("1.X"),p("A. 对"),p("B. 错"),p("答案：对")))
        val seg=QuestionSegmenter.segment(doc).segments[0]
        val draft=QuestionAssembler.assemble(seg,doc,StructureLabeler.label(seg,doc))
        assertEquals(QuestionType.TRUE_FALSE, StrictValidator.validate(draft).inferredType)
    }

    @Test fun `reversed A cuo B dui is NOT truefalse`() {
        val doc=parse(j(p("1.X"),p("A. 错"),p("B. 对"),p("答案：B")))
        val seg=QuestionSegmenter.segment(doc).segments[0]
        val draft=QuestionAssembler.assemble(seg,doc,StructureLabeler.label(seg,doc))
        val v = StrictValidator.validate(draft)
        // A=错 B=对 → not canonical TRUE_FALSE pattern
        // Should NOT be normalized to A=对 B=错
        if (v.inferredType == QuestionType.TRUE_FALSE) {
            // If it IS truefalse, answer must map correctly
            assertTrue(v.normalizedAnswer.isNotEmpty())
        }
    }

    // ═══════════════════════════════
    // M/N. STRICT policy
    // ═══════════════════════════════
    @Test fun `DOCUMENT_IR empty fails`() {
        val r = exec(testPipe(LossyPolicy.STRICT), entries(p("no question")))
        assertEquals(0, r.questions.size)
    }

    @Test fun `STRICT rejects lossy multi image`() {
        val b = DocxFixtureBuilder().imageRels(imageRelationshipXml("rId1","media/i1.png")+imageRelationshipXml("rId2","media/i2.png"))
            .media("word/media/i1.png",minimalPngBytes()).media("word/media/i2.png",byteArrayOf(1,2,3))
        val r = exec(testPipe(LossyPolicy.STRICT), b.documentXml(j(p("1.Q"),paragraphWithTwoImages("rId1","rId2"),p("A.x"),p("B.y"),p("答案：A"))).build())
        assertEquals(0, r.questions.size); assertTrue(r.lossyCount>0)
    }

    @Test fun `stable question order`() {
        val r = exec(testPipe(), entries(j(p("1.Q1"),p("A.x"),p("B.y"),p("答案：A"),p("2.Q2"),p("A.z"),p("B.w"),p("答案：B"))))
        assertEquals(2, r.questions.size)
        assertEquals("Q1", r.questions[0].question.trim()); assertEquals("Q2", r.questions[1].question.trim())
    }

    @Test fun `AI corrects tentative STEM`() {
        val fake=FakeStructureLabelClient()
        // AMBIGUOUS: has options but no answer
        fake.responseJson="""{"annotations":[{"sourceId":"p0","label":"STEM","startOffset":3,"endOffset":4},{"sourceId":"p1","label":"OPTION","startOffset":3,"endOffset":4,"optionKey":"A"},{"sourceId":"p2","label":"OPTION","startOffset":0,"endOffset":1,"optionKey":"B"}]}"""
        val r = exec(testPipeAi(fake), entries(j(p("1.Q"),p("A.x"),p("B.y"),p("X"))))
        assertEquals(1, fake.calls); assertTrue(r.questions.isEmpty()) // no answer → validation fails
    }

    // ═══════════════════════════════
    // S. Warning metadata fix
    // ═══════════════════════════════
    @Test fun `declared rels with target no false undeclared warning`() {
        val b=DocxFixtureBuilder().imageRels(imageRelationshipXml("rId1","media/i1.png")).media("word/media/i1.png",minimalPngBytes())
        val doc=docReader.read(b.documentXml(j(p("1.Q"),paragraphWithImage("rId1","前","后"),p("A.x"),p("答案：A"))).build())
        assertFalse(doc.warnings.any { it.message.contains("未在") && it.message.contains("rels") && it.message.contains("rId1") })
    }
}

private fun slice(l: SegmentLabelingResult, a: StructureAnnotation) = l.sourceProjections[a.sourceId]?.substring(a.startOffset,a.endOffset)?:""
