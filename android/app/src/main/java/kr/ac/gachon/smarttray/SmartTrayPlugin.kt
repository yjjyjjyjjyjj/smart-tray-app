package kr.ac.gachon.smarttray

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import androidx.activity.result.ActivityResult
import com.getcapacitor.annotation.ActivityCallback
import android.content.*
import android.os.*
import android.net.Uri
import android.provider.Settings
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import kr.ac.gachon.smarttray.bluetooth.*
import org.json.JSONObject
import kr.ac.gachon.smarttray.weather.WeatherManager

@CapacitorPlugin(name = "SmartTray", permissions = [
    Permission(alias = "bluetoothConnect", strings = [Manifest.permission.BLUETOOTH_CONNECT]),
    Permission(alias = "bluetoothScan", strings = [Manifest.permission.BLUETOOTH_SCAN]),
    Permission(alias = "location", strings = [Manifest.permission.ACCESS_FINE_LOCATION]),
    Permission(alias = "weatherLocation", strings = [Manifest.permission.ACCESS_COARSE_LOCATION]),
    Permission(alias = "notifications", strings = [Manifest.permission.POST_NOTIFICATIONS])
])
class SmartTrayPlugin : Plugin() {
    private val main = Handler(Looper.getMainLooper())
    private var service: SmartTrayBluetoothService? = null
    private var bound = false
    private var returningFromLocationSettings = false
    private val waiting = mutableListOf<Pair<PluginCall, (SmartTrayBluetoothService) -> Unit>>()
    private val observer: (JSONObject) -> Unit = { notifyListeners("stateChanged", decorate(it)) }
    private val routeObserver: (String) -> Unit = { notifyListeners("notificationOpened", JSObject().put("route", it), true) }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as SmartTrayBluetoothService.LocalBinder).service
            val pending = waiting.toList(); waiting.clear()
            pending.forEach { (call, action) -> guarded(call) { action(service!!) } }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }
    override fun load() { main.post {
        TrayRepository.initialize(context.applicationContext)
        TrayRepository.observe(observer); TrayRepository.observeRoutes(routeObserver)
        bound = context.bindService(Intent(context, SmartTrayBluetoothService::class.java), connection, Context.BIND_AUTO_CREATE)
    } }
    private fun decorate(state: JSONObject): JSObject {
        val notifications = TrayNotifications(context)
        return JSObject(state.toString())
            .put("notificationsAllowed", notifications.allowed())
            .put("notificationPermission", if (Build.VERSION.SDK_INT >= 33) getPermissionState("notifications").toString() else if (notifications.appAllowed()) "granted" else "denied")
            .put("notificationSettingsRequired", notificationSettingsRequired(notifications))
            .put("bluetooth", bluetoothStatus())
            .put("location", JSObject().put("permission", getPermissionState("weatherLocation").toString())
                .put("enabled", WeatherManager.locationEnabled(context)))
    }
    private fun notificationSettingsRequired(notifications: TrayNotifications): Boolean = !notifications.allowed() &&
        (Build.VERSION.SDK_INT < 33 || getPermissionState("notifications") in setOf(PermissionState.DENIED, PermissionState.GRANTED))
    private fun bluetoothStatus(): JSObject {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        return JSObject().put("available", adapter != null)
            .put("permission", if (Build.VERSION.SDK_INT >= 31) getPermissionState("bluetoothConnect").toString() else "granted")
            .put("enabled", runCatching { adapter?.isEnabled == true }.getOrDefault(false))
    }
    private fun guarded(call: PluginCall, action: () -> Unit) {
        try { action() } catch (error: Exception) {
            val message = error.message ?: "장치 작업을 완료하지 못했어요."
            TrayRepository.error(message); call.reject(message)
        }
    }
    private fun withService(call: PluginCall, action: (SmartTrayBluetoothService) -> Unit) { main.post { guarded(call) {
        val active = service
        if (active != null) action(active) else if (bound) waiting.add(call to action) else call.reject("Bluetooth 서비스를 시작하지 못했어요. 앱을 다시 열어 주세요.")
    } } }
    @PluginMethod fun getState(call: PluginCall) { main.post { call.resolve(decorate(TrayRepository.snapshot())) } }
    @PluginMethod fun consumeNotificationRoute(call: PluginCall) { main.post { call.resolve(JSObject().put("route", TrayRepository.consumeRoute())) } }
    @PluginMethod fun savePreferences(call: PluginCall) { main.post { guarded(call) {
        val preferences = call.getObject("preferences") ?: error("설정 값이 없어요.")
        TrayRepository.savePreferences(preferences); call.resolve(decorate(TrayRepository.snapshot()))
    } } }
    @PluginMethod fun markRead(call: PluginCall) { main.post {
        val ids = call.getArray("ids")
        TrayRepository.markRead(if (ids == null) emptySet() else (0 until ids.length()).map { ids.optString(it) }.toSet())
        call.resolve(decorate(TrayRepository.snapshot()))
    } }
    private fun bluetoothAliases(call: PluginCall): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        if (call.getBoolean("discovery", false) == true) arrayOf("bluetoothConnect", "bluetoothScan") else arrayOf("bluetoothConnect")
        else if (call.getBoolean("discovery", false) == true) arrayOf("location") else emptyArray()
    @PluginMethod fun requestBluetoothPermissions(call: PluginCall) { main.post { guarded(call) {
        val aliases = bluetoothAliases(call)
        val states = aliases.map { getPermissionState(it) }
        when {
            states.all { it == PermissionState.GRANTED } -> bluetoothPermissionResult(call)
            // Capacitor records DENIED only after a request that Android cannot show again.
            // PROMPT (first request) and PROMPT_WITH_RATIONALE still use the system dialog.
            states.any { it == PermissionState.DENIED } -> {
                val message = if (Build.VERSION.SDK_INT >= 31)
                    "권한을 다시 요청할 수 없어 앱 설정을 열어요. 권한에서 근처 기기를 허용해 주세요."
                else "권한을 다시 요청할 수 없어 앱 설정을 열어요. 권한에서 위치를 허용해 주세요."
                TrayRepository.error(message)
                call.resolve(JSObject().put("granted", false).put("settingsRequired", true).put("message", message))
            }
            else -> requestPermissionForAliases(aliases, call, "bluetoothPermissionResult")
        }
    } } }
    @PermissionCallback private fun bluetoothPermissionResult(call: PluginCall) {
        if (bluetoothAliases(call).all { getPermissionState(it) == PermissionState.GRANTED }) call.resolve(JSObject().put("granted", true))
        else {
            val message = if (Build.VERSION.SDK_INT >= 31) "Bluetooth를 사용하려면 근처 기기 권한이 필요해요. 다시 시도해 권한을 허용해 주세요." else "주변 검색을 위해 위치 권한을 허용해 주세요. 페어링된 장치는 위치 검색 없이 연결할 수 있어요."
            TrayRepository.error(message); call.reject(message)
        }
        TrayRepository.publish()
    }
    @PluginMethod fun enableBluetooth(call: PluginCall) { main.post { guarded(call) {
        if (Build.VERSION.SDK_INT >= 31 && getPermissionState("bluetoothConnect") != PermissionState.GRANTED) error("주변 기기 권한을 먼저 허용해 주세요.")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: error("Bluetooth를 지원하지 않는 기기예요.")
        if (adapter.isEnabled) call.resolve(JSObject().put("enabled", true))
        else startActivityForResult(call, Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), "bluetoothEnableResult")
    } } }
    @ActivityCallback private fun bluetoothEnableResult(call: PluginCall, result: ActivityResult) {
        if (result.resultCode == android.app.Activity.RESULT_OK) call.resolve(JSObject().put("enabled", true))
        else { val message = "Bluetooth 켜기를 취소했어요. 연결하려면 Bluetooth를 켜 주세요."; TrayRepository.error(message); call.reject(message) }
        TrayRepository.publish()
    }
    @PluginMethod fun openBluetoothSettings(call: PluginCall) { main.post { guarded(call) {
        activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); call.resolve()
    } } }
    @PluginMethod fun openBluetoothPermissions(call: PluginCall) { main.post { guarded(call) {
        activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))); call.resolve()
    } } }
    @PluginMethod fun requestNotificationPermission(call: PluginCall) { main.post {
        val notifications = TrayNotifications(context)
        if (notifications.allowed() || notificationSettingsRequired(notifications)) notificationPermissionResult(call)
        else requestPermissionForAlias("notifications", call, "notificationPermissionResult")
    } }
    @PermissionCallback private fun notificationPermissionResult(call: PluginCall) {
        val notifications = TrayNotifications(context)
        call.resolve(JSObject().put("granted", notifications.allowed())
            .put("settingsRequired", notificationSettingsRequired(notifications)))
        TrayRepository.publish()
    }
    @PluginMethod fun openNotificationSettings(call: PluginCall) { main.post { guarded(call) {
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        val notifications = TrayNotifications(context)
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            if (notifications.appAllowed() && notifications.reminderChannelBlocked())
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, TrayNotifications.ALERTS)
            else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else fallback
        try { activity.startActivity(intent) } catch (_: ActivityNotFoundException) { activity.startActivity(fallback) }
        call.resolve()
    } } }
    @PluginMethod fun refreshWeather(call: PluginCall) { main.post {
        // Home entry may refresh automatically, but only an explicit weather action opens permission UI.
        if (!WeatherManager.permitted(context) && call.getBoolean("automatic", false) != true)
            requestPermissionForAlias("weatherLocation", call, "weatherPermissionResult")
        else weatherPermissionResult(call)
    } }
    @PermissionCallback private fun weatherPermissionResult(call: PluginCall) { main.post {
        WeatherManager.refresh(context, true) { call.resolve(decorate(TrayRepository.snapshot())) }
    } }
    @PluginMethod fun openLocationSettings(call: PluginCall) = openSettings(call, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    @PluginMethod fun openAppLocationSettings(call: PluginCall) = openSettings(call,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    private fun openSettings(call: PluginCall, intent: Intent) { main.post { guarded(call) {
        try {
            activity.startActivity(intent)
            returningFromLocationSettings = true
            call.resolve()
        } catch (_: ActivityNotFoundException) {
            call.reject("휴대폰 설정에서 위치 기능과 아마따의 위치 권한을 확인해 주세요.")
        }
    } } }
    override fun handleOnResume() { main.post {
        service?.verifyPermissions()
        val returning = returningFromLocationSettings
        returningFromLocationSettings = false
        TrayRepository.publish() // Refresh permission and device-location switches after Settings.
        if (WeatherManager.locationEnabled(context) && WeatherManager.permitted(context) &&
            (returning || WeatherManager.hasLocation(context) && TrayRepository.settingSnapshot().umbrella && TrayRepository.settingSnapshot().enabled))
            WeatherManager.refresh(context, true)
    } }
    override fun handleOnPause() { main.post { service?.stopScan() } } // Discovery only while UI is visible; the socket stays alive.
    @PluginMethod fun listPairedDevices(call: PluginCall) = withService(call) { it.listPairedDevices(); call.resolve() }
    @PluginMethod fun scan(call: PluginCall) = withService(call) { it.scan(); call.resolve() }
    @PluginMethod fun connect(call: PluginCall) = withService(call) {
        val address = call.getString("address") ?: error("연결할 장치를 선택해 주세요.")
        require(BluetoothAdapter.checkBluetoothAddress(address)) { "장치 주소가 올바르지 않아요." }
        it.validateSelection(address)
        TrayRepository.connection("connecting")
        try {
            val intent = Intent(context, SmartTrayBluetoothService::class.java).setAction(SmartTrayBluetoothService.CONNECT).putExtra("address", address)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
            call.resolve(JSObject().put("accepted", true))
        } catch (error: Exception) {
            TrayRepository.connection("disconnected", "앱을 연 상태에서 권한을 확인하고 다시 연결해 주세요.")
            throw error
        }
    }
    @PluginMethod fun disconnect(call: PluginCall) = withService(call) { it.disconnect(); call.resolve(JSObject().put("stopped", true)) }
    @PluginMethod fun command(call: PluginCall) = withService(call) { active ->
        val action = call.getString("command") ?: error("장치 명령을 확인해 주세요.")
        val space = call.getInt("space") ?: call.getInt("trayId") ?: error("보관 공간을 확인해 주세요.")
        active.command(action, space).whenComplete { ack, error -> main.post {
            if (error != null) call.reject(error.message ?: "장치 응답을 확인하지 못했어요.", "DEVICE_COMMAND_FAILED")
            else call.resolve(JSObject().put("accepted", true).put("req", ack.request).put("status", ack.status))
        } }
    }
    @PluginMethod fun previewNotification(call: PluginCall) { main.post { guarded(call) {
        val settings = TrayRepository.settingSnapshot()
        val message = trayNotificationMessage(TRAY_IDS.map { settings.aliases[it] ?: "${it}번 트레이" })
        check(TrayNotifications(context).post(AlertDecision("잠깐, 두고 간 물건이 있어요", message, TRAY_IDS, "preview"))) {
            "휴대폰 설정에서 아마따 알림을 허용해 주세요."
        }
        call.resolve(JSObject().put("posted", true))
    } } }
    override fun handleOnDestroy() { main.post {
        TrayRepository.removeObserver(observer); TrayRepository.removeRouteObserver(routeObserver)
        waiting.forEach { it.first.reject("화면이 닫혀 요청이 취소되었어요.") }; waiting.clear()
        if (bound) { context.unbindService(connection); bound = false }
        service = null // The started foreground service owns the connection, not the plugin.
    } }
}
