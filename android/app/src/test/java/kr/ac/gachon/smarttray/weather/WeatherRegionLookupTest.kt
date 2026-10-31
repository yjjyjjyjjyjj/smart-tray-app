package kr.ac.gachon.smarttray.weather

import org.junit.Assert.*
import org.junit.Test

class WeatherRegionLookupTest {
    @Test fun regionIncludesProvinceCityAndDistrictWithoutNeighbourhood() {
        assertEquals("경기도 성남시 수정구", weatherRegionName("경기도", null, "성남시", "수정구 복정동"))
        assertEquals("서울특별시 중구", weatherRegionName("서울특별시", "중구", null, "명동"))
    }
    @Test fun duplicatedAndBlankAddressPartsAreRemoved() {
        assertEquals("경기도 성남시 수정구", weatherRegionName(" 경기도 ", "성남시", "성남시 수정구", "수정구"))
        assertEquals("세종특별자치시", weatherRegionName("세종특별자치시", "", null, "어진동"))
        assertNull(weatherRegionName(null, " ", null, "복정동"))
    }
    private class Fixture {
        lateinit var reply: (String?) -> Unit
        lateinit var timeout: () -> Unit
        val values = mutableListOf<String?>()
        var timerCancelled = false
        fun start() = WeatherRegionLookup({ reply = it }, {
            timeout = it; val cancel: () -> Unit = { timerCancelled = true }; cancel
        }, { values.add(it) }).start()
    }
    @Test fun slowLookupFinishesUnavailableAndIgnoresLateResults() {
        val f = Fixture(); f.start(); f.timeout(); f.reply("서울특별시 중구")
        assertEquals(listOf<String?>(null), f.values); assertTrue(f.timerCancelled)
    }
    @Test fun successfulLookupFinishesOnceAndCancelsTimer() {
        val f = Fixture(); f.start(); f.reply("서울특별시 중구"); f.timeout(); f.reply(null)
        assertEquals(listOf("서울특별시 중구"), f.values); assertTrue(f.timerCancelled)
    }
    @Test fun cancelWhenLocationChangesIgnoresOldRegion() {
        val f = Fixture(); val cancel = f.start(); cancel(); f.reply("이전 지역"); f.timeout()
        assertTrue(f.values.isEmpty()); assertTrue(f.timerCancelled)
    }
    @Test fun absentGeocoderOrEmptyResultIsUnavailable() {
        val f = Fixture(); f.start(); f.reply("   "); assertEquals(listOf<String?>(null), f.values)
        val values = mutableListOf<String?>()
        WeatherRegionLookup({ throw IllegalStateException("No geocoder") }, { {} }, { values.add(it) }).start()
        assertEquals(listOf<String?>(null), values)
    }
    @Test fun synchronousResultAlsoCancelsTimeout() {
        var cancelled = false; val values = mutableListOf<String?>()
        WeatherRegionLookup({ it("경기도 성남시") }, { { cancelled = true } }, { values.add(it) }).start()
        assertEquals(listOf("경기도 성남시"), values); assertTrue(cancelled)
    }
}
