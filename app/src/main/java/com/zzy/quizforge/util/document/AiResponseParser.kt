package com.zzy.quizforge.util.document

import com.google.gson.JsonParser

/**
 * Parses and validates raw AI Structure Label JSON response.
 * Checks actual JSON keys BEFORE Gson deserialization.
 */
object AiResponseParser {

    private val TOP_LEVEL_ALLOWLIST = setOf("annotations")
    private val ANNOTATION_ALLOWLIST = setOf("sourceId", "label", "startOffset", "endOffset", "optionKey")
    private val VALID_LABELS = setOf("STEM", "OPTION", "ANSWER", "EXPLANATION", "TYPE_HINT", "OTHER")

    data class ParseResult(
        val annotations: List<RawAiAnnotation>,
        val errors: List<String>,
    )

    fun parse(rawJson: String): ParseResult {
        val errors = mutableListOf<String>()

        val root = runCatching { JsonParser.parseString(rawJson).asJsonObject }
            .getOrElse { return ParseResult(emptyList(), listOf("Invalid JSON: ${it.message}")) }

        // Check top-level keys
        for (key in root.keySet()) {
            if (key !in TOP_LEVEL_ALLOWLIST) {
                errors += "Forbidden top-level key: '$key'"
            }
        }

        val annotationsArr = root.getAsJsonArray("annotations")
            ?: return ParseResult(emptyList(), errors + "Missing or null 'annotations' array")

        if (!root.has("annotations")) {
            return ParseResult(emptyList(), errors + "Missing required 'annotations' field")
        }

        val rawAnnotations = mutableListOf<RawAiAnnotation>()
        for ((i, elem) in annotationsArr.withIndex()) {
            if (!elem.isJsonObject) { errors += "annotations[$i] not an object"; continue }
            val obj = elem.asJsonObject

            // Check annotation-level keys
            for (key in obj.keySet()) {
                if (key !in ANNOTATION_ALLOWLIST) {
                    errors += "annotations[$i]: forbidden key '$key'"
                }
            }

            val sourceId = obj.get("sourceId")?.asString ?: ""
            val label = obj.get("label")?.asString ?: ""
            val startOffset = obj.get("startOffset")?.asInt ?: -1
            val endOffset = obj.get("endOffset")?.asInt ?: -1
            val optionKey = obj.get("optionKey")?.asString

            if (label !in VALID_LABELS) errors += "annotations[$i]: invalid label '$label'"
            if (sourceId.isBlank()) errors += "annotations[$i]: missing sourceId"

            rawAnnotations += RawAiAnnotation(sourceId, label, startOffset, endOffset, optionKey)
        }

        return ParseResult(rawAnnotations, errors)
    }
}
