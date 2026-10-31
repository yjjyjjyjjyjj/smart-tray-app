package kr.ac.gachon.smarttray

import android.Manifest
import android.app.NotificationManager
import android.content.*
import android.content.pm.ActivityInfo
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.ac.gachon.smarttray.bluetooth.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Disposable emulator only: inject domain readings, never fabricated sensor packets. */
@RunWith(AndroidJUnit4::class)
class ReminderIntegrationTest {
    @Test fun nativeServicePostsTimedAlertWithoutWebViewAndCancelsOnClose() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        if (Build.VERSION.SDK_INT >= 33) i.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val bound = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { bound.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        context.bindService(Intent(context, SmartTrayBluetoothService::class.java), connection, Context.BIND_AUTO_CREATE)
        assertTrue(bound.await(10, TimeUnit.SECONDS))
        val manager = context.getSystemService(NotificationManager::class.java)
        val saved = TrayRepository.snapshot().getJSONObject("preferences")
        try {
            i.runOnMainSync {
                val preferences = JSONObject(saved.toString())
                preferences.getJSONObject("notifications").put("enabled", true).put("door", false).put("belongings", false).put("umbrella", true).put("doorLeftOpen", true).put("doorOpenSeconds", 1)
                TrayRepository.savePreferences(preferences)
                TrayRepository.connection("connected")
                val trays = TRAY_IDS.map { TrayReading(it, false, 0.0) }
                TrayRepository.accept(DeviceReading("closed", trays, UmbrellaReading(true)))
                TrayRepository.accept(DeviceReading("open", trays, UmbrellaReading(true)))
            }
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (manager.activeNotifications.none { it.notification.extras.getCharSequence("android.title").toString() == "현관문이 계속 열려 있어요" } && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            assertEquals(1, manager.activeNotifications.count { it.notification.extras.getCharSequence("android.title").toString() == "현관문이 계속 열려 있어요" })
            assertFalse(manager.activeNotifications.any { it.notification.extras.getCharSequence("android.title").toString() == "오늘은 우산도 챙겨요" }) // no verified forecast
            manager.cancelAll()
            i.runOnMainSync {
                val trays = TRAY_IDS.map { TrayReading(it, false, 0.0) }
                TrayRepository.accept(DeviceReading("closed", trays, UmbrellaReading(true)))
                TrayRepository.accept(DeviceReading("open", trays, UmbrellaReading(true)))
                TrayRepository.accept(DeviceReading("closed", trays, UmbrellaReading(true)))
            }
            Thread.sleep(1400)
            assertEquals(0, manager.activeNotifications.count { it.notification.extras.getCharSequence("android.title").toString() == "현관문이 계속 열려 있어요" })
            assertEquals(1, TrayRepository.snapshot().getJSONObject("preferences").getJSONObject("notifications").getInt("doorOpenSeconds"))
        } finally {
            i.runOnMainSync { TrayRepository.connection("disconnected"); TrayRepository.savePreferences(saved) }
            context.unbindService(connection); manager.cancelAll()
        }
    }
    private fun withDoorService(block: (SmartTrayBluetoothService, NotificationManager) -> Unit) {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        if (Build.VERSION.SDK_INT >= 33) i.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val bound = CountDownLatch(1)
        lateinit var service: SmartTrayBluetoothService
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as SmartTrayBluetoothService.LocalBinder).service; bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        assertTrue(context.bindService(Intent(context, SmartTrayBluetoothService::class.java), connection, Context.BIND_AUTO_CREATE))
        assertTrue(bound.await(10, TimeUnit.SECONDS))
        val manager = context.getSystemService(NotificationManager::class.java)
        val saved = TrayRepository.snapshot().getJSONObject("preferences")
        manager.cancelAll()
        try {
            i.runOnMainSync {
                val prefs = JSONObject(saved.toString())
                prefs.getJSONObject("notifications").put("enabled", true).put("door", false)
                    .put("belongings", false).put("umbrella", false).put("doorLeftOpen", true).put("doorOpenSeconds", 1)
                TrayRepository.savePreferences(prefs)
            }
            block(service, manager)
        } finally {
            i.runOnMainSync { service.disconnect(); TrayRepository.savePreferences(saved) }
            context.unbindService(connection); manager.cancelAll(); i.waitForIdleSync()
        }
    }
    private fun reading(door: String) = DeviceReading(door, TRAY_IDS.map { TrayReading(it, false, 0.0) }, UmbrellaReading(false))
    private fun reminderCount(manager: NotificationManager) = manager.activeNotifications.count { it.notification.channelId == TrayNotifications.ALERTS }
    private fun awaitReminder(manager: NotificationManager) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (reminderCount(manager) == 0 && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertEquals(1, reminderCount(manager))
    }
    @Test fun lostConnectionKeepsNativeTimerReconnectCloseCancelsAndManualStopCancels() = withDoorService { service, manager ->
        val i = InstrumentationRegistry.getInstrumentation()
        i.runOnMainSync { TrayRepository.connection("disconnected") }
        Thread.sleep(1200); assertEquals(0, reminderCount(manager)) // No observed opening.
        i.runOnMainSync {
            TrayRepository.connection("connected")
            TrayRepository.accept(reading("closed")); TrayRepository.accept(reading("open"))
            TrayRepository.connection("connecting") // Real RFCOMM failure/retry state; no JS timer.
        }
        awaitReminder(manager)
        val reminder = manager.activeNotifications.single { it.notification.channelId == TrayNotifications.ALERTS }
        assertEquals("현관문이 닫혔는지 확인해 주세요", reminder.notification.extras.getCharSequence("android.title").toString())
        assertTrue(reminder.notification.extras.getCharSequence("android.text").toString().contains("1초 동안 닫힘 신호"))
        i.runOnMainSync { TrayRepository.connection("connected"); TrayRepository.accept(reading("open")) }
        Thread.sleep(1200); assertEquals(1, reminderCount(manager)) // No repeated alert on reconnect.
        manager.cancelAll()
        i.runOnMainSync {
            TrayRepository.accept(reading("closed")); TrayRepository.accept(reading("open"))
            TrayRepository.connection("connecting")
            TrayRepository.connection("connected"); TrayRepository.accept(reading("closed"))
        }
        Thread.sleep(1200); assertEquals(0, reminderCount(manager))
        i.runOnMainSync {
            TrayRepository.accept(reading("open"))
            service.onStartCommand(Intent().setAction(SmartTrayBluetoothService.STOP), 0, 1)
        }
        Thread.sleep(1200); assertEquals(0, reminderCount(manager))
        assertEquals("disconnected", TrayRepository.snapshot().getString("connection"))
    }
    @Test fun terminalTransportErrorRetainsOnlyThePendingDoorCheck() = withDoorService { service, manager ->
        val i = InstrumentationRegistry.getInstrumentation()
        i.runOnMainSync {
            TrayRepository.connection("connected")
            TrayRepository.accept(reading("closed")); TrayRepository.accept(reading("open"))
            service.disconnect("Bluetooth가 꺼졌어요.", keepDoorCheck = true)
        }
        assertEquals("disconnected", TrayRepository.snapshot().getString("connection"))
        awaitReminder(manager)
        assertFalse(i.targetContext.getSharedPreferences(SmartTrayBluetoothService.CONNECTION_STORE, Context.MODE_PRIVATE).contains("wanted_address"))
        Thread.sleep(1200); assertEquals(1, reminderCount(manager))
    }

    @Test fun twoTrayPreferencesAndSensorSnapshotsPreserveRemainingSettings() {
        val i = InstrumentationRegistry.getInstrumentation()
        i.runOnMainSync {
            TrayRepository.initialize(i.targetContext)
            val saved = TrayRepository.snapshot().getJSONObject("preferences")
            try {
                val legacy = JSONObject(saved.toString())
                val trays = legacy.getJSONArray("trays")
                trays.getJSONObject(0).put("alias", "현관 왼쪽").put("notification", false)
                trays.getJSONObject(1).put("alias", "현관 오른쪽").getJSONObject("effect").put("duration", 20)
                trays.put(JSONObject(trays.getJSONObject(1).toString()).put("id", 3).put("alias", "제거할 자리"))
                legacy.getJSONObject("notifications").put("enabled", false).put("doorOpenSeconds", 77)
                TrayRepository.savePreferences(legacy)
                val migrated = TrayRepository.snapshot().getJSONObject("preferences")
                assertEquals(2, migrated.getJSONArray("trays").length())
                assertEquals("현관 왼쪽", migrated.getJSONArray("trays").getJSONObject(0).getString("alias"))
                assertEquals("현관 오른쪽", migrated.getJSONArray("trays").getJSONObject(1).getString("alias"))
                assertFalse(migrated.getJSONArray("trays").getJSONObject(0).getBoolean("notification"))
                assertEquals(20, migrated.getJSONArray("trays").getJSONObject(1).getJSONObject("effect").getInt("duration"))
                assertEquals(77, TrayRepository.settingSnapshot().doorOpenSeconds)
                TrayRepository.connection("disconnected"); TrayRepository.connection("connected")
                val readings = TRAY_IDS.map { TrayReading(it, true, 20.0) }
                TrayRepository.accept(DeviceReading("closed", readings, UmbrellaReading(true)))
                assertEquals(2, TrayRepository.snapshot().getJSONArray("trays").length())
                assertEquals("closed", TrayRepository.snapshot().getString("door"))
                assertFalse(TrayRepository.snapshot().isNull("lastReceived"))
                // A legacy third reading cannot silently become the infrared umbrella sensor.
                TrayRepository.accept(DeviceReading("open", readings + TrayReading(3, true, 10.0)))
                assertEquals("closed", TrayRepository.snapshot().getString("door"))
            } finally {
                TrayRepository.connection("disconnected"); TrayRepository.savePreferences(saved)
            }
        }
    }

    @Test fun activityRequestsPortrait() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, info.screenOrientation)
    }
}
