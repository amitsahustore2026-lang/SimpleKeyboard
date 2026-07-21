package com.smartkeyboard.ai

import android.content.Context

/**
 * Stores which optional toolbar items are shown and in what order.
 * Clipboard and Paste are permanent, locked toolbar items and are never
 * represented here - they are always rendered first, unconditionally.
 */
class ToolbarPrefs(context: Context) {

    private val appContext = context.applicationContext

    companion object {
        const val VOICE = "VOICE"
        const val EMOJI = "EMOJI"
        const val GIF = "GIF"
        const val TRANSLATE = "TRANSLATE"
        const val UNDO = "UNDO"
        const val REDO = "REDO"
        const val SETTINGS = "SETTINGS"

        val DEFAULT_ORDER = listOf(VOICE, EMOJI, GIF, TRANSLATE, UNDO, REDO, SETTINGS)
    }

    private fun prefs() = appContext.getSharedPreferences("toolbar_settings", Context.MODE_PRIVATE)

    fun getOrder(): List<String> {
        val stored = prefs().getString("order", null) ?: return DEFAULT_ORDER
        val list = stored.split(",").filter { it.isNotBlank() }
        // Guard against corrupted/partial data - fall back to default if it
        // doesn't contain exactly the known items.
        return if (list.toSet() == DEFAULT_ORDER.toSet()) list else DEFAULT_ORDER
    }

    fun setOrder(order: List<String>) {
        prefs().edit().putString("order", order.joinToString(",")).apply()
    }

    fun getHidden(): Set<String> {
        return prefs().getString("hidden", "")?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    fun isVisible(item: String): Boolean = item !in getHidden()

    fun setVisible(item: String, visible: Boolean) {
        val hidden = getHidden().toMutableSet()
        if (visible) hidden.remove(item) else hidden.add(item)
        prefs().edit().putString("hidden", hidden.joinToString(",")).apply()
    }

    fun moveUp(item: String) {
        val order = getOrder().toMutableList()
        val idx = order.indexOf(item)
        if (idx > 0) {
            order.removeAt(idx)
            order.add(idx - 1, item)
            setOrder(order)
        }
    }

    fun moveDown(item: String) {
        val order = getOrder().toMutableList()
        val idx = order.indexOf(item)
        if (idx in 0 until order.size - 1) {
            order.removeAt(idx)
            order.add(idx + 1, item)
            setOrder(order)
        }
    }

    fun labelFor(item: String): String = when (item) {
        VOICE -> "🎤 Voice"
        EMOJI -> "😊 Emoji"
        GIF -> "🎞 GIF"
        TRANSLATE -> "🌍 Translate"
        UNDO -> "↶ Undo"
        REDO -> "↷ Redo"
        SETTINGS -> "⚙ Settings"
        else -> item
    }
}
