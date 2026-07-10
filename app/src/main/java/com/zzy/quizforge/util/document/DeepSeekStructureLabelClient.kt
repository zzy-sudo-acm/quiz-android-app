package com.zzy.quizforge.util.document

import com.google.gson.Gson
import com.zzy.quizforge.data.remote.DeepSeekApi

class DeepSeekStructureLabelClient(
    private val api: DeepSeekApi,
) : StructureLabelClient {
    private val gson = Gson()

    override suspend fun labelStructure(apiKey: String, snapshot: SegmentSnapshot): String {
        val json = gson.toJson(snapshot)
        return api.labelStructure(apiKey, json)
    }
}
