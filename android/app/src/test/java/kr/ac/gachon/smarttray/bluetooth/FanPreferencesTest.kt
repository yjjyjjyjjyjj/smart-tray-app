package kr.ac.gachon.smarttray.bluetooth

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FanPreferencesTest {
    private fun input(duration: Any?) = JSONObject()
        .put("umbrella", JSONObject().put("fan", JSONObject().put("duration", duration)))
        .put("notifications", JSONObject().put("umbrella", false))
        .put("trays", JSONArray().put(JSONObject().put("alias", "출근 트레이")))
    private fun duration(preferences: JSONObject) = preferences.getJSONObject("umbrella").getJSONObject("fan").getInt("duration")

    @Test fun supportedTimesSurviveSerializationWithoutChangingExistingPreferences() {
        for (seconds in listOf(0, 15, 30, 60)) {
            val saved = TrayRepository.sanitize(input(seconds))
            val restored = TrayRepository.sanitize(JSONObject(saved.toString()))
            assertEquals(seconds, duration(restored))
            assertEquals("출근 트레이", restored.getJSONArray("trays").getJSONObject(0).getString("alias"))
            assertFalse(restored.getJSONObject("notifications").getBoolean("umbrella"))
        }
    }
    @Test fun legacyPreferencesDefaultToOffAndKeepNamesAndNotificationSettings() {
        val legacy = input(60).apply { remove("umbrella") }
        val restored = TrayRepository.sanitize(legacy)
        assertEquals(0, duration(restored))
        assertEquals("출근 트레이", restored.getJSONArray("trays").getJSONObject(0).getString("alias"))
        assertFalse(restored.getJSONObject("notifications").getBoolean("umbrella"))
    }
    @Test fun malformedTimesCannotEnableFanConfiguration() {
        for (value in listOf(null, JSONObject.NULL, -1, 1, 15.5, 61, "15", true, JSONObject())) {
            assertEquals(0, duration(TrayRepository.sanitize(input(value))))
        }
    }
}
