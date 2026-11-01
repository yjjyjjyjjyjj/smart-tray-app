package kr.ac.gachon.smarttray.bluetooth

import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

sealed interface FirmwareMessage {
    data class State(val reading: DeviceReading) : FirmwareMessage
    data class Ack(val request: String, val status: String, val reason: String) : FirmwareMessage
}

/** main_edit.c/main_edit.c.ino v1.1: LF-delimited UTF-8 JSON (wire version 1).
 * Owned by one reader. Broken/oversized lines never advance sequence or sensor freshness. */
class ArduinoJsonProtocol(private val clock: () -> Long = System::currentTimeMillis) : FirmwareProtocol {
    override val ready = true
    private val buffer = ByteArray(MAX_STATE_BYTES)
    private var length = 0
    private var dropping = false
    private var boot: Long? = null
    private var sequence: Long? = null
    override fun reset() { length = 0; dropping = false; boot = null; sequence = null }
    override fun feed(chunk: ByteArray) = feedMessages(chunk).filterIsInstance<FirmwareMessage.State>().map { it.reading }
    fun feedMessages(chunk: ByteArray): List<FirmwareMessage> {
        val messages = mutableListOf<FirmwareMessage>()
        for (byte in chunk) when (byte.toInt()) {
            13 -> Unit
            10 -> {
                if (!dropping && length > 0) runCatching { decode() }.getOrNull()?.let(messages::add)
                length = 0; dropping = false
            }
            else -> if (!dropping) {
                if (length == buffer.size) { dropping = true; length = 0 }
                else buffer[length++] = byte
            }
        }
        return messages
    }
    private fun decode(): FirmwareMessage? {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(buffer, 0, length)).toString()
        val tokener = JSONTokener(text)
        val json = tokener.nextValue() as? JSONObject ?: return null
        require(tokener.nextClean() == '\u0000')
        return when (json.getString("type")) {
            "ack" -> {
                val request = json.getString("req")
                val status = json.getString("status")
                require(request.matches(Regex("[A-Za-z0-9_-]{1,11}")))
                require(status in setOf("accepted", "rejected", "completed", "stopped"))
                FirmwareMessage.Ack(request, status, json.optString("reason", ""))
            }
            "state" -> state(json)
            else -> null
        }
    }
    private fun state(json: JSONObject): FirmwareMessage.State? {
        require(integer(json, "v", 1) == 1L)
        // Both counters wrap on AVR, including boot 65535 -> 0 and seq UINT32_MAX -> 0.
        val nextBoot = integer(json, "boot", 65535)
        val nextSequence = integer(json, "seq", 0xffff_ffffL)
        val door = json.getJSONObject("door")
        val doorHealth = health(door)
        val doorState = door.getString("state").also { require(it in setOf("open", "closed")) }
        val trays = json.getJSONArray("trays")
        require(trays.length() == 2)
        val now = clock()
        val readings = (0 until trays.length()).map { index ->
            val tray = trays.getJSONObject(index)
            val id = integer(tray, "id", 2).toInt().also { require(it in TRAY_IDS) }
            val weight = number(tray, "weight").also { require(it in 0.0..3000.0) }
            val output = output(tray, now)
            TrayReading(id, boolean(tray, "present"), weight, health(tray), output.first, output.second)
        }
        require(readings.map { it.id }.toSet() == TRAY_IDS.toSet())
        val umbrella = json.getJSONObject("umbrella")
        val output = output(umbrella, now)
        val fan = json.getJSONObject("fan")
        val active = boolean(fan, "active")
        val remaining = integer(fan, "rem_ms", 60000)
        val reading = DeviceReading(if (doorHealth == "ok") doorState else "unknown", readings,
            UmbrellaReading(boolean(umbrella, "present"), health(umbrella), output.first, output.second,
                active, if (active && remaining > 0) now + remaining else 0), nextBoot, nextSequence)
        if (boot == nextBoot && sequence != null && !forward(nextSequence, sequence!!, 0xffff_ffffL)) return null
        if (boot != null && boot != nextBoot && !forward(nextBoot, boot!!, 65535)) return null
        boot = nextBoot; sequence = nextSequence
        return FirmwareMessage.State(reading)
    }
    private fun output(json: JSONObject, now: Long): Pair<Boolean, Long> {
        val mode = json.getString("led").also { require(it in setOf("off", "normal", "steril")) }
        val remaining = integer(json, "steril_ms", 30000)
        return (mode == "normal") to if (mode == "steril" && remaining > 0) now + remaining else 0
    }
    private fun health(json: JSONObject) = json.getString("sensor").also { require(it in setOf("ok", "waiting", "error")) }
    private fun boolean(json: JSONObject, key: String) = (json.get(key) as? Boolean) ?: error("Invalid $key")
    private fun number(json: JSONObject, key: String): Double = (json.get(key) as? Number)?.toDouble()
        ?.also { require(it.isFinite()) } ?: error("Invalid $key")
    private fun integer(json: JSONObject, key: String, max: Long): Long = number(json, key)
        .also { require(it >= 0 && it <= max.toDouble() && it % 1.0 == 0.0) }.toLong()
    private fun forward(next: Long, previous: Long, mask: Long) = ((next - previous) and mask) in 1..(mask / 2)
    companion object { const val MAX_STATE_BYTES = 2048 }
}
