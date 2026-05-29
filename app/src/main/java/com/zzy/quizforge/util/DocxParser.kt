package com.zzy.quizforge.util

import android.content.Context
import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.util.zip.ZipInputStream

data class ImportedImage(
    val marker: String,
    val uri: String,
    val fileName: String,
)

data class DocumentContent(
    val text: String,
    val images: List<ImportedImage>,
)

class DocxParser(private val context: Context) {
    fun extractDocument(uri: Uri): DocumentContent {
        val entries = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取文档" }
            ZipInputStream(input).use { zip ->
                buildMap {
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory) {
                            put(entry.name, zip.readBytes())
                        }
                    }
                }
            }
        }

        val xml = entries["word/document.xml"]?.toString(Charsets.UTF_8)
        require(!xml.isNullOrBlank()) { "没有在 docx 中找到正文内容" }
        val imageRelationships = parseImageRelationships(
            entries["word/_rels/document.xml.rels"]?.toString(Charsets.UTF_8).orEmpty(),
        )
        val parsed = parseDocumentXml(xml, imageRelationships.keys)
        val images = parsed.markerByRelationship.mapNotNull { (relationshipId, marker) ->
            val targetPath = imageRelationships[relationshipId] ?: return@mapNotNull null
            val bytes = entries[normalizeRelationshipTarget(targetPath)] ?: return@mapNotNull null
            saveImage(marker, targetPath, bytes)
        }
        return DocumentContent(
            text = parsed.text.trim(),
            images = images,
        )
    }

    private fun parseDocumentXml(
        xml: String,
        imageRelationshipIds: Set<String>,
    ): ParsedDocument {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))

        val builder = StringBuilder()
        val markerByRelationship = linkedMapOf<String, String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val tagName = parser.name?.substringAfter(':')
            when {
                event == XmlPullParser.START_TAG && tagName == "t" -> {
                    builder.append(parser.nextText())
                }
                event == XmlPullParser.START_TAG && tagName == "blip" -> {
                    val relationshipId = parser.findRelationshipId()
                    if (relationshipId != null && relationshipId in imageRelationshipIds) {
                        val marker = markerByRelationship.getOrPut(relationshipId) {
                            "[图片${markerByRelationship.size + 1}]"
                        }
                        builder.append('\n').append(marker).append('\n')
                    }
                }
                event == XmlPullParser.END_TAG && tagName == "p" -> {
                    builder.append('\n')
                }
            }
            event = parser.next()
        }
        val text = builder.toString()
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        return ParsedDocument(text = text, markerByRelationship = markerByRelationship)
    }

    private fun parseImageRelationships(xml: String): Map<String, String> {
        if (xml.isBlank()) return emptyMap()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        val images = linkedMapOf<String, String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val tagName = parser.name?.substringAfter(':')
            if (event == XmlPullParser.START_TAG && tagName == "Relationship") {
                val id = parser.getAttributeValue(null, "Id")
                val target = parser.getAttributeValue(null, "Target")
                val type = parser.getAttributeValue(null, "Type").orEmpty()
                if (!id.isNullOrBlank() && !target.isNullOrBlank() && type.contains("/image")) {
                    images[id] = target
                }
            }
            event = parser.next()
        }
        return images
    }

    private fun normalizeRelationshipTarget(target: String): String {
        val normalized = target.replace('\\', '/').removePrefix("/")
        return if (normalized.startsWith("word/")) {
            normalized
        } else {
            "word/$normalized"
        }.replace("word/../", "")
    }

    private fun saveImage(marker: String, targetPath: String, bytes: ByteArray): ImportedImage {
        val dir = File(context.filesDir, "docx-images").apply { mkdirs() }
        val extension = targetPath.substringAfterLast('.', "png").lowercase()
        val fileName = "docx_${System.currentTimeMillis()}_${marker.filter { it.isDigit() }}.$extension"
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        return ImportedImage(
            marker = marker,
            uri = file.absolutePath,
            fileName = fileName,
        )
    }

    private fun XmlPullParser.findRelationshipId(): String? {
        for (index in 0 until attributeCount) {
            val name = getAttributeName(index)?.substringAfter(':')
            if (name == "embed" || name == "link") {
                return getAttributeValue(index)
            }
        }
        return null
    }

    private data class ParsedDocument(
        val text: String,
        val markerByRelationship: Map<String, String>,
    )
}
