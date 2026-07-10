package com.zzy.quizforge.util.document

import org.xmlpull.v1.XmlPullParser
import java.io.Reader

/**
 * 为 JVM 单元测试提供的最小 XmlPullParser 实现。
 *
 * 支持 OOXML 测试所需的核心 XmlPullParser API。
 * 基于简单字符串 tokenization — 足够处理受控的测试 fixture。
 */
class TestXmlPullParser(reader: Reader) : XmlPullParser {

    companion object {
        // XmlPullParser 接口常量 — JVM 测试类路径上无 android.jar，需本地定义
        const val START_DOCUMENT = 0
        const val END_DOCUMENT = 1
        const val START_TAG = 2
        const val END_TAG = 3
        const val TEXT = 4
        const val CDSECT = 5
        const val ENTITY_REF = 6
        const val IGNORABLE_WHITESPACE = 7
        const val PROCESSING_INSTRUCTION = 8
        const val COMMENT = 9
        const val DOCDECL = 10
    }

    private val tokens: List<Token>
    private var pos = -1

    init {
        val raw = reader.readText()
        tokens = tokenize(raw)
        reader.close()
    }

    // ═══════════════════════════════════════════════════════
    // Core API
    // ═══════════════════════════════════════════════════════

    override fun next(): Int {
        pos++
        return if (pos < tokens.size) tokens[pos].type else END_DOCUMENT
    }

    override fun nextText(): String {
        var t = next()
        while (t != TEXT && t != END_DOCUMENT) t = next()
        return if (t == TEXT) tokens[pos].text else ""
    }

    override fun getEventType(): Int =
        if (pos in tokens.indices) tokens[pos].type
        else if (pos < 0) START_DOCUMENT
        else END_DOCUMENT

    override fun getName(): String = currentToken()?.name ?: ""

    override fun getText(): String = currentToken()?.text ?: ""

    override fun getAttributeValue(namespace: String?, name: String): String {
        val key = name?.substringAfter(':') ?: name ?: ""
        return currentToken()?.attrs?.get(key) ?: ""
    }

    override fun getAttributeName(index: Int): String =
        currentToken()?.attrKeys?.getOrNull(index) ?: ""

    override fun getAttributeValue(index: Int): String {
        val key = currentToken()?.attrKeys?.getOrNull(index) ?: return ""
        return currentToken()?.attrs?.get(key) ?: ""
    }

    override fun getAttributeCount(): Int = currentToken()?.attrKeys?.size ?: 0

    // ═══════════════════════════════════════════════════════
    // Stubs
    // ═══════════════════════════════════════════════════════

    override fun getNamespace(): String = ""
    override fun getPositionDescription(): String = "pos $pos"
    override fun getLineNumber(): Int = 0
    override fun getColumnNumber(): Int = 0
    override fun require(type: Int, namespace: String?, name: String?) {}
    override fun nextToken(): Int = next()
    override fun isWhitespace(): Boolean = false
    override fun getPrefix(): String = ""
    override fun getInputEncoding(): String = "UTF-8"
    override fun defineEntityReplacementText(entityName: String?, replacementText: String?) {}
    override fun getNamespaceCount(depth: Int): Int = 0
    override fun getNamespaceUri(pos: Int): String = ""
    override fun getAttributePrefix(index: Int): String = ""
    override fun getAttributeType(index: Int): String = "CDATA"
    override fun isAttributeDefault(index: Int): Boolean = false
    override fun getAttributeNamespace(index: Int): String = ""
    override fun setInput(reader: Reader?) {}
    override fun setInput(inputStream: java.io.InputStream?, inputEncoding: String?) {}
    override fun getNamespace(namespace: String?): String = ""
    override fun getProperty(name: String?): Any? = null
    override fun setProperty(name: String?, value: Any?) {}
    override fun getFeature(name: String?): Boolean = false
    override fun setFeature(name: String?, state: Boolean) {}
    override fun getNamespacePrefix(pos: Int): String = ""
    override fun getDepth(): Int = 0
    override fun getTextCharacters(into: IntArray?): CharArray = getText().toCharArray()
    override fun isEmptyElementTag(): Boolean = false
    override fun nextTag(): Int {
        var t = next()
        while (t == TEXT || t == IGNORABLE_WHITESPACE) t = next()
        return t
    }

    // ═══════════════════════════════════════════════════════
    // Implementation
    // ═══════════════════════════════════════════════════════

    private fun currentToken(): Token? =
        if (pos in tokens.indices) tokens[pos] else null

    private data class Token(
        val type: Int,
        val name: String = "",
        val text: String = "",
        val attrs: Map<String, String> = emptyMap(),
        val attrKeys: List<String> = emptyList(),
    )

    private fun tokenize(xml: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val len = xml.length

        while (i < len) {
            when {
                xml[i] == '<' && i + 1 < len && xml[i + 1] == '/' -> {
                    // End tag
                    val end = xml.indexOf('>', i)
                    if (end < 0) break
                    val name = extractName(xml, i + 2, end)
                    tokens += Token(END_TAG, name = name)
                    i = end + 1
                }
                xml[i] == '<' && i + 1 < len && xml[i + 1] == '?' -> {
                    val end = xml.indexOf("?>", i)
                    if (end < 0) break
                    i = end + 2
                }
                xml[i] == '<' && i + 1 < len && xml[i + 1] == '!' -> {
                    val end = xml.indexOf('>', i)
                    if (end < 0) break
                    i = end + 1
                }
                xml[i] == '<' -> {
                    // Start tag or self-closing
                    val end = xml.indexOf('>', i)
                    if (end < 0) break
                    val selfClose = xml[end - 1] == '/'
                    val tagEnd = if (selfClose) end - 1 else end
                    val name = extractName(xml, i + 1, tagEnd)
                    val attrs = parseAttrs(xml, i + 1 + name.length, tagEnd)
                    tokens += Token(START_TAG, name = name, attrs = attrs, attrKeys = attrs.keys.toList())
                    if (selfClose) tokens += Token(END_TAG, name = name)
                    i = end + 1
                }
                else -> {
                    // Text
                    val start = i
                    while (i < len && xml[i] != '<') i++
                    val text = xml.substring(start, i).trim()
                    if (text.isNotEmpty()) {
                        tokens += Token(TEXT, text = text)
                    }
                }
            }
        }

        val result = mutableListOf<Token>()
        result += Token(START_DOCUMENT)
        result += tokens
        result += Token(END_DOCUMENT)
        return result
    }

    private fun extractName(s: String, start: Int, end: Int): String {
        val sub = s.substring(start, minOf(end, s.length)).trim()
        val space = sub.indexOf(' ')
        return if (space > 0) sub.substring(0, space) else sub
    }

    private fun parseAttrs(s: String, start: Int, end: Int): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var i = start.coerceAtMost(s.length)
        val limit = end.coerceAtMost(s.length)

        while (i < limit) {
            while (i < limit && s[i].isWhitespace()) i++
            if (i >= limit) break
            val eq = s.indexOf('=', i)
            if (eq < 0 || eq >= limit) break
            val attrName = s.substring(i, eq).trim()
            i = eq + 1
            while (i < limit && s[i].isWhitespace()) i++
            if (i >= limit) break
            val quote = s[i]
            if (quote != '"' && quote != '\'') break
            i++
            val valEnd = s.indexOf(quote, i)
            if (valEnd < 0 || valEnd >= limit) break
            // 去掉命名空间前缀以匹配 XmlPullParser.getAttributeValue(null, localName) 行为
            val localName = attrName.substringAfter(':')
            result[localName] = s.substring(i, valEnd)
            i = valEnd + 1
        }
        return result
    }
}
