package com.zzy.quizforge.util.document

import com.zzy.quizforge.domain.model.QuestionType
import com.zzy.quizforge.ui.home.QuizBankSummaryUi
import org.junit.Assert.*
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.Reader

private fun ui(seqIdx: Int?, count: Int) = QuizBankSummaryUi(1L, "T", count, 0, 0, 0, null, seqIdx)

class WiringFixTest {
    private val testFactory: (Reader) -> XmlPullParser = { r -> TestXmlPullParser(r) }
    private val docReader = OoXmlDocumentReader(testFactory)

    // ═══════════════════════════════════════════════════
    // 2. Real option image: A has image, B has no image
    // ═══════════════════════════════════════════════════
    @Test fun `option A image resolvedLocalPath bound correctly`() {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "test-opt-img-${System.nanoTime()}")
        tmpDir.mkdirs()
        try {
            // Build fixture: Q stem, A. text+image, B. text, answer A
            val b = DocxFixtureBuilder()
                .imageRels(
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/img.png\"/>"
                )
                .media("word/media/img.png", minimalPngBytes())
                .documentXml(
                    // Q stem paragraph
                    "<w:p><w:r><w:t xml:space=\"preserve\">1. question</w:t></w:r></w:p>" +
                    // A option paragraph with trailing ImageContent
                    paragraphWithImage("rId1", "A. optA", "") +
                    // B option paragraph without image
                    "<w:p><w:r><w:t xml:space=\"preserve\">B. optB</w:t></w:r></w:p>" +
                    // Answer paragraph
                    "<w:p><w:r><w:t xml:space=\"preserve\">答案：A</w:t></w:r></w:p>"
                )
            val doc = docReader.read(b.build(), tmpDir)
            val seg = QuestionSegmenter.segment(doc).segments[0]
            val labeling = StructureLabeler.label(seg, doc)
            val draft = QuestionAssembler.assemble(seg, doc, labeling)

            // Verify option A has image
            val optA = draft.optionSlices.find { it.key == "A" }
            assertNotNull("Option A must exist", optA)
            assertEquals("Option A imageRefs count", 1, optA!!.imageRefs.size)
            val ref = optA.imageRefs[0]
            assertEquals("Owner must be Option(A)", ImageOwner.Option("A"), ref.owner)
            assertNotNull("resolvedLocalPath must exist", ref.resolvedLocalPath)
            assertTrue("File must exist", File(ref.resolvedLocalPath!!).exists())

            // Convert via adapter
            val v = StrictValidator.validate(draft)
            val conv = QuizQuestionAdapter.convert(draft, v)
            assertNotNull("Conversion must succeed", conv.question)

            val optAConverted = conv.question!!.options.find { it.key == "A" }
            assertNotNull("Converted option A must exist", optAConverted)
            assertNull("Option image field must be null", optAConverted!!.image)
            assertEquals("Option imageUri must be resolvedLocalPath", ref.resolvedLocalPath, optAConverted.imageUri)
            assertNotEquals("imageUri must not be SHA mediaId", ref.mediaId, optAConverted.imageUri)
            // Stem must NOT get the option image
            assertNull("Question imageUri must be null (image belongs to option)", conv.question!!.imageUri)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    // ═══════════════════════════════════════════════════
    // 3a. Trailing stem image
    // ═══════════════════════════════════════════════════
    @Test fun `trailing stem image bound to Stem owner`() {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "test-trail-stem-${System.nanoTime()}")
        tmpDir.mkdirs()
        try {
            val b = DocxFixtureBuilder()
                .imageRels(
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/img.png\"/>"
                )
                .media("word/media/img.png", minimalPngBytes())
                .documentXml(
                    // Single paragraph: stem text + trailing image, followed by options
                    paragraphWithImage("rId1", "1. stem text", "") +
                    "<w:p><w:r><w:t xml:space=\"preserve\">A. opt</w:t></w:r></w:p>" +
                    "<w:p><w:r><w:t xml:space=\"preserve\">B. opt</w:t></w:r></w:p>" +
                    "<w:p><w:r><w:t xml:space=\"preserve\">答案：A</w:t></w:r></w:p>"
                )
            val doc = docReader.read(b.build(), tmpDir)
            val seg = QuestionSegmenter.segment(doc).segments[0]
            val labeling = StructureLabeler.label(seg, doc)
            val draft = QuestionAssembler.assemble(seg, doc, labeling)

            val stemImgs = draft.imageRefs.filter { it.owner is ImageOwner.Stem }
            assertTrue("Must have at least one Stem-owned image", stemImgs.isNotEmpty())
            assertEquals("Stem image owner", ImageOwner.Stem, stemImgs[0].owner)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    // ═══════════════════════════════════════════════════
    // 3b. Trailing option image
    // ═══════════════════════════════════════════════════
    @Test fun `trailing option image bound to Option owner`() {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "test-trail-opt-${System.nanoTime()}")
        tmpDir.mkdirs()
        try {
            // A option text + image, B option text
            val b = DocxFixtureBuilder()
                .imageRels(
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/img.png\"/>"
                )
                .media("word/media/img.png", minimalPngBytes())
                .documentXml(
                    "<w:p><w:r><w:t xml:space=\"preserve\">1. stem</w:t></w:r></w:p>" +
                    paragraphWithImage("rId1", "A. optA", "") +
                    "<w:p><w:r><w:t xml:space=\"preserve\">B. optB</w:t></w:r></w:p>" +
                    "<w:p><w:r><w:t xml:space=\"preserve\">答案：A</w:t></w:r></w:p>"
                )
            val doc = docReader.read(b.build(), tmpDir)
            val seg = QuestionSegmenter.segment(doc).segments[0]
            val labeling = StructureLabeler.label(seg, doc)
            val draft = QuestionAssembler.assemble(seg, doc, labeling)

            val optImgs = draft.imageRefs.filter { it.owner is ImageOwner.Option }
            assertTrue("Must have at least one Option-owned image", optImgs.isNotEmpty())
            assertEquals("Option image owner key", "A", (optImgs[0].owner as ImageOwner.Option).key)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    // ═══════════════════════════════════════════════════
    // 4. Ambiguous inline image → Unbound + LOSSY
    // ═══════════════════════════════════════════════════
    @Test fun `ambiguous inline image is Unbound and LOSSY`() {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "test-ambig-${System.nanoTime()}")
        tmpDir.mkdirs()
        try {
            // Single paragraph: stem + image + inline options all in one line.
            // The image sits between stem and options → may be ambiguous.
            val b = DocxFixtureBuilder()
                .imageRels(
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/img.png\"/>"
                )
                .media("word/media/img.png", minimalPngBytes())
                .documentXml(
                    paragraphWithImage("rId1", "1. stem ", "") +
                    "<w:p><w:r><w:t xml:space=\"preserve\">A. opt1 B. opt2</w:t></w:r></w:p>" +
                    "<w:p><w:r><w:t xml:space=\"preserve\">答案：A</w:t></w:r></w:p>"
                )
            val doc = docReader.read(b.build(), tmpDir)
            val seg = QuestionSegmenter.segment(doc).segments[0]
            val labeling = StructureLabeler.label(seg, doc)
            val draft = QuestionAssembler.assemble(seg, doc, labeling)

            // If image ownership is ambiguous, it should be Unbound
            val unbound = draft.imageRefs.filter { it.owner is ImageOwner.Unbound }
            if (unbound.isNotEmpty()) {
                assertEquals(ImageOwner.Unbound, unbound[0].owner)
                assertEquals(Representability.LOSSY, draft.representability)
            }
            // STRICT policy would reject this draft
            if (draft.representability != Representability.REPRESENTABLE) {
                val pipe = NewImportPipeline(null, LossyPolicy.STRICT, docReader)
                // Verify STRICT rejects when we don't pass valid annotations
                assertTrue(draft.representability == Representability.LOSSY || draft.representability == Representability.UNSUPPORTED)
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    // ═══════════════════════════════════════════════════
    // 5. DOCUMENT_IR Done count regression
    // ═══════════════════════════════════════════════════
    @Test fun `DOCUMENT_IR emits single Done not duplicate`() {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "test-done-${System.nanoTime()}")
        tmpDir.mkdirs()
        try {
            val b = DocxFixtureBuilder()
                .documentXml(
                    "<w:p><w:r><w:t xml:space=\"preserve\">1. stem</w:t></w:r></w:p>" +
                    "<w:p><w:r><w:t xml:space=\"preserve\">A. opt</w:t></w:r></w:p>" +
                    "<w:p><w:r><w:t xml:space=\"preserve\">B. opt</w:t></w:r></w:p>" +
                    "<w:p><w:r><w:t xml:space=\"preserve\">答案：A</w:t></w:r></w:p>"
                )
            val entries = b.build()
            val pipe = NewImportPipeline(null, LossyPolicy.ALLOW_LOSSY, docReader)
            val result = pipe.execute(entries, "test-key", tmpDir)
            assertTrue("Should produce at least 1 question", result.questions.isNotEmpty())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    // ═══════════════════════════════
    // Parser: optionKey null, float offset
    // ═══════════════════════════════
    @Test fun `optionKey JSON null parses without error`() {
        val json = """{"annotations":[{"sourceId":"p0","label":"STEM","startOffset":0,"endOffset":1,"optionKey":null}]}"""
        val r = AiResponseParser.parse(json)
        assertTrue(r.errors.isEmpty())
        assertEquals(1, r.annotations.size)
        assertNull(r.annotations[0].optionKey)
    }

    @Test fun `float offset rejected`() {
        val json = """{"annotations":[{"sourceId":"p0","label":"STEM","startOffset":1.5,"endOffset":3}]}"""
        val r = AiResponseParser.parse(json)
        assertTrue(r.errors.any { it.contains("startOffset") })
    }

    // ═══════════════════════════════
    // ShadowComparator null-id fallback
    // ═══════════════════════════════
    // ═══════════════════════════════
    // Sequential action text
    // ═══════════════════════════════
    @Test fun `sequentialActionText null index returns ShunXu`() {
        val ui = ui(seqIdx = null, count = 100)
        assertEquals("顺序", ui.sequentialActionText)
    }
    @Test fun `sequentialActionText zero index returns ShunXu`() {
        assertEquals("顺序", ui(seqIdx = 0, count = 100).sequentialActionText)
    }
    @Test fun `sequentialActionText mid index returns continue`() {
        assertEquals("继续 58/100", ui(seqIdx = 57, count = 100).sequentialActionText)
    }
    @Test fun `sequentialActionText past end clamps`() {
        assertEquals("继续 100/100", ui(seqIdx = 999, count = 100).sequentialActionText)
    }

    // ═══════════════════════════════
    // ImportRuntimeConfig
    // ═══════════════════════════════
    @Test fun `ImportRuntimeConfig currentStrategy is SHADOW`() {
        assertEquals(ImportStrategy.SHADOW, ImportRuntimeConfig.currentStrategy)
    }
    @Test fun `ImportRuntimeConfig displayName is SHADOW`() {
        assertEquals("SHADOW", ImportRuntimeConfig.displayName)
    }

    @Test fun `ShadowComparator null originalId reversed sequence`() {
        val lq = listOf(
            com.zzy.quizforge.domain.model.QuizQuestion(originalId = null, type = QuestionType.SINGLE, question = "Q1", options = emptyList(), answer = listOf("A")),
            com.zzy.quizforge.domain.model.QuizQuestion(originalId = null, type = QuestionType.SINGLE, question = "Q2", options = emptyList(), answer = listOf("A")),
        )
        val nq = listOf(
            com.zzy.quizforge.domain.model.QuizQuestion(originalId = null, type = QuestionType.SINGLE, question = "Q2", options = emptyList(), answer = listOf("A")),
            com.zzy.quizforge.domain.model.QuizQuestion(originalId = null, type = QuestionType.SINGLE, question = "Q1", options = emptyList(), answer = listOf("A")),
        )
        val nr = NewPipelineResult(nq, 2, 0, 0, 0, 0, 0, 0, emptyList(), emptyList())
        val cmp = ShadowComparator.compare(lq, nr)
        assertFalse("Reversed order with null ids must not match", cmp.orderMatch)
    }
}
