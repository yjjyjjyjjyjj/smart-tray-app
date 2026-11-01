package kr.ac.gachon.smarttray.bluetooth

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArduinoJsonProtocolTest {
    private val now = 1_000_000L
    // Field names and limits copied from sendStateSnapshot in main_edit.c.ino v1.1.
    private fun state(boot: Long = 3, seq: Long = 42) = JSONObject("""{"type":"state","v":1,"boot":$boot,"seq":$seq,"door":{"state":"open","sensor":"ok"},"trays":[{"id":1,"present":true,"weight":128.5,"sensor":"ok","led":"normal","steril_ms":0},{"id":2,"present":false,"weight":0.0,"sensor":"ok","led":"off","steril_ms":0}],"umbrella":{"present":true,"sensor":"ok","led":"steril","steril_ms":8000},"fan":{"active":true,"rem_ms":15000}}""")
    private fun bytes(value: JSONObject) = (value.toString() + "\r\n").toByteArray()
    @Test fun decodesEveryPossibleSplitAndMergedAckWithoutInventingReadings() {
        val wire = bytes(state())
        for (split in 0..wire.size) {
            val protocol = ArduinoJsonProtocol { now }
            val result = protocol.feedMessages(wire.copyOfRange(0, split)) + protocol.feedMessages(wire.copyOfRange(split, wire.size))
            val reading = (result.single() as FirmwareMessage.State).reading
            assertEquals(128.5, reading.trays[0].weight, 0.0)
            assertEquals(now + 8000, reading.umbrella!!.sterilizationUntil)
            assertEquals(now + 15000, reading.umbrella.fanUntil)
            assertTrue(reading.trays[0].ledActive)
        }
        val protocol = ArduinoJsonProtocol { now }
        val messages = protocol.feedMessages(bytes(state()) + "{\"type\":\"ack\",\"req\":\"a123\",\"status\":\"accepted\"}\n".toByteArray() + bytes(state(seq = 43)))
        assertEquals(3, messages.size)
        assertTrue(messages[1] is FirmwareMessage.Ack)
    }
    @Test fun rejectsInvalidFieldsWithoutConsumingSequenceAndRecoversAtNextLine() {
        val invalid = listOf<(JSONObject) -> Unit>(
            { it.put("v", 2) }, { it.put("boot", -1) }, { it.put("seq", 1.5) }, { it.put("seq", "42") },
            { it.getJSONObject("door").put("state", "ajar") },
            { it.getJSONArray("trays").getJSONObject(0).put("id", 2) },
            { it.getJSONArray("trays").getJSONObject(0).put("present", "true") },
            { it.getJSONArray("trays").getJSONObject(0).put("weight", -0.1) },
            { it.getJSONArray("trays").getJSONObject(0).put("weight", 3000.1) },
            { it.getJSONArray("trays").getJSONObject(0).put("sensor", "broken") },
            { it.getJSONArray("trays").getJSONObject(0).remove("led") },
            { it.getJSONObject("umbrella").put("steril_ms", 30001) },
            { it.getJSONObject("fan").put("rem_ms", 60001) }, { it.remove("umbrella") }, { it.remove("fan") }
        )
        invalid.forEach { corrupt ->
            val protocol = ArduinoJsonProtocol { now }
            assertTrue(protocol.feed(bytes(state().also(corrupt))).isEmpty())
            assertEquals(1, protocol.feed(bytes(state())).size)
        }
    }
    @Test fun malformedOversizedNonUtf8AndTrailingGarbageRecoverWithoutFreshness() {
        val protocol = ArduinoJsonProtocol { now }
        val broken = "not-json\n".toByteArray() + ByteArray(4096) { 65 } + "\n".toByteArray() +
            byteArrayOf(0xc3.toByte(), 0x28, 10) + (state().toString() + " garbage\n").toByteArray()
        assertTrue(protocol.feed(broken).isEmpty())
        assertEquals(1, protocol.feed(bytes(state())).size)
    }
    @Test fun dropsDuplicateOutOfOrderAndPreviousBootAndSupportsBothCounterRollovers() {
        val protocol = ArduinoJsonProtocol { now }
        assertEquals(1, protocol.feed(bytes(state(65535, 0xffff_fffeL))).size)
        assertEquals(1, protocol.feed(bytes(state(65535, 0xffff_ffffL))).size)
        assertEquals(1, protocol.feed(bytes(state(65535, 0))).size)
        assertTrue(protocol.feed(bytes(state(65535, 0))).isEmpty())
        assertTrue(protocol.feed(bytes(state(65535, 0xffff_ffffL))).isEmpty())
        assertEquals(1, protocol.feed(bytes(state(0, 1))).size)
        assertTrue(protocol.feed(bytes(state(65535, 9))).isEmpty())
        protocol.reset()
        assertEquals(1, protocol.feed(bytes(state(1, 1))).size)
    }
    @Test fun errorsDoNotBecomeAnEmptyHealthySensorOrDoorOpening() {
        val reading = ArduinoJsonProtocol { now }.feed(bytes(state().also {
            it.getJSONObject("door").put("sensor", "error")
            it.getJSONArray("trays").getJSONObject(0).put("sensor", "error")
            it.getJSONObject("umbrella").put("sensor", "waiting")
        })).single()
        assertEquals("unknown", reading.door)
        assertEquals("error", reading.trays[0].sensor)
        assertEquals("waiting", reading.umbrella!!.sensor)
    }
}
