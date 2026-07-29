package com.zzy.quizforge.data.local

import com.zzy.quizforge.data.local.entity.QuestionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionMappersTest {
    @Test
    fun `legacy option JSON without imageUris maps safely`() {
        val entity = QuestionEntity(
            bankId = 1,
            type = "single",
            question = "旧题干",
            optionsJson = """[{"key":"A","text":"旧选项","imageUri":"/data/old-option.png"}]""",
            answerJson = "[\"A\"]",
            imageUri = "/data/old-stem.png",
        )

        val question = entity.toDomain()

        assertEquals(listOf("/data/old-stem.png"), question.imageUris)
        assertEquals(listOf("/data/old-option.png"), question.options.single().imageUris)
        assertTrue(question.answer == listOf("A"))
    }
}
