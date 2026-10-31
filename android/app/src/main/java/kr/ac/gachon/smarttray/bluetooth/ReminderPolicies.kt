package kr.ac.gachon.smarttray.bluetooth

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

const val DOOR_OPEN_MAX_SECONDS = 300
const val SENSOR_FRESH_MS = 45_000L
const val WEATHER_FRESH_MS = 60 * 60_000L

/** Monotonic times only. Repeated packets/settings edits must not restart an open interval. */
class DoorLeftOpenPolicy {
    var openedAt: Long? = null
        private set
    private var notified = false
    fun reset() { openedAt = null; notified = false }
    fun evaluate(door: String, fresh: Boolean, enabled: Boolean, seconds: Int, now: Long): AlertDecision? {
        // Only a confirmed close cancels an observed opening. Losing contact is not a close.
        if (fresh && door == "closed") { reset(); return null }
        if (fresh && door == "open" && openedAt == null) openedAt = now
        val start = openedAt ?: return null // Unknown/disconnected alone cannot start a timer.
        val duration = seconds.coerceIn(1, DOOR_OPEN_MAX_SECONDS)
        if (!enabled || notified || now - start < duration * 1000L) return null
        notified = true
        return if (fresh && door == "open")
            AlertDecision("현관문이 계속 열려 있어요", "현관문이 ${duration}초 이상 열려 있어요. 문이 잘 닫혔는지 확인해 주세요.", emptyList(), "door-left-open")
        else
            AlertDecision("현관문이 닫혔는지 확인해 주세요", "문이 열린 뒤 ${duration}초 동안 닫힘 신호를 받지 못했어요. 외출하셨다면 현관문을 확인해 주세요.", emptyList(), "door-left-open")
    }
    fun remaining(seconds: Int, now: Long): Long? = openedAt?.takeIf { !notified }?.let {
        (it + seconds.coerceIn(1, DOOR_OPEN_MAX_SECONDS) * 1000L - now).coerceAtLeast(0)
    }
}

data class RainHour(val start: Long, val end: Long, val rainy: Boolean, val condition: String? = null)
data class RainWindow(val start: Long, val end: Long)
data class RainForecast(val fetchedAt: Long, val timezone: String, val hours: List<RainHour>, val validUntil: Long = Long.MAX_VALUE) {
    fun fresh(now: Long) = now >= fetchedAt && now - fetchedAt <= WEATHER_FRESH_MS && now < validUntil
    fun windows(now: Long): List<RainWindow> {
        if (!fresh(now)) return emptyList()
        val calendar = Calendar.getInstance(TimeZone.getTimeZone(timezone)).apply {
            timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val dayStart = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        val dayEnd = calendar.timeInMillis
        val windows = mutableListOf<RainWindow>()
        hours.filter { it.rainy && it.end > now && it.start < dayEnd && it.end > dayStart }
            .sortedBy { it.start }.forEach { hour ->
                val next = RainWindow(maxOf(dayStart, hour.start), minOf(dayEnd, hour.end))
                val last = windows.lastOrNull()
                if (last != null && next.start <= last.end) windows[windows.lastIndex] = RainWindow(last.start, maxOf(last.end, next.end))
                else windows.add(next)
            }
        return windows
    }
    fun label(now: Long): String {
        val format = SimpleDateFormat("HH:mm", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone(timezone) }
        return windows(now).joinToString(", ") { "${format.format(it.start)}~${format.format(it.end)}" }
    }
}

/** Evaluate again when a requested forecast arrives; never replay a baseline/open event. */
class UmbrellaAlertPolicy {
    private var previousDoor = "unknown"
    private var pending = false
    fun reset() { previousDoor = "unknown"; pending = false }
    fun evaluate(door: String, umbrellaPresent: Boolean?, fresh: Boolean, enabled: Boolean,
        forecast: RainForecast?, now: Long): AlertDecision? {
        if (!fresh || door !in listOf("open", "closed")) { reset(); return null }
        if (door == "open" && previousDoor == "closed") pending = enabled && umbrellaPresent == true
        previousDoor = door
        if (door == "closed" || !enabled || umbrellaPresent != true) pending = false
        if (!pending || forecast == null || !forecast.fresh(now)) return null
        pending = false
        val hours = forecast.label(now)
        if (hours.isEmpty()) return null
        return AlertDecision("오늘은 우산도 챙겨요", "오늘 $hours 비 예보가 있어요. 우산 공간에 있는 접이식 우산을 챙겨 주세요.", emptyList(), "umbrella")
    }
    fun needsForecast() = pending
}
