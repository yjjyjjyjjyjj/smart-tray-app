package kr.ac.gachon.smarttray

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.ac.gachon.smarttray.weather.WeatherRegionResolver
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in public Seoul coordinate check; no device position, permission or app settings are changed. */
@RunWith(AndroidJUnit4::class)
class WeatherRegionLiveTest {
    @Test fun publicSeoulCoordinatesResolveToAnAdministrativeRegion() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("regionLive") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val done = CountDownLatch(1)
        val name = AtomicReference<String?>()
        instrumentation.runOnMainSync {
            WeatherRegionResolver.resolve(instrumentation.targetContext, 37.57, 126.98) {
                name.set(it); done.countDown()
            }
        }
        assertTrue("Region lookup did not complete", done.await(6, TimeUnit.SECONDS))
        assertNotNull("Geocoder did not return a region before its timeout", name.get())
        assertTrue("Expected a Korean administrative area", name.get()?.contains("서울") == true)
    }
}
