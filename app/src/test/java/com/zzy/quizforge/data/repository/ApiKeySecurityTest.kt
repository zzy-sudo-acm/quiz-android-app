package com.zzy.quizforge.data.repository

import org.junit.Assert.*
import org.junit.Test

/**
 * 验证 API Key 安全存储的核心决策逻辑。
 *
 * SettingsStore 因 Android Context 依赖无法直接 JVM 测试，
 * 这里测试其内部使用的纯逻辑函数。
 *
 * 生产代码中 SettingsStore.getApiKey() 实现：
 *   if (isEncrypted) prefs.getString(KEY, "").orEmpty() else ""
 *
 * SettingsStore.init 行为：
 *   if (!isEncrypted && prefs.contains(KEY)) prefs.edit().remove(KEY).apply()
 */
class ApiKeySecurityTest {

    // ═══════════════════════════════════════════════════════════
    // getApiKey() 的纯逻辑等价函数
    // ═══════════════════════════════════════════════════════════

    private fun resolveApiKey(isEncrypted: Boolean, storedValue: String?): String =
        if (isEncrypted) storedValue.orEmpty() else ""

    // ── isEncrypted=false 时，即使存储中有旧 Key 也不返回 ──
    @Test
    fun `isEncrypted false returns empty even if old plaintext key exists`() {
        val result = resolveApiKey(isEncrypted = false, storedValue = "sk-old-plaintext-key")
        assertEquals("", result)
    }

    @Test
    fun `isEncrypted false returns empty when stored value is null`() {
        val result = resolveApiKey(isEncrypted = false, storedValue = null)
        assertEquals("", result)
    }

    // ── isEncrypted=true 时正常返回 ──
    @Test
    fun `isEncrypted true returns stored value`() {
        val result = resolveApiKey(isEncrypted = true, storedValue = "sk-encrypted-key")
        assertEquals("sk-encrypted-key", result)
    }

    @Test
    fun `isEncrypted true returns empty when nothing stored`() {
        val result = resolveApiKey(isEncrypted = true, storedValue = null)
        assertEquals("", result)
    }

    @Test
    fun `isEncrypted true returns empty when stored empty string`() {
        val result = resolveApiKey(isEncrypted = true, storedValue = "")
        assertEquals("", result)
    }

    // ═══════════════════════════════════════════════════════════
    // 历史 Key 清理逻辑：init 中 shouldClean 的纯逻辑等价
    // ═══════════════════════════════════════════════════════════

    private fun shouldCleanLegacyKey(isEncrypted: Boolean, hasLegacyKey: Boolean): Boolean =
        !isEncrypted && hasLegacyKey

    @Test
    fun `history plaintext key cleaned when isEncrypted false and legacy key exists`() {
        assertTrue(shouldCleanLegacyKey(isEncrypted = false, hasLegacyKey = true))
    }

    @Test
    fun `no cleanup when isEncrypted true regardless of legacy key`() {
        assertFalse(shouldCleanLegacyKey(isEncrypted = true, hasLegacyKey = true))
        assertFalse(shouldCleanLegacyKey(isEncrypted = true, hasLegacyKey = false))
    }

    @Test
    fun `no cleanup when no legacy key exists`() {
        assertFalse(shouldCleanLegacyKey(isEncrypted = false, hasLegacyKey = false))
    }
}
