package com.zzy.quizforge.util.document

/** Client for AI-based structure labeling. */
interface StructureLabelClient {
    /** Label a single QuestionSegment. Returns raw AI response JSON string. */
    suspend fun labelStructure(apiKey: String, snapshot: SegmentSnapshot): String
}

/** Fake client for testing — records call count, returns configurable response. */
class FakeStructureLabelClient : StructureLabelClient {
    var calls = 0
    var responseJson: String = """{"annotations":[]}"""

    override suspend fun labelStructure(apiKey: String, snapshot: SegmentSnapshot): String {
        calls++
        return responseJson
    }
}
