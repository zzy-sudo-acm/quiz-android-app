package com.zzy.quizforge.data.remote

import com.google.gson.Gson
import com.zzy.quizforge.util.document.SmartImportModelClient
import com.zzy.quizforge.util.document.SmartModelRequest

class DeepSeekSmartImportClient(
    private val api: DeepSeekApi,
    private val modelName: () -> String = { DeepSeekApi.DEFAULT_MODEL },
) : SmartImportModelClient {
    private val gson = Gson()

    override suspend fun complete(apiKey: String, request: SmartModelRequest): String =
        api.completeSmartImport(apiKey, request.stage, gson.toJson(request), modelName())
}
