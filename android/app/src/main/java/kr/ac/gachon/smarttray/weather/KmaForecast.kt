package kr.ac.gachon.smarttray.weather

import kr.ac.gachon.smarttray.bluetooth.RainForecast
import kr.ac.gachon.smarttray.bluetooth.RainHour
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.*

enum class WeatherFailure(val userMessage: String) {
    CONFIGURATION("날씨 인증키가 설정되지 않았어요. 앱 설정을 확인해 주세요."),
    AUTHORIZATION("기상청 인증키와 단기예보조회 활용 승인을 확인해 주세요."),
    QUOTA("기상청 날씨 조회 한도를 초과했어요. 잠시 후 다시 확인해 주세요."),
    LOCATION("이 위치의 기상청 단기예보를 제공할 수 없어요. 국내 위치에서 다시 확인해 주세요."),
    NO_DATA("기상청 예보가 아직 준비되지 않았어요. 잠시 후 다시 확인해 주세요."),
    INVALID_DATA("기상청 예보가 불완전해요. 잠시 후 다시 확인해 주세요."),
    NETWORK("날씨를 가져오지 못했어요. 인터넷 연결을 확인하고 다시 시도해 주세요.")
}
class WeatherException(val failure: WeatherFailure) : Exception(failure.userMessage)
data class ForecastGrid(val x: Int, val y: Int)
data class ForecastIssue(val date: String, val time: String)
data class ForecastPage(val total: Int, val items: List<JSONObject>)

/** API Hub getVilageFcst, June 2026 guide. Times and grid are always KST / Lambert 5 km. */
object KmaForecast {
    const val HOUR = 3_600_000L
    private val zone get() = TimeZone.getTimeZone("Asia/Seoul")
    private fun format(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone; isLenient = false }
    fun calendar(at: Long) = Calendar.getInstance(zone).apply { timeInMillis = at }
    fun issueAt(at: Long) = ForecastIssue(format("yyyyMMdd").format(at), format("HHmm").format(at))
    fun timestamp(date: String, time: String): Long {
        if (!date.matches(Regex("[0-9]{8}")) || !time.matches(Regex("[0-9]{2}00"))) throw WeatherException(WeatherFailure.INVALID_DATA)
        // KMA normally returns next-day 0000; accept the equivalent 2400 boundary explicitly.
        return if (time == "2400") format("yyyyMMddHHmm").parse(date + "0000")!!.time + 24 * HOUR
            else format("yyyyMMddHHmm").parse(date + time)!!.time
    }
    fun latestIssue(now: Long): ForecastIssue {
        val c = calendar(now - 10 * 60_000L) // Publication: 02/05/08/11/14/17/20/23, available ten minutes later.
        val hour = c.get(Calendar.HOUR_OF_DAY)
        val base = listOf(2, 5, 8, 11, 14, 17, 20, 23).lastOrNull { it <= hour }
        if (base == null) c.add(Calendar.DAY_OF_YEAR, -1)
        c.set(Calendar.HOUR_OF_DAY, base ?: 23); c.set(Calendar.MINUTE, 0)
        return issueAt(c.timeInMillis)
    }
    fun previousIssue(issue: ForecastIssue) = issueAt(timestamp(issue.date, issue.time) - 3 * HOUR)
    fun grid(latitude: Double, longitude: Double): ForecastGrid {
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in 32.0..39.5 || longitude !in 124.0..132.0)
            throw WeatherException(WeatherFailure.LOCATION)
        val rad = PI / 180
        val sn = ln(cos(30 * rad) / cos(60 * rad)) / ln(tan(PI / 4 + 60 * rad / 2) / tan(PI / 4 + 30 * rad / 2))
        val sf = tan(PI / 4 + 30 * rad / 2).pow(sn) * cos(30 * rad) / sn
        val re = 6371.00877 / 5
        val ro = re * sf / tan(PI / 4 + 38 * rad / 2).pow(sn)
        val ra = re * sf / tan(PI / 4 + latitude * rad / 2).pow(sn)
        val theta = (longitude - 126) * rad * sn
        val grid = ForecastGrid(floor(ra * sin(theta) + 43 + .5).toInt(), floor(ro - ra * cos(theta) + 136 + .5).toInt())
        if (grid.x !in 1..149 || grid.y !in 1..253) throw WeatherException(WeatherFailure.LOCATION)
        return grid
    }
    fun page(json: JSONObject, pageNumber: Int): ForecastPage = checked {
        val response = json.getJSONObject("response")
        when (response.getJSONObject("header").getString("resultCode")) {
            "00" -> Unit
            "03" -> throw WeatherException(WeatherFailure.NO_DATA)
            "20", "21", "30", "31", "32" -> throw WeatherException(WeatherFailure.AUTHORIZATION)
            "22" -> throw WeatherException(WeatherFailure.QUOTA)
            else -> throw WeatherException(WeatherFailure.INVALID_DATA)
        }
        val body = response.getJSONObject("body")
        val total = body.getInt("totalCount")
        if (total == 0) throw WeatherException(WeatherFailure.NO_DATA)
        require(total in 1..5000 && body.getInt("pageNo") == pageNumber && body.getInt("numOfRows") == 1000)
        val items = body.getJSONObject("items").getJSONArray("item")
        val expected = minOf(1000, total - (pageNumber - 1) * 1000)
        require(expected > 0 && items.length() == expected)
        ForecastPage(total, (0 until items.length()).map { items.getJSONObject(it) })
    }
    fun parse(items: List<JSONObject>, grid: ForecastGrid, issue: ForecastIssue, now: Long): RainForecast = checked {
        val endOfDay = calendar(now).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val hourly = sortedMapOf<Long, Int>()
        val sky = mutableMapOf<Long, Int>()
        for (item in items) {
            require(item.getString("baseDate") == issue.date && item.getString("baseTime") == issue.time)
            require(item.getInt("nx") == grid.x && item.getInt("ny") == grid.y)
            val category = item.getString("category")
            if (category != "PTY" && category != "SKY") continue
            val end = timestamp(item.getString("fcstDate"), item.getString("fcstTime"))
            val code = item.getString("fcstValue").toIntOrNull()
            require(code != null && if (category == "PTY") code in 0..4 else code in setOf(1, 3, 4))
            val readings = if (category == "PTY") hourly else sky
            require(readings[end] == null || readings[end] == code)
            readings[end] = code
        }
        // Missing hours are unknown, never a dry forecast. Validate the entire remainder of today.
        var end = now / HOUR * HOUR + HOUR
        while (end <= endOfDay) { require(hourly.containsKey(end)); end += HOUR }
        // KMA's sky/PTY at 19:00 describes 18:00–19:00 (official short-term forecast help).
        RainForecast(now, "Asia/Seoul", hourly.map { (endTime, precipitation) ->
            val condition = when (precipitation) {
                1 -> "rain"
                2 -> "rain-snow"
                3 -> "snow"
                4 -> "shower"
                else -> when (sky[endTime]) { 1 -> "clear"; 3 -> "partly-cloudy"; 4 -> "cloudy"; else -> null }
            }
            // Missing SKY is unknown; dry weather must never imply a clear sky.
            RainHour(endTime - HOUR, endTime, precipitation in setOf(1, 2, 4), condition)
        }, endOfDay)
    }
    private inline fun <T> checked(block: () -> T): T = try { block() }
        catch (e: WeatherException) { throw e }
        catch (_: Exception) { throw WeatherException(WeatherFailure.INVALID_DATA) }
}
