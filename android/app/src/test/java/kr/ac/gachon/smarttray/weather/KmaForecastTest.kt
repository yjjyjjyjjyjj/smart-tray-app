package kr.ac.gachon.smarttray.weather

import kr.ac.gachon.smarttray.bluetooth.UmbrellaAlertPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder
import java.util.TimeZone

class KmaForecastTest {
    private val grid = ForecastGrid(60, 127)
    private val issue = ForecastIssue("20261031", "0800")
    private val now = KmaForecast.timestamp("20261031", "0900") + 30 * 60_000
    private fun rows(): List<JSONObject> = (10..24).map { h -> JSONObject()
        .put("baseDate", issue.date).put("baseTime", issue.time).put("nx", 60).put("ny", 127).put("category", "PTY")
        .put("fcstDate", if (h == 24) "20261101" else "20261031").put("fcstTime", if (h == 24) "0000" else "%02d00".format(h))
        .put("fcstValue", when (h) { 11 -> "1"; 12 -> "2"; 17 -> "4"; 19 -> "3"; else -> "0" }) }
    private fun page(rows: List<JSONObject>, total: Int = rows.size, number: Int = 1) = JSONObject().put("response", JSONObject()
        .put("header", JSONObject().put("resultCode", "00"))
        .put("body", JSONObject().put("totalCount", total).put("pageNo", number).put("numOfRows", 1000)
            .put("items", JSONObject().put("item", JSONArray(rows)))))
    private fun failure(expected: WeatherFailure, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("Expected a controlled weather error", error is WeatherException)
        assertEquals(expected, (error as WeatherException).failure)
    }
    @Test fun gridMatchesPublishedReferenceAndSeoulJeju() {
        assertEquals(ForecastGrid(59, 125), KmaForecast.grid(37.488201, 126.929810))
        assertEquals(grid, KmaForecast.grid(37.5665, 126.9780))
        assertEquals(ForecastGrid(53, 38), KmaForecast.grid(33.4996, 126.5312))
        failure(WeatherFailure.LOCATION) { KmaForecast.grid(35.68, 139.69) }
        failure(WeatherFailure.LOCATION) { KmaForecast.grid(Double.NaN, 127.0) }
    }
    @Test fun publicationDelayAndPreviousDayAreIndependentOfDeviceTimeZone() {
        val saved = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val base = KmaForecast.timestamp("20261031", "0200")
            assertEquals(ForecastIssue("20261030", "2300"), KmaForecast.latestIssue(base + 599999))
            assertEquals(ForecastIssue("20261031", "0200"), KmaForecast.latestIssue(base + 600000))
            assertEquals(ForecastIssue("20261030", "2300"), KmaForecast.latestIssue(KmaForecast.timestamp("20261031", "0000")))
            assertEquals(ForecastIssue("20261030", "2300"), KmaForecast.previousIssue(ForecastIssue("20261031", "0200")))
        } finally { TimeZone.setDefault(saved) }
    }
    @Test fun rainMixedRainAndShowerMergePrecedingHoursButSnowDoesNot() {
        val forecast = KmaForecast.parse(rows(), grid, issue, now)
        assertEquals("10:00~12:00, 16:00~17:00", forecast.label(now))
        val policy = UmbrellaAlertPolicy()
        policy.evaluate("closed", true, true, true, forecast, now)
        assertTrue(policy.evaluate("open", true, true, true, forecast, now)!!.message.contains("10:00~12:00, 16:00~17:00"))
        assertNull(policy.evaluate("open", true, true, true, forecast, now))
    }
    @Test fun skyAndPrecipitationRemainDistinctForWeatherDisplay() {
        val sky = rows().map { JSONObject(it.toString()).put("category", "SKY").put("fcstValue", "1") }
        val forecast = KmaForecast.parse(rows() + sky, grid, issue, now)
        assertEquals("clear", forecast.hours.first().condition)
        assertEquals("rain", forecast.hours[1].condition)
        assertEquals("rain-snow", forecast.hours[2].condition)
        assertEquals("shower", forecast.hours[7].condition)
        assertEquals("snow", forecast.hours[9].condition)
        assertFalse(forecast.hours[9].rainy)
        for ((code, expected) in mapOf("3" to "partly-cloudy", "4" to "cloudy")) {
            val dry = KmaForecast.parse(rows().map { it.put("fcstValue", "0") } + sky.map { it.put("fcstValue", code) }, grid, issue, now)
            assertEquals(expected, dry.hours.first().condition)
            assertTrue(dry.windows(now).isEmpty())
        }
        val snowy = KmaForecast.parse(rows().map { it.put("fcstValue", "3") } + sky, grid, issue, now)
        val policy = UmbrellaAlertPolicy(); policy.evaluate("closed", true, true, true, snowy, now)
        assertNull(policy.evaluate("open", true, true, true, snowy, now))
    }
    @Test fun missingSkyDoesNotBecomeClearAndConflictingWeatherIsRejected() {
        assertNull(KmaForecast.parse(rows(), grid, issue, now).hours.first().condition)
        val sky = JSONObject(rows()[0].toString()).put("category", "SKY").put("fcstValue", "4")
        failure(WeatherFailure.INVALID_DATA) { KmaForecast.parse(rows() + sky + JSONObject(sky.toString()).put("fcstValue", "1"), grid, issue, now) }
        failure(WeatherFailure.INVALID_DATA) { KmaForecast.parse(rows() + JSONObject(sky.toString()).put("fcstValue", "2"), grid, issue, now) }
        // Rain and mixed rain/snow are both wet, but conflicting PTY values must not be merged.
        failure(WeatherFailure.INVALID_DATA) { KmaForecast.parse(rows() + JSONObject(rows()[1].toString()).put("fcstValue", "2"), grid, issue, now) }
    }
    @Test fun completeDryForecastIsValid() {
        val forecast = KmaForecast.parse(rows().map { it.put("fcstValue", "0") }, grid, issue, now)
        assertTrue(forecast.fresh(now)); assertTrue(forecast.windows(now).isEmpty())
        val policy = UmbrellaAlertPolicy(); policy.evaluate("closed", true, true, true, forecast, now)
        assertNull(policy.evaluate("open", true, true, true, forecast, now))
    }
    @Test fun missingInvalidAndConflictingReadingsNeverBecomeDry() {
        failure(WeatherFailure.INVALID_DATA) { KmaForecast.parse(rows().drop(1), grid, issue, now) }
        for (invalid in listOf("-900", "5", "", "NaN")) failure(WeatherFailure.INVALID_DATA) {
            KmaForecast.parse(rows().also { it[0].put("fcstValue", invalid) }, grid, issue, now)
        }
        failure(WeatherFailure.INVALID_DATA) { KmaForecast.parse(rows().also { it[0].put("fcstValue", JSONObject.NULL) }, grid, issue, now) }
        failure(WeatherFailure.INVALID_DATA) { KmaForecast.parse(rows() + JSONObject(rows()[0].toString()).put("fcstValue", "1"), grid, issue, now) }
        failure(WeatherFailure.INVALID_DATA) { KmaForecast.parse(rows().also { it[0].put("fcstDate", "20260230") }, grid, issue, now) }
    }
    @Test fun wrongGridAndWrongIssueAreRejected() {
        failure(WeatherFailure.INVALID_DATA) { KmaForecast.parse(rows(), ForecastGrid(61, 127), issue, now) }
        failure(WeatherFailure.INVALID_DATA) { KmaForecast.parse(rows(), grid, ForecastIssue(issue.date, "0500"), now) }
    }
    @Test fun midnightIsClippedAndRequiresRefreshOnNextDay() {
        val late = KmaForecast.timestamp("20261031", "2300") + 30 * 60_000
        val forecast = KmaForecast.parse(rows().map { it.put("fcstValue", "1") }, grid, issue, late)
        assertEquals("23:00~00:00", forecast.label(late))
        assertFalse(forecast.fresh(KmaForecast.timestamp("20261101", "0000")))
        assertEquals(KmaForecast.timestamp("20261101", "0000"), KmaForecast.timestamp("20261031", "2400"))
    }
    @Test fun incompletePagesAndApiErrorsAreRejected() {
        failure(WeatherFailure.INVALID_DATA) { KmaForecast.page(page(rows(), 1001), 1) }
        failure(WeatherFailure.INVALID_DATA) { KmaForecast.page(page(rows(), number = 2), 1) }
        for ((code, reason) in mapOf("03" to WeatherFailure.NO_DATA, "30" to WeatherFailure.AUTHORIZATION, "22" to WeatherFailure.QUOTA)) {
            failure(reason) { KmaForecast.page(JSONObject().put("response", JSONObject().put("header", JSONObject().put("resultCode", code))), 1) }
        }
    }
    @Test fun clientReadsAllPagesBeforeDecodingAndSendsGridNotLocation() {
        val padding = (1..990).map { JSONObject(rows()[0].toString()).put("category", "TMP") }
        val all = padding + rows()
        val queries = mutableListOf<Map<String, String>>()
        val client = KmaWeatherClient("local-test-key", { url ->
            assertEquals("apihub.kma.go.kr", url.host); assertTrue(url.path.endsWith("/getVilageFcst"))
            val query = url.query.split("&").associate { it.split("=", limit = 2).let { p -> p[0] to URLDecoder.decode(p[1], "UTF-8") } }
            queries.add(query)
            val number = query.getValue("pageNo").toInt()
            page(all.drop((number - 1) * 1000).take(1000), all.size, number).toString()
        }, { now })
        assertEquals("10:00~12:00, 16:00~17:00", client.download(37.5665, 126.9780).label(now))
        assertEquals(2, queries.size)
        assertEquals("60", queries[0]["nx"]); assertEquals("127", queries[0]["ny"])
        assertFalse(queries[0].containsKey("latitude")); assertFalse(queries[0].containsKey("longitude"))
        assertEquals("0800", queries[0]["base_time"])
    }
    @Test fun fallbackIsLimitedToOnePreviousIssueForNoData() {
        var count = 0
        val result = KmaWeatherClient("test", { url ->
            count++
            if (count == 1) "{\"response\":{\"header\":{\"resultCode\":\"03\"}}}"
            else { assertTrue(url.query.contains("base_time=0500")); page(rows().map { it.put("baseTime", "0500") }).toString() }
        }, { now }).download(37.5665, 126.9780)
        assertEquals(2, count); assertTrue(result.fresh(now))
        count = 0
        failure(WeatherFailure.NO_DATA) { KmaWeatherClient("test", { count++; throw WeatherException(WeatherFailure.NO_DATA) }, { now }).download(37.56, 126.98) }
        assertEquals(2, count)
    }
    @Test fun authorizationNetworkAndMissingKeyDoNotRetryOrReturnDry() {
        for (reason in listOf(WeatherFailure.AUTHORIZATION, WeatherFailure.QUOTA, WeatherFailure.NETWORK)) {
            var count = 0
            failure(reason) { KmaWeatherClient("test", { count++; throw WeatherException(reason) }, { now }).download(37.56, 126.98) }
            assertEquals(1, count)
        }
        failure(WeatherFailure.CONFIGURATION) { KmaWeatherClient("", { error("Must not call HTTP") }).download(37.56, 126.98) }
        failure(WeatherFailure.INVALID_DATA) { KmaWeatherClient("test", { "<html>upstream error</html>" }).download(37.56, 126.98) }
    }
}
