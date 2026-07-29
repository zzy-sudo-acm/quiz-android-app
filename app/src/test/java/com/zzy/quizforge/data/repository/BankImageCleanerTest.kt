package com.zzy.quizforge.data.repository

import com.google.gson.Gson
import com.zzy.quizforge.data.local.entity.QuestionEntity
import com.zzy.quizforge.domain.model.QuestionOption
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BankImageCleanerTest {
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("quizforge-image-cleanup-").toFile()
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun `delete bank images removes owned files but keeps shared and unrelated files`() {
        val cleaner = BankImageCleaner(filesDir)
        val legacyImage = createFile("docx-images/legacy.png")
        val sharedImage = createFile("docx-images-ir/shared.png")
        val unrelatedPrivateFile = createFile("other/keep.txt")
        val orphanInBankDirectory = createFile("quiz-banks/7/images/orphan.png")
        val retainedInBankDirectory = createFile("quiz-banks/7/images/retained.png")

        cleaner.deleteBankImages(
            bankId = 7,
            imagePaths = listOf(
                legacyImage.absolutePath,
                sharedImage.absolutePath,
                unrelatedPrivateFile.absolutePath,
                "content://documents/image/1",
            ),
            retainedImagePaths = listOf(sharedImage.absolutePath, retainedInBankDirectory.absolutePath),
        )

        assertFalse(legacyImage.exists())
        assertTrue(sharedImage.exists())
        assertTrue(unrelatedPrivateFile.exists())
        assertFalse(orphanInBankDirectory.exists())
        assertTrue(retainedInBankDirectory.exists())
    }

    @Test
    fun `path traversal cannot delete a file outside import directories`() {
        val cleaner = BankImageCleaner(filesDir)
        val unrelatedPrivateFile = createFile("other/keep.txt")
        val traversingPath = File(filesDir, "docx-images/../other/keep.txt").path

        cleaner.deleteBankImages(
            bankId = 3,
            imagePaths = listOf(traversingPath),
            retainedImagePaths = emptyList(),
        )

        assertTrue(unrelatedPrivateFile.exists())
    }

    @Test
    fun `clear all removes import directories only`() {
        val cleaner = BankImageCleaner(filesDir)
        val importedFiles = listOf(
            createFile("docx-images/a.png"),
            createFile("docx-images-ir/b.png"),
            createFile("quiz-banks/5/images/c.png"),
            createFile("import-temp/task/d.png"),
        )
        val unrelatedPrivateFile = createFile("other/keep.txt")

        cleaner.clearAllImportedFiles()

        importedFiles.forEach { assertFalse(it.exists()) }
        assertTrue(unrelatedPrivateFile.exists())
    }

    @Test
    fun `startup cleanup removes stale import tasks and leaves banks and unrelated files intact`() {
        val cleaner = BankImageCleaner(filesDir)
        val staleOne = createFile("import-temp/old-task/images/a.png")
        val staleTwo = createFile("import-temp/second-task/cache.json")
        val bankImage = createFile("quiz-banks/9/images/keep.png")
        val unrelatedPrivateFile = createFile("other/keep.txt")
        val currentTask = createFile("import-temp/current-task/images/active.png")

        cleaner.clearStaleImportTasks(listOf("old-task", "second-task"))

        assertFalse(staleOne.exists())
        assertFalse(staleTwo.exists())
        assertTrue(bankImage.exists())
        assertTrue(unrelatedPrivateFile.exists())
        assertTrue(currentTask.exists())
        assertTrue(File(filesDir, "import-temp").isDirectory)
    }

    @Test
    fun `referenced paths include stem and option images and tolerate malformed options`() {
        val stem = createFile("docx-images/stem.png").absolutePath
        val extraStem = createFile("docx-images/stem-2.png").absolutePath
        val option = createFile("docx-images/option.png").absolutePath
        val secondOption = createFile("docx-images/option-2.png").absolutePath
        val normal = question(
            imageUri = stem,
            optionsJson = Gson().toJson(
                listOf(QuestionOption("A", "选项", imageUri = option, imageUris = listOf(option, secondOption))),
            ),
        )
        val malformed = question(imageUri = stem, optionsJson = "not-json").copy(
            imageUrisJson = Gson().toJson(listOf(stem, extraStem)),
        )

        val paths = referencedImagePaths(listOf(normal, malformed))

        assertTrue(stem in paths)
        assertTrue(extraStem in paths)
        assertTrue(option in paths)
        assertTrue(secondOption in paths)
    }

    private fun question(imageUri: String?, optionsJson: String): QuestionEntity =
        QuestionEntity(
            bankId = 1,
            type = "single",
            question = "题干",
            optionsJson = optionsJson,
            answerJson = "[\"A\"]",
            imageUri = imageUri,
        )

    private fun createFile(relativePath: String): File =
        File(filesDir, relativePath).also { file ->
            file.parentFile?.mkdirs()
            file.writeText("fixture")
        }
}
