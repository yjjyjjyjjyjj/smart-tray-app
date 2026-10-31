package kr.ac.gachon.smarttray.bluetooth

val TRAY_IDS = listOf(1, 2)

/** App domain types, NOT a sensor wire format. Populate only after firmware analysis. */
data class TrayReading(val id: Int, val present: Boolean, val weight: Double,
    val sensor: String = "ok", val ledActive: Boolean = false, val sterilizationUntil: Long = 0)
data class UmbrellaReading(val present: Boolean, val sensor: String = "ok")
data class DeviceReading(val door: String, val trays: List<TrayReading>, val umbrella: UmbrellaReading? = null)

interface FirmwareProtocol {
    val ready: Boolean
    /** Stateful byte-stream boundary. A chunk can contain partial/multiple frames.
     * A verified implementation must bound buffering, validate frames and retain partial input.
     * No delimiter, encoding or frame length is assumed here. Called on the single I/O worker. */
    fun feed(chunk: ByteArray): List<DeviceReading>
    fun reset()
}

/** Drop unknown bytes without accumulating them or fabricating sensor/command results. */
class PendingFirmwareProtocol : FirmwareProtocol {
    override val ready = false
    override fun feed(chunk: ByteArray): List<DeviceReading> = emptyList()
    override fun reset() = Unit
}

data class NotificationSettings(val enabled: Boolean, val door: Boolean, val belongings: Boolean,
    val aliases: Map<Int, String>, val enabledTrays: Set<Int>,
    val doorLeftOpen: Boolean = true, val doorOpenSeconds: Int = 30, val umbrella: Boolean = true)
data class AlertDecision(val title: String, val message: String, val trayIds: List<Int>, val kind: String)

/** Keep activity records, system notifications and previews consistent. */
fun trayDisplayName(alias: String): String {
    val name = alias.trim()
    return if (name.endsWith("트레이")) name else "$name 트레이"
}
fun trayActivityTitle(alias: String, present: Boolean): String {
    val name = trayDisplayName(alias)
    return if (present) "${name}에 물건이 놓였어요" else "${name}에서 물건이 없어졌어요"
}
fun trayNotificationMessage(aliases: List<String>): String {
    return aliases.joinToString(", ", transform = ::trayDisplayName) + "에 물건이 남아있어요. 외출 전에 확인해주세요."
}

/** Called on native receipt, independent of WebView/JS lifecycle. */
class DoorAlertPolicy {
    private var previousDoor = "unknown"
    fun reset() { previousDoor = "unknown" }
    fun evaluate(reading: DeviceReading, settings: NotificationSettings): AlertDecision? {
        if (reading.door !in listOf("open", "closed")) return null
        // Initial/reconnection snapshots establish a baseline, not a new door-opening event.
        val opened = reading.door == "open" && previousDoor == "closed"
        previousDoor = reading.door
        if (!opened || !settings.enabled) return null
        val left = if (settings.belongings) reading.trays.filter {
            it.id in TRAY_IDS && it.id in settings.enabledTrays && it.present && it.sensor == "ok"
        }.map { it.id }.distinct() else emptyList()
        if (left.isNotEmpty()) return AlertDecision("잠깐, 두고 간 물건이 있어요",
            trayNotificationMessage(left.map { settings.aliases[it] ?: "${it}번 트레이" }), left, "belongings")
        return if (settings.door) AlertDecision("현관문이 열렸어요", "트레이 상태를 확인하고 외출을 준비하세요.", emptyList(), "door") else null
    }
}
