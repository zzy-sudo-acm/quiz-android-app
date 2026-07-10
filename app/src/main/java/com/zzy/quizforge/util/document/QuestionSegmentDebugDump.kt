package com.zzy.quizforge.util.document

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject

object QuestionSegmentDebugDump {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun toJson(result: SegmentationResult): String {
        val root = JsonObject()

        // segments
        val segsArr = JsonArray()
        for (seg in result.segments) {
            val s = JsonObject()
            s.addProperty("segmentId", seg.segmentId)
            seg.originalQuestionNumber?.let { s.addProperty("originalQuestionNumber", it) }
            s.addProperty("startSourceOrder", seg.startSourceOrder)
            s.addProperty("endSourceOrder", seg.endSourceOrder)
            s.add("sourceIds", gson.toJsonTree(seg.sourceIds))
            s.add("sourceOrders", gson.toJsonTree(seg.sourceOrders))
            // signals summary
            val sigs = JsonArray()
            for (sig in seg.signals) {
                sigs.add(signalToJson(sig))
            }
            s.add("signals", sigs)
            segsArr.add(s)
        }
        root.add("segments", segsArr)

        // unassigned
        root.add("unassignedSourceIds", gson.toJsonTree(result.unassignedSourceIds))

        // warnings
        val warns = JsonArray()
        for (w in result.warnings) warns.add(w)
        root.add("warnings", warns)

        root.addProperty("segmentCount", result.segments.size)
        root.addProperty("unassignedCount", result.unassignedSourceIds.size)
        root.addProperty("warningCount", result.warnings.size)
        root.addProperty("signalCount", result.signalCount)

        return gson.toJson(root)
    }

    fun summary(result: SegmentationResult): String {
        val parts = mutableListOf<String>()
        parts += "${result.segments.size} segments"
        if (result.unassignedSourceIds.isNotEmpty()) {
            parts += "${result.unassignedSourceIds.size} unassigned"
        }
        if (result.warnings.isNotEmpty()) {
            parts += "${result.warnings.size} warnings"
        }
        parts += "${result.signalCount} signals"
        return "SegmentationResult(${parts.joinToString(", ")})"
    }

    private fun signalToJson(sig: SegmentSignal): JsonObject {
        return when (sig) {
            is SegmentSignal.QuestionStart -> JsonObject().apply {
                addProperty("type", "QuestionStart")
                addProperty("sourceId", sig.sourceId)
                addProperty("reason", sig.reason)
            }
            is SegmentSignal.OptionMarker -> JsonObject().apply {
                addProperty("type", "OptionMarker")
                addProperty("sourceId", sig.sourceId)
                addProperty("keys", sig.keys.joinToString(","))
            }
            is SegmentSignal.AnswerMarker -> JsonObject().apply {
                addProperty("type", "AnswerMarker")
                addProperty("sourceId", sig.sourceId)
            }
            is SegmentSignal.ExplanationMarker -> JsonObject().apply {
                addProperty("type", "ExplanationMarker")
                addProperty("sourceId", sig.sourceId)
            }
            is SegmentSignal.UnassignedBlock -> JsonObject().apply {
                addProperty("type", "UnassignedBlock")
                addProperty("sourceId", sig.sourceId)
                addProperty("reason", sig.reason)
            }
        }
    }
}
