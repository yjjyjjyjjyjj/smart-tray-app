package kr.ac.gachon.smarttray.weather

/** Administrative area only: never expose street addresses, buildings or coordinates. */
fun weatherRegionName(admin: String?, subAdmin: String?, locality: String?, subLocality: String?): String? {
    val words = listOf(admin, subAdmin, locality).filterNotNull()
        .flatMap { it.trim().split(Regex("\\s+")) }.filter { it.isNotBlank() }.toMutableList()
    // In Korea subLocality may contain both a district and a neighbourhood. Keep the district only.
    subLocality?.trim()?.split(Regex("\\s+"))?.filter { it.endsWith("구") || it.endsWith("군") || it.endsWith("시") }
        ?.let(words::addAll)
    return words.distinct().joinToString(" ").ifBlank { null }
}

/** All callbacks are serialized by the caller. A slow geocoder must never block the forecast. */
class WeatherRegionLookup(
    private val request: ((String?) -> Unit) -> Unit,
    private val scheduleTimeout: (() -> Unit) -> (() -> Unit),
    private val onResult: (String?) -> Unit
) {
    private var complete = false
    private var cancelTimeout: (() -> Unit)? = null
    fun start(): () -> Unit {
        cancelTimeout = scheduleTimeout { finish(null) }
        try { request(::finish) } catch (_: Exception) { finish(null) }
        return { complete = true; cancelTimeout?.invoke() }
    }
    private fun finish(name: String?) {
        if (complete) return
        complete = true
        cancelTimeout?.invoke()
        onResult(name?.trim()?.ifBlank { null })
    }
}
