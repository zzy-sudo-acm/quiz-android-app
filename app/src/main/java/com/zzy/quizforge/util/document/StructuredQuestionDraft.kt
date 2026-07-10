package com.zzy.quizforge.util.document

/**
 * StructuredQuestionDraft — source-traceable intermediate representation.
 *
 * Assembled from validated annotations + SourceProjection text extraction.
 * All text content comes from exact annotation ranges on SourceProjection.
 * No AI text, no rewritten content, no [图片N] markers.
 */
data class StructuredQuestionDraft(
    val segmentId: String,
    val originalQuestionNumber: Int?,
    val stemSlices: List<TextSlice>,
    val optionSlices: List<OptionSlice>,
    val answerSlices: List<TextSlice>,
    val explanationSlices: List<TextSlice>,
    val typeHint: String?,
    /** All sourceIds consumed in this draft. */
    val sourceIdsConsumed: List<String>,
    /** Image refs from all source blocks. */
    val imageRefs: List<ImageRef>,
    /** Table source refs. */
    val tableRefs: List<TableRef>,
    val warnings: List<String>,
    val representability: Representability,
) {
    /** Combined stem text in source order. */
    val stemText: String get() = stemSlices.joinToString("") { it.text }

    /** Combined answer text in source order. */
    val answerText: String get() = answerSlices.joinToString("") { it.text }

    /** Combined explanation text in source order. */
    val explanationText: String get() = explanationSlices.joinToString("") { it.text }

    /** Ordered option keys. */
    val optionKeys: List<String> get() = optionSlices.map { it.key }
}

data class TextSlice(
    val sourceId: String,
    val sourceOrder: Int,
    val text: String,
    val label: AnnotationLabel,
)

data class OptionSlice(
    val sourceId: String,
    val sourceOrder: Int,
    val key: String,
    val text: String,
    val imageRefs: List<ImageRef> = emptyList(),
)

sealed interface ImageOwner { data object Stem : ImageOwner; data class Option(val key: String) : ImageOwner; data object Unbound : ImageOwner; data object TableCell : ImageOwner }

data class ImageRef(
    val mediaId: String?,
    val relationshipId: String?,
    val sourceBlockId: String,
    val sourceOrder: Int = -1,
    val inlineIndex: Int = -1,
    val charOffset: Int = -1,
    val owner: ImageOwner = ImageOwner.Unbound,
    val resolvedLocalPath: String? = null,
)

data class TableRef(
    val sourceId: String,
    val sourceOrder: Int,
)

enum class Representability {
    /** All rich structure fits QuizQuestion model. */
    REPRESENTABLE,
    /** Some structure lost (e.g., multiple images → only one stored). */
    LOSSY,
    /** Cannot fit into current QuizQuestion at all. */
    UNSUPPORTED,
}
