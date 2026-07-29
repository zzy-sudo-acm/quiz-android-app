package com.zzy.quizforge.util.document

internal const val IMAGE_OWNER_STEM = "stem"
internal const val IMAGE_OWNER_OTHER = "other"
internal fun imageOptionOwner(key: String): String = "option:${key.uppercase()}"

/** Resolves an inline image to the nearest preceding visible field marker in one source block. */
internal fun sourceImageOwner(rawText: String, charOffset: Int?): String? {
    val offset = charOffset ?: return null
    val markers = mutableListOf(-1 to IMAGE_OWNER_STEM)
    QUESTION_MARKER.findAll(rawText).forEach { markers += it.range.first to IMAGE_OWNER_STEM }
    OPTION_MARKER.findAll(rawText).forEach { match ->
        markers += match.groups[1]!!.range.first to imageOptionOwner(match.groupValues[1])
    }
    OTHER_FIELD_MARKER.findAll(rawText).forEach { match ->
        markers += match.groups[1]!!.range.first to IMAGE_OWNER_OTHER
    }
    return markers.filter { it.first <= offset }.maxByOrNull { it.first }?.second ?: IMAGE_OWNER_STEM
}

private val QUESTION_MARKER = Regex(
    """(?:^|[\r\n\s])(?:(?:第\s*)?\d{1,6}\s*题|[（(]\s*\d{1,6}\s*[）)]|\d{1,6}\s*[.．、)）])""",
)
private val OPTION_MARKER = Regex("""(?:^|[\r\n\s])([A-Ha-h])\s*[.．、:：)）]""")
private val OTHER_FIELD_MARKER = Regex(
    """(?:^|[\r\n\s])(答案|正确答案|参考答案|标准答案|解析|解释|题解|知识点|考点|题型)\s*[:：]?""",
)
