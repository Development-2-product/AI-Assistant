package com.iamode.app.core.diagnostics

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last problems (and successes) on this phone, shown in Connection check and included in the
 * report you can copy. Records reasons only, never message text, names or numbers.
 */
@Singleton
class DiagnosticsLog @Inject constructor(@ApplicationContext context: Context) {

    data class Entry(val time: Long, val area: String, val ok: Boolean, val detail: String)

    private val prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
    private val lock = Any()

    fun record(area: String, ok: Boolean, detail: String) = synchronized(lock) {
        val list = load().toMutableList()
        list += Entry(System.currentTimeMillis(), area, ok, detail.take(300))
        val trimmed = list.takeLast(MAX)
        val arr = JSONArray()
        trimmed.forEach { e ->
            arr.put(JSONObject().put("t", e.time).put("a", e.area).put("ok", e.ok).put("d", e.detail))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun entries(): List<Entry> = synchronized(lock) { load().reversed() }

    fun clear() = synchronized(lock) { prefs.edit().remove(KEY).apply() }

    private fun load(): List<Entry> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.getLong("t"), o.getString("a"), o.getBoolean("ok"), o.getString("d"))
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "entries"
        const val MAX = 40
    }
}
