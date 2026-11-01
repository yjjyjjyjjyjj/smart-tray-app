package kr.ac.gachon.smarttray.bluetooth

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Process-level state. All mutations run on the Android main looper. No WebView ownership. */
object TrayRepository {
    private lateinit var disk: SharedPreferences
    private lateinit var activityHistory: ActivityHistory
    private var initialized = false
    private var preferences = defaultPreferences()
    private var state = JSONObject()
    private val listeners = linkedSetOf<(JSONObject) -> Unit>()
    private var publishing = false
    private var publishPending = false
    private val routeListeners = linkedSetOf<(String) -> Unit>()
    private val policy = DoorAlertPolicy()
    private var pendingRoute: String? = null
    const val PROTOCOL_MESSAGE = "Arduino 상태 수신 대기 중이에요. 장치 전원과 Bluetooth 모드를 확인해 주세요."
    fun initialize(context: Context) {
        if (initialized) return
        disk = context.getSharedPreferences("smart_tray", Context.MODE_PRIVATE)
        activityHistory = ActivityHistory(
            { disk.getString("activities.v1", null) },
            { disk.edit().putString("activities.v1", it).commit() }
        )
        preferences = runCatching { sanitize(JSONObject(disk.getString("preferences", "{}")!!)) }.getOrDefault(defaultPreferences())
        state = JSONObject().put("connection", "disconnected").put("connectionError", "")
            .put("scanState", "idle").put("scanResults", JSONArray()).put("lastReceived", JSONObject.NULL)
            .put("door", "unknown").put("trays", JSONArray(TRAY_IDS.map { id ->
                JSONObject().put("id", id).put("present", false).put("weight", 0.0).put("sensor", "waiting")
                    .put("ledActive", false).put("effectUntil", 0).put("measuredAt", JSONObject.NULL).put("changedAt", JSONObject.NULL)
            })).put("umbrella", JSONObject().put("present", false).put("sensor", "waiting").put("measuredAt", JSONObject.NULL))
            .put("weather", JSONObject().put("status", "idle").put("message", "현재 위치로 날씨를 확인해 주세요.").put("windows", JSONArray()))
            .put("notifications", JSONArray()).put("activities", activityHistory.entries)
            .put("activityStorageError", activityHistory.storageError)
            .put("protocolReady", false).put("protocolMessage", PROTOCOL_MESSAGE).put("deviceName", "아마따")
            .put("configSync", JSONObject().put("status", "offline").put("message", "연결하면 저장한 설정을 장치에 적용해요."))
        initialized = true
    }
    fun snapshot(): JSONObject = JSONObject(state.toString()).put("preferences", JSONObject(preferences.toString()))
    fun observe(listener: (JSONObject) -> Unit) { listeners.add(listener) }
    fun removeObserver(listener: (JSONObject) -> Unit) { listeners.remove(listener) }
    fun observeRoutes(listener: (String) -> Unit) { routeListeners.add(listener) }
    fun removeRouteObserver(listener: (String) -> Unit) { routeListeners.remove(listener) }
    fun publish() {
        publishPending = true
        if (publishing) return
        publishing = true
        try {
            // A policy/config observer can patch state while notifying. Finish this snapshot
            // first, then deliver the new one; never overwrite a newer UI snapshot with an old one.
            do {
                publishPending = false
                val copy = snapshot()
                listeners.toList().forEach { it(copy) }
            } while (publishPending)
        } finally { publishing = false }
    }
    fun patch(block: (JSONObject) -> Unit) { block(state); publish() }
    fun savePreferences(value: JSONObject) {
        val next = sanitize(value)
        check(disk.edit().putString("preferences", next.toString()).commit()) { "설정을 저장하지 못했어요." }
        preferences = next
        publish()
    }
    fun connection(value: String, error: String = "") {
        if (value != "connected") {
            policy.reset(); state.put("lastReceived", JSONObject.NULL).put("door", "unknown").put("protocolReady", false)
                .put("configSync", JSONObject().put("status", "offline").put("message", "연결하면 저장한 설정을 장치에 적용해요."))
        }
        state.put("connection", value).put("connectionError", error)
        publish()
    }
    fun error(message: String) { patch { it.put("connectionError", message) } }
    fun awaitDeviceState() {
        policy.reset()
        patch { it.put("lastReceived", JSONObject.NULL).put("door", "unknown")
            .put("protocolReady", false).put("protocolMessage", PROTOCOL_MESSAGE)
            .put("configSync", JSONObject().put("status", "pending").put("message", "장치 상태와 설정을 다시 확인하고 있어요.")) }
    }
    fun settingSnapshot(): NotificationSettings {
        val flags = preferences.getJSONObject("notifications")
        val trays = preferences.getJSONArray("trays")
        return NotificationSettings(flags.getBoolean("enabled"), flags.getBoolean("door"), flags.getBoolean("belongings"),
            TRAY_IDS.indices.associate { (it + 1) to trays.getJSONObject(it).getString("alias") },
            TRAY_IDS.indices.filter { trays.getJSONObject(it).getBoolean("notification") }.map { it + 1 }.toSet(),
            flags.getBoolean("doorLeftOpen"), flags.getInt("doorOpenSeconds"), flags.getBoolean("umbrella"))
    }
    fun accept(reading: DeviceReading): AlertDecision? {
        if (state.optString("connection") != "connected") return null
        if (reading.door !in listOf("open", "closed", "unknown") || reading.trays.map { it.id }.toSet() != TRAY_IDS.toSet()
            || reading.trays.size != TRAY_IDS.size || reading.trays.any { !it.weight.isFinite() || it.weight !in 0.0..3000.0 || it.sensor !in listOf("ok", "waiting", "error") }) return null
        if (reading.umbrella != null && reading.umbrella.sensor !in listOf("ok", "waiting", "error")) return null
        val now = System.currentTimeMillis()
        if (reading.boot != null && state.has("firmwareBoot") && state.optLong("firmwareBoot") != reading.boot) policy.reset()
        reading.boot?.let { state.put("firmwareBoot", it) }
        reading.sequence?.let { state.put("firmwareSequence", it) }
        state.put("protocolReady", true).put("protocolMessage", "")
        val umbrella = reading.umbrella
        state.put("umbrella", JSONObject().put("present", umbrella?.present ?: false).put("sensor", umbrella?.sensor ?: "waiting")
            .put("ledActive", umbrella?.ledActive ?: false).put("effectUntil", umbrella?.sterilizationUntil ?: 0)
            .put("fanActive", umbrella?.fanActive ?: false).put("fanUntil", umbrella?.fanUntil ?: 0)
            .put("measuredAt", if (umbrella != null && umbrella.sensor != "waiting") now else JSONObject.NULL))
        val old = state.getJSONArray("trays")
        val next = JSONArray(reading.trays.sortedBy { it.id }.map { tray ->
            val previous = old.getJSONObject(tray.id - 1)
            val changed = previous.optString("sensor") != "ok" || previous.optBoolean("present") != tray.present
            if (changed && tray.sensor == "ok" && previous.optString("sensor") == "ok") {
                val alias = preferences.getJSONArray("trays").getJSONObject(tray.id - 1).getString("alias")
                log(if (tray.present) "placed" else "removed", trayActivityTitle(alias, tray.present), "트레이 0${tray.id}", tray.id)
            }
            JSONObject().put("id", tray.id).put("present", tray.present)
                .put("weight", if (tray.sensor == "ok") tray.weight else previous.optDouble("weight", 0.0)).put("sensor", tray.sensor)
                .put("ledActive", tray.ledActive).put("effectUntil", tray.sterilizationUntil)
                .put("changedAt", if (changed) now else previous.optLong("changedAt", now))
                .put("measuredAt", if (tray.sensor == "waiting") JSONObject.NULL else now)
        })
        if (reading.door in listOf("open", "closed") && state.optString("door") != reading.door)
            log("door", if (reading.door == "open") "현관문이 열렸어요" else "현관문이 닫혔어요", "문 감지 센서")
        state.put("door", reading.door).put("trays", next).put("lastReceived", now)
        val alert = policy.evaluate(reading, settingSnapshot())
        publish()
        return alert
    }
    fun addNotification(alert: AlertDecision): JSONObject {
        val notice = JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis())
            .put("read", false).put("title", alert.title).put("message", alert.message).put("kind", alert.kind)
            .put("trayIds", JSONArray(alert.trayIds)).put("items", JSONArray())
        prepend("notifications", notice, 50)
        log("notification", alert.title, alert.message)
        publish()
        return notice
    }
    fun markRead(ids: Set<String>) {
        val notices = state.getJSONArray("notifications")
        for (i in 0 until notices.length()) if (notices.getJSONObject(i).optString("id") in ids) notices.getJSONObject(i).put("read", true)
        publish()
    }
    fun openNotification(route: String?, id: String?) {
        // Notifications posted before the two-tray revision may still target the removed tray.
        val target = if (route == "detail/3") "trays" else route
        if (target !in listOf("home", "trays", "umbrella", "detail/1", "detail/2")) return
        id?.let { markRead(setOf(it)) }
        if (routeListeners.isEmpty()) pendingRoute = target else routeListeners.toList().forEach { it(target!!) }
    }
    fun consumeRoute(): String? = pendingRoute.also { pendingRoute = null }
    fun log(type: String, title: String, description: String, trayId: Int? = null) {
        activityHistory.append(JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis())
            .put("type", type).put("title", title).put("description", description).put("trayId", trayId ?: JSONObject.NULL))
        state.put("activities", activityHistory.entries).put("activityStorageError", activityHistory.storageError)
    }
    private fun prepend(key: String, value: JSONObject, limit: Int) {
        val old = state.getJSONArray(key); val next = JSONArray().put(value)
        for (i in 0 until minOf(old.length(), limit - 1)) next.put(old.get(i))
        state.put(key, next)
    }
    private fun defaultPreferences() = JSONObject().put("schemaVersion", 2).put("setupDone", false)
        .put("notifications", JSONObject().put("enabled", true).put("door", true).put("belongings", true)
            .put("doorLeftOpen", true).put("doorOpenSeconds", 30).put("umbrella", true))
        .put("umbrella", JSONObject().put("fan", JSONObject().put("duration", 0))
            .put("effect", JSONObject().put("enabled", false).put("onDetect", false).put("onDoor", false).put("duration", 10)))
        .put("trays", JSONArray(TRAY_IDS.map { id -> JSONObject().put("id", id).put("alias", "${id}번 트레이").put("notification", true)
            .put("led", JSONObject().put("enabled", true).put("onDetect", true).put("onDoor", true))
            .put("effect", JSONObject().put("enabled", false).put("onDetect", false).put("onDoor", false).put("duration", 10)) }))
    internal fun sanitize(input: JSONObject): JSONObject {
        val result = defaultPreferences().put("setupDone", input.optBoolean("setupDone", false))
        // Preferences remain available offline and are synchronized after a valid device snapshot.
        val fanDuration = input.optJSONObject("umbrella")?.optJSONObject("fan")?.opt("duration")
        val fanSeconds = if (fanDuration is Number && fanDuration.toDouble() in listOf(0.0, 15.0, 30.0, 60.0)) fanDuration.toInt() else 0
        result.getJSONObject("umbrella").getJSONObject("fan").put("duration", fanSeconds)
        val savedEffect = input.optJSONObject("umbrella")?.optJSONObject("effect")
        val effect = result.getJSONObject("umbrella").getJSONObject("effect")
        for (key in listOf("enabled", "onDetect", "onDoor")) {
            val value = savedEffect?.opt(key)
            if (value is Boolean) effect.put(key, value)
        }
        val duration = savedEffect?.opt("duration")
        if (duration is Number && duration.toDouble() in listOf(5.0, 10.0, 20.0, 30.0)) effect.put("duration", duration.toInt())
        else effect.put("enabled", false) // Unsupported saved times reset to 10 seconds, OFF.
        val notifications = result.getJSONObject("notifications")
        for (key in listOf("enabled", "door", "belongings", "doorLeftOpen", "umbrella")) notifications.put(key, input.optJSONObject("notifications")?.optBoolean(key, true) ?: true)
        notifications.put("doorOpenSeconds", (input.optJSONObject("notifications")?.optInt("doorOpenSeconds", 30) ?: 30).coerceIn(DOOR_OPEN_MIN_SECONDS, DOOR_OPEN_MAX_SECONDS))
        for (i in TRAY_IDS.indices) {
            val previous = input.optJSONArray("trays")?.optJSONObject(i) ?: continue
            val tray = result.getJSONArray("trays").getJSONObject(i)
            tray.put("alias", previous.optString("alias").trim().take(16).ifBlank { "${i + 1}번 트레이" })
                .put("notification", previous.optBoolean("notification", true))
            for (domain in listOf("led", "effect")) for (key in listOf("enabled", "onDetect", "onDoor")) {
                val default = tray.getJSONObject(domain).getBoolean(key)
                tray.getJSONObject(domain).put(key, previous.optJSONObject(domain)?.optBoolean(key, default) ?: default)
            }
            val duration = previous.optJSONObject("effect")?.optInt("duration", 10) ?: 10
            tray.getJSONObject("effect").put("duration", if (duration in listOf(5, 10, 20, 30)) duration else 10)
        }
        return result
    }
}
