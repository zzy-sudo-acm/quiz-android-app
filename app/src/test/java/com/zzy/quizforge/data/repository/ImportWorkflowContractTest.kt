package com.zzy.quizforge.data.repository

import com.zzy.quizforge.util.document.ImportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards product-routing invariants that span Android-owned classes and therefore cannot be
 * exercised as plain JVM behavior without replacing ContentResolver and ViewModel lifecycles.
 */
class ImportWorkflowContractTest {
    @Test
    fun `production exposes exactly standard and smart import modes without old routes`() {
        assertEquals(setOf("STANDARD", "SMART"), enumValues<ImportMode>().map { it.name }.toSet())

        val forbidden = listOf("SHADOW", "DOCUMENT_IR", "LEGACY", "ShadowComparator")
        val violations = productionFiles()
            .flatMap { file -> forbidden.filter { token -> file.readText().contains(token) }.map { file to it } }
            .toList()

        assertTrue("旧导入路由仍出现在生产源码：$violations", violations.isEmpty())
    }

    @Test
    fun `one import task loads the DOCX archive once and commit reuses prepared data`() {
        val source = modulePath("src/main/java/com/zzy/quizforge/data/repository/ImportRepository.kt").readText()

        assertEquals(1, Regex("""DocxArchiveLoader\.load\(""").findAll(source).count())
        assertFalse(source.section("suspend fun recognizeSmart", "suspend fun retrySmartRecord")
            .contains("DocxArchiveLoader.load("))
        assertFalse(source.section("suspend fun retrySmartRecord", "suspend fun commitPreparedImport")
            .contains("DocxArchiveLoader.load("))
        assertFalse(source.section("suspend fun commitPreparedImport", "suspend fun cancelImport")
            .contains("DocxArchiveLoader.load("))
    }

    @Test
    fun `smart preparation cannot call the model and UI requires the consent dialog`() {
        val repository = modulePath(
            "src/main/java/com/zzy/quizforge/data/repository/ImportRepository.kt",
        ).readText()
        val prepare = repository.section("suspend fun prepareImport", "/** User-confirmed smart recognition")
        assertFalse(prepare.contains("smartClient"))
        assertFalse(prepare.contains("api."))

        val screen = modulePath("src/main/java/com/zzy/quizforge/ui/importdoc/ImportScreen.kt").readText()
        assertEquals(1, Regex("""viewModel\.recognizeSmart\(\)""").findAll(screen).count())
        val consent = screen.section("if (showSmartConsent)", "if (showFailures)")
        assertTrue(consent.contains("确认调用模型 API"))
        assertTrue(consent.contains("viewModel.recognizeSmart()"))
    }

    @Test
    fun `standard mode never reads the API key and settings store does not eagerly read it`() {
        val repository = modulePath(
            "src/main/java/com/zzy/quizforge/data/repository/ImportRepository.kt",
        ).readText()
        val prepare = repository.section("suspend fun prepareImport", "/** User-confirmed smart recognition")
        assertFalse(prepare.contains("getApiKey()"))
        assertFalse(prepare.contains("hasApiKey()"))

        val viewModel = modulePath(
            "src/main/java/com/zzy/quizforge/ui/importdoc/ImportViewModel.kt",
        ).readText()
        assertFalse(viewModel.section("class ImportViewModel", "fun selectMode").contains("hasApiKey()"))
        assertTrue(viewModel.contains("mode == ImportMode.SMART && repository.hasApiKey()"))
        assertTrue(viewModel.contains("smartMode && repository.hasApiKey()"))

        val settings = modulePath(
            "src/main/java/com/zzy/quizforge/data/repository/SettingsStore.kt",
        ).readText()
        assertFalse(settings.contains("MutableStateFlow(getApiKey())"))
    }

    @Test
    fun `model identifiers are centralized and report warnings are always visible`() {
        val productionText = productionFiles().joinToString("\n") { it.readText() }
        assertEquals(1, Regex("deepseek-v4-flash").findAll(productionText).count())
        assertEquals(1, Regex("deepseek-v4-pro").findAll(productionText).count())

        val screen = modulePath("src/main/java/com/zzy/quizforge/ui/importdoc/ImportScreen.kt").readText()
        assertTrue(screen.contains("report.warnings.forEach"))
        assertTrue(screen.contains("需要人工确认"))
        assertTrue(screen.contains("else if (report.warnings.isEmpty())"))
    }

    @Test
    fun `cancelled import operations cannot report failure or overwrite a newer state`() {
        val viewModel = modulePath(
            "src/main/java/com/zzy/quizforge/ui/importdoc/ImportViewModel.kt",
        ).readText()

        assertEquals(
            4,
            Regex("""catch \(cancelled: CancellationException\)""").findAll(viewModel).count(),
        )
        assertFalse(
            Regex("""runCatching\s*\{\s*repository\.(prepareImport|recognizeSmart|retrySmartRecord|commitPreparedImport)""")
                .containsMatchIn(viewModel),
        )
        assertTrue(viewModel.contains("operationGeneration++"))
        assertTrue(viewModel.contains("if (isCurrentOperation(operation)) _uiState.update(transform)"))
    }

    @Test
    fun `cancel and lifecycle discard both remove task files and request cache`() {
        val source = modulePath("src/main/java/com/zzy/quizforge/data/repository/ImportRepository.kt").readText()
        val cancel = source.section("suspend fun cancelImport", "/** Lifecycle cleanup")
        val discard = source.section("fun discardImport", "fun hasApiKey")

        assertTrue(cancel.contains("prepared.tempDir.deleteRecursively()"))
        assertTrue(cancel.contains("smartCaches.remove(prepared.taskId)"))
        assertTrue(cancel.contains("NonCancellable"))
        assertTrue(discard.contains("cleanupScope.launch { cancelImport(prepared) }"))
    }

    private fun productionFiles(): Sequence<File> = modulePath("src/main")
        .walkTopDown()
        .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }

    private fun String.section(start: String, end: String): String {
        assertTrue("缺少起始标记：$start", contains(start))
        val tail = substringAfter(start)
        assertTrue("缺少结束标记：$end", tail.contains(end))
        return tail.substringBefore(end)
    }

    private fun modulePath(relativePath: String): File {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        while (directory != null) {
            val direct = File(directory, relativePath)
            if (direct.exists()) return direct
            val fromRootProject = File(directory, "app/$relativePath")
            if (fromRootProject.exists()) return fromRootProject
            directory = directory.parentFile
        }
        error("找不到项目路径：$relativePath")
    }
}
