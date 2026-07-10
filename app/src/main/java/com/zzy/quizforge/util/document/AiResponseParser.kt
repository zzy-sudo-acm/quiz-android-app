package com.zzy.quizforge.util.document

import com.google.gson.JsonParser

object AiResponseParser {
    private val TOP_ALLOW = setOf("annotations")
    private val ANN_ALLOW = setOf("sourceId", "label", "startOffset", "endOffset", "optionKey")

    data class ParseResult(val annotations: List<RawAiAnnotation>, val errors: List<String>)

    fun parse(rawJson: String): ParseResult {
        val errors = mutableListOf<String>()
        val root = runCatching { JsonParser.parseString(rawJson) }
            .getOrElse { return ParseResult(emptyList(), listOf("Invalid JSON: ${it.message}")) }

        if (!root.isJsonObject) return ParseResult(emptyList(), listOf("Root must be JSON object"))
        val rootObj = root.asJsonObject
        for (k in rootObj.keySet()) if (k !in TOP_ALLOW) errors += "Forbidden top-level key: $k"

        val arr = rootObj.get("annotations")
        if (arr == null || !arr.isJsonArray) return ParseResult(emptyList(), errors + "Missing/not-array 'annotations'")

        val raw = mutableListOf<RawAiAnnotation>()
        for ((i, e) in arr.asJsonArray.withIndex()) {
            if (!e.isJsonObject) { errors += "annotations[$i] not object"; continue }
            val o = e.asJsonObject
            for (k in o.keySet()) if (k !in ANN_ALLOW) errors += "annotations[$i]: forbidden key '$k'"

            val sid = o.get("sourceId"); val lbl = o.get("label")
            val so = o.get("startOffset"); val eo = o.get("endOffset"); val ok = o.get("optionKey")

            if (sid == null || !sid.isJsonPrimitive || !sid.asJsonPrimitive.isString) { errors += "annotations[$i]: sourceId not string"; continue }
            if (lbl == null || !lbl.isJsonPrimitive || !lbl.asJsonPrimitive.isString) { errors += "annotations[$i]: label not string"; continue }
            if (so == null || !so.isJsonPrimitive || !so.asJsonPrimitive.isNumber || so.asJsonPrimitive.asNumber.toDouble() % 1 != 0.0) { errors += "annotations[$i]: startOffset not integer"; continue }
            if (eo == null || !eo.isJsonPrimitive || !eo.asJsonPrimitive.isNumber || eo.asJsonPrimitive.asNumber.toDouble() % 1 != 0.0) { errors += "annotations[$i]: endOffset not integer"; continue }
            // optionKey: null (not present), JSON null, or string are all valid
            if (ok != null && !ok.isJsonNull && (!ok.isJsonPrimitive || !ok.asJsonPrimitive.isString)) { errors += "annotations[$i]: optionKey not string|null"; continue }

            raw += RawAiAnnotation(sid.asString, lbl.asString, so.asInt, eo.asInt, ok?.asString)
        }
        return ParseResult(raw, errors)
    }
}
