package com.zzy.quizforge.util.document

import com.zzy.quizforge.domain.model.QuizQuestion
import java.io.File

/** The only two product-visible import paths. */
enum class ImportMode(val dbValue: String) {
    STANDARD("standard"),
    SMART("smart"),
}

enum class SourceBlockType(val wireValue: String) {
    PARAGRAPH("paragraph"),
    TABLE_CELL("tableCell"),
    UNSUPPORTED("unsupported"),
}

data class SourceNumbering(
    val numId: String,
    val level: Int,
    val displayText: String?,
)

data class SourceTablePosition(
    val tableSourceId: String,
    val row: Int,
    val column: Int,
)

data class SourceImageRef(
    val mediaId: String?,
    val relationshipId: String?,
    val localPath: String?,
    val contentType: String?,
    val supportedForDisplay: Boolean,
    /** Character position in the containing [ImportSourceBlock.rawText], when recoverable. */
    val charOffset: Int? = null,
)

/**
 * Stable, model-neutral projection of a DOCX source location.
 *
 * [sourceOrderStart]/[sourceOrderEnd] point back to the DocumentIR traversal range. A table
 * cell is intentionally one source block so row/column semantics are never flattened away.
 */
data class ImportSourceBlock(
    val sourceId: String,
    val sourceOrder: Int,
    val sourceType: SourceBlockType,
    val rawText: String,
    val numbering: SourceNumbering? = null,
    val table: SourceTablePosition? = null,
    val images: List<SourceImageRef> = emptyList(),
    val sourceOrderStart: Int = sourceOrder,
    val sourceOrderEnd: Int = sourceOrder,
    val unsupportedReason: String? = null,
) {
    val isNonEmpty: Boolean
        get() = rawText.isNotBlank() || images.isNotEmpty() || unsupportedReason != null
}

enum class SourceLedgerStatus {
    ACCEPTED_QUESTION,
    REJECTED_QUESTION,
    NON_QUESTION_CONTENT,
    UNSUPPORTED_CONTENT,
}

enum class CandidateQuestionStatus {
    ACCEPTED,
    REJECTED,
}

/** Stable codes persisted in reports and safe to depend on from UI/tests. */
enum class ImportFailureReason {
    MISSING_STEM,
    MISSING_OPTIONS,
    MISSING_ANSWER,
    ANSWER_NOT_IN_OPTIONS,
    MULTIPLE_QUESTIONS_MERGED,
    AUTO_NUMBERING_UNRESOLVED,
    TABLE_LAYOUT_UNSUPPORTED,
    TEXTBOX_UNSUPPORTED,
    EMBEDDED_OBJECT_UNSUPPORTED,
    IMAGE_OWNER_UNKNOWN,
    IMAGE_FORMAT_UNSUPPORTED,
    API_KEY_MISSING,
    API_REQUEST_FAILED,
    API_RESPONSE_TRUNCATED,
    API_INVALID_JSON,
    API_RETURNED_NULL,
    API_HALLUCINATED_CONTENT,
    DUPLICATE_QUESTION,
    SOURCE_NOT_COVERED,
    SOURCE_LEDGER_INCOMPLETE,
    INVALID_QUESTION_TYPE,
    DUPLICATE_OPTION,
}

data class QuestionProvenance(
    val sourceIds: List<String>,
    val questionSource: List<String>,
    val optionSources: Map<String, List<String>>,
    val answerSource: List<String>,
    val explanationSource: List<String> = emptyList(),
    val knowledgeSource: List<String> = emptyList(),
)

data class RecognizedQuestion(
    val question: QuizQuestion,
    val provenance: QuestionProvenance,
    val originalQuestionNumber: Int?,
)

data class ImportReportRecord(
    val sourceIds: List<String>,
    val originalQuestionNumber: Int?,
    val rawText: String,
    val status: SourceLedgerStatus,
    val reasonCode: ImportFailureReason? = null,
    val reasonMessage: String? = null,
    val createdQuestionIds: List<Long> = emptyList(),
    val createdQuestionIndexes: List<Int> = emptyList(),
    val apiAttempted: Boolean = false,
)

data class ImportReport(
    val reportId: String,
    val fileName: String,
    val importMode: ImportMode,
    val startedAt: Long,
    val finishedAt: Long,
    val totalSourceBlocks: Int,
    val candidateQuestionCount: Int,
    val acceptedQuestionCount: Int,
    val rejectedQuestionCount: Int,
    val nonQuestionCount: Int,
    val unsupportedCount: Int,
    val imageCount: Int,
    val tableCount: Int,
    val usedApi: Boolean,
    val apiRequestCount: Int,
    val warnings: List<String>,
    val records: List<ImportReportRecord>,
    val ledgerComplete: Boolean,
    /** In-memory retry context; persisted records still contain the source text and status. */
    val answerSectionSourceIds: List<String> = emptyList(),
) {
    init {
        require(candidateQuestionCount == acceptedQuestionCount + rejectedQuestionCount) {
            "候选题账本不平衡"
        }
    }

    val hasUncertainContent: Boolean
        get() = rejectedQuestionCount > 0 || unsupportedCount > 0 || !ledgerComplete
}

data class ImportRecognitionResult(
    val questions: List<RecognizedQuestion>,
    val report: ImportReport,
)

data class PreparedImport(
    val taskId: String,
    val fileName: String,
    val mode: ImportMode,
    val tempDir: File,
    val document: StructuredDocument,
    val sourceBlocks: List<ImportSourceBlock>,
    val standardPreflight: ImportRecognitionResult? = null,
) {
    val imageCount: Int get() = sourceBlocks.sumOf { it.images.size }
    val tableCount: Int get() = sourceBlocks.mapNotNull { it.table?.tableSourceId }.distinct().size
}
