package com.zzy.quizforge.domain.model

import org.junit.Assert.*
import org.junit.Test

class QuestionTypeTest {

    // ── fromRaw (宽松) ──
    @Test
    fun `fromRaw single`() {
        assertEquals(QuestionType.SINGLE, QuestionType.fromRaw("single"))
    }

    @Test
    fun `fromRaw multiple`() {
        assertEquals(QuestionType.MULTIPLE, QuestionType.fromRaw("multiple"))
    }

    @Test
    fun `fromRaw multi alias`() {
        assertEquals(QuestionType.MULTIPLE, QuestionType.fromRaw("multi"))
    }

    @Test
    fun `fromRaw truefalse`() {
        assertEquals(QuestionType.TRUE_FALSE, QuestionType.fromRaw("truefalse"))
    }

    @Test
    fun `fromRaw judge alias`() {
        assertEquals(QuestionType.TRUE_FALSE, QuestionType.fromRaw("judge"))
    }

    @Test
    fun `fromRaw boolean alias`() {
        assertEquals(QuestionType.TRUE_FALSE, QuestionType.fromRaw("boolean"))
    }

    @Test
    fun `fromRaw unknown defaults to SINGLE`() {
        assertEquals(QuestionType.SINGLE, QuestionType.fromRaw("unknown"))
    }

    @Test
    fun `fromRaw null defaults to SINGLE`() {
        assertEquals(QuestionType.SINGLE, QuestionType.fromRaw(null))
    }

    @Test
    fun `fromRaw empty defaults to SINGLE`() {
        assertEquals(QuestionType.SINGLE, QuestionType.fromRaw(""))
    }

    // ── fromRawStrict (严格) ──
    @Test
    fun `fromRawStrict single`() {
        assertEquals(QuestionType.SINGLE, QuestionType.fromRawStrict("single"))
    }

    @Test
    fun `fromRawStrict multiple`() {
        assertEquals(QuestionType.MULTIPLE, QuestionType.fromRawStrict("multiple"))
    }

    @Test
    fun `fromRawStrict multi alias`() {
        assertEquals(QuestionType.MULTIPLE, QuestionType.fromRawStrict("multi"))
    }

    @Test
    fun `fromRawStrict truefalse`() {
        assertEquals(QuestionType.TRUE_FALSE, QuestionType.fromRawStrict("truefalse"))
    }

    @Test
    fun `fromRawStrict judge alias`() {
        assertEquals(QuestionType.TRUE_FALSE, QuestionType.fromRawStrict("judge"))
    }

    @Test
    fun `fromRawStrict true_false alias`() {
        assertEquals(QuestionType.TRUE_FALSE, QuestionType.fromRawStrict("true_false"))
    }

    @Test
    fun `fromRawStrict unknown returns null`() {
        assertNull(QuestionType.fromRawStrict("unknown"))
    }

    @Test
    fun `fromRawStrict misspelled returns null`() {
        assertNull(QuestionType.fromRawStrict("mutiple"))
    }

    @Test
    fun `fromRawStrict null returns null`() {
        assertNull(QuestionType.fromRawStrict(null))
    }

    @Test
    fun `fromRawStrict empty returns null`() {
        assertNull(QuestionType.fromRawStrict(""))
    }
}
