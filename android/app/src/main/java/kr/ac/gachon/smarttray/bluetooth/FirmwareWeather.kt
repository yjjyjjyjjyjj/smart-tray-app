package kr.ac.gachon.smarttray.bluetooth

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

object FirmwareWeather {
    /** Signature excludes TTL, so normal 1-second snapshots do not resend weather. */
    fun frame(forecast: RainForecast?, now: Long): JSONObject {
        fun noData() = JSONObject().put("type", "weather").put("valid", false)
            .put("icon", "none").put("line1", "NO DATA").put("windows", JSONArray().put("")).put("ttl_s", 0)
        if (forecast == null || !forecast.fresh(now)) return noData()
        val zone = TimeZone.getTimeZone(forecast.timezone)
        val midnight = Calendar.getInstance(zone).apply {
            timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val hours = forecast.hours.filter { it.end > now && it.start < midnight }.sortedBy { it.start }
        val conditions = setOf("clear", "partly-cloudy", "cloudy", "rain", "rain-snow", "shower", "snow")
        // Do not imply sunny/no snow when SKY is missing or the rest of today has gaps.
        var covered = now
        for (hour in hours) {
            if (hour.start > covered || hour.condition !in conditions || hour.end <= hour.start) return noData()
            covered = maxOf(covered, hour.end)
        }
        if (covered < midnight) return noData()
        val rain = forecast.windows(now)
        val snow = hours.any { it.condition in setOf("snow", "rain-snow") }
        val icon = when {
            rain.isNotEmpty() -> "rain"
            snow -> "snow"
            hours.firstOrNull()?.condition == "clear" -> "sunny"
            hours.firstOrNull()?.condition in setOf("cloudy", "partly-cloudy") -> "cloudy"
            else -> return noData()
        }
        val format = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = zone }
        fun endLabel(end: Long) = if (end == midnight) "24:00" else format.format(end)
        val windows = if (rain.isNotEmpty()) rain.take(2).map { "${format.format(it.start)}-${endLabel(it.end)}" }
            else listOf(if (snow) "NO RAIN" else "NO RAIN/SNOW")
        // Expire before the next forecast boundary, midnight, or the original freshness deadline.
        val expires = minOf(forecast.fetchedAt + WEATHER_FRESH_MS, forecast.validUntil, midnight,
            hours.first().end)
        val ttl = ((expires - now) / 1000).coerceIn(0, 3600)
        if (ttl == 0L) return noData()
        return JSONObject().put("type", "weather").put("valid", true).put("icon", icon)
            .put("line1", icon.uppercase(Locale.US)).put("windows", JSONArray(windows)).put("ttl_s", ttl)
    }
}
