package com.zzy.quizforge.util

/**
 * 统一的答案标准化工具。
 *
 * 所有答案解析路径（本地规则解析 + AI 返回 JSON 校验）
 * 必须共用此方法，不得维护多套不同行为的答案解析逻辑。
 *
 * 规则：
 *  - "AB" / "ABC" 等连续大写字母 → 逐字拆分
 *  - 中文/英文逗号、顿号、空格 → 分隔符
 *  - 中文判断值（对/错/正确/错误/√/×）→ 映射为 A / B
 *  - 结果统一 uppercase、distinct、sorted、仅保留 [A-H]
 *  - 空白/无法识别 → 空列表
 */
object AnswerNormalizer {

    /**
     * 将任意原始答案字符串标准化为选项 key 列表。
     *
     * 示例：
     *   "AB"     → ["A", "B"]
     *   "A,B"    → ["A", "B"]
     *   "A、B"   → ["A", "B"]
     *   "A B"    → ["A", "B"]
     *   "a b"    → ["A", "B"]
     *   "ABC"    → ["A", "B", "C"]
     *   "对"     → ["A"]
     *   "正确"   → ["A"]
     *   "√"      → ["A"]
     *   "错"     → ["B"]
     *   "错误"   → ["B"]
     *   "×"      → ["B"]
     *   "X"      → []  (非 A-H)
     *   ""       → []
     */
    fun normalize(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 中文真假值优先映射
        val trueFalse = when (trimmed) {
            "对", "正确", "√" -> listOf("A")
            "错", "错误", "×" -> listOf("B")
            else -> null
        }
        if (trueFalse != null) return trueFalse

        return trimmed
            .replace("，", ",")
            .replace("、", ",")
            .replace(" ", "")
            .split(",")
            .flatMap { token ->
                val stripped = token.trim()
                if (stripped.isEmpty()) return@flatMap emptyList<String>()
                // "AB" → ["A", "B"]: 多字符连续大写字母展开
                if (stripped.length > 1 && stripped.all { it.uppercaseChar() in 'A'..'H' }) {
                    stripped.map { it.uppercaseChar().toString() }
                } else {
                    listOf(stripped.take(1).uppercase())
                }
            }
            .filter { it.matches(Regex("[A-H]")) }
            .distinct()
            .sorted()
    }

    /**
     * 接受 JSON 解析出的原始值（可能是单字符串或字符串数组），
     * join 后调用 [normalize] 统一处理。
     */
    fun normalizeFromJsonStrings(values: List<String>): List<String> {
        if (values.isEmpty()) return emptyList()
        return normalize(values.joinToString(","))
    }
}
