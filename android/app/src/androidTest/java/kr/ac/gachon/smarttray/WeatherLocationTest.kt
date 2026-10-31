package kr.ac.gachon.smarttray

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.ac.gachon.smarttray.bluetooth.TrayRepository
import kr.ac.gachon.smarttray.weather.KmaForecast
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Disposable emulator only. ALL Java HTTP(S) is intercepted; NO location leaves the device.
 * Android supplies a fixed synthetic network-provider location; the forecast is a local fixture.
 * Run this class in its own instrumentation invocation: URL factories live for the process lifetime. */
@RunWith(AndroidJUnit4::class)
class WeatherLocationTest {
    @Test fun androidLocationReachesWeatherUiWithoutExternalRequests() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        fun shell(command: String): String = i.uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { String(it.readBytes()) }
        }
        val previousLocation = shell("cmd location is-location-enabled").trim()
        val request = AtomicReference<URL>()
        val requestCount = AtomicInteger()
        fun fixture(url: URL): String {
            val query = Uri.parse(url.toString())
            val now = System.currentTimeMillis()
            val hour = now / KmaForecast.HOUR * KmaForecast.HOUR
            val rows = JSONArray((1..48).map { offset ->
                val time = KmaForecast.issueAt(hour + offset * KmaForecast.HOUR)
                JSONObject().put("baseDate", query.getQueryParameter("base_date")).put("baseTime", query.getQueryParameter("base_time"))
                    .put("nx", query.getQueryParameter("nx")!!.toInt()).put("ny", query.getQueryParameter("ny")!!.toInt())
                    .put("category", "PTY").put("fcstDate", time.date).put("fcstTime", time.time).put("fcstValue", if (offset == 1) "1" else "0")
            })
            return JSONObject().put("response", JSONObject().put("header", JSONObject().put("resultCode", "00"))
                .put("body", JSONObject().put("totalCount", 48).put("pageNo", 1).put("numOfRows", 1000)
                    .put("items", JSONObject().put("item", rows)))).toString()
        }
        URL.setURLStreamHandlerFactory { protocol ->
            if (protocol != "https" && protocol != "http") null else object : URLStreamHandler() {
                override fun openConnection(url: URL): URLConnection {
                    check(url.host == "apihub.kma.go.kr" && url.path.endsWith("/getVilageFcst")) { "Unexpected test request" }
                    request.set(url); requestCount.incrementAndGet()
                    return object : HttpURLConnection(url) {
                        override fun connect() = Unit
                        override fun disconnect() = Unit
                        override fun usingProxy() = false
                        override fun getResponseCode() = 200
                        override fun getInputStream() = fixture(url).byteInputStream()
                    }
                }
            }
        }
        i.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        shell("appops set com.android.shell android:mock_location allow")
        shell("cmd location set-location-enabled true")
        shell("cmd location providers add-test-provider network")
        shell("cmd location providers set-test-provider-enabled network true")
        i.runOnMainSync {
            TrayRepository.initialize(context)
            val prefs = JSONObject(TrayRepository.snapshot().getJSONObject("preferences").toString()).put("setupDone", false)
            TrayRepository.savePreferences(prefs)
        }
        val activity = i.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun js(source: String): String {
            val value = AtomicReference<String>(); val done = CountDownLatch(1)
            i.runOnMainSync { activity.bridge.webView.evaluateJavascript(source) { value.set(it); done.countDown() } }
            assertTrue(done.await(5, TimeUnit.SECONDS)); return value.get()
        }
        try {
            var deadline = System.currentTimeMillis() + 15000
            while (js("!!window.SmartTrayApp && !!document.querySelector('#location-setup')") != "true" && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertEquals("true", js("SmartTrayApp.store.get().location.permission === 'granted'"))
            js("document.querySelector('#location-setup [data-action=refresh-weather]').click()")
            deadline = System.currentTimeMillis() + 25000
            while (js("SmartTrayApp.store.get().weather.status === 'ready'") != "true" && System.currentTimeMillis() < deadline) {
                shell("cmd location providers set-test-provider-location network --location 37.45,127.10 --accuracy 1000")
                Thread.sleep(200)
            }
            assertEquals("true", js("SmartTrayApp.store.get().weather.status === 'ready' && SmartTrayApp.store.get().weather.locatedAt > 0"))
            val url = Uri.parse(checkNotNull(request.get()).toString())
            val expectedGrid = KmaForecast.grid(37.45, 127.10)
            assertTrue(kotlin.math.abs(expectedGrid.x - url.getQueryParameter("nx")!!.toInt()) <= 2)
            assertTrue(kotlin.math.abs(expectedGrid.y - url.getQueryParameter("ny")!!.toInt()) <= 2)
            assertNull(url.getQueryParameter("latitude")); assertNull(url.getQueryParameter("longitude"))
            assertEquals("true", js("SmartTrayApp.store.get().weather.source === '기상청'"))
            assertEquals("true", js("document.querySelector('#location-setup').textContent.includes('확인 완료')"))
            fun verifyHomeRefresh(action: String) {
                val before = requestCount.get()
                js(action)
                val end = System.currentTimeMillis() + 25000
                while ((requestCount.get() <= before || js("SmartTrayApp.store.get().weather.status === 'ready'") != "true") && System.currentTimeMillis() < end) {
                    shell("cmd location providers set-test-provider-location network --location 37.45,127.10 --accuracy 1000")
                    Thread.sleep(200)
                }
                assertEquals(before + 1, requestCount.get())
                assertEquals("true", js("location.hash === '#home' && SmartTrayApp.store.get().weather.status === 'ready'"))
            }
            verifyHomeRefresh("document.querySelector('[data-action=finish-setup]').click()")
            verifyHomeRefresh("document.querySelector('.bottom-nav a[href=\"#home\"]').click()")
            js("SmartTrayApp.navigate('umbrella')")
            verifyHomeRefresh("SmartTrayApp.navigate('home')")

        } finally {
            i.runOnMainSync { activity.finish() }
            shell("cmd location providers remove-test-provider network")
            shell("appops set com.android.shell android:mock_location default")
            shell("cmd location set-location-enabled $previousLocation")
        }
    }
}
