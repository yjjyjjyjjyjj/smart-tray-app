package kr.ac.gachon.smarttray.weather

/** One bounded foreground lookup. Provider failures must not discard another provider's result.
 * Callbacks and start() run on the same thread; Android supplies elapsed-realtime ages. */
internal class WeatherLocationLookup<T : Any>(
    private val providers: List<String>,
    private val cached: (String) -> T?,
    private val ageMillis: (T) -> Long,
    private val request: (String, (T?) -> Unit) -> (() -> Unit),
    private val scheduleTimeout: (() -> Unit) -> (() -> Unit),
    private val onResult: (T?) -> Unit,
    private val elapsedRealtimeMillis: () -> Long
) {
    private var started = false
    private var finished = false
    private var remaining = providers.size
    private var cancelTimeout: (() -> Unit)? = null
    private val cancellations = mutableListOf<() -> Unit>()

    private fun recentCache(): T? = providers.mapNotNull { runCatching { cached(it) }.getOrNull() }
        .filter { ageMillis(it) in 0..FALLBACK_MAX_AGE_MS }.minByOrNull(ageMillis)

    fun start() {
        check(!started); started = true
        val recent = recentCache()
        if (recent != null && ageMillis(recent) <= FRESH_MAX_AGE_MS) { complete(recent); return }
        if (providers.isEmpty()) { complete(null); return }
        cancelTimeout = scheduleTimeout { complete(recentCache()) }
        for (provider in providers) {
            if (finished) break
            val requestedAt = elapsedRealtimeMillis()
            var responded = false
            val respond: (T?) -> Unit = response@ { location ->
                if (finished || responded) return@response
                responded = true; remaining--
                // The provider checks its cache when the request arrives. Do not reject a
                // valid boundary fix just because callback delivery took a few milliseconds.
                // Only the actual request duration (at most 10 s) is allowed, never an
                // unbounded stale cache or a future timestamp. Keep the measured time intact.
                val elapsed = elapsedRealtimeMillis() - requestedAt
                val age = location?.let(ageMillis)
                if (location != null && age != null && elapsed in 0..REQUEST_TIMEOUT_MS &&
                    age in 0..(FALLBACK_MAX_AGE_MS + elapsed)) complete(location)
                else if (remaining == 0) complete(recentCache())
            }
            try {
                val cancel = request(provider, respond)
                if (finished) runCatching(cancel) else cancellations.add(cancel)
            } catch (_: Exception) { respond(null) }
        }
    }

    private fun complete(location: T?) {
        if (finished) return
        finished = true
        cancelTimeout?.let { runCatching(it) }
        cancellations.forEach { runCatching(it) }; cancellations.clear()
        onResult(location)
    }

    companion object {
        const val REQUEST_TIMEOUT_MS = 10_000L
        const val FRESH_MAX_AGE_MS = 2 * 60_000L
        const val FALLBACK_MAX_AGE_MS = 5 * 60_000L
    }
}
