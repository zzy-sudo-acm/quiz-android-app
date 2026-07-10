package com.zzy.quizforge.util

import org.junit.Assert.*
import org.junit.Test

/**
 * 验证图片 marker 精确清理：只删除实际成功绑定的 marker，
 * 未绑定的 marker 必须保留作为多图能力不足的显式信号。
 *
 * 当前架构限制：imageUri: String? 只能存单图。
 * 多图支持留到 Document IR 第二阶段。
 */
class ImageMarkerCleanupTest {

    // 模拟 ImportRepository.removeSpecificMarker 的行为
    private fun removeSpecificMarker(text: String, marker: String): String =
        text.replace(marker, "")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()

    // ── Case 1: 单 marker 成功绑定 ──
    @Test
    fun `bound marker removed from question text`() {
        val input = "根据下图回答\n[图片1]"
        val cleaned = removeSpecificMarker(input, "[图片1]")
        assertEquals("根据下图回答", cleaned)
    }

    @Test
    fun `bound marker in middle of text removed`() {
        val input = "请看[图片1]回答问题"
        val cleaned = removeSpecificMarker(input, "[图片1]")
        assertEquals("请看回答问题", cleaned)
    }

    // ── Case 2: 两个 marker，只绑定第一个 ──
    @Test
    fun `only bound marker removed unbound marker preserved`() {
        val input = "观察两图\n[图片1]\n比较\n[图片2]"
        // 只绑定了 [图片1]
        val cleaned = removeSpecificMarker(input, "[图片1]")
        assertEquals("观察两图\n\n比较\n[图片2]", cleaned)
    }

    @Test
    fun `only bound marker removed unbound marker preserved no extra spaces`() {
        val input = "[图片1] 题干 [图片2]"
        val cleaned = removeSpecificMarker(input, "[图片1]")
        assertEquals("题干 [图片2]", cleaned)
    }

    // ── Case 3: option 级别的精确清理 ──
    @Test
    fun `option A bound marker removed option B unbound marker preserved`() {
        // Option A 文本：绑定 [图片1]
        val optionAText = "[图片1]A. 选项内容"
        val cleanedA = removeSpecificMarker(optionAText, "[图片1]")
        assertEquals("A. 选项内容", cleanedA)

        // Option B 文本：[图片2] 未绑定，必须保留
        val optionBText = "[图片2]B. 选项内容"
        val cleanedB = removeSpecificMarker(optionBText, "[图片1]") // [图片1] 不在 B 中
        assertEquals("[图片2]B. 选项内容", cleanedB) // 不变
    }

    // ── 文本不含指定 marker 时不变 ──
    @Test
    fun `text without specified marker unchanged`() {
        val input = "这是正常的题干，没有任何图片"
        val cleaned = removeSpecificMarker(input, "[图片1]")
        assertEquals("这是正常的题干，没有任何图片", cleaned)
    }

    // ── 多余空行清理 ──
    @Test
    fun `excess blank lines after marker removal collapsed`() {
        val input = "第一行\n\n[图片1]\n\n\n第二行"
        val cleaned = removeSpecificMarker(input, "[图片1]")
        assertEquals("第一行\n\n第二行", cleaned)
    }
}
