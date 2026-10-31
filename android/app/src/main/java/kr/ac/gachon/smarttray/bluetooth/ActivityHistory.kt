package kr.ac.gachon.smarttray.bluetooth

import org.json.JSONArray
import org.json.JSONObject

/** Bounded, newest-first history. The repository supplies private on-device storage. */
internal class ActivityHistory(read: () -> String?, private val write: (String) -> Boolean) {
    var storageError = false
        private set
    var entries: JSONArray = try { decode(read()) } catch (_: Exception) {
        storageError = true
        JSONArray()
    }
        private set

    fun append(event: JSONObject) {
        val next = JSONArray().put(event)
        for (index in 0 until minOf(entries.length(), LIMIT - 1)) next.put(entries.getJSONObject(index))
        entries = next
        // Keep the in-memory event if storage fails, and expose the failure to the UI.
        storageError = !runCatching { write(entries.toString()) }.getOrDefault(false)
    }

    companion object {
        const val LIMIT = 200
        private val types = setOf("placed", "removed", "door", "notification", "device")
        private fun decode(raw: String?): JSONArray {
            val source = runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
            val result = JSONArray()
            for (index in 0 until source.length()) {
                if (result.length() == LIMIT) break
                val event = source.optJSONObject(index) ?: continue
                val id = event.opt("id") as? String ?: continue
                val title = event.opt("title") as? String ?: continue
                val type = event.opt("type") as? String ?: continue
                val time = event.opt("time") as? Number ?: continue
                if (id.isBlank() || title.isBlank() || type !in types || !time.toDouble().isFinite()
                    || time.toDouble() <= 0 || time.toDouble() > 8_640_000_000_000_000.0) continue
                val trayId = (event.opt("trayId") as? Number)?.toDouble()?.takeIf { it == 1.0 || it == 2.0 }?.toInt()
                result.put(JSONObject().put("id", id).put("title", title).put("type", type).put("time", time.toLong())
                    .put("description", event.opt("description") as? String ?: "").put("trayId", trayId ?: JSONObject.NULL))
            }
            return result
        }
    }
}
