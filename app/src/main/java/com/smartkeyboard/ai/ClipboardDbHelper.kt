package com.smartkeyboard.ai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * All clipboard history persistence lives here. Every public method is
 * wrapped so a database problem can never crash the keyboard - callers
 * get an empty/no-op result instead of an exception propagating up.
 */
class ClipboardDbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    private val appContext = context.applicationContext

    companion object {
        private const val DB_NAME = "clipboard_history.db"
        private const val DB_VERSION = 1
        private const val TABLE = "clips"
        private const val DEFAULT_MAX_SIZE = 1000
        private const val DEFAULT_QUERY_LIMIT = 200
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              content TEXT NOT NULL,
              timestamp INTEGER NOT NULL,
              pinned INTEGER NOT NULL DEFAULT 0,
              favorite INTEGER NOT NULL DEFAULT 0,
              category TEXT NOT NULL DEFAULT 'OTHERS'
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_clips_timestamp ON $TABLE(timestamp)")
        db.execSQL("CREATE INDEX idx_clips_pinned ON $TABLE(pinned)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    // ---------- Settings (max size / auto-delete hours) ----------

    private fun prefs() =
        appContext.getSharedPreferences("clipboard_settings", Context.MODE_PRIVATE)

    fun getMaxHistorySize(): Int = prefs().getInt("max_size", DEFAULT_MAX_SIZE)

    fun setMaxHistorySize(size: Int) {
        val clamped = size.coerceIn(50, 1000)
        prefs().edit().putInt("max_size", clamped).apply()
    }

    fun getAutoDeleteHours(): Int = prefs().getInt("auto_delete_hours", 0)

    fun setAutoDeleteHours(hours: Int) {
        prefs().edit().putInt("auto_delete_hours", hours).apply()
    }

    // ---------- Insert / capture ----------

    fun insertClip(rawText: String): Long {
        return try {
            val text = rawText.take(20000) // guard against absurdly huge clips
            if (text.isBlank()) return -1

            val db = writableDatabase

            // avoid inserting an exact duplicate of the most recent entry
            db.rawQuery(
                "SELECT content FROM $TABLE ORDER BY timestamp DESC LIMIT 1", null
            ).use { c ->
                if (c.moveToFirst() && c.getString(0) == text) return -1
            }

            val values = ContentValues().apply {
                put("content", text)
                put("timestamp", System.currentTimeMillis())
                put("pinned", 0)
                put("favorite", 0)
                put("category", ClipboardCategorizer.categorize(text))
            }
            val id = db.insert(TABLE, null, values)

            trimToMaxSize()
            applyAutoDelete()

            id
        } catch (e: Exception) {
            -1
        }
    }

    // ---------- Reads ----------

    fun getRecent(limit: Int = 5): List<ClipItem> = safeQuery(
        "SELECT * FROM $TABLE ORDER BY timestamp DESC LIMIT ?",
        arrayOf(limit.toString())
    )

    fun getByCategory(category: String, limit: Int = DEFAULT_QUERY_LIMIT): List<ClipItem> {
        if (category == ClipboardCategorizer.ALL) {
            return safeQuery(
                "SELECT * FROM $TABLE ORDER BY timestamp DESC LIMIT ?",
                arrayOf(limit.toString())
            )
        }
        return safeQuery(
            "SELECT * FROM $TABLE WHERE category = ? ORDER BY timestamp DESC LIMIT ?",
            arrayOf(category, limit.toString())
        )
    }

    fun search(query: String, category: String, limit: Int = DEFAULT_QUERY_LIMIT): List<ClipItem> {
        val like = "%${query.trim()}%"
        return if (category == ClipboardCategorizer.ALL) {
            safeQuery(
                "SELECT * FROM $TABLE WHERE content LIKE ? ORDER BY timestamp DESC LIMIT ?",
                arrayOf(like, limit.toString())
            )
        } else {
            safeQuery(
                "SELECT * FROM $TABLE WHERE content LIKE ? AND category = ? ORDER BY timestamp DESC LIMIT ?",
                arrayOf(like, category, limit.toString())
            )
        }
    }

    fun getFavorites(limit: Int = DEFAULT_QUERY_LIMIT): List<ClipItem> = safeQuery(
        "SELECT * FROM $TABLE WHERE favorite = 1 ORDER BY timestamp DESC LIMIT ?",
        arrayOf(limit.toString())
    )

    // ---------- Mutations ----------

    fun setPinned(id: Long, pinned: Boolean) = safeExec {
        writableDatabase.execSQL(
            "UPDATE $TABLE SET pinned = ? WHERE id = ?",
            arrayOf(if (pinned) 1 else 0, id)
        )
    }

    fun setFavorite(id: Long, favorite: Boolean) = safeExec {
        writableDatabase.execSQL(
            "UPDATE $TABLE SET favorite = ? WHERE id = ?",
            arrayOf(if (favorite) 1 else 0, id)
        )
    }

    fun deleteClip(id: Long) = safeExec {
        writableDatabase.execSQL("DELETE FROM $TABLE WHERE id = ?", arrayOf(id))
    }

    /** Explicit user action - wipes everything, including pinned items. */
    fun clearAll() = safeExec {
        writableDatabase.execSQL("DELETE FROM $TABLE")
    }

    // ---------- Automatic housekeeping (pinned items are always protected) ----------

    private fun trimToMaxSize() = safeExec {
        val max = getMaxHistorySize()
        writableDatabase.execSQL(
            """
            DELETE FROM $TABLE WHERE pinned = 0 AND id NOT IN (
              SELECT id FROM $TABLE WHERE pinned = 0 ORDER BY timestamp DESC LIMIT ?
            )
            """.trimIndent(),
            arrayOf(max)
        )
    }

    private fun applyAutoDelete() = safeExec {
        val hours = getAutoDeleteHours()
        if (hours <= 0) return@safeExec
        val cutoff = System.currentTimeMillis() - hours * 3600_000L
        writableDatabase.execSQL(
            "DELETE FROM $TABLE WHERE pinned = 0 AND timestamp < ?",
            arrayOf(cutoff)
        )
    }

    // ---------- Backup / Restore ----------
    // Written to app-specific external storage - no runtime permission or
    // file picker needed on API 24+.

    fun backupFile(): File = File(appContext.getExternalFilesDir(null), "clipboard_backup.json")

    fun exportBackup(): Boolean {
        return try {
            val all = safeQuery("SELECT * FROM $TABLE ORDER BY timestamp DESC", emptyArray())
            val arr = JSONArray()
            for (item in all) {
                val obj = JSONObject()
                obj.put("content", item.content)
                obj.put("timestamp", item.timestamp)
                obj.put("pinned", item.pinned)
                obj.put("favorite", item.favorite)
                obj.put("category", item.category)
                arr.put(obj)
            }
            backupFile().writeText(arr.toString())
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Returns number of items imported, or -1 on failure. */
    fun importBackup(): Int {
        return try {
            val file = backupFile()
            if (!file.exists()) return -1
            val arr = JSONArray(file.readText())
            val db = writableDatabase
            var count = 0
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val values = ContentValues().apply {
                    put("content", obj.getString("content"))
                    put("timestamp", obj.getLong("timestamp"))
                    put("pinned", if (obj.optBoolean("pinned")) 1 else 0)
                    put("favorite", if (obj.optBoolean("favorite")) 1 else 0)
                    put("category", obj.optString("category", ClipboardCategorizer.OTHERS))
                }
                db.insert(TABLE, null, values)
                count++
            }
            count
        } catch (e: Exception) {
            -1
        }
    }

    // ---------- Helpers ----------

    private fun safeExec(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // swallow - a housekeeping failure must never crash the keyboard
        }
    }

    private fun safeQuery(sql: String, args: Array<String>): List<ClipItem> {
        val results = mutableListOf<ClipItem>()
        try {
            readableDatabase.rawQuery(sql, args).use { c ->
                val idxId = c.getColumnIndexOrThrow("id")
                val idxContent = c.getColumnIndexOrThrow("content")
                val idxTimestamp = c.getColumnIndexOrThrow("timestamp")
                val idxPinned = c.getColumnIndexOrThrow("pinned")
                val idxFavorite = c.getColumnIndexOrThrow("favorite")
                val idxCategory = c.getColumnIndexOrThrow("category")
                while (c.moveToNext()) {
                    results.add(
                        ClipItem(
                            id = c.getLong(idxId),
                            content = c.getString(idxContent),
                            timestamp = c.getLong(idxTimestamp),
                            pinned = c.getInt(idxPinned) == 1,
                            favorite = c.getInt(idxFavorite) == 1,
                            category = c.getString(idxCategory)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // return whatever was collected so far (likely empty) rather than crash
        }
        return results
    }
}
