package kr.ac.gachon.smarttray.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationCompat
import android.os.CancellationSignal
import kr.ac.gachon.smarttray.bluetooth.*
import org.json.JSONArray
import org.json.JSONObject
import kr.ac.gachon.smarttray.BuildConfig
import java.util.concurrent.Executors
import kotlin.math.round

/** Location is acquired only while the app is visible, after the user's permission action.
 * The Bluetooth service refreshes forecasts using the last confirmed approximate location. */
object WeatherManager {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var busy = false
    private var generation = 0
    private val callbacks = mutableListOf<() -> Unit>()
    var forecast: RainForecast? = null
        private set
    fun hasLocation(context: Context) = context.getSharedPreferences("weather_location", Context.MODE_PRIVATE).contains("latitude")
    fun locationEnabled(context: Context) = LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java))
    fun permitted(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun refresh(context: Context, useCurrentLocation: Boolean, done: () -> Unit = {}) {
        if (!permitted(context)) { publish("permission", "날씨를 확인하려면 위치 권한을 허용해 주세요."); done(); return }
        callbacks.add(done)
        if (busy) return
        busy = true
        val token = ++generation
        publish("loading", if (useCurrentLocation) "현재 위치와 오늘의 비 예보를 확인하고 있어요." else "마지막 확인 위치의 비 예보를 갱신하고 있어요.")
        val app = context.applicationContext
        val disk = app.getSharedPreferences("weather_location", Context.MODE_PRIVATE)
        fun fetch(lat: Double, lon: Double, locatedAt: Long) {
            worker.execute {
                val result = runCatching { KmaWeatherClient(BuildConfig.KMA_API_KEY).download(lat, lon) }
                main.post {
                    if (token != generation) return@post
                    if (!permitted(app)) { forecast = null; finish("permission", "위치 권한이 해제되어 날씨를 확인할 수 없어요."); return@post }
                    result.fold({ value ->
                        forecast = value
                        disk.edit().putString("latitude", lat.toString()).putString("longitude", lon.toString()).putLong("locatedAt", locatedAt).apply()
                        finish("ready", "", locatedAt)
                    }, { error -> finish("error", (error as? WeatherException)?.failure?.userMessage ?: WeatherFailure.NETWORK.userMessage) })
                }
            }
        }
        if (!useCurrentLocation) {
            val lat = disk.getString("latitude", null)?.toDoubleOrNull()
            val lon = disk.getString("longitude", null)?.toDoubleOrNull()
            if (lat == null || lon == null) { finish("location", "앱에서 현재 위치로 날씨를 먼저 확인해 주세요."); return }
            fetch(lat, lon, disk.getLong("locatedAt", 0)); return
        }
        val manager = app.getSystemService(LocationManager::class.java)
        if (!LocationManagerCompat.isLocationEnabled(manager)) {
            finish("location", "휴대폰의 위치 기능을 켜고 다시 확인해 주세요."); return
        }
        val precise = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (precise) add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        // Network-only coarse requests can time out even when Android has a recent fused fix.
        // Reuse a bounded recent fix, or race available providers instead of selecting only one.
        WeatherLocationLookup<Location>(
            providers = providers,
            cached = { manager.getLastKnownLocation(it) },
            ageMillis = { SystemClock.elapsedRealtime() - LocationCompat.getElapsedRealtimeMillis(it) },
            request = { provider, result ->
                val cancellation = CancellationSignal()
                LocationManagerCompat.getCurrentLocation(manager, provider, cancellation, ContextCompat.getMainExecutor(app)) { result(it) }
                val cancel: () -> Unit = { cancellation.cancel() }
                cancel
            },
            scheduleTimeout = { action ->
                val timeout = Runnable(action)
                main.postDelayed(timeout, 20_000)
                val cancel: () -> Unit = { main.removeCallbacks(timeout) }
                cancel
            },
            onResult = { location ->
                if (token == generation) {
                    when {
                        !permitted(app) -> finish("permission", "위치 권한을 허용해 주세요.")
                        !LocationManagerCompat.isLocationEnabled(manager) -> finish("location", "휴대폰의 위치 기능을 켜고 다시 확인해 주세요.")
                        location == null -> finish("location", "현재 위치를 확인하지 못했어요. 휴대폰의 위치 정확도와 Wi-Fi·모바일 데이터 연결을 확인한 뒤 다시 시도해 주세요.")
                        else -> {
                            val age = SystemClock.elapsedRealtime() - LocationCompat.getElapsedRealtimeMillis(location)
                            fetch(round(location.latitude * 100) / 100, round(location.longitude * 100) / 100, System.currentTimeMillis() - age.coerceAtLeast(0))
                        }
                    }
                }
            }
        ).start()
    }

    private fun finish(status: String, message: String, locatedAt: Long? = null) {
        busy = false
        if (status != "ready") forecast = null
        publish(status, message, locatedAt)
        val pending = callbacks.toList(); callbacks.clear(); pending.forEach { it() }
    }
    private fun publish(status: String, message: String, locatedAt: Long? = null) {
        TrayRepository.patch { state ->
            val value = state.optJSONObject("weather") ?: JSONObject()
            value.put("status", status).put("message", message).put("source", "기상청")
            if (locatedAt != null) value.put("locatedAt", locatedAt)
            val current = forecast
            if (status == "ready" && current != null) {
                val now = System.currentTimeMillis()
                value.put("fetchedAt", current.fetchedAt).put("timezone", current.timezone).put("validUntil", current.validUntil)
                    .put("windows", JSONArray(current.windows(now).map { JSONObject().put("start", it.start).put("end", it.end) }))
                    .put("rainHours", current.label(now))
                    .put("hours", JSONArray(current.hours.filter { it.end > now && it.start < current.validUntil }.map {
                        JSONObject().put("start", it.start).put("end", it.end).put("condition", it.condition ?: JSONObject.NULL)
                    }))
            } else value.put("windows", JSONArray()).put("rainHours", "").put("hours", JSONArray())
            state.put("weather", value)
        }
    }
}
