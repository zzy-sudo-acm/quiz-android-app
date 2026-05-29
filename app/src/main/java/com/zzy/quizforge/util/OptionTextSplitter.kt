package com.zzy.quizforge.util

import com.zzy.quizforge.domain.model.QuestionOption

object OptionTextSplitter {
    private val markerRegex = Regex("""([A-Ha-h])\s*[\.\．、:：\)）]\s*""")
    private val leadingMarkerRegex = Regex("""^\s*([A-Ha-h])\s*[\.\．、:：\)）]\s*([\s\S]*)$""")

    data class InlineOptions(
        val leadingText: String,
        val options: List<QuestionOption>,
    )

    fun splitInlineOptions(line: String): InlineOptions? {
        val markers = findSequentialMarkers(line)
        if (markers.size < 2) return null

        val options = markers.mapIndexedNotNull { index, marker ->
            val nextStart = markers.getOrNull(index + 1)?.start ?: line.length
            val text = line.substring(marker.end, nextStart).trim()
            if (text.isBlank()) {
                null
            } else {
                QuestionOption(key = marker.key, text = text)
            }
        }

        if (options.size < 2) return null
        return InlineOptions(
            leadingText = line.substring(0, markers.first().start).trim(),
            options = options,
        )
    }

    fun normalizeOptions(options: List<QuestionOption>): List<QuestionOption> {
        val normalized = mutableListOf<QuestionOption>()

        options.forEach { option ->
            val key = option.key.trim().uppercase().takeIf { it.matches(Regex("[A-H]")) } ?: return@forEach
            val rawText = option.text.trim()
            val source = if (hasLeadingMarker(rawText)) rawText else "$key、$rawText"
            val split = splitInlineOptions(source)

            if (split != null && split.leadingText.isBlank()) {
                normalized += split.options.map { splitOption ->
                    if (splitOption.key == key) {
                        splitOption.copy(image = option.image, imageUri = option.imageUri)
                    } else {
                        splitOption
                    }
                }
            } else {
                normalized += option.copy(
                    key = key,
                    text = stripLeadingMarker(rawText).second,
                )
            }
        }

        return normalized
            .filter { it.text.isNotBlank() }
            .distinctBy { it.key }
            .sortedBy { it.key }
    }

    fun optionFromRaw(rawText: String, fallbackKey: String): QuestionOption {
        val trimmed = rawText.trim()
        val (detectedKey, text) = stripLeadingMarker(trimmed)
        return QuestionOption(
            key = detectedKey ?: fallbackKey,
            text = text.ifBlank { trimmed },
        )
    }

    private fun hasLeadingMarker(text: String): Boolean =
        leadingMarkerRegex.matches(text.trim())

    private fun stripLeadingMarker(text: String): Pair<String?, String> {
        val match = leadingMarkerRegex.matchEntire(text.trim())
        return if (match != null) {
            match.groupValues[1].uppercase() to match.groupValues[2].trim()
        } else {
            null to text.trim()
        }
    }

    private fun findSequentialMarkers(text: String): List<Marker> {
        val allMarkers = markerRegex.findAll(text).map {
            Marker(
                key = it.groupValues[1].uppercase(),
                start = it.range.first,
                end = it.range.last + 1,
            )
        }.toList()
        if (allMarkers.isEmpty()) return emptyList()

        val firstIndex = allMarkers.indexOfFirst { marker ->
            marker.key == "A" && isLikelyFirstMarker(text, marker.start)
        }
        if (firstIndex < 0) return emptyList()

        val selected = mutableListOf(allMarkers[firstIndex])
        var expected = 'B'
        for (index in firstIndex + 1 until allMarkers.size) {
            val marker = allMarkers[index]
            val key = marker.key.first()
            if (key == expected) {
                selected += marker
                expected += 1
            } else if (key > expected) {
                break
            }
        }

        return selected
    }

    private fun isLikelyFirstMarker(text: String, start: Int): Boolean {
        if (start == 0) return true
        val previous = text[start - 1]
        return previous.isWhitespace() || previous in "。；;，,、：:（）()[]【】"
    }

    private data class Marker(
        val key: String,
        val start: Int,
        val end: Int,
    )
}
