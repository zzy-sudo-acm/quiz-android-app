package com.zzy.quizforge.util.document

/**
 * ============================================================================
 * Document IR — 结构化文档中间表示
 * ============================================================================
 *
 * 这是文档结构模型，不是题目模型。
 * 禁止在此文件中出现 QuizQuestion / QuestionOption / answer / single / multiple 等语义。
 *
 * 核心区分：
 *   sourceId    — 本次解析内唯一标识，用于去重和诊断
 *   sourceOrder — 文档正文顺序，用于排序
 *   NumberingRef — Word 自动编号引用（numId + ilvl），不是用户可见题号
 *
 * 与 QuizQuestion.originalId 完全独立。
 */

// ═══════════════════════════════════════════════════════════════════════════
// 顶层文档
// ═══════════════════════════════════════════════════════════════════════════

data class StructuredDocument(
    val blocks: List<DocumentBlock>,
    val media: List<DocumentMedia>,
    val numberingDefinitions: Map<String, NumberingDefinition>,
    val warnings: List<DocumentWarning>,
)

// ═══════════════════════════════════════════════════════════════════════════
// Document Block
// ═══════════════════════════════════════════════════════════════════════════

sealed interface DocumentBlock {
    val sourceId: String
    val sourceOrder: Int
}

data class ParagraphBlock(
    override val sourceId: String,
    override val sourceOrder: Int,
    val numbering: NumberingRef?,
    val content: List<InlineContent>,
) : DocumentBlock

data class TableBlock(
    override val sourceId: String,
    override val sourceOrder: Int,
    val rows: List<TableRow>,
) : DocumentBlock

// ═══════════════════════════════════════════════════════════════════════════
// Table
// ═══════════════════════════════════════════════════════════════════════════

data class TableRow(
    val cells: List<TableCell>,
)

data class TableCell(
    val blocks: List<DocumentBlock>,
)

// ═══════════════════════════════════════════════════════════════════════════
// Inline Content
// ═══════════════════════════════════════════════════════════════════════════

sealed interface InlineContent

data class TextContent(
    val text: String,
) : InlineContent

data class ImageContent(
    val mediaId: String,
) : InlineContent

data object LineBreakContent : InlineContent

// ═══════════════════════════════════════════════════════════════════════════
// Numbering
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Word 自动编号引用（来自 w:numPr）。
 *
 * 本阶段不渲染完整编号文字，仅保留 numId 和 level。
 */
data class NumberingRef(
    val numId: String,
    val level: Int,
)

/**
 * 编号定义（来自 word/numbering.xml）。
 *
 * key = numId 字符串
 */
data class NumberingDefinition(
    val numId: String,
    val abstractNumId: String,
    val levels: Map<Int, NumberingLevel>,
)

data class NumberingLevel(
    val level: Int,
    val numFmt: String?,
    val lvlText: String?,
    val start: Int?,
)

// ═══════════════════════════════════════════════════════════════════════════
// Media
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 文档媒体文件。
 *
 * mediaId 基于内容 SHA-256，保证重复图片去重。
 */
data class DocumentMedia(
    val mediaId: String,
    val relationshipId: String?,
    val fileName: String,
    val localPath: String?,
    val contentType: String?,
)
