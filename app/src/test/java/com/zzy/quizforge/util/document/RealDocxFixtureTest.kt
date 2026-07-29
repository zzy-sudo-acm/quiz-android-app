package com.zzy.quizforge.util.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class RealDocxFixtureTest {
    private val reader = OoXmlDocumentReader { TestXmlPullParser(it) }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `real standard docx preserves numbering images and visible malformed question`() {
        val document = reader.read(
            entries("/fixtures/standard-regression.docx"),
            temporaryFolder.newFolder("standard-media"),
        )
        val sources = SourceBlockExtractor.extract(document)
        val result = StandardFormatParser(clock = { 1 }, reportId = { "fixture" })
            .parse("standard-regression.docx", sources)

        assertEquals(result.report.records.joinToString("\n"), 3, result.report.acceptedQuestionCount)
        assertEquals(1, result.report.rejectedQuestionCount)
        assertEquals(4, result.report.imageCount)
        assertTrue(result.report.ledgerComplete)
        assertEquals("1.", sources.first { it.rawText.contains("根据两张") }.numbering?.displayText)
        assertEquals(2, result.questions.first().question.imageUris.size)
        assertEquals(2, result.questions.first().question.options.first().imageUris.size)
    }

    @Test
    fun `real smart docx keeps table coordinates and oversized source text`() {
        val document = reader.read(entries("/fixtures/smart-regression.docx"))
        val sources = SourceBlockExtractor.extract(document)

        assertTrue(sources.any { it.sourceType == SourceBlockType.TABLE_CELL && it.table != null })
        assertTrue(sources.any { it.rawText.length > 5_000 })
        val slices = SourceBlockChunker(maxEstimatedTokens = 500, overlapBlocks = 1).chunk(sources).flatMap { it.slices }
        assertTrue(slices.groupBy { it.sourceId }.any { (_, value) -> value.size > 1 })
    }

    private fun entries(resource: String): Map<String, ByteArray> {
        val bytes = requireNotNull(javaClass.getResourceAsStream(resource)) { "Missing $resource" }.use { it.readBytes() }
        return buildMap {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) put(entry.name, zip.readBytes())
                }
            }
        }
    }
}
