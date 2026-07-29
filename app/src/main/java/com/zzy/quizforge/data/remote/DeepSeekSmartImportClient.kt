package com.zzy.quizforge.data.remote

import com.google.gson.Gson
import com.zzy.quizforge.util.document.SmartImportModelClient
import com.zzy.quizforge.util.document.SmartModelCompletion
import com.zzy.quizforge.util.document.SmartModelRequest
import com.zzy.quizforge.util.document.SmartModelUsage

class DeepSeekSmartImportClient(
    private val api: DeepSeekApi,
    private val modelName: () -> String = { DeepSeekApi.DEFAULT_MODEL },
) : SmartImportModelClient {
    private val gson = Gson()

    override suspend fun complete(apiKey: String, request: SmartModelRequest): SmartModelCompletion =
        api.completeSmartImport(apiKey, request.stage, gson.toJson(request), modelName()).let { completion ->
            SmartModelCompletion(
                content = completion.content,
                finishReason = completion.finishReason,
                usage = completion.usage?.let { usage ->
                    SmartModelUsage(
                        promptTokens = usage.promptTokens,
                        completionTokens = usage.completionTokens,
                        totalTokens = usage.totalTokens,
                    )
                },
            )
        }
}
