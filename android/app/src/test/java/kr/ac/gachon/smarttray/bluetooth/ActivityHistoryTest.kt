package kr.ac.gachon.smarttray.bluetooth

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ActivityHistoryTest {
    @get:Rule val folder = TemporaryFolder()
    private fun event(index: Int) = JSONObject().put("id", "event-$index").put("time", 1_700_000_000_000L + index)
        .put("type", "placed").put("title", "1번 트레이에 물건이 놓였어요").put("description", "트레이 01").put("trayId", 1)
    private fun open(file: File) = ActivityHistory({ file.readText() }, { file.writeText(it); true })

    @Test fun newInstanceRestoresNewestTwoHundredFromDiskWithoutPreferencesOrSensors() {
        val file = folder.newFile("activities.json").apply { writeText("[]") }
        val first = open(file)
        repeat(205) { first.append(event(it)) }
        assertFalse(first.storageError)
        assertEquals(200, JSONArray(file.readText()).length())
        val restored = open(file)
        assertEquals(200, restored.entries.length())
        assertEquals("event-204", restored.entries.getJSONObject(0).getString("id"))
        assertEquals("event-5", restored.entries.getJSONObject(199).getString("id"))
        assertEquals(1_700_000_000_204L, restored.entries.getJSONObject(0).getLong("time"))
        restored.append(event(205))
        assertEquals("event-6", open(file).entries.getJSONObject(199).getString("id"))
    }
    @Test fun damagedAndOversizedHistoryCannotBreakRestoration() {
        assertEquals(0, ActivityHistory({ "broken-json" }, { true }).entries.length())
        val raw = JSONArray().put(JSONObject().put("type", "invalid")).put(JSONObject.NULL)
        repeat(205) { raw.put(event(it)) }
        val restored = ActivityHistory({ raw.toString() }, { true })
        assertEquals(200, restored.entries.length())
        assertEquals("event-0", restored.entries.getJSONObject(0).getString("id"))
        assertEquals("event-199", restored.entries.getJSONObject(199).getString("id"))
        val invalidTray = event(1).put("trayId", 999)
        assertTrue(ActivityHistory({ JSONArray().put(invalidTray).toString() }, { true }).entries.getJSONObject(0).isNull("trayId"))
    }
    @Test fun failedWritesKeepSessionHistoryAndCanRecoverOnNextEvent() {
        var stored: String? = null
        var writable = false
        val history = ActivityHistory({ stored }, { if (writable) { stored = it; true } else false })
        history.append(event(1))
        assertTrue(history.storageError); assertEquals(1, history.entries.length()); assertNull(stored)
        writable = true; history.append(event(2))
        assertFalse(history.storageError)
        assertEquals(2, ActivityHistory({ stored }, { true }).entries.length())
        val unavailable = ActivityHistory({ throw IllegalStateException("read blocked") }, { throw IllegalStateException("write blocked") })
        assertTrue(unavailable.storageError); unavailable.append(event(1)); assertTrue(unavailable.storageError)
    }
}
