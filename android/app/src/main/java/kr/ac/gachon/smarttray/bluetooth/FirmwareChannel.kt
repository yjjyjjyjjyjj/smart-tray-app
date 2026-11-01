package kr.ac.gachon.smarttray.bluetooth

import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Main-thread request queue. UNO SoftwareSerial cannot reliably receive while sending.
 * Send one frame after a complete state, never immediately after its early ACK. */
class FirmwareChannel(
    private val write: (ByteArray) -> CompletableFuture<Unit>,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val dispatch: (() -> Unit) -> Unit,
    private val onAck: (FirmwareMessage.Ack) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Request(val id: String, val payload: JSONObject, val result: CompletableFuture<FirmwareMessage.Ack>, val queuedAt: Long)
    private val queue = ArrayDeque<Request>()
    private var active: Request? = null
    private var cancelTimeout: (() -> Unit)? = null
    private var closed = false
    private val prefix = "a" + UUID.randomUUID().toString().take(4)
    private var counter = 0L
    fun request(payload: JSONObject): CompletableFuture<FirmwareMessage.Ack> {
        val result = CompletableFuture<FirmwareMessage.Ack>()
        if (closed || queue.size >= 16) { result.completeExceptionally(IOException("장치 연결과 요청 상태를 확인해 주세요.")); return result }
        val id = prefix + (++counter).toString(36)
        require(id.length <= 11)
        val value = JSONObject(payload.toString()).put("req", id)
        require(value.toString().toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) { "장치 수신 길이를 초과했어요." }
        val pending = Request(id, value, result, clock())
        queue.addLast(pending)
        val cancelQueued = schedule(QUEUE_TIMEOUT_MS) {
            if (queue.remove(pending)) result.completeExceptionally(IOException("센서 수신이 멈춰 장치 요청을 보내지 못했어요. 연결을 확인해 주세요."))
        }
        result.whenComplete { _, _ -> cancelQueued() }
        return result
    }
    fun start(): CompletableFuture<FirmwareMessage.Ack> {
        val result = request(JSONObject().put("type", "get_state"))
        pump() // Wakes btPeerActive even if no heartbeat has been sent yet.
        return result
    }
    fun stateReceived() { pump() }
    fun ack(ack: FirmwareMessage.Ack) {
        onAck(ack)
        val pending = active?.takeIf { it.id == ack.request } ?: return
        if (ack.status !in setOf("accepted", "rejected")) return
        active = null; cancelTimeout?.invoke(); cancelTimeout = null
        if (ack.status == "accepted") pending.result.complete(ack)
        else pending.result.completeExceptionally(IOException(reasonMessage(ack.reason)))
        // The firmware may now be transmitting ~500 bytes of state; wait for its LF.
    }
    private fun pump() {
        if (closed || active != null || queue.isEmpty()) return
        val pending = queue.removeFirst(); active = pending
        cancelTimeout = schedule(ACK_TIMEOUT_MS) {
            if (active === pending) {
                active = null; cancelTimeout = null
                pending.result.completeExceptionally(IOException("장치 응답을 확인하지 못했어요. 상태를 확인한 뒤 다시 시도해 주세요."))
            }
        }
        val payload = JSONObject(pending.payload.toString())
        if (payload.optString("type") == "weather" && payload.optBoolean("valid")) {
            // Queue time and one second for UART delivery must not extend the original expiry.
            val ttl = payload.getLong("ttl_s") - ((clock() - pending.queuedAt).coerceAtLeast(0) + 999) / 1000 - 1
            if (ttl <= 0) payload.put("valid", false).put("icon", "none").put("line1", "NO DATA")
                .put("windows", org.json.JSONArray().put("")).put("ttl_s", 0)
            else payload.put("ttl_s", ttl)
        }
        write((payload.toString() + "\n").toByteArray(Charsets.UTF_8)).whenComplete { _, error ->
            if (error != null) dispatch {
                if (active === pending) {
                    active = null; cancelTimeout?.invoke(); cancelTimeout = null
                    pending.result.completeExceptionally(error)
                }
            }
        }
    }
    fun close() {
        closed = true; cancelTimeout?.invoke(); cancelTimeout = null
        val pending = listOfNotNull(active) + queue.toList()
        active = null; queue.clear()
        pending.forEach { it.result.completeExceptionally(IOException("장치 연결이 바뀌어 요청이 취소됐어요.")) }
    }
    companion object {
        const val MAX_REQUEST_BYTES = 167
        const val ACK_TIMEOUT_MS = 6000L
        const val QUEUE_TIMEOUT_MS = 20_000L
        fun reasonMessage(reason: String) = when (reason) {
            "no_item" -> "물건이나 우산을 놓은 뒤 다시 시도해 주세요."
            "sensor_error" -> "센서 상태를 확인한 뒤 다시 시도해 주세요."
            "steril_disabled" -> "살균을 활성화한 뒤 다시 시도해 주세요."
            "already_running" -> "이미 살균 중이에요."
            else -> "장치가 요청을 거절했어요. 설정과 연결을 확인해 주세요."
        }
    }
}

/** Only supported hardware settings. 0/1 booleans are explicitly accepted by the AVR parser
 * and keep even the umbrella frame below its 167-byte input limit. */
object FirmwareSettings {
    fun frames(preferences: JSONObject): List<JSONObject> = (1..3).map { space ->
        val source = if (space == 3) preferences.getJSONObject("umbrella") else preferences.getJSONArray("trays").getJSONObject(space - 1)
        val led = source.optJSONObject("led")
        val effect = source.getJSONObject("effect")
        JSONObject().put("type", "set_config").put("space", space)
            .put("led_en", if (led?.optBoolean("enabled", true) != false) 1 else 0)
            .put("led_door", if (led?.optBoolean("onDoor", true) != false) 1 else 0)
            .put("steril_en", if (effect.getBoolean("enabled")) 1 else 0)
            .put("steril_detect", if (effect.getBoolean("onDetect")) 1 else 0)
            .put("steril_door", if (effect.getBoolean("onDoor")) 1 else 0)
            .put("steril_sec", effect.getInt("duration")).also {
                if (space == 3) it.put("fan_sec", source.getJSONObject("fan").getInt("duration"))
            }
    }
}
