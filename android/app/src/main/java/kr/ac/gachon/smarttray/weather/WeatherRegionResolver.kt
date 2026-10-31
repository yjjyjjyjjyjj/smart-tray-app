package kr.ac.gachon.smarttray.weather

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.Locale
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Best-effort name for the same rounded coordinates used by the weather request. */
object WeatherRegionResolver {
    private val main = Handler(Looper.getMainLooper())
    // Older synchronous Geocoder implementations can stall. Never queue unbounded stale requests.
    private val legacyWorker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, SynchronousQueue<Runnable>())
    fun resolve(context: Context, latitude: Double, longitude: Double, done: (String?) -> Unit): () -> Unit =
        WeatherRegionLookup(
            request = { result ->
                fun deliver(addresses: List<Address>?) {
                    val address = addresses?.firstOrNull()
                    val name = address?.let { weatherRegionName(it.adminArea, it.subAdminArea, it.locality, it.subLocality) }
                    main.post { result(name) }
                }
                if (!Geocoder.isPresent()) result(null)
                else {
                    val geocoder = Geocoder(context.applicationContext, Locale.KOREA)
                    if (Build.VERSION.SDK_INT >= 33) {
                        geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) = deliver(addresses)
                            override fun onError(errorMessage: String?) { main.post { result(null) } }
                        })
                    } else legacyWorker.execute {
                        @Suppress("DEPRECATION")
                        val addresses = runCatching { geocoder.getFromLocation(latitude, longitude, 1) }.getOrNull()
                        deliver(addresses)
                    }
                }
            },
            scheduleTimeout = { action ->
                val timer = Runnable(action)
                main.postDelayed(timer, 4_000)
                val cancel: () -> Unit = { main.removeCallbacks(timer) }; cancel
            }, onResult = done
        ).start()
}
