package com.zzy.quizforge.util.document

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

object DocxArchiveLoader {

    private const val MAX_ENTRY_SIZE = 50 * 1024 * 1024L // 50 MB
    private const val MAX_TOTAL_SIZE = 200 * 1024 * 1024L // 200 MB

    fun load(resolver: ContentResolver, uri: Uri): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        var totalSize = 0L

        resolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue

                    val name = entry.name.replace('\\', '/').trimStart('/')
                    if (name.contains("..")) throw SecurityException("Path traversal: $name")
                    if (name.isBlank()) continue

                    if (entries.containsKey(name)) {
                        throw IllegalStateException("Duplicate ZIP entry: $name")
                    }

                    val bos = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var len: Int
                    var entrySize = 0L
                    while (zip.read(buf).also { len = it } > 0) {
                        bos.write(buf, 0, len)
                        entrySize += len
                        if (entrySize > MAX_ENTRY_SIZE) throw IllegalStateException("Entry too large: $name")
                    }

                    totalSize += entrySize
                    if (totalSize > MAX_TOTAL_SIZE) throw IllegalStateException("Total size exceeds limit")

                    entries[name] = bos.toByteArray()
                }
            }
        } ?: throw IllegalArgumentException("无法读取文档")

        if (!entries.containsKey("word/document.xml")) {
            throw IllegalArgumentException("缺少 word/document.xml")
        }

        return entries
    }
}
