package com.zzy.quizforge.util.document

import java.util.Locale

/** Converts DocumentIR to the shared source layer used by both product import modes. */
object SourceBlockExtractor {
    fun extract(document: StructuredDocument): List<ImportSourceBlock> {
        val mediaById = document.media.associateBy { it.mediaId }
        val numbering = NumberingRenderer(document.numberingDefinitions)
        val result = mutableListOf<ImportSourceBlock>()
        var outputOrder = 0

        for (block in document.blocks) {
            when (block) {
                is ParagraphBlock -> {
                    result += paragraphSource(
                        block = block,
                        outputOrder = outputOrder++,
                        mediaById = mediaById,
                        numbering = numbering,
                    )
                }

                is TableBlock -> {
                    if (block.rows.isEmpty()) {
                        result += ImportSourceBlock(
                            sourceId = block.sourceId,
                            sourceOrder = outputOrder++,
                            sourceType = SourceBlockType.UNSUPPORTED,
                            rawText = "",
                            sourceOrderStart = block.sourceOrder,
                            sourceOrderEnd = block.sourceOrder,
                            unsupportedReason = "空表格或无法读取的表格结构",
                        )
                    } else {
                        block.rows.forEachIndexed { rowIndex, row ->
                            row.cells.forEachIndexed { columnIndex, cell ->
                                val nested = collectParagraphs(cell.blocks)
                                val projections = nested.map(SourceProjection::from)
                                var projectionOffset = 0
                                val imageRefs = projections.flatMap { projection ->
                                    val refs = projection.imageRefs().map { image ->
                                        imageRef(
                                            image.imageMediaId,
                                            image.imageRelationshipId,
                                            mediaById,
                                            projectionOffset + image.charStart,
                                        )
                                    }
                                    projectionOffset += projection.text.length + 1
                                    refs
                                }
                                val orders = nested.map { it.sourceOrder }
                                val firstNumbered = nested.firstOrNull { it.numbering != null }
                                result += ImportSourceBlock(
                                    sourceId = "${block.sourceId}:r${rowIndex}c${columnIndex}",
                                    sourceOrder = outputOrder++,
                                    sourceType = SourceBlockType.TABLE_CELL,
                                    // Keep the exact projection so image offsets and rawText share
                                    // one coordinate system, including leading tabs/spaces.
                                    rawText = projections.joinToString("\n") { it.text },
                                    numbering = firstNumbered?.numbering?.let { ref ->
                                        numbering.next(ref)
                                    },
                                    table = SourceTablePosition(block.sourceId, rowIndex, columnIndex),
                                    images = imageRefs,
                                    sourceOrderStart = orders.minOrNull() ?: block.sourceOrder,
                                    sourceOrderEnd = orders.maxOrNull() ?: block.sourceOrder,
                                )
                            }
                        }
                    }
                }
            }
        }
        document.warnings.filter { it.message.startsWith("[UNSUPPORTED]") }.forEachIndexed { index, warning ->
            val payload = warning.message.removePrefix("[UNSUPPORTED]").trim()
            val reason = payload.substringBefore("\n[RAW]").trim()
            val rawText = payload.substringAfter("\n[RAW]", "").trim()
            result += ImportSourceBlock(
                sourceId = "unsupported$index",
                sourceOrder = outputOrder++,
                sourceType = SourceBlockType.UNSUPPORTED,
                rawText = rawText,
                unsupportedReason = reason,
            )
        }
        return result
    }

    private fun paragraphSource(
        block: ParagraphBlock,
        outputOrder: Int,
        mediaById: Map<String, DocumentMedia>,
        numbering: NumberingRenderer,
    ): ImportSourceBlock {
        val projection = SourceProjection.from(block)
        return ImportSourceBlock(
            sourceId = block.sourceId,
            sourceOrder = outputOrder,
            sourceType = SourceBlockType.PARAGRAPH,
            // Parsers may trim individual semantic lines, but source offsets must stay relative
            // to the unmodified projection used to calculate image positions.
            rawText = projection.text,
            numbering = block.numbering?.let(numbering::next),
            images = projection.imageRefs().map { image ->
                imageRef(image.imageMediaId, image.imageRelationshipId, mediaById, image.charStart)
            },
            sourceOrderStart = block.sourceOrder,
            sourceOrderEnd = block.sourceOrder,
        )
    }

    private fun collectParagraphs(blocks: List<DocumentBlock>): List<ParagraphBlock> = buildList {
        for (block in blocks) {
            when (block) {
                is ParagraphBlock -> add(block)
                is TableBlock -> block.rows.forEach { row ->
                    row.cells.forEach { cell -> addAll(collectParagraphs(cell.blocks)) }
                }
            }
        }
    }

    private fun imageRef(
        mediaId: String?,
        relationshipId: String?,
        mediaById: Map<String, DocumentMedia>,
        charOffset: Int? = null,
    ): SourceImageRef {
        val media = mediaId?.let(mediaById::get)
        val extension = media?.fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
        val displayable = extension in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
        return SourceImageRef(
            mediaId = mediaId,
            relationshipId = relationshipId,
            localPath = media?.localPath,
            contentType = media?.contentType,
            supportedForDisplay = displayable,
            charOffset = charOffset,
        )
    }
}

private class NumberingRenderer(
    private val definitions: Map<String, NumberingDefinition>,
) {
    private val counters = mutableMapOf<String, MutableMap<Int, Int>>()

    fun next(ref: NumberingRef): SourceNumbering {
        val definition = definitions[ref.numId]
        val perLevel = counters.getOrPut(ref.numId) { mutableMapOf() }
        val levelDefinition = definition?.levels?.get(ref.level)
        val next = (perLevel[ref.level] ?: ((levelDefinition?.start ?: 1) - 1)) + 1
        perLevel[ref.level] = next
        perLevel.keys.filter { it > ref.level }.toList().forEach(perLevel::remove)

        val template = levelDefinition?.lvlText
        val display = template?.let { raw ->
            Regex("%([1-9])").replace(raw) { match ->
                val level = match.groupValues[1].toInt() - 1
                val value = perLevel[level] ?: definitions[ref.numId]?.levels?.get(level)?.start ?: 1
                format(value, definition?.levels?.get(level)?.numFmt)
            }
        }
        return SourceNumbering(ref.numId, ref.level, display)
    }

    private fun format(value: Int, format: String?): String = when (format) {
        "upperLetter" -> toLetters(value).uppercase(Locale.ROOT)
        "lowerLetter" -> toLetters(value).lowercase(Locale.ROOT)
        "upperRoman" -> toRoman(value).uppercase(Locale.ROOT)
        "lowerRoman" -> toRoman(value).lowercase(Locale.ROOT)
        "bullet" -> "•"
        else -> value.toString()
    }

    private fun toLetters(input: Int): String {
        var value = input.coerceAtLeast(1)
        val result = StringBuilder()
        while (value > 0) {
            value--
            result.append(('A'.code + value % 26).toChar())
            value /= 26
        }
        return result.reverse().toString()
    }

    private fun toRoman(input: Int): String {
        var value = input.coerceIn(1, 3999)
        val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        return buildString {
            for (index in values.indices) {
                while (value >= values[index]) {
                    append(symbols[index])
                    value -= values[index]
                }
            }
        }
    }
}
