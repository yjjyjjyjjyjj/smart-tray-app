package kr.ac.gachon.smarttray.weather

import org.junit.Assert.*
import org.junit.Test

class WeatherLocationLookupTest {
    private data class Fix(val name: String, val age: Long)
    private class Fixture {
        val cache = mutableMapOf<String, Fix>()
        val requests = linkedMapOf<String, (Fix?) -> Unit>()
        val cancelled = mutableListOf<String>()
        val results = mutableListOf<Fix?>()
        var timeout: (() -> Unit)? = null
        var timeoutCancelled = false
        var failProvider: String? = null
        fun start(providers: List<String> = listOf("fused", "network")) = WeatherLocationLookup(
            providers, { cache[it] }, Fix::age,
            { provider, callback ->
                if (provider == failProvider) throw SecurityException("Provider unavailable")
                requests[provider] = callback
                val cancel: () -> Unit = { cancelled.add(provider) }; cancel
            },
            { callback -> timeout = callback; val cancel: () -> Unit = { timeoutCancelled = true }; cancel },
            { results.add(it) }
        ).start()
    }

    @Test fun recentFusedCacheWorksWithoutWaitingForUnresponsiveNetwork() {
        val f = Fixture()
        f.cache["network"] = Fix("old network", 6 * 60_000)
        val fresh = Fix("recent fused", 60_000)
        f.cache["fused"] = fresh; f.start()
        assertEquals(listOf(fresh), f.results)
        assertTrue(f.requests.isEmpty()); assertNull(f.timeout)
    }
    @Test fun freshestCacheWinsRegardlessOfProviderOrder() {
        val f = Fixture()
        f.cache["fused"] = Fix("fused", 90_000); f.cache["network"] = Fix("network", 30_000)
        f.start(); assertEquals("network", f.results.single()!!.name)
    }
    @Test fun oneProviderFailureWaitsForOtherProviderAndCancelsLateCallbacks() {
        val f = Fixture(); f.start()
        f.requests.getValue("network")(null); assertTrue(f.results.isEmpty())
        val fix = Fix("fused", 0); f.requests.getValue("fused")(fix)
        f.requests.getValue("network")(Fix("late network", 0)); f.timeout!!()
        assertEquals(listOf(fix), f.results)
        assertEquals(setOf("network", "fused"), f.cancelled.toSet()); assertTrue(f.timeoutCancelled)
    }
    @Test fun providerExceptionDoesNotPreventAnotherProviderFromResponding() {
        val f = Fixture(); f.failProvider = "fused"; f.start()
        val fix = Fix("network", 0); f.requests.getValue("network")(fix)
        assertEquals(listOf(fix), f.results)
    }
    @Test fun timeoutUsesOnlyBoundedCacheAndStopsEveryRequest() {
        val f = Fixture(); f.cache["fused"] = Fix("fallback", 4 * 60_000); f.start()
        assertTrue(f.results.isEmpty()); f.timeout!!()
        assertEquals("fallback", f.results.single()!!.name)
        assertEquals(setOf("network", "fused"), f.cancelled.toSet())
    }
    @Test fun oldOrFutureLocationsCannotBecomeCurrentWeather() {
        for (age in listOf(-1L, 5 * 60_000L + 1)) {
            val f = Fixture(); f.cache["fused"] = Fix("invalid cache", age); f.start()
            f.requests.getValue("fused")(Fix("invalid result", age)); f.requests.getValue("network")(null)
            assertEquals(listOf<Fix?>(null), f.results); assertTrue(f.timeoutCancelled)
        }
    }
    @Test fun synchronousResultCompletesOnceAndCancelsItsRequest() {
        val results = mutableListOf<Fix?>(); var cancelled = false; var timerCancelled = false
        WeatherLocationLookup<Fix>(listOf("fused", "network"), { null }, Fix::age,
            { _, callback -> callback(Fix("immediate", 0)); val cancel: () -> Unit = { cancelled = true }; cancel },
            { val cancel: () -> Unit = { timerCancelled = true }; cancel }, { results.add(it) }).start()
        assertEquals(1, results.size); assertTrue(cancelled); assertTrue(timerCancelled)
    }
    @Test fun noProvidersCompletesWithoutStartingRequests() {
        val f = Fixture(); f.start(emptyList())
        assertEquals(listOf<Fix?>(null), f.results); assertTrue(f.requests.isEmpty()); assertNull(f.timeout)
    }
}
