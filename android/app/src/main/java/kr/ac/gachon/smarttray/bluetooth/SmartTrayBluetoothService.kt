package kr.ac.gachon.smarttray.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kr.ac.gachon.smarttray.weather.WeatherManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.util.concurrent.Executor
import java.util.concurrent.TimeoutException

@SuppressLint("MissingPermission") // Public gates + native permission checks + SecurityException handling.
@Suppress("DEPRECATION")
class SmartTrayBluetoothService : Service() {
    companion object {
        const val CONNECT = "kr.ac.gachon.smarttray.CONNECT"
        const val STOP = "kr.ac.gachon.smarttray.STOP"
        const val CONNECTION_STORE = "spp_connection"
        const val PAIR_TIMEOUT_MS = 60_000L
    }
    inner class LocalBinder : Binder() { val service get() = this@SmartTrayBluetoothService }
    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val devices = linkedMapOf<String, JSONObject>()
    private lateinit var notices: TrayNotifications
    private lateinit var session: RfcommSession
    private val adapter get() = getSystemService(BluetoothManager::class.java)?.adapter
    private val disk get() = getSharedPreferences(CONNECTION_STORE, MODE_PRIVATE)
    private var address: String? = null
    private var wanted = false
    private var destroyed = false
    private var pairing = false
    private var scanning = false
    private var discoveryStarted = false
    private var retry = ReconnectPolicy()
    private var epoch = 0L
    private val stopScanTask = Runnable { stopScan() }
    private val pairTimeout = Runnable { if (pairing) failConnection("페어링 시간이 초과됐어요. 시스템 Bluetooth 설정에서 페어링을 확인해 주세요.") }
    private val reconnectTask = Runnable { openSocket() }
    private val permissionCheck = object : Runnable {
        override fun run() {
            verifyPermissions()
            if (!destroyed && (wanted || scanning)) main.postDelayed(this, 5000)
        }
    }
    fun verifyPermissions() {
        if (destroyed) return
        if (wanted && !canConnect()) failConnection("주변 기기 권한이 해제됐어요. 권한을 허용한 뒤 다시 연결해 주세요.")
        else if (scanning && !canDiscover()) { stopScan(); TrayRepository.error("검색 권한이 해제됐어요. 권한을 확인해 주세요.") }
    }
    private fun watchPermissions() { main.removeCallbacks(permissionCheck); main.postDelayed(permissionCheck, 5000) }
    private var remindersStopped = false
    private var finishAfterDoorCheck = false
    private val doorWatch = DoorLeftOpenPolicy()
    private val umbrellaPolicy = UmbrellaAlertPolicy()
    private var weatherAttemptedForOpen = false
    private var doorWakeLock: PowerManager.WakeLock? = null
    private val reminderTick = Runnable { evaluateReminders(TrayRepository.snapshot()) }
    private val reminderObserver: (JSONObject) -> Unit = { evaluateReminders(it) }
    private fun evaluateReminders(state: JSONObject) {
        if (destroyed || remindersStopped) return
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val received = state.optLong("lastReceived", 0)
        val fresh = state.optString("connection") == "connected" && received > 0 && now - received in 0..SENSOR_FRESH_MS
        val door = state.optString("door")
        val settings = TrayRepository.settingSnapshot()
        val doorAlert = doorWatch.evaluate(door, fresh, settings.enabled && settings.doorLeftOpen, settings.doorOpenSeconds, elapsed)
        // Mark policies before posting: adding an inbox item publishes another snapshot.
        val umbrella = state.optJSONObject("umbrella")
        val measured = umbrella?.optLong("measuredAt", 0) ?: 0
        val present = if (umbrella?.optString("sensor") == "ok" && measured > 0 && now - measured in 0..SENSOR_FRESH_MS) umbrella.optBoolean("present") else null
        val weather = if (WeatherManager.permitted(this) && state.optJSONObject("weather")?.optString("status") == "ready") WeatherManager.forecast else null
        val umbrellaAlert = umbrellaPolicy.evaluate(door, present, fresh, settings.enabled && settings.umbrella, weather, now)
        main.removeCallbacks(reminderTick)
        // RFCOMM loss clears repository sensor freshness, but must not erase this pending check.
        val remaining = if (settings.enabled && settings.doorLeftOpen) doorWatch.remaining(settings.doorOpenSeconds, elapsed) else null
        if (remaining != null) {
            main.postDelayed(reminderTick, remaining.coerceAtLeast(1))
            if (doorWakeLock?.isHeld != true) {
                doorWakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartTray:doorTimer").apply {
                    setReferenceCounted(false); acquire((DOOR_OPEN_MAX_SECONDS + 5) * 1000L)
                }
            }
        } else releaseDoorWakeLock()
        if (!fresh || door != "open") weatherAttemptedForOpen = false
        val fetchWeather = umbrellaPolicy.needsForecast() && !weatherAttemptedForOpen
        if (fetchWeather) weatherAttemptedForOpen = true
        doorAlert?.let { notices.post(it) }
        umbrellaAlert?.let { notices.post(it) }
        if (fetchWeather) WeatherManager.refresh(this, false)
        if (finishAfterDoorCheck && remaining == null) {
            finishAfterDoorCheck = false; remindersStopped = true
            doorWatch.reset()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }
    private fun releaseDoorWakeLock() { if (doorWakeLock?.isHeld == true) doorWakeLock?.release(); doorWakeLock = null }
    private val bluetoothEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (destroyed || intent == null) return
            try {
                when (intent.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                        if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF)
                            failConnection("Bluetooth가 꺼졌어요. 켠 뒤 장치를 다시 선택해 주세요.")
                        else TrayRepository.publish()
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> if (scanning) discoveryStarted = true
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (scanning && discoveryStarted) finishScan()
                    BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_NAME_CHANGED -> if (scanning) {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                        val signal = if (intent.hasExtra(BluetoothDevice.EXTRA_RSSI)) "${intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, 0)} dBm" else null
                        addDevice(device, intent.getStringExtra(BluetoothDevice.EXTRA_NAME), signal); publishDevices()
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                        if (devices.containsKey(device.address)) { addDevice(device); publishDevices() }
                        if (!wanted || !pairing || device.address != address) return
                        when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                            BluetoothDevice.BOND_BONDED -> { pairing = false; main.removeCallbacks(pairTimeout); openSocket() }
                            BluetoothDevice.BOND_NONE -> failConnection("페어링이 취소되었거나 실패했어요. HC-06의 PIN과 전원을 확인하고 다시 선택해 주세요.")
                        }
                    }
                }
            } catch (_: SecurityException) { failConnection("주변 기기 권한이 해제됐어요. 권한을 확인해 주세요.") }
        }
    }
    override fun onCreate() {
        super.onCreate(); TrayRepository.initialize(applicationContext); notices = TrayNotifications(this)
        // Never reinterpret a previously selected BLE peripheral as an HC-06 device.
        getSharedPreferences("ble_connection", MODE_PRIVATE).edit().remove("wanted_address").apply()
        session = RfcommSession({ target -> AndroidSerialSocket(checkNotNull(adapter).getRemoteDevice(target)) }, Executor { main.post(it) })
        TrayRepository.observe(reminderObserver)
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED); addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED); addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED); addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        // Bluetooth system broadcasts may come from the privileged Bluetooth app's separate UID.
        ContextCompat.registerReceiver(this, bluetoothEvents, filter, ContextCompat.RECEIVER_EXPORTED)
    }
    override fun onBind(intent: Intent?): IBinder = binder
    override fun onUnbind(intent: Intent?): Boolean { stopScan(); return false }
    // stopWithTask=false: removing the Activity task does not stop this started service.
    // Force-stop/Android Active Apps Stop sends no cleanup callback; Android stops the process.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { disconnect(); return START_NOT_STICKY }
        if (intent != null && intent.action != CONNECT) { if (!wanted) stopSelf(); return if (wanted) START_STICKY else START_NOT_STICKY }
        val restored = intent == null
        val target = if (restored) disk.getString("wanted_address", null) else intent?.getStringExtra("address")
        if (target == null || !BluetoothAdapter.checkBluetoothAddress(target)) {
            disconnect(if (restored) "" else "장치 목록에서 연결할 HC-06을 선택해 주세요."); return START_NOT_STICKY
        }
        if (wanted && address == target) return START_STICKY // Duplicate start must not spawn another loop.
        try {
            requireBluetooth()
            val device = adapter!!.getRemoteDevice(target)
            check(device.type != BluetoothDevice.DEVICE_TYPE_LE) { "HC-06용 Classic 장치를 선택해 주세요." }
            if (restored) check(device.bondState == BluetoothDevice.BOND_BONDED) { "페어링이 해제됐어요. 앱에서 장치를 다시 선택해 주세요." }
            ServiceCompat.startForeground(this, TrayNotifications.SERVICE_ID, notices.connection("HC-06 연결을 준비하고 있어요"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
            // A pending door check belongs to the previously selected device.
            if (address != target) {
                main.removeCallbacks(reminderTick); doorWatch.reset(); umbrellaPolicy.reset(); releaseDoorWakeLock()
            }
            remindersStopped = false; finishAfterDoorCheck = false
            stopScan(); session.stop(); epoch++; main.removeCallbacks(reconnectTask); main.removeCallbacks(pairTimeout)
            address = target; wanted = true; pairing = false; watchPermissions()
            retry = ReconnectPolicy(if (restored) disk.getInt("retry_count", 0) else 0)
            check(disk.edit().putString("wanted_address", target).putInt("retry_count", retry.attempts).commit()) { "연결 의도를 저장하지 못했어요. 다시 시도해 주세요." }
            TrayRepository.patch { it.put("deviceName", device.name?.trim()?.takeIf(String::isNotEmpty) ?: "선택한 트레이")
                .put("deviceAddress", target).put("transportBytesReceived", 0).put("lastTransportReceived", JSONObject.NULL)
                .put("protocolReady", false).put("protocolMessage", TrayRepository.PROTOCOL_MESSAGE) }
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                pairing = true
                phase("pairing")
                updateNotice("HC-06 시스템 페어링 확인 대기")
                main.postDelayed(pairTimeout, PAIR_TIMEOUT_MS)
                if (device.bondState != BluetoothDevice.BOND_BONDING && !device.createBond())
                    failConnection("페어링을 시작하지 못했어요. 시스템 Bluetooth 설정에서 확인해 주세요.")
            } else openSocket()
        } catch (error: Exception) { failConnection(error.message ?: "연결을 시작하지 못했어요. 앱을 연 상태에서 다시 시도해 주세요.") }
        return if (wanted) START_STICKY else START_NOT_STICKY
    }
    private fun canConnect() = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    private fun canDiscover() = canConnect() && ContextCompat.checkSelfPermission(this,
        if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun requireBluetooth() {
        check(canConnect()) { "휴대폰 설정에서 아마따의 주변 기기 권한을 허용해 주세요." }
        check(adapter != null) { "이 기기는 Bluetooth를 지원하지 않아요." }
        check(adapter!!.isEnabled) { "Bluetooth가 꺼져 있어요. 휴대폰 Bluetooth를 켜 주세요." }
    }
    private fun addDevice(device: BluetoothDevice, discoveredName: String? = null, signal: String? = null) {
        val name = if (device.type == BluetoothDevice.DEVICE_TYPE_LE) null else trayBluetoothName(discoveredName, device.name)
        if (name == null) {
            devices.remove(device.address) // A name update can also remove an earlier match.
            return
        }
        val previous = devices[device.address]
        devices[device.address] = JSONObject().put("address", device.address).put("name", name)
            .put("bonded", device.bondState == BluetoothDevice.BOND_BONDED)
            .put("signal", signal ?: previous?.optString("signal")?.takeIf(String::isNotEmpty) ?: "페어링된 장치")
    }
    private fun publishDevices() = TrayRepository.patch { it.put("scanResults", JSONArray(devices.values.sortedByDescending { d -> d.optBoolean("bonded") })) }
    fun listPairedDevices() {
        requireBluetooth(); check(!pairing && TrayRepository.snapshot().optString("connection") != "connecting") { "연결을 취소한 뒤 목록을 새로 확인해 주세요." }
        stopScan(); devices.clear()
        adapter!!.bondedDevices.forEach { addDevice(it) }; publishDevices()
        TrayRepository.patch { it.put("scanState", "paired").put("connectionError", "") }
    }
    fun validateSelection(target: String) {
        requireBluetooth()
        check(BluetoothAdapter.checkBluetoothAddress(target) && devices.containsKey(target)) { "검색되거나 페어링된 장치 목록에서 선택해 주세요." }
        check(!wanted && TrayRepository.snapshot().optString("connection") != "connecting") { "현재 연결을 해제한 뒤 다른 장치를 선택해 주세요." }
    }
    fun scan() {
        requireBluetooth()
        check(canDiscover()) { "주변 장치 검색 권한을 허용해 주세요. 페어링된 장치 목록은 별도로 확인할 수 있어요." }
        check(!wanted) { "현재 연결을 해제한 뒤 새 장치를 검색해 주세요." }
        listPairedDevices()
        if (Build.VERSION.SDK_INT <= 30) check(WeatherManager.locationEnabled(this)) { "이 Android 버전에서는 주변 검색에 휴대폰 위치 기능이 필요해요. 페어링된 장치는 목록에서 연결할 수 있어요." }
        scanning = true; discoveryStarted = false; watchPermissions()
        TrayRepository.patch { it.put("scanState", "scanning").put("connectionError", "") }
        try {
            cancelDiscovery()
            check(adapter!!.startDiscovery()) { "Classic 장치 검색을 시작하지 못했어요. 잠시 후 다시 시도해 주세요." }
            main.postDelayed(stopScanTask, 15_000)
        } catch (error: Exception) { stopScan(); throw error }
    }
    private fun cancelDiscovery() {
        if (Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)
            adapter?.cancelDiscovery()
    }
    private fun finishScan() { scanning = false; discoveryStarted = false; main.removeCallbacks(stopScanTask)
        TrayRepository.patch { if (it.optString("scanState") == "scanning") it.put("scanState", "done") } }
    fun stopScan() {
        if (scanning) runCatching { cancelDiscovery() }
        finishScan()
    }
    private fun phase(value: String, message: String = "") {
        TrayRepository.patch { it.put("connectionPhase", value).put("retryAttempt", retry.attempts) }
        TrayRepository.connection(if (value == "connected") "connected" else "connecting", message)
    }
    private fun openSocket() {
        if (!wanted || destroyed) return
        try {
            requireBluetooth()
            check(adapter!!.getRemoteDevice(address).bondState == BluetoothDevice.BOND_BONDED) { "페어링이 해제됐어요. 장치를 다시 선택해 주세요." }
            cancelDiscovery()
        } catch (error: Exception) { failConnection(error.message ?: "Bluetooth 상태를 확인해 주세요."); return }
        phase("connecting", if (retry.attempts > 0) "HC-06에 다시 연결하고 있어요 (${retry.attempts}회차)." else "")
        updateNotice(if (retry.attempts > 0) "HC-06 재연결 중 · ${retry.attempts}회차" else "HC-06 RFCOMM 연결 중")
        val token = ++epoch
        val protocol: FirmwareProtocol = PendingFirmwareProtocol() // New framing state for every socket.
        var total = 0L; var lastPublished = 0L
        session.connect(address!!, object : RfcommSession.Listener {
            override fun connected() {
                if (!wanted || token != epoch) return
                retry.reset(); disk.edit().putInt("retry_count", 0).apply()
                TrayRepository.patch { it.put("protocolReady", protocol.ready).put("protocolMessage", if (protocol.ready) "" else TrayRepository.PROTOCOL_MESSAGE) }
                phase("connected"); updateNotice("HC-06 연결됨 · " + if (protocol.ready) "센서 수신 대기" else "센서 규격 확인 대기")
            }
            override fun bytes(chunk: ByteArray) {
                // Executed on the sole I/O worker; feed must preserve partial frames across reads.
                val readings = try { protocol.feed(chunk) } catch (_: Exception) {
                    protocol.reset(); main.post { if (wanted && token == epoch) TrayRepository.error("센서 데이터 형식을 확인해 주세요.") }; emptyList()
                }
                total += chunk.size
                val now = System.currentTimeMillis(); val publishBytes = now - lastPublished >= 1000
                if (publishBytes) lastPublished = now
                val count = total
                if (publishBytes || readings.isNotEmpty()) main.post {
                    if (wanted && token == epoch) {
                        if (publishBytes) TrayRepository.patch { it.put("transportBytesReceived", count).put("lastTransportReceived", now) }
                        readings.forEach { TrayRepository.accept(it)?.let(notices::post) }
                    }
                }
            }
            override fun failed(error: Exception) {
                if (!wanted || token != epoch) return
                if (error is SecurityException || !canConnect()) { failConnection("주변 기기 권한이 해제됐어요. 권한을 허용한 뒤 다시 연결해 주세요."); return }
                val reason = when (error) {
                    is TimeoutException -> "HC-06 연결 응답 시간이 초과됐어요."
                    is EOFException -> "HC-06이 데이터 연결을 닫았어요."
                    else -> "HC-06 연결에 실패했거나 통신이 끊겼어요. 전원과 거리를 확인해 주세요."
                }
                scheduleReconnect(reason)
            }
        })
    }
    private fun scheduleReconnect(reason: String) {
        val delay = retry.nextDelay()
        if (!disk.edit().putInt("retry_count", retry.attempts).commit()) { failConnection("연결 복구 상태를 저장하지 못했어요. 다시 연결해 주세요."); return }
        phase("retrying", "$reason ${delay / 1000}초 후 다시 시도해요 (${retry.attempts}회차).")
        updateNotice("HC-06 재연결 대기 · ${retry.attempts}회차")
        main.removeCallbacks(reconnectTask); main.postDelayed(reconnectTask, delay)
    }
    private fun updateNotice(text: String) {
        try { getSystemService(android.app.NotificationManager::class.java).notify(TrayNotifications.SERVICE_ID, notices.connection(text)) }
        catch (_: SecurityException) { }
    }
    private fun failConnection(message: String) = disconnect(message, keepDoorCheck = true)
    fun disconnect(message: String = "", keepDoorCheck: Boolean = false) {
        val settings = TrayRepository.settingSnapshot()
        val keep = keepDoorCheck && settings.enabled && settings.doorLeftOpen &&
            doorWatch.remaining(settings.doorOpenSeconds, SystemClock.elapsedRealtime()) != null
        // Suppress intermediate repository snapshots during cleanup. Manual STOP always cancels.
        remindersStopped = true; finishAfterDoorCheck = keep
        main.removeCallbacks(reminderTick); umbrellaPolicy.reset()
        if (!keep) { doorWatch.reset(); releaseDoorWakeLock() }
        wanted = false; pairing = false; epoch++; main.removeCallbacks(permissionCheck)
        main.removeCallbacks(reconnectTask); main.removeCallbacks(pairTimeout)
        stopScan(); session.stop()
        val cleared = disk.edit().clear().commit()
        TrayRepository.patch { it.put("protocolReady", false).put("connectionPhase", "idle").put("retryAttempt", 0) }
        TrayRepository.connection("disconnected", if (cleared) message else "연결은 해제됐지만 저장소를 정리하지 못했어요. 앱 설정을 확인해 주세요.")
        if (keep) {
            // Adapter/permission failure ends reconnect attempts, but an observed open door still
            // gets its bounded confirmation check while Android lets this service run.
            remindersStopped = false
            updateNotice("현관문 닫힘 확인 대기 · Bluetooth 연결 끊김")
            evaluateReminders(TrayRepository.snapshot())
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }
    override fun onDestroy() {
        destroyed = true; wanted = false; epoch++; pairing = false
        TrayRepository.removeObserver(reminderObserver)
        main.removeCallbacksAndMessages(null); stopScan(); session.close()
        releaseDoorWakeLock(); doorWatch.reset(); umbrellaPolicy.reset()
        unregisterReceiver(bluetoothEvents)
        val previousError = TrayRepository.snapshot().optString("connectionError")
        TrayRepository.patch { it.put("connectionPhase", "idle").put("protocolReady", false) }
        TrayRepository.connection("disconnected", previousError)
        // Preserve the SPP connection intent only for system-driven recreation, never explicit stop.
        super.onDestroy()
    }
}
