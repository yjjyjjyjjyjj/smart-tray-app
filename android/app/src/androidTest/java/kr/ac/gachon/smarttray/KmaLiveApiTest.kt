package kr.ac.gachon.smarttray

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.ac.gachon.smarttray.weather.KmaWeatherClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real API check. Public Seoul grid only; no phone location is requested or sent. */
@RunWith(AndroidJUnit4::class)
class KmaLiveApiTest {
    @Test fun approvedKeyRetrievesCompleteTodayForecastFromAndroid() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("kmaLive") == "true")
        assertTrue("Local KMA API Hub key is required", BuildConfig.KMA_API_KEY.isNotBlank())
        val forecast = KmaWeatherClient(BuildConfig.KMA_API_KEY).download(37.5665, 126.9780)
        assertEquals("Asia/Seoul", forecast.timezone)
        assertTrue(forecast.fresh(System.currentTimeMillis()))
        assertTrue(forecast.hours.isNotEmpty())
    }
}
