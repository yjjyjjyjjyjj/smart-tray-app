package kr.ac.gachon.smarttray.bluetooth

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.TimeZone

class ReminderPoliciesTest {
    private fun at(value: String) = SimpleDateFormat("yyyy-MM-dd HH:mm").apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }.parse(value)!!.time
    private val now = at("2026-10-31 09:30")
    private fun forecast(rain: Double = 1.0, fetchedAt: Long = now) = RainForecast(fetchedAt, "Asia/Seoul", listOf(
        RainHour(at("2026-10-31 07:00"), at("2026-10-31 08:00"), rain > 0),
        RainHour(at("2026-10-31 10:00"), at("2026-10-31 11:00"), rain > 0),
        RainHour(at("2026-10-31 11:00"), at("2026-10-31 12:00"), rain > 0),
        RainHour(at("2026-10-31 16:00"), at("2026-10-31 17:00"), rain > 0),
        RainHour(at("2026-11-01 10:00"), at("2026-11-01 11:00"), rain > 0)))
    @Test fun timerUsesElapsedDurationAndNotPacketCount() {
        val p = DoorLeftOpenPolicy()
        assertNull(p.evaluate("open", true, true, 30, 1000))
        assertNull(p.evaluate("open", true, true, 30, 30999))
        assertEquals("door-left-open", p.evaluate("open", true, true, 30, 31000)?.kind)
        assertNull(p.evaluate("open", true, true, 30, 32000))
    }
    @Test fun disconnectedOrStaleDoorKeepsOriginalDeadlineAndUsesConfirmationMessage() {
        for (door in listOf("unknown", "open", "closed")) {
            val p = DoorLeftOpenPolicy()
            p.evaluate("open", true, true, 30, 1000)
            assertNull(p.evaluate(door, false, true, 30, 11000))
            assertEquals(20000L, p.remaining(30, 11000))
            assertNull(p.evaluate(door, false, true, 30, 30999))
            val alert = p.evaluate(door, false, true, 30, 31000)
            assertEquals("현관문이 닫혔는지 확인해 주세요", alert?.title)
            assertTrue(alert!!.message.contains("30초 동안 닫힘 신호"))
            assertNull(p.remaining(30, 31000))
            assertNull(p.evaluate("open", true, true, 30, 32000)) // no repeat after reconnect
        }
    }
    @Test fun confirmedCloseCancelsEvenAfterReconnectAndNextOpeningGetsNewDeadline() {
        val p = DoorLeftOpenPolicy()
        p.evaluate("open", true, true, 10, 0)
        p.evaluate("unknown", false, true, 10, 5000)
        p.evaluate("closed", true, true, 10, 9000)
        assertNull(p.remaining(10, 10000))
        assertNull(p.evaluate("unknown", false, true, 10, 11000))
        assertNull(p.evaluate("open", true, true, 10, 12000))
        assertNotNull(p.evaluate("open", true, true, 10, 22000))
    }
    @Test fun reconnectOpenDoesNotRestartTheTimerAndUnknownCannotStartOne() {
        val p = DoorLeftOpenPolicy()
        assertNull(p.evaluate("unknown", false, true, 30, 0))
        assertNull(p.remaining(30, 99999))
        p.evaluate("open", true, true, 30, 100000)
        p.evaluate("unknown", false, true, 30, 110000)
        assertNull(p.evaluate("open", true, true, 30, 120000))
        assertEquals(100000L, p.openedAt)
        assertEquals("현관문이 계속 열려 있어요", p.evaluate("open", true, true, 30, 130000)?.title)
    }
    @Test fun durationIsBoundedToOneThroughThreeHundredSecondsAndManualResetCancels() {
        for ((input, seconds) in listOf(0 to 1, 1 to 1, 300 to 300, 600 to 300)) {
            val p = DoorLeftOpenPolicy()
            p.evaluate("open", true, true, input, 0)
            assertNull(p.evaluate("unknown", false, true, input, seconds * 1000L - 1))
            assertNotNull(p.evaluate("unknown", false, true, input, seconds * 1000L))
        }
        val p = DoorLeftOpenPolicy()
        p.evaluate("open", true, true, 30, 0); p.reset()
        assertNull(p.evaluate("unknown", false, true, 30, 30000))
        assertNull(p.remaining(30, 30000))
    }
    @Test fun disabledWhileDisconnectedDoesNotPostButEnablingUsesOriginalTime() {
        val p = DoorLeftOpenPolicy()
        p.evaluate("open", true, true, 30, 0)
        assertNull(p.evaluate("unknown", false, false, 30, 30000))
        assertNotNull(p.evaluate("unknown", false, true, 30, 30001))
    }
    @Test fun storedSixHundredSecondSettingMigratesWithoutLosingOtherPreferences() {
        val previous = org.json.JSONObject().put("notifications", org.json.JSONObject()
            .put("doorOpenSeconds", 600).put("umbrella", false))
            .put("trays", org.json.JSONArray().put(org.json.JSONObject().put("alias", "현관 왼쪽")))
        val next = TrayRepository.sanitize(previous)
        assertEquals(300, next.getJSONObject("notifications").getInt("doorOpenSeconds"))
        assertFalse(next.getJSONObject("notifications").getBoolean("umbrella"))
        assertEquals("현관 왼쪽", next.getJSONArray("trays").getJSONObject(0).getString("alias"))
        assertEquals(30, TrayRepository.sanitize(org.json.JSONObject()).getJSONObject("notifications").getInt("doorOpenSeconds"))
    }
    @Test fun changingThresholdKeepsOriginalOpenTimeAndDisabledNeverPosts() {
        val p = DoorLeftOpenPolicy(); p.evaluate("open", true, true, 60, 0)
        assertNull(p.evaluate("open", true, false, 5, 6000))
        assertNotNull(p.evaluate("open", true, true, 5, 6000))
    }
    @Test fun rainWindowsMergeOnlyRemainingHoursOfLocalToday() {
        assertEquals("10:00~12:00, 16:00~17:00", forecast().label(now))
        assertTrue(forecast(0.0).windows(now).isEmpty())
        assertTrue(forecast(fetchedAt = now - WEATHER_FRESH_MS - 1).windows(now).isEmpty())
        val nearMidnight = at("2026-10-31 23:30")
        val f = RainForecast(nearMidnight, "Asia/Seoul", listOf(RainHour(at("2026-10-31 23:00"), at("2026-11-01 00:00"), true), RainHour(at("2026-11-01 00:00"), at("2026-11-01 01:00"), true)))
        assertEquals(1, f.windows(nearMidnight).size)
        assertEquals(at("2026-11-01 00:00"), f.windows(nearMidnight).single().end)
    }
    @Test fun umbrellaRequiresOpeningStoredUmbrellaAndRainAndDoesNotRepeat() {
        val p = UmbrellaAlertPolicy()
        assertNull(p.evaluate("open", true, true, true, forecast(), now)) // baseline
        p.evaluate("closed", true, true, true, forecast(), now)
        val notice = p.evaluate("open", true, true, true, forecast(), now)
        assertEquals("umbrella", notice?.kind)
        assertTrue(notice!!.message.contains("10:00~12:00, 16:00~17:00"))
        assertNull(p.evaluate("open", true, true, true, forecast(), now))
    }
    @Test fun dryEmptyUnknownStaleAndDisabledNeverSendUmbrellaAlerts() {
        for (present in listOf(true, false, null)) for (rain in listOf(0.0, 1.0)) for (enabled in listOf(true, false)) for (fresh in listOf(true, false)) {
            val p = UmbrellaAlertPolicy(); p.evaluate("closed", present, fresh, enabled, forecast(rain), now)
            val notice = p.evaluate("open", present, fresh, enabled, forecast(rain), now)
            assertEquals(present == true && rain > 0 && enabled && fresh, notice != null)
        }
    }
    @Test fun forecastCanArriveAfterDoorOpensButClosedDoorCancelsIt() {
        val p = UmbrellaAlertPolicy(); p.evaluate("closed", true, true, true, null, now)
        assertNull(p.evaluate("open", true, true, true, null, now)); assertTrue(p.needsForecast())
        assertNotNull(p.evaluate("open", true, true, true, forecast(), now))
        p.evaluate("closed", true, true, true, null, now); p.evaluate("open", true, true, true, null, now)
        p.evaluate("closed", true, true, true, null, now)
        assertNull(p.evaluate("closed", true, true, true, forecast(), now))
    }
    @Test fun placingOrRemovingUmbrellaWhileDoorStaysOpenDoesNotAlert() {
        val p = UmbrellaAlertPolicy(); p.evaluate("closed", false, true, true, forecast(), now)
        assertNull(p.evaluate("open", false, true, true, forecast(), now))
        assertNull(p.evaluate("open", true, true, true, forecast(), now))
        assertNull(p.evaluate("open", false, true, true, forecast(), now))
    }
}
