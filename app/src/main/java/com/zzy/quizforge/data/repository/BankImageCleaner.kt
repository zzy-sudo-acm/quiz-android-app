package com.zzy.quizforge.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.zzy.quizforge.data.local.entity.QuestionEntity
import com.zzy.quizforge.domain.model.QuestionOption
import java.io.File

private val imageCleanerGson = Gson()
private val imageCleanerStringListType = object : TypeToken<List<String?>>() {}.type
private val imageCleanerOptionListType = object : TypeToken<List<QuestionOption>>() {}.type

internal fun referencedImagePaths(questions: Iterable<QuestionEntity>): Set<String> = buildSet {
    questions.forEach { question ->
        question.imageUri?.takeIf(String::isNotBlank)?.let(::add)
        runCatching {
            imageCleanerGson.fromJson<List<String?>>(question.imageUrisJson, imageCleanerStringListType)
        }.getOrNull()?.filterNotNull()?.filter(String::isNotBlank)?.forEach(::add)
        runCatching {
            imageCleanerGson.fromJson<List<QuestionOption>>(question.optionsJson, imageCleanerOptionListType)
        }.getOrNull()?.forEach { option ->
                option.imageUri?.takeIf(String::isNotBlank)?.let(::add)
                option.imageUris.orEmpty().filter(String::isNotBlank).forEach(::add)
        }
    }
}

/**
 * 只清理 App 私有目录中由题库导入流程创建的图片。
 *
 * 旧版导入把图片放在 docx-images，结构化导入放在 docx-images-ir；
 * 新版按题库隔离的目录为 quiz-banks/{bankId}。外部路径和 content:// URI 永不删除。
 */
internal class BankImageCleaner(
    private val filesDir: File,
) {
    fun deleteBankImages(
        bankId: Long,
        imagePaths: Collection<String>,
        retainedImagePaths: Collection<String>,
    ) {
        val failures = mutableListOf<File>()
        val retained = retainedImagePaths.mapNotNull(::canonicalFileOrNull).toSet()
        imagePaths
            .mapNotNull(::canonicalFileOrNull)
            .filter(::isImportedImage)
            .filterNot(retained::contains)
            .distinct()
            .forEach { deleteFile(it, failures) }

        if (bankId > 0) {
            deleteTreeExcept(File(filesDir, "quiz-banks/$bankId"), retained, failures)
        }
        check(failures.isEmpty()) { "有 ${failures.size} 个题库图片文件无法删除" }
    }

    fun clearAllImportedFiles() {
        val failures = mutableListOf<File>()
        IMPORT_DIRECTORY_NAMES.forEach { directoryName ->
            val directory = File(filesDir, directoryName)
            if (directory.exists() && !directory.deleteRecursively()) failures += directory
        }
        check(failures.isEmpty()) { "有 ${failures.size} 个导入图片目录无法完全删除" }
    }

    /** Removes task directories left by a previous process; call only during application startup. */
    fun clearStaleImportTasks(taskNames: Collection<String>) {
        if (taskNames.isEmpty()) return
        val root = canonicalFileOrNull(File(filesDir, "import-temp")) ?: return
        if (!root.exists() || !isInsideFilesDir(root)) return
        val failures = mutableListOf<File>()
        taskNames.distinct().forEach { taskName ->
            val child = File(root, taskName)
            val canonicalChild = canonicalFileOrNull(child)
            if (
                taskName.isBlank() || File(taskName).name != taskName ||
                canonicalChild == null || canonicalChild == root ||
                !canonicalChild.toPath().startsWith(root.toPath())
            ) {
                failures += child
            } else if (!canonicalChild.deleteRecursively() && canonicalChild.exists()) {
                failures += canonicalChild
            }
        }
        check(failures.isEmpty()) { "有 ${failures.size} 个上次遗留的导入任务无法清理" }
    }

    private fun isImportedImage(file: File): Boolean =
        importedImageRoots().any { root -> file.toPath().startsWith(root.toPath()) && file != root }

    private fun importedImageRoots(): List<File> = listOf(
        File(filesDir, "docx-images"),
        File(filesDir, "docx-images-ir"),
        File(filesDir, "quiz-banks"),
    ).mapNotNull(::canonicalFileOrNull)

    private fun deleteTreeExcept(root: File, retained: Set<File>, failures: MutableList<File>) {
        val canonicalRoot = canonicalFileOrNull(root) ?: return
        if (!canonicalRoot.exists() || !isInsideFilesDir(canonicalRoot)) return

        canonicalRoot.walkBottomUp().forEach { entry ->
            val canonicalEntry = canonicalFileOrNull(entry) ?: return@forEach
            if (canonicalEntry.isDirectory) {
                if (canonicalEntry.listFiles().isNullOrEmpty()) {
                    if (!canonicalEntry.delete() && canonicalEntry.exists()) failures += canonicalEntry
                }
            } else if (canonicalEntry !in retained) {
                deleteFile(canonicalEntry, failures)
            }
        }
    }

    private fun deleteFile(file: File, failures: MutableList<File>) {
        if (!file.delete() && file.exists()) failures += file
    }

    private fun isInsideFilesDir(file: File): Boolean {
        val root = canonicalFileOrNull(filesDir) ?: return false
        return file.toPath().startsWith(root.toPath()) && file != root
    }

    private fun canonicalFileOrNull(path: String): File? {
        if (path.isBlank() || path.contains("://")) return null
        return canonicalFileOrNull(File(path))
    }

    private fun canonicalFileOrNull(file: File): File? = runCatching { file.canonicalFile }.getOrNull()

    private companion object {
        val IMPORT_DIRECTORY_NAMES = listOf(
            "docx-images",
            "docx-images-ir",
            "quiz-banks",
            "import-temp",
        )
    }
}
