package com.zzy.quizforge.data.remote

enum class ModelTier(
    val storageValue: String,
    val label: String,
    val modelName: String,
) {
    QUICK("quick", "快速模式", "deepseek-v4-flash"),
    HIGH_QUALITY("high_quality", "高质量模式", "deepseek-v4-pro");

    companion object {
        fun fromStorage(value: String?): ModelTier = entries.firstOrNull { it.storageValue == value } ?: QUICK
    }
}

object DeepSeekModelCatalog {
    const val BASE_URL = "https://api.deepseek.com/chat/completions"
    val defaultTier: ModelTier = ModelTier.QUICK
}
