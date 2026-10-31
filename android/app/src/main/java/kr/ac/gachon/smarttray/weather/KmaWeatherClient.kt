package kr.ac.gachon.smarttray.weather

import kr.ac.gachon.smarttray.bluetooth.RainForecast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Native-only HTTP client. Never log request URLs: API Hub authentication is a query parameter. */
class KmaWeatherClient(
    private val key: String,
    private val request: (URL) -> String = ::requestKma,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun download(latitude: Double, longitude: Double): RainForecast {
        if (key.isBlank()) throw WeatherException(WeatherFailure.CONFIGURATION)
        val grid = KmaForecast.grid(latitude, longitude)
        val issue = KmaForecast.latestIssue(clock())
        return try { fetch(grid, issue) } catch (e: WeatherException) {
            // A newly published cycle may not yet be populated. At most one older cycle, only for NO_DATA.
            if (e.failure != WeatherFailure.NO_DATA) throw e
            fetch(grid, KmaForecast.previousIssue(issue))
        }
    }
    private fun fetch(grid: ForecastGrid, issue: ForecastIssue): RainForecast {
        val items = mutableListOf<JSONObject>()
        var expectedTotal: Int? = null
        var pageNumber = 1
        do {
            val params = "pageNo=$pageNumber&numOfRows=1000&dataType=JSON&base_date=${issue.date}&base_time=${issue.time}&nx=${grid.x}&ny=${grid.y}&authKey=${URLEncoder.encode(key, "UTF-8")}"
            val json = try { JSONObject(request(URL("https://apihub.kma.go.kr/api/typ02/openApi/VilageFcstInfoService_2.0/getVilageFcst?$params"))) }
                catch (e: WeatherException) { throw e }
                catch (_: org.json.JSONException) { throw WeatherException(WeatherFailure.INVALID_DATA) }
                catch (_: Exception) { throw WeatherException(WeatherFailure.NETWORK) }
            val page = KmaForecast.page(json, pageNumber)
            if (expectedTotal != null && expectedTotal != page.total) throw WeatherException(WeatherFailure.INVALID_DATA)
            expectedTotal = page.total
            items.addAll(page.items); pageNumber++
        } while (items.size < expectedTotal!!)
        return KmaForecast.parse(items, grid, issue, clock())
    }
}

private fun requestKma(url: URL): String {
    val connection = url.openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 8_000; connection.readTimeout = 8_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        when (connection.responseCode) {
            200 -> Unit
            401, 403 -> throw WeatherException(WeatherFailure.AUTHORIZATION)
            429 -> throw WeatherException(WeatherFailure.QUOTA)
            else -> throw WeatherException(WeatherFailure.NETWORK)
        }
        return connection.inputStream.use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size == -1) break
                if (bytes.size() + size > 2 * 1024 * 1024) throw WeatherException(WeatherFailure.INVALID_DATA)
                bytes.write(buffer, 0, size)
            }
            bytes.toString("UTF-8")
        }
    } finally { connection.disconnect() }
}
