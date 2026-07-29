package com.zzy.quizforge.data.repository

import com.zzy.quizforge.util.document.ImportReport
import com.zzy.quizforge.util.document.ImportReportRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApiKeyBackupRulesTest {
    @Test
    fun `legacy backup excludes secure and fallback preferences`() {
        val xml = projectFile("src/main/res/xml/backup_rules.xml").readText()

        assertEquals(1, xml.countExclusion("quizforge_secure_settings.xml"))
        assertEquals(1, xml.countExclusion("quizforge_settings_fallback.xml"))
    }

    @Test
    fun `cloud backup and device transfer both exclude api key preferences`() {
        val xml = projectFile("src/main/res/xml/data_extraction_rules.xml").readText()

        assertTrue(xml.contains("<cloud-backup>"))
        assertTrue(xml.contains("<device-transfer>"))
        assertEquals(2, xml.countExclusion("quizforge_secure_settings.xml"))
        assertEquals(2, xml.countExclusion("quizforge_settings_fallback.xml"))
    }

    @Test
    fun `manifest applies both backup rule files`() {
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }

    @Test
    fun `import report schema cannot contain api key`() {
        val reportFieldNames = (ImportReport::class.java.declaredFields + ImportReportRecord::class.java.declaredFields)
            .map { it.name.lowercase() }

        assertFalse(reportFieldNames.any { it.contains("apikey") || it.contains("credential") })
    }

    private fun String.countExclusion(fileName: String): Int {
        val escapedFileName = Regex.escape(fileName)
        return Regex("""<exclude\s+domain="sharedpref"\s+path="$escapedFileName"\s*/>""")
            .findAll(this)
            .count()
    }

    private fun projectFile(moduleRelativePath: String): File {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        while (directory != null) {
            val direct = File(directory, moduleRelativePath)
            if (direct.isFile) return direct
            val fromRootProject = File(directory, "app/$moduleRelativePath")
            if (fromRootProject.isFile) return fromRootProject
            directory = directory.parentFile
        }
        error("找不到项目文件：$moduleRelativePath")
    }
}
