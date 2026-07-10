package com.zzy.quizforge.util.document

/**
 * Source text projection — unified, deterministic text representation of Document IR.
 *
 * All annotation offsets (both deterministic and AI) must be based on this projection.
 * No component may independently derive its own text via filterIsInstance<TextContent>().joinToString().
 */
data class SourceProjection(
    val text: String,
    /** Inline content index → character offset in [text]. */
    val inlineOffsets: List<InlineOffset>,
    val sourceId: String,
    val sourceOrder: Int,
) {
    data class InlineOffset(
        val index: Int,
        val charStart: Int,
        val charEnd: Int,
        val type: String, // "text", "image", "lineBreak"
        val imageMediaId: String? = null,
        val imageRelationshipId: String? = null,
    )

    companion object {
        fun from(block: DocumentBlock): SourceProjection {
            val sourceId = block.sourceId
            val sourceOrder = block.sourceOrder
            if (block !is ParagraphBlock) {
                return SourceProjection("", emptyList(), sourceId, sourceOrder)
            }

            val sb = StringBuilder()
            val offsets = mutableListOf<InlineOffset>()

            for ((i, inline) in block.content.withIndex()) {
                val start = sb.length
                when (inline) {
                    is TextContent -> sb.append(inline.text)
                    is LineBreakContent -> sb.append('\n')
                    is ImageContent -> { /* no text placeholder */ }
                }
                val end = sb.length
                val type = when (inline) {
                    is TextContent -> "text"
                    is ImageContent -> "image"
                    is LineBreakContent -> "lineBreak"
                    else -> "other"
                }
                offsets += InlineOffset(
                    index = i,
                    charStart = start,
                    charEnd = end,
                    type = type,
                    imageMediaId = (inline as? ImageContent)?.mediaId,
                    imageRelationshipId = (inline as? ImageContent)?.relationshipId,
                )
            }

            return SourceProjection(sb.toString(), offsets, sourceId, sourceOrder)
        }
    }

    fun imageRefs(): List<InlineOffset> = inlineOffsets.filter { it.type == "image" }

    fun substring(startOffset: Int, endOffset: Int): String =
        text.substring(startOffset.coerceIn(0, text.length), endOffset.coerceIn(0, text.length))
}
