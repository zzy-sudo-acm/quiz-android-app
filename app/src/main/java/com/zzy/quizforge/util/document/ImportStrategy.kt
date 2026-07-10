package com.zzy.quizforge.util.document

enum class ImportStrategy(val label: String) {
    LEGACY("旧流水线"),
    SHADOW("新旧对比"),
    DOCUMENT_IR("文档IR"),
}

enum class LossyPolicy(val label: String) {
    STRICT("仅接受完整可表达题目"),
    ALLOW_LOSSY("接受结构有损题目"),
}

object ImportRuntimeConfig {
    val currentStrategy: ImportStrategy = ImportStrategy.SHADOW
    val displayName: String get() = when (currentStrategy) {
        ImportStrategy.LEGACY -> "LEGACY"
        ImportStrategy.SHADOW -> "SHADOW"
        ImportStrategy.DOCUMENT_IR -> "DOCUMENT_IR"
    }
}
