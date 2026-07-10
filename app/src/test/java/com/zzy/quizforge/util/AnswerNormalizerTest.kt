package com.zzy.quizforge.util

import org.junit.Assert.*
import org.junit.Test

class AnswerNormalizerTest {

    // ── 基本单字符 ──
    @Test
    fun `single letter A`() {
        assertEquals(listOf("A"), AnswerNormalizer.normalize("A"))
    }

    @Test
    fun `single letter lowercase a`() {
        assertEquals(listOf("A"), AnswerNormalizer.normalize("a"))
    }

    // ── 多字符连续展开 "AB" → ["A","B"] ──
    @Test
    fun `multi-char AB`() {
        assertEquals(listOf("A", "B"), AnswerNormalizer.normalize("AB"))
    }

    @Test
    fun `multi-char ABC`() {
        assertEquals(listOf("A", "B", "C"), AnswerNormalizer.normalize("ABC"))
    }

    @Test
    fun `multi-char lowercase ab`() {
        assertEquals(listOf("A", "B"), AnswerNormalizer.normalize("ab"))
    }

    // ── 分隔符处理 ──
    @Test
    fun `comma separated A,B`() {
        assertEquals(listOf("A", "B"), AnswerNormalizer.normalize("A,B"))
    }

    @Test
    fun `chinese comma separated`() {
        assertEquals(listOf("A", "B"), AnswerNormalizer.normalize("A，B"))
    }

    @Test
    fun `chinese dun comma separated`() {
        assertEquals(listOf("A", "B"), AnswerNormalizer.normalize("A、B"))
    }

    @Test
    fun `space separated A B`() {
        assertEquals(listOf("A", "B"), AnswerNormalizer.normalize("A B"))
    }

    @Test
    fun `lowercase space separated a b`() {
        assertEquals(listOf("A", "B"), AnswerNormalizer.normalize("a b"))
    }

    @Test
    fun `mixed separators`() {
        assertEquals(listOf("A", "B", "C"), AnswerNormalizer.normalize("A, B、C"))
    }

    // ── 中文真假值 ──
    @Test
    fun `true value dui`() {
        assertEquals(listOf("A"), AnswerNormalizer.normalize("对"))
    }

    @Test
    fun `true value zhengque`() {
        assertEquals(listOf("A"), AnswerNormalizer.normalize("正确"))
    }

    @Test
    fun `true value checkmark`() {
        assertEquals(listOf("A"), AnswerNormalizer.normalize("√"))
    }

    @Test
    fun `false value cuo`() {
        assertEquals(listOf("B"), AnswerNormalizer.normalize("错"))
    }

    @Test
    fun `false value cuowu`() {
        assertEquals(listOf("B"), AnswerNormalizer.normalize("错误"))
    }

    @Test
    fun `false value cross`() {
        assertEquals(listOf("B"), AnswerNormalizer.normalize("×"))
    }

    // ── 去重 ──
    @Test
    fun `duplicate A,A`() {
        assertEquals(listOf("A"), AnswerNormalizer.normalize("A,A"))
    }

    @Test
    fun `duplicate through multi-char AAB`() {
        assertEquals(listOf("A", "B"), AnswerNormalizer.normalize("AAB"))
    }

    // ── 非 A-H 过滤 ──
    @Test
    fun `invalid letter X`() {
        assertEquals(emptyList<String>(), AnswerNormalizer.normalize("X"))
    }

    @Test
    fun `invalid mixed with valid`() {
        assertEquals(listOf("A"), AnswerNormalizer.normalize("A,X"))
    }

    // ── 空白输入 ──
    @Test
    fun `empty string`() {
        assertEquals(emptyList<String>(), AnswerNormalizer.normalize(""))
    }

    @Test
    fun `blank string`() {
        assertEquals(emptyList<String>(), AnswerNormalizer.normalize("   "))
    }

    // ── normalizeFromJsonStrings ──
    @Test
    fun `json array to normalized`() {
        assertEquals(
            listOf("A", "B"),
            AnswerNormalizer.normalizeFromJsonStrings(listOf("A", "B")),
        )
    }

    @Test
    fun `json single multi-char string to normalized`() {
        assertEquals(
            listOf("A", "B"),
            AnswerNormalizer.normalizeFromJsonStrings(listOf("AB")),
        )
    }

    @Test
    fun `json empty list`() {
        assertEquals(
            emptyList<String>(),
            AnswerNormalizer.normalizeFromJsonStrings(emptyList()),
        )
    }
}
