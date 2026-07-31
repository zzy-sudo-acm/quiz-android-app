package com.zzy.quizforge.util.document

import com.zzy.quizforge.domain.model.QuizQuestion
import java.text.Normalizer

/**
 * 跨导入模式的重复判定键。标准解析与智能识别共用同一套规范化规则，
 * 保证同一道题在两种模式下得到完全一致的重复判定。
 */
object QuestionDuplicateKey {

    /** 与智能识别原有判定保持一致的完整键：题干 + 每个选项的标号与文本。 */
    fun canonical(value: QuizQuestion): String = buildString {
        append(normalize(value.question))
        value.options.forEach { option ->
            append('|').append(option.key).append('=').append(normalize(option.text))
        }
    }

    /** 用于尚处于候选阶段（未组装为 [QuizQuestion]）的题目比较。 */
    fun canonical(question: String, options: List<Pair<String, String>>): String = buildString {
        append(normalize(question))
        options.forEach { (key, text) ->
            append('|').append(key.uppercase()).append('=').append(normalize(text))
        }
    }

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(Regex("\\s+"), "")
        .replace('，', ',')
        .replace('：', ':')
}
