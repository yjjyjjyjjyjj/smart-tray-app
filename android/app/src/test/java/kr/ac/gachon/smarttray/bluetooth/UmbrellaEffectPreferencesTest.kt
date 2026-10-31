package kr.ac.gachon.smarttray.bluetooth

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UmbrellaEffectPreferencesTest {
    private fun input(effect: JSONObject? = null) = JSONObject()
        .put("umbrella", JSONObject().put("fan", JSONObject().put("duration", 60)).apply { effect?.let { put("effect", it) } })
        .put("trays", JSONArray().put(JSONObject().put("alias", "차키")))
        .put("notifications", JSONObject().put("umbrella", false))
    private fun effect(value: JSONObject) = value.getJSONObject("umbrella").getJSONObject("effect")
    @Test fun previousSettingsStartDisabledWithoutLosingFanAliasOrAlerts() {
        val saved = TrayRepository.sanitize(input())
        assertFalse(effect(saved).getBoolean("enabled"))
        assertFalse(effect(saved).getBoolean("onDetect")); assertFalse(effect(saved).getBoolean("onDoor"))
        assertEquals(0, effect(saved).getInt("duration"))
        assertEquals(60, saved.getJSONObject("umbrella").getJSONObject("fan").getInt("duration"))
        assertEquals("차키", saved.getJSONArray("trays").getJSONObject(0).getString("alias"))
        assertFalse(saved.getJSONObject("notifications").getBoolean("umbrella"))
    }
    @Test fun allSupportedDurationsAndSwitchesSurviveSerialization() {
        for (seconds in listOf(0, 15, 30, 60)) {
            val expected = JSONObject().put("enabled", true).put("onDetect", true).put("onDoor", true).put("duration", seconds)
            val saved = TrayRepository.sanitize(input(expected))
            val restored = TrayRepository.sanitize(JSONObject(saved.toString()))
            assertEquals(seconds > 0, effect(restored).getBoolean("enabled"))
            for (key in listOf("onDetect", "onDoor")) assertTrue(effect(restored).getBoolean(key))
            assertEquals(seconds, effect(restored).getInt("duration"))
            assertEquals(60, restored.getJSONObject("umbrella").getJSONObject("fan").getInt("duration"))
        }
    }
    @Test fun malformedValuesCannotEnableSterilizationOrSetUnsupportedTimes() {
        for (seconds in listOf(null, JSONObject.NULL, -1, 5, 10, 20, 61, 5.5, "15", true, JSONObject())) {
            val bad = JSONObject().put("enabled", "true").put("onDetect", 1).put("onDoor", JSONObject.NULL).put("duration", seconds)
            val restored = effect(TrayRepository.sanitize(input(bad)))
            assertFalse(restored.getBoolean("enabled")); assertFalse(restored.getBoolean("onDetect")); assertFalse(restored.getBoolean("onDoor"))
            assertEquals(0, restored.getInt("duration"))
        }
    }
    @Test fun obsoleteTimesDisableOnlyUmbrellaSterilization() {
        for (seconds in listOf(5, 10, 20)) {
            val old = input(JSONObject().put("enabled", true).put("onDetect", true).put("duration", seconds))
            old.getJSONArray("trays").getJSONObject(0).put("effect", JSONObject().put("duration", seconds))
            val migrated = TrayRepository.sanitize(old)
            assertEquals(0, effect(migrated).getInt("duration")); assertFalse(effect(migrated).getBoolean("enabled"))
            assertTrue(effect(migrated).getBoolean("onDetect"))
            assertEquals(seconds, migrated.getJSONArray("trays").getJSONObject(0).getJSONObject("effect").getInt("duration"))
            assertEquals(60, migrated.getJSONObject("umbrella").getJSONObject("fan").getInt("duration"))
        }
    }
}
