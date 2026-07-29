package com.zzy.quizforge.util.document

enum class LossyPolicy(val label: String) {
    STRICT("仅接受完整可表达题目"),
    ALLOW_LOSSY("接受结构有损题目"),
}
