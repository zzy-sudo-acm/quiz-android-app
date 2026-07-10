package com.zzy.quizforge.util.document

import com.zzy.quizforge.domain.model.QuestionOption
import com.zzy.quizforge.domain.model.QuestionType
import com.zzy.quizforge.domain.model.QuizQuestion

/**
 * Converts a validated StructuredQuestionDraft into the existing QuizQuestion domain model.
 *
 * Only converts REPRESENTABLE or LOSSY drafts.
 * UNSUPPORTED drafts must not be converted without explicit handling.
 */
object QuizQuestionAdapter {

    data class ConversionResult(
        val question: QuizQuestion?,
        val status: ConversionStatus,
        val warnings: List<String>,
    )

    enum class ConversionStatus {
        CONVERTED,
        CONVERTED_LOSSY,
        SKIPPED_UNSUPPORTED,
        SKIPPED_UNREPRESENTABLE,
    }

    fun convert(
        draft: StructuredQuestionDraft,
        validation: StrictValidator.ValidationResult,
    ): ConversionResult {
        val warnings = mutableListOf<String>()

        if (!validation.isValid) {
            return ConversionResult(null, ConversionStatus.SKIPPED_UNSUPPORTED,
                validation.errors.map { "validation: $it" })
        }

        val type = validation.inferredType ?: return ConversionResult(
            null, ConversionStatus.SKIPPED_UNSUPPORTED, listOf("无法推断题型")
        )

        val status = when (draft.representability) {
            Representability.REPRESENTABLE -> ConversionStatus.CONVERTED
            Representability.LOSSY -> {
                warnings += "Rich structure lossy: table=${draft.tableRefs.isNotEmpty()}, " +
                    "images=${draft.imageRefs.size}"
                ConversionStatus.CONVERTED_LOSSY
            }
            Representability.UNSUPPORTED -> return ConversionResult(
                null, ConversionStatus.SKIPPED_UNREPRESENTABLE,
                listOf("Draft is UNSUPPORTED")
            )
        }

        // Build options from slices with resolved image paths
        val options = draft.optionSlices.map { slice ->
            val optImg = slice.imageRefs.firstOrNull { it.resolvedLocalPath != null }
            if (optImg == null && slice.imageRefs.any { it.mediaId != null }) {
                warnings += "Option ${slice.key} image unresolved: mediaId without localPath"
            }
            QuestionOption(
                key = slice.key,
                text = slice.text,
                image = null,
                imageUri = optImg?.resolvedLocalPath,
            )
        }

        // Normalize truefalse options
        val finalOptions = if (type == QuestionType.TRUE_FALSE) {
            listOf(QuestionOption("A", "对"), QuestionOption("B", "错"))
        } else {
            options
        }

        // Resolve stem image from resolved path, never raw mediaId
        val stemImage = draft.imageRefs.firstOrNull { it.owner == ImageOwner.Stem && it.resolvedLocalPath != null }
        if (stemImage == null && draft.imageRefs.any { it.owner == ImageOwner.Stem && it.mediaId != null }) {
            warnings += "Stem image unresolved: mediaId without localPath"
        }

        val question = QuizQuestion(
            originalId = draft.originalQuestionNumber?.takeIf { it > 0 },
            type = type,
            question = draft.stemText.trim(),
            options = finalOptions,
            answer = validation.normalizedAnswer,
            explanation = draft.explanationText.trim().takeIf { it.isNotBlank() },
            knowledge = null,
            image = null,
            imageUri = stemImage?.resolvedLocalPath,
        )

        return ConversionResult(question, status, warnings)
    }
}
