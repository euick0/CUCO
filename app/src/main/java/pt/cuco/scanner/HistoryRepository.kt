package pt.cuco.scanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val id: Long,
    val serial: String,
    val ctime: String,
    val usage: String,
    val timestamp: Long,
    val status: String,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_SUBMITTED = "submitted"
    }
}

object HistoryRepository {

    private const val PREFS_NAME = "cuco_history"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 100

    fun loadAll(context: Context): List<HistoryEntry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                HistoryEntry(
                    id = obj.getLong("id"),
                    serial = obj.getString("serial"),
                    ctime = obj.getString("ctime"),
                    usage = obj.getString("usage"),
                    timestamp = obj.getLong("timestamp"),
                    status = obj.getString("status"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addEntry(context: Context, entry: HistoryEntry) {
        val current = loadAll(context).toMutableList()
        current.add(0, entry)
        val capped = if (current.size > MAX_ENTRIES) current.take(MAX_ENTRIES) else current
        save(context, capped)
    }

    fun updateStatus(context: Context, id: Long, status: String) {
        val updated = loadAll(context).map { entry ->
            if (entry.id == id) entry.copy(status = status) else entry
        }
        save(context, updated)
    }

    /** Updates the field values of an existing entry (used when the user edits them). */
    fun updateFields(context: Context, id: Long, serial: String, ctime: String, usage: String) {
        val updated = loadAll(context).map { entry ->
            if (entry.id == id) {
                entry.copy(serial = serial, ctime = ctime, usage = usage)
            } else {
                entry
            }
        }
        save(context, updated)
    }

    fun deleteEntry(context: Context, id: Long) {
        save(context, loadAll(context).filter { it.id != id })
    }

    private fun save(context: Context, entries: List<HistoryEntry>) {
        val arr = JSONArray()
        entries.forEach { entry ->
            arr.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("serial", entry.serial)
                    put("ctime", entry.ctime)
                    put("usage", entry.usage)
                    put("timestamp", entry.timestamp)
                    put("status", entry.status)
                }
            )
        }
        prefs(context).edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
