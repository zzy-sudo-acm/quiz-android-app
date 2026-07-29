package com.zzy.quizforge.util.document

import com.zzy.quizforge.domain.model.QuestionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardImportPipelineTest {

    @Test
    fun `answer A without options is not invented as true false`() {
        val result = StandardFormatParser(clock = { 1 }, reportId = { "no-guess" }).parse(
            "broken.docx",
            listOf(
                source("stem", 0, "1. 缺失选项的选择题"),
                source("answer", 1, "答案：A"),
            ),
        )

        assertTrue(result.questions.isEmpty())
        assertEquals(ImportFailureReason.MISSING_OPTIONS, result.report.records.single().reasonCode)
    }

    private val parser = StandardFormatParser(
        clock = { 1_000L },
        reportId = { "standard-report" },
    )

    @Test
    fun `source ledger covers only meaningful sources and accepted status has precedence`() {
        val text = source("text", 0, "正文")
        val imageOnly = source(
            id = "image",
            order = 1,
            text = "",
            images = listOf(image("media-1", "/images/one.png")),
        )
        val unsupported = source(
            id = "unsupported",
            order = 2,
            text = "",
            type = SourceBlockType.UNSUPPORTED,
            unsupportedReason = "文本框暂不支持",
        )
        val empty = source("empty", 3, "   ")
        val ledger = SourceLedger(listOf(text, imageOnly, unsupported, empty))

        assertEquals(listOf("text", "image", "unsupported"), ledger.uncoveredIds())
        assertNull(ledger.source("empty"))

        ledger.mark(listOf("text"), SourceLedgerStatus.NON_QUESTION_CONTENT)
        ledger.mark(listOf("text"), SourceLedgerStatus.REJECTED_QUESTION)
        ledger.mark(listOf("text"), SourceLedgerStatus.ACCEPTED_QUESTION)
        ledger.mark(listOf("text"), SourceLedgerStatus.NON_QUESTION_CONTENT)
        ledger.mark(listOf("image"), SourceLedgerStatus.NON_QUESTION_CONTENT)
        ledger.mark(listOf("unsupported"), SourceLedgerStatus.UNSUPPORTED_CONTENT)

        assertEquals(SourceLedgerStatus.ACCEPTED_QUESTION, ledger.status("text"))
        assertEquals(1, ledger.counts().getValue(SourceLedgerStatus.ACCEPTED_QUESTION))
        assertEquals(1, ledger.counts().getValue(SourceLedgerStatus.NON_QUESTION_CONTENT))
        assertEquals(1, ledger.counts().getValue(SourceLedgerStatus.UNSUPPORTED_CONTENT))
        assertTrue(ledger.isComplete())
    }

    @Test
    fun `source extractor preserves automatic numbering table position and every image`() {
        val document = StructuredDocument(
            blocks = listOf(
                paragraph(
                    id = "p-question-1",
                    order = 10,
                    numbering = NumberingRef("questions", 0),
                    content = listOf(
                        TextContent("第一道自动编号题"),
                        ImageContent("png", "r-png"),
                        ImageContent("emf", "r-emf"),
                    ),
                ),
                paragraph(
                    id = "p-question-2",
                    order = 11,
                    numbering = NumberingRef("questions", 0),
                    content = listOf(TextContent("第二道自动编号题")),
                ),
                TableBlock(
                    sourceId = "table-1",
                    sourceOrder = 12,
                    rows = listOf(
                        TableRow(
                            cells = listOf(
                                TableCell(
                                    blocks = listOf(
                                        paragraph("cell-p", 13, content = listOf(TextContent("单元格内容"))),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            media = listOf(
                DocumentMedia("png", "r-png", "one.PNG", "/images/one.png", "image/png"),
                DocumentMedia("emf", "r-emf", "two.emf", "/images/two.emf", "image/x-emf"),
            ),
            numberingDefinitions = mapOf(
                "questions" to NumberingDefinition(
                    numId = "questions",
                    abstractNumId = "abstract-questions",
                    levels = mapOf(
                        0 to NumberingLevel(0, "decimal", "%1.", 7),
                    ),
                ),
            ),
            warnings = emptyList(),
        )

        val blocks = SourceBlockExtractor.extract(document)

        assertEquals(listOf("7.", "8."), blocks.take(2).map { it.numbering?.displayText })
        assertEquals(listOf("png", "emf"), blocks[0].images.map { it.mediaId })
        assertEquals(listOf(true, false), blocks[0].images.map { it.supportedForDisplay })
        assertEquals(listOf("/images/one.png", "/images/two.emf"), blocks[0].images.map { it.localPath })

        val tableCell = blocks.single { it.sourceType == SourceBlockType.TABLE_CELL }
        assertEquals("table-1:r0c0", tableCell.sourceId)
        assertEquals("单元格内容", tableCell.rawText)
        assertEquals(SourceTablePosition("table-1", 0, 0), tableCell.table)
        assertEquals(13, tableCell.sourceOrderStart)
        assertEquals(13, tableCell.sourceOrderEnd)
    }

    @Test
    fun `leading whitespace keeps raw text and inline image offsets in one coordinate system`() {
        val inline = listOf(
            TextContent("\t  1. 看图选择"),
            ImageContent("stem-image", "r-stem"),
            TextContent(" A. 甲"),
            ImageContent("option-a-image", "r-a"),
            TextContent(" B. 乙 答案：A"),
        )
        val document = StructuredDocument(
            blocks = listOf(
                paragraph("leading-paragraph", 0, content = inline),
                TableBlock(
                    sourceId = "leading-table",
                    sourceOrder = 1,
                    rows = listOf(
                        TableRow(listOf(TableCell(listOf(paragraph("leading-cell", 2, content = inline))))),
                    ),
                ),
            ),
            media = emptyList(),
            numberingDefinitions = emptyMap(),
            warnings = emptyList(),
        )

        val extracted = SourceBlockExtractor.extract(document)
        val paragraph = extracted.first { it.sourceId == "leading-paragraph" }
        val tableCell = extracted.first { it.sourceType == SourceBlockType.TABLE_CELL }

        for (source in listOf(paragraph, tableCell)) {
            assertTrue(source.rawText.startsWith("\t  "))
            assertEquals(IMAGE_OWNER_STEM, sourceImageOwner(source.rawText, source.images[0].charOffset))
            assertEquals(imageOptionOwner("A"), sourceImageOwner(source.rawText, source.images[1].charOffset))
            assertEquals(source.rawText.indexOf(" A."), source.images[0].charOffset)
            assertEquals(source.rawText.indexOf(" B."), source.images[1].charOffset)
        }
    }

    @Test
    fun `standard parser accepts single multiple and final true false question without trailing newline`() {
        val lastQuestionWithoutTrailingNewline = """
            (3) Kotlin 是一种编程语言
            题型：判断题
            答案：对
        """.trimIndent()
        assertFalse(lastQuestionWithoutTrailingNewline.endsWith('\n'))

        val result = parser.parse(
            fileName = "three-questions.docx",
            sourceBlocks = listOf(
                source(
                    "q1",
                    0,
                    """
                        1. HTTP 默认使用哪个应用层协议？
                        题型：单选题
                        A. HTTP
                        B. FTP
                        答案：A
                    """.trimIndent(),
                ),
                source(
                    "q2",
                    1,
                    """
                        第2题 以下哪些属于传输层协议？
                        题型：多选题
                        A. TCP
                        B. IP
                        C. UDP
                        答案：A、C
                    """.trimIndent(),
                ),
                source("q3", 2, lastQuestionWithoutTrailingNewline),
            ),
        )

        assertEquals(3, result.questions.size)
        assertEquals(
            listOf(QuestionType.SINGLE, QuestionType.MULTIPLE, QuestionType.TRUE_FALSE),
            result.questions.map { it.question.type },
        )
        assertEquals(listOf("A"), result.questions[0].question.answer)
        assertEquals(listOf("A", "C"), result.questions[1].question.answer)
        assertEquals(listOf("A"), result.questions[2].question.answer)
        assertEquals(listOf("A" to "对", "B" to "错"), result.questions[2].question.options.map { it.key to it.text })
        assertEquals(listOf(1, 2, 3), result.questions.map { it.originalQuestionNumber })

        with(result.report) {
            assertEquals(ImportMode.STANDARD, importMode)
            assertEquals(3, acceptedQuestionCount)
            assertEquals(0, rejectedQuestionCount)
            assertEquals(3, totalSourceBlocks)
            assertFalse(usedApi)
            assertEquals(0, apiRequestCount)
            assertTrue(ledgerComplete)
        }
    }

    @Test
    fun `standard parser makes malformed question a visible rejection`() {
        val result = parser.parse(
            fileName = "missing-answer.docx",
            sourceBlocks = listOf(
                source(
                    "q-bad",
                    0,
                    """
                        1. 这道题故意没有答案
                        A. 选项甲
                        B. 选项乙
                    """.trimIndent(),
                ),
            ),
        )

        assertTrue(result.questions.isEmpty())
        assertEquals(1, result.report.rejectedQuestionCount)
        assertTrue(result.report.ledgerComplete)
        val record = result.report.records.single()
        assertEquals(SourceLedgerStatus.REJECTED_QUESTION, record.status)
        assertEquals(ImportFailureReason.MISSING_ANSWER, record.reasonCode)
        assertEquals(listOf("q-bad"), record.sourceIds)
    }

    @Test
    fun `rendered Word numbering drives question and option keys`() {
        val document = StructuredDocument(
            blocks = listOf(
                paragraph(
                    "question",
                    0,
                    NumberingRef("question-list", 0),
                    listOf(TextContent("自动编号题干")),
                ),
                paragraph(
                    "option-a",
                    1,
                    NumberingRef("option-list", 0),
                    listOf(TextContent("甲选项")),
                ),
                paragraph(
                    "option-b",
                    2,
                    NumberingRef("option-list", 0),
                    listOf(TextContent("乙选项")),
                ),
                paragraph("answer", 3, content = listOf(TextContent("答案：B"))),
            ),
            media = emptyList(),
            numberingDefinitions = mapOf(
                "question-list" to NumberingDefinition(
                    "question-list",
                    "abstract-question",
                    mapOf(0 to NumberingLevel(0, "decimal", "%1.", 7)),
                ),
                "option-list" to NumberingDefinition(
                    "option-list",
                    "abstract-option",
                    mapOf(0 to NumberingLevel(0, "upperLetter", "%1.", 1)),
                ),
            ),
            warnings = emptyList(),
        )

        val sourceBlocks = SourceBlockExtractor.extract(document)
        assertEquals(listOf("7.", "A.", "B.", null), sourceBlocks.map { it.numbering?.displayText })

        val result = parser.parse("automatic-numbering.docx", sourceBlocks)

        val recognized = result.questions.single()
        assertEquals(7, recognized.originalQuestionNumber)
        assertEquals("自动编号题干", recognized.question.question)
        assertEquals(listOf("A" to "甲选项", "B" to "乙选项"), recognized.question.options.map { it.key to it.text })
        assertEquals(listOf("B"), recognized.question.answer)
        assertTrue(result.report.ledgerComplete)
    }

    @Test
    fun `standard parser preserves multiple stem and option images and never uses API`() {
        val result = parser.parse(
            fileName = "images.docx",
            sourceBlocks = listOf(
                source(
                    id = "stem",
                    order = 0,
                    text = "1. 根据两张图选择正确项",
                    images = listOf(
                        image("one", "/images/one.png"),
                        image("two", "/images/two.jpg"),
                    ),
                ),
                source(
                    id = "option-a",
                    order = 1,
                    text = "A. 甲",
                    images = listOf(
                        image("option-one", "/images/option-one.png"),
                        image("option-two", "/images/option-two.jpg"),
                    ),
                ),
                source("option-b", 2, "B. 乙"),
                source("answer", 3, "答案：A"),
            ),
        )

        val question = result.questions.single().question
        assertEquals("/images/one.png", question.imageUri)
        assertEquals(listOf("/images/one.png", "/images/two.jpg"), question.imageUris)
        assertEquals("/images/option-one.png", question.options[0].imageUri)
        assertEquals(
            listOf("/images/option-one.png", "/images/option-two.jpg"),
            question.options[0].imageUris,
        )
        assertEquals(4, result.report.imageCount)
        assertFalse(result.report.usedApi)
        assertEquals(0, result.report.apiRequestCount)
        assertTrue(result.report.records.none { it.apiAttempted })
    }

    private fun source(
        id: String,
        order: Int,
        text: String,
        type: SourceBlockType = SourceBlockType.PARAGRAPH,
        images: List<SourceImageRef> = emptyList(),
        unsupportedReason: String? = null,
    ) = ImportSourceBlock(
        sourceId = id,
        sourceOrder = order,
        sourceType = type,
        rawText = text,
        images = images,
        unsupportedReason = unsupportedReason,
    )

    private fun image(mediaId: String, path: String) = SourceImageRef(
        mediaId = mediaId,
        relationshipId = "rel-$mediaId",
        localPath = path,
        contentType = "image/${path.substringAfterLast('.')}",
        supportedForDisplay = true,
    )

    private fun paragraph(
        id: String,
        order: Int,
        numbering: NumberingRef? = null,
        content: List<InlineContent>,
    ) = ParagraphBlock(
        sourceId = id,
        sourceOrder = order,
        numbering = numbering,
        content = content,
    )
}
