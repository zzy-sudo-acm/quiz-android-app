package com.zzy.quizforge.util.document

import com.zzy.quizforge.domain.model.QuestionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

/**
 * Opt-in acceptance test for the user's original 314-question document. The private source file is
 * intentionally not copied into the repository; set QUIZFORGE_REAL_DOCX to run this test locally.
 */
class ExternalTaggedDocxAcceptanceTest {
    private val reader = OoXmlDocumentReader { TestXmlPullParser(it) }

    @Test(timeout = 10_000)
    fun `original tagged docx imports all 314 questions without API`() {
        val path = System.getenv("QUIZFORGE_REAL_DOCX")
        val file = path?.let(::File)
        assumeTrue("Set QUIZFORGE_REAL_DOCX to the original tagged DOCX", file?.isFile == true)
        file!!

        val document = reader.read(entries(file))
        val sources = SourceBlockExtractor.extract(document)
        val result = requireNotNull(
            StandardFormatParser(clock = { 1L }, reportId = { "external-tagged" })
                .parseTaggedIfComplete(file.name, sources),
        )

        assertEquals(1_866, document.blocks.size)
        assertEquals(1_550, sources.count { it.isNonEmpty })
        assertEquals(314, result.questions.size)
        assertEquals(84, result.questions.count { it.question.type == QuestionType.TRUE_FALSE })
        assertEquals(132, result.questions.count { it.question.type == QuestionType.SINGLE })
        assertEquals(98, result.questions.count { it.question.type == QuestionType.MULTIPLE })
        assertEquals(listOf("A"), result.questions[234].question.answer)
        assertEquals(QuestionType.TRUE_FALSE, result.questions[234].question.type)
        assertFalse(result.questions[234].question.question.contains("（对）"))
        assertEquals(listOf("A"), result.questions[241].question.answer)
        assertEquals(QuestionType.SINGLE, result.questions[241].question.type)
        assertEquals(4, result.questions[241].question.options.size)
        assertEquals(314, result.report.acceptedQuestionCount)
        assertEquals(0, result.report.rejectedQuestionCount)
        assertFalse(result.report.usedApi)
        assertEquals(0, result.report.apiRequestCount)
        assertTrue(result.report.ledgerComplete)
    }

    private fun entries(file: File): Map<String, ByteArray> = buildMap {
        ZipInputStream(FileInputStream(file)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) put(entry.name, zip.readBytes())
            }
        }
    }

}
