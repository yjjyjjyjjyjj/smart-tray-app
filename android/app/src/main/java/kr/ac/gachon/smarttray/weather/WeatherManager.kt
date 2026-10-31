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
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
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
    private var regionName: String? = null
    private var regionStatus = "idle"
    private var cancelRegion: (() -> Unit)? = null
    private fun clearRegion() {
        cancelRegion?.invoke(); cancelRegion = null
        regionName = null; regionStatus = "idle"
    }
    // Debug metadata only; never log coordinates, addresses, request URLs or location.toString().
    private fun traceLocation(event: String, provider: String = "", location: Location? = null) {
        if (BuildConfig.DEBUG) android.util.Log.d("AmattaLocation",
            "$event provider=$provider received=${location != null} ageMs=${location?.let { SystemClock.elapsedRealtime() - LocationCompat.getElapsedRealtimeMillis(it) }}")
    }
    private fun publishRegion() {
        TrayRepository.patch { state ->
            val weather = state.optJSONObject("weather") ?: JSONObject()
            weather.put("regionName", regionName ?: JSONObject.NULL).put("regionStatus", regionStatus)
            state.put("weather", weather)
        }
    }
    private val callbacks = mutableListOf<() -> Unit>()
    var forecast: RainForecast? = null
        private set
    fun hasLocation(context: Context) = context.getSharedPreferences("weather_location", Context.MODE_PRIVATE).contains("latitude")
    fun locationEnabled(context: Context) = LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java))
    fun permitted(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun refresh(context: Context, useCurrentLocation: Boolean, done: () -> Unit = {}) {
        if (!permitted(context)) { clearRegion(); publish("permission", "날씨를 확인하려면 위치 권한을 허용해 주세요."); done(); return }
        callbacks.add(done)
        if (busy) return
        busy = true
        val token = ++generation
        clearRegion()
        publish("loading", if (useCurrentLocation) "오늘의 예보를 확인하고 있어요." else "마지막 확인 위치의 비 예보를 갱신하고 있어요.")
        val app = context.applicationContext
        val disk = app.getSharedPreferences("weather_location", Context.MODE_PRIVATE)
        fun fetch(lat: Double, lon: Double, locatedAt: Long) {
            fun matchesSavedLocation() = disk.getString("latitude", null)?.toDoubleOrNull() == lat &&
                disk.getString("longitude", null)?.toDoubleOrNull() == lon
            regionName = if (matchesSavedLocation()) disk.getString("regionName", null)?.takeIf { it.isNotBlank() } else null
            regionStatus = if (regionName != null) "ready" else "loading"
            publishRegion()
            if (regionName == null) cancelRegion = WeatherRegionResolver.resolve(app, lat, lon) { name ->
                // Ignore results from a superseded location or a revoked permission.
                if (token == generation && permitted(app)) {
                    regionName = name; regionStatus = if (name != null) "ready" else "unavailable"
                    if (matchesSavedLocation()) disk.edit().putString("regionName", name).apply()
                    publishRegion()
                }
            }
            worker.execute {
                val result = runCatching { KmaWeatherClient(BuildConfig.KMA_API_KEY).download(lat, lon) }
                main.post {
                    if (token != generation) return@post
                    if (!permitted(app)) { forecast = null; finish("permission", "위치 권한이 해제되어 날씨를 확인할 수 없어요."); return@post }
                    result.fold({ value ->
                        forecast = value
                        disk.edit().putString("latitude", lat.toString()).putString("longitude", lon.toString()).putLong("locatedAt", locatedAt).putString("regionName", regionName).apply()
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
        val googleProvider = "play-services"
        val googleAvailable = runCatching {
            GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(app) == ConnectionResult.SUCCESS
        }.getOrDefault(false)
        val providers = buildList {
            if (googleAvailable) add(googleProvider)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (precise) add(LocationManager.GPS_PROVIDER)
        }.filter { it == googleProvider || runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        traceLocation("start", providers.joinToString(","))
        // Network-only coarse requests can time out even when Android has a recent fused fix.
        // Reuse a bounded recent fix, or race available providers instead of selecting only one.
        WeatherLocationLookup<Location>(
            providers = providers,
            cached = { provider ->
                // Google getCurrentLocation handles its own asynchronous cache lookup with the same age limit.
                if (provider == googleProvider) null else manager.getLastKnownLocation(provider).also { traceLocation("cache", provider, it) }
            },
            ageMillis = { SystemClock.elapsedRealtime() - LocationCompat.getElapsedRealtimeMillis(it) },
            request = { provider, result ->
                if (provider == googleProvider) {
                    val cancellation = CancellationTokenSource()
                    val request = CurrentLocationRequest.Builder()
                        .setGranularity(Granularity.GRANULARITY_COARSE)
                        .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                        .setMaxUpdateAgeMillis(WeatherLocationLookup.FALLBACK_MAX_AGE_MS)
                        .setDurationMillis(WeatherLocationLookup.REQUEST_TIMEOUT_MS)
                        .build()
                    LocationServices.getFusedLocationProviderClient(app).getCurrentLocation(request, cancellation.token)
                        .addOnSuccessListener(ContextCompat.getMainExecutor(app)) { traceLocation("response", provider, it); result(it) }
                        .addOnFailureListener(ContextCompat.getMainExecutor(app)) {
                            traceLocation("request-failed", provider); result(null)
                        }
                    val cancel: () -> Unit = { cancellation.cancel() }; cancel
                } else {
                    val cancellation = CancellationSignal()
                    LocationManagerCompat.getCurrentLocation(manager, provider, cancellation, ContextCompat.getMainExecutor(app)) { traceLocation("response", provider, it); result(it) }
                    val cancel: () -> Unit = { cancellation.cancel() }; cancel
                }
            },
            scheduleTimeout = { action ->
                val timeout = Runnable { traceLocation("timeout"); action() }
                main.postDelayed(timeout, WeatherLocationLookup.REQUEST_TIMEOUT_MS)
                val cancel: () -> Unit = { main.removeCallbacks(timeout) }
                cancel
            },
            onResult = { location ->
                traceLocation("selected", location = location)
                if (token == generation) {
                    when {
                        !permitted(app) -> finish("permission", "위치 권한을 허용해 주세요.")
                        !LocationManagerCompat.isLocationEnabled(manager) -> finish("location", "휴대폰의 위치 기능을 켜고 다시 확인해 주세요.")
                        location == null -> finish("location", "새 위치를 아직 받지 못했어요. 잠시 후 다시 시도해 주세요.")
                        else -> {
                            val age = SystemClock.elapsedRealtime() - LocationCompat.getElapsedRealtimeMillis(location)
                            fetch(round(location.latitude * 100) / 100, round(location.longitude * 100) / 100, System.currentTimeMillis() - age.coerceAtLeast(0))
                        }
                    }
                }
            },
            elapsedRealtimeMillis = SystemClock::elapsedRealtime
        ).start()
    }

    private fun finish(status: String, message: String, locatedAt: Long? = null) {
        busy = false
        if (status != "ready") forecast = null
        if (status == "permission") clearRegion()
        publish(status, message, locatedAt)
        val pending = callbacks.toList(); callbacks.clear(); pending.forEach { it() }
    }
    private fun publish(status: String, message: String, locatedAt: Long? = null) {
        TrayRepository.patch { state ->
            val value = state.optJSONObject("weather") ?: JSONObject()
            value.put("status", status).put("message", message).put("source", "기상청")
                .put("regionName", regionName ?: JSONObject.NULL).put("regionStatus", regionStatus)
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
