package com.smartkeyboard.ai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Stores Smart Clipboard Formatter rules and settings.
 *
 * Deliberately a separate database file from ClipboardDbHelper's
 * clipboard_history.db, so nothing about this Phase 3 feature can ever
 * touch or risk the already-working clipboard history storage.
 */
class FormatterDbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    private val appContext = context.applicationContext

    companion object {
        private const val DB_NAME = "formatter_rules.db"
        private const val DB_VERSION = 1
        private const val TABLE = "rules"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              type TEXT NOT NULL,
              enabled INTEGER NOT NULL DEFAULT 1,
              priority INTEGER NOT NULL DEFAULT 0,
              phone_only INTEGER NOT NULL DEFAULT 0,
              config TEXT NOT NULL DEFAULT '{}'
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_rules_priority ON $TABLE(priority)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    // ---------- Settings (master toggles) ----------

    private fun prefs() =
        appContext.getSharedPreferences("formatter_settings", Context.MODE_PRIVATE)

    fun isFormatterEnabled(): Boolean = prefs().getBoolean("formatter_enabled", false)
    fun setFormatterEnabled(enabled: Boolean) {
        prefs().edit().putBoolean("formatter_enabled", enabled).apply()
    }

    fun isAutoFormatEnabled(): Boolean = prefs().getBoolean("auto_format_enabled", false)
    fun setAutoFormatEnabled(enabled: Boolean) {
        prefs().edit().putBoolean("auto_format_enabled", enabled).apply()
    }

    fun isSmartPhoneModeEnabled(): Boolean = prefs().getBoolean("smart_phone_mode_enabled", false)
    fun setSmartPhoneModeEnabled(enabled: Boolean) {
        prefs().edit().putBoolean("smart_phone_mode_enabled", enabled).apply()
    }

    // ---------- Rules CRUD ----------

    fun getAllRulesSorted(): List<FormatRule> = safeQuery(
        "SELECT * FROM $TABLE ORDER BY priority ASC", emptyArray()
    )

    fun getEnabledRulesSorted(): List<FormatRule> = safeQuery(
        "SELECT * FROM $TABLE WHERE enabled = 1 ORDER BY priority ASC", emptyArray()
    )

    fun insertRule(rule: FormatRule): Long {
        return try {
            val values = ContentValues().apply {
                put("name", rule.name)
                put("type", rule.type)
                put("enabled", if (rule.enabled) 1 else 0)
                put("priority", rule.priority)
                put("phone_only", if (rule.phoneOnly) 1 else 0)
                put("config", rule.config)
            }
            writableDatabase.insert(TABLE, null, values)
        } catch (e: Exception) {
            -1
        }
    }

    fun updateRule(rule: FormatRule) = safeExec {
        val values = ContentValues().apply {
            put("name", rule.name)
            put("type", rule.type)
            put("enabled", if (rule.enabled) 1 else 0)
            put("priority", rule.priority)
            put("phone_only", if (rule.phoneOnly) 1 else 0)
            put("config", rule.config)
        }
        writableDatabase.update(TABLE, values, "id = ?", arrayOf(rule.id.toString()))
    }

    fun setRuleEnabled(id: Long, enabled: Boolean) = safeExec {
        writableDatabase.execSQL(
            "UPDATE $TABLE SET enabled = ? WHERE id = ?",
            arrayOf(if (enabled) 1 else 0, id)
        )
    }

    fun deleteRule(id: Long) = safeExec {
        writableDatabase.execSQL("DELETE FROM $TABLE WHERE id = ?", arrayOf(id))
    }

    // ---------- Helpers ----------

    private fun safeExec(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // a rule-storage failure must never crash the keyboard
        }
    }

    private fun safeQuery(sql: String, args: Array<String>): List<FormatRule> {
        val results = mutableListOf<FormatRule>()
        try {
            readableDatabase.rawQuery(sql, args).use { c ->
                val idxId = c.getColumnIndexOrThrow("id")
                val idxName = c.getColumnIndexOrThrow("name")
                val idxType = c.getColumnIndexOrThrow("type")
                val idxEnabled = c.getColumnIndexOrThrow("enabled")
                val idxPriority = c.getColumnIndexOrThrow("priority")
                val idxPhoneOnly = c.getColumnIndexOrThrow("phone_only")
                val idxConfig = c.getColumnIndexOrThrow("config")
                while (c.moveToNext()) {
                    results.add(
                        FormatRule(
                            id = c.getLong(idxId),
                            name = c.getString(idxName),
                            type = c.getString(idxType),
                            enabled = c.getInt(idxEnabled) == 1,
                            priority = c.getInt(idxPriority),
                            phoneOnly = c.getInt(idxPhoneOnly) == 1,
                            config = c.getString(idxConfig)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // return whatever was collected so far
        }
        return results
    }
}
