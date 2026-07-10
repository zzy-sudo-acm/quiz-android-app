package com.zzy.quizforge.util.document

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Document IR 可读性调试输出。
 *
 * 用途：开发者在测试和调试中直接检查某份 DOCX 被解析成什么结构。
 * 不在正式 UI 中使用。
 */
object DocumentDebugDump {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    /** 生成可读 JSON 字符串。 */
    fun toJson(document: StructuredDocument): String {
        val root = JsonObject()

        // blocks
        val blocksArr = JsonArray()
        for (block in document.blocks) {
            blocksArr.add(blockToJson(block))
        }
        root.add("blocks", blocksArr)

        // media
        val mediaArr = JsonArray()
        for (m in document.media) {
            val mj = JsonObject()
            mj.addProperty("mediaId", m.mediaId.take(12) + "...")
            mj.addProperty("relationshipId", m.relationshipId)
            mj.addProperty("fileName", m.fileName)
            mj.addProperty("localPath", m.localPath)
            mj.addProperty("contentType", m.contentType)
            mediaArr.add(mj)
        }
        root.add("media", mediaArr)

        // numbering definitions
        val numObj = JsonObject()
        for ((numId, def) in document.numberingDefinitions) {
            val defObj = JsonObject()
            defObj.addProperty("abstractNumId", def.abstractNumId)
            val levelsObj = JsonObject()
            for ((lvl, nl) in def.levels) {
                val lvlObj = JsonObject()
                nl.numFmt?.let { lvlObj.addProperty("numFmt", it) }
                nl.lvlText?.let { lvlObj.addProperty("lvlText", it) }
                nl.start?.let { lvlObj.addProperty("start", it) }
                levelsObj.add(lvl.toString(), lvlObj)
            }
            defObj.add("levels", levelsObj)
            numObj.add(numId, defObj)
        }
        root.add("numberingDefinitions", numObj)

        // warnings
        val warnArr = JsonArray()
        for (w in document.warnings) {
            val wj = JsonObject()
            wj.addProperty("level", w.level.name)
            wj.addProperty("message", w.message)
            warnArr.add(wj)
        }
        root.add("warnings", warnArr)

        root.addProperty("blockCount", document.blocks.size)
        root.addProperty("mediaCount", document.media.size)

        return gson.toJson(root)
    }

    private fun blockToJson(block: DocumentBlock): JsonObject {
        return when (block) {
            is ParagraphBlock -> {
                JsonObject().apply {
                    addProperty("type", "paragraph")
                    addProperty("sourceId", block.sourceId)
                    addProperty("sourceOrder", block.sourceOrder)
                    block.numbering?.let {
                        val n = JsonObject()
                        n.addProperty("numId", it.numId)
                        n.addProperty("level", it.level)
                        add("numbering", n)
                    }
                    val contentArr = JsonArray()
                    for (c in block.content) {
                        contentArr.add(inlineToJson(c))
                    }
                    add("content", contentArr)
                }
            }
            is TableBlock -> {
                JsonObject().apply {
                    addProperty("type", "table")
                    addProperty("sourceId", block.sourceId)
                    addProperty("sourceOrder", block.sourceOrder)
                    addProperty("rowCount", block.rows.size)
                    val rowsArr = JsonArray()
                    for ((ri, row) in block.rows.withIndex()) {
                        val rowObj = JsonObject()
                        rowObj.addProperty("cellCount", row.cells.size)
                        val cellsArr = JsonArray()
                        for ((ci, cell) in row.cells.withIndex()) {
                            val cellObj = JsonObject()
                            cellObj.addProperty("index", ci)
                            val cellBlocks = JsonArray()
                            for (cb in cell.blocks) {
                                cellBlocks.add(blockToJson(cb))
                            }
                            cellObj.add("blocks", cellBlocks)
                            cellsArr.add(cellObj)
                        }
                        rowObj.add("cells", cellsArr)
                        rowsArr.add(rowObj)
                    }
                    add("rows", rowsArr)
                }
            }
        }
    }

    private fun inlineToJson(inline: InlineContent): JsonObject {
        return when (inline) {
            is TextContent -> JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", inline.text)
            }
            is ImageContent -> JsonObject().apply {
                addProperty("type", "image")
                addProperty("mediaId", inline.mediaId)
            }
            is LineBreakContent -> JsonObject().apply {
                addProperty("type", "lineBreak")
            }
        }
    }

    /** 生成单行摘要（用于快速日志）。 */
    fun summary(document: StructuredDocument): String {
        val parts = mutableListOf<String>()
        parts += "${document.blocks.size} blocks"
        parts += "${document.media.size} media"
        parts += "${document.numberingDefinitions.size} numbering defs"
        if (document.warnings.isNotEmpty()) {
            parts += "${document.warnings.size} warnings"
        }
        return "StructuredDocument(${parts.joinToString(", ")})"
    }
}
