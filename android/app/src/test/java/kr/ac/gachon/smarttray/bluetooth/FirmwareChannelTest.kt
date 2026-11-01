package kr.ac.gachon.smarttray.bluetooth

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture

class FirmwareChannelTest {
    private class Harness {
        data class Timer(val delay: Long, val action: () -> Unit, var cancelled: Boolean = false)
        val writes = mutableListOf<JSONObject>()
        val timers = mutableListOf<Timer>()
        val channel = FirmwareChannel({ bytes ->
            assertEquals(10, bytes.last().toInt())
            assertTrue(bytes.size <= FirmwareChannel.MAX_REQUEST_BYTES + 1)
            writes.add(JSONObject(bytes.toString(Charsets.UTF_8)))
            CompletableFuture.completedFuture(Unit)
        }, { delay, action ->
            val task = Timer(delay, action); timers.add(task)
            val cancel: () -> Unit = { task.cancelled = true }; cancel
        }, { it() })
        fun ack(status: String = "accepted", reason: String = "") = channel.ack(FirmwareMessage.Ack(writes.last().getString("req"), status, reason))
        fun expire(delay: Long) = timers.toList().filter { !it.cancelled && it.delay == delay }.forEach { it.action() }
    }
    private fun cmd() = JSONObject().put("type", "cmd").put("space", 1).put("action", "start_steril")
    @Test fun handshakeAndQueueWaitForStateAfterEarlyAck() {
        val h = Harness(); val handshake = h.channel.start()
        val command = h.channel.request(cmd())
        assertEquals("get_state", h.writes.single().getString("type"))
        h.ack(); assertTrue(handshake.get().status == "accepted"); assertEquals(1, h.writes.size)
        h.channel.stateReceived(); assertEquals(2, h.writes.size)
        h.channel.ack(FirmwareMessage.Ack("old", "accepted", "")); assertFalse(command.isDone)
        h.ack(); assertTrue(command.get().status == "accepted")
    }
    @Test fun rejectedTimeoutAndDisconnectNeverReplayManualStart() {
        val h = Harness(); h.channel.start(); h.ack()
        val rejected = h.channel.request(cmd()); h.channel.stateReceived()
        h.ack("rejected", "no_item"); assertTrue(rejected.isCompletedExceptionally)
        val timeout = h.channel.request(cmd()); h.channel.stateReceived()
        h.expire(FirmwareChannel.ACK_TIMEOUT_MS); assertTrue(timeout.isCompletedExceptionally)
        repeat(5) { h.channel.stateReceived() }; assertEquals(3, h.writes.size)
        val queued = h.channel.request(cmd()); h.channel.close()
        assertTrue(queued.isCompletedExceptionally)
        assertTrue(h.channel.request(cmd()).isCompletedExceptionally)
    }
    @Test fun stalledSensorStreamExpiresUnsentRequests() {
        val h = Harness(); val pending = h.channel.request(cmd())
        h.expire(FirmwareChannel.QUEUE_TIMEOUT_MS)
        assertTrue(pending.isCompletedExceptionally)
        h.channel.stateReceived(); assertTrue(h.writes.isEmpty())
    }
    @Test fun terminalAckDoesNotMasqueradeAsCommandAcceptance() {
        val h = Harness(); val pending = h.channel.start()
        h.ack("completed", "duration_elapsed"); assertFalse(pending.isDone)
        h.ack(); assertEquals("accepted", pending.get().status)
    }
    @Test fun queuedWeatherExpiresBeforeItsOriginalForecastDeadline() {
        var now = 1000L
        val writes = mutableListOf<JSONObject>()
        val channel = FirmwareChannel({ bytes ->
            writes.add(JSONObject(bytes.toString(Charsets.UTF_8))); CompletableFuture.completedFuture(Unit)
        }, { _, _ -> {} }, { it() }, clock = { now })
        channel.request(JSONObject().put("type", "weather").put("valid", true).put("icon", "sunny")
            .put("line1", "SUNNY").put("windows", JSONArray().put("NO RAIN/SNOW")).put("ttl_s", 5))
        now += 5000
        channel.stateReceived()
        assertFalse(writes.single().getBoolean("valid"))
        assertEquals(0, writes.single().getInt("ttl_s"))
    }
    @Test fun allConfigurationsFitActualAvrBufferAndPreserveIndependentSettings() {
        fun effect() = JSONObject().put("enabled", true).put("onDetect", true).put("onDoor", false).put("duration", 30)
        val preferences = JSONObject().put("trays", JSONArray((1..2).map { JSONObject()
            .put("led", JSONObject().put("enabled", false).put("onDetect", true).put("onDoor", true)).put("effect", effect()) }))
            .put("umbrella", JSONObject().put("effect", effect()).put("fan", JSONObject().put("duration", 60)))
        val frames = FirmwareSettings.frames(preferences)
        frames.forEach { assertTrue(it.put("req", "a1234567890").toString().toByteArray().size <= 167) }
        assertEquals(0, frames[0].getInt("led_en")); assertFalse(frames[0].has("onDetect"))
        assertEquals(60, frames[2].getInt("fan_sec")); assertEquals(30, frames[2].getInt("steril_sec"))
        assertFalse(frames[0].has("fan_sec")); assertFalse(frames[2].has("alias"))
    }
}
