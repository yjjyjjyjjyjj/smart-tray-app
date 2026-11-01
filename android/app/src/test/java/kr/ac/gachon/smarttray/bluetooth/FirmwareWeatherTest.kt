package kr.ac.gachon.smarttray.bluetooth

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class FirmwareWeatherTest {
    private val now = ZonedDateTime.parse("2026-11-01T18:30:00+09:00").toInstant().toEpochMilli()
    private val hour = 3_600_000L
    private fun forecast(conditions: List<String?>) = RainForecast(now, "Asia/Seoul", conditions.mapIndexed { i, c ->
        val start = now - hour / 2 + i * hour
        RainHour(start, start + hour, c in setOf("rain", "rain-snow", "shower"), c)
    }, now + 11 * hour / 2)
    @Test fun sendsAtMostTwoRainWindowsAndUsesRainBeforeSnow() {
        val frame = FirmwareWeather.frame(forecast(listOf("rain", "snow", "shower", "clear", "rain-snow", "clear")), now)
        assertEquals("rain", frame.getString("icon"))
        assertEquals(2, frame.getJSONArray("windows").length())
        assertEquals("18:00-19:00", frame.getJSONArray("windows").getString(0))
        assertEquals("20:00-21:00", frame.getJSONArray("windows").getString(1))
        assertEquals(1800, frame.getInt("ttl_s"))
        assertTrue(frame.put("req", "a1234567890").toString().toByteArray().size <= 167)
    }
    @Test fun distinguishesSnowDryCloudyAndMissingSky() {
        for ((condition, icon, label) in listOf(Triple("snow", "snow", "NO RAIN"),
            Triple("clear", "sunny", "NO RAIN/SNOW"), Triple("partly-cloudy", "cloudy", "NO RAIN/SNOW"))) {
            val frame = FirmwareWeather.frame(forecast(List(6) { condition }), now)
            assertEquals(icon, frame.getString("icon")); assertEquals(label, frame.getJSONArray("windows").getString(0))
        }
        assertFalse(FirmwareWeather.frame(forecast(List(6) { null }), now).getBoolean("valid"))
    }
    @Test fun failedStaleIncompleteFutureAndExpiredForecastsNeverBecomeSunny() {
        val valid = forecast(List(6) { "clear" })
        for (f in listOf(null, valid.copy(fetchedAt = now - WEATHER_FRESH_MS - 1), valid.copy(fetchedAt = now + 1),
            valid.copy(validUntil = now), valid.copy(hours = valid.hours.dropLast(1)))) {
            val frame = FirmwareWeather.frame(f, now)
            assertFalse(frame.getBoolean("valid")); assertEquals("NO DATA", frame.getString("line1"))
        }
    }
    @Test fun midnightIs2400AndTtlCannotCrossOriginalForecastDeadline() {
        val f = forecast(List(6) { "rain" }).copy(fetchedAt = now - WEATHER_FRESH_MS + 5000)
        val frame = FirmwareWeather.frame(f, now)
        assertEquals("18:00-24:00", frame.getJSONArray("windows").getString(0))
        assertEquals(5, frame.getInt("ttl_s"))
    }
}
