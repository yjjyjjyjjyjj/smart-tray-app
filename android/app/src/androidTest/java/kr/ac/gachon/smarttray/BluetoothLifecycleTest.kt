package kr.ac.gachon.smarttray

import android.app.Service
import android.content.*
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.ac.gachon.smarttray.bluetooth.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Disposable emulator. Verifies service policy/storage, NOT physical HC-06 connectivity. */
@RunWith(AndroidJUnit4::class)
class BluetoothLifecycleTest {
    @Test fun explicitStopClearsOnlyConnectionIntentAndEmptyStickyRestartDoesNotConnect() {
        val i=InstrumentationRegistry.getInstrumentation(); val context=i.targetContext
        val connected=CountDownLatch(1); var service:SmartTrayBluetoothService?=null
        val connection=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName?, binder:IBinder?) {
                service=(binder as SmartTrayBluetoothService.LocalBinder).service; connected.countDown()
            }
            override fun onServiceDisconnected(name:ComponentName?) = Unit
        }
        assertTrue(context.bindService(Intent(context,SmartTrayBluetoothService::class.java),connection,Context.BIND_AUTO_CREATE))
        assertTrue(connected.await(10,TimeUnit.SECONDS))
        val disk=context.getSharedPreferences(SmartTrayBluetoothService.CONNECTION_STORE,Context.MODE_PRIVATE)
        val manifest=context.packageManager.getServiceInfo(ComponentName(context,SmartTrayBluetoothService::class.java),0)
        assertEquals(0,manifest.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,manifest.foregroundServiceType)
        var previous:JSONObject?=null
        try {
            i.runOnMainSync {
                previous=TrayRepository.snapshot().getJSONObject("preferences")
                val settings=JSONObject(previous.toString())
                settings.getJSONArray("trays").getJSONObject(0).put("alias","보존할 별칭")
                settings.getJSONObject("notifications").put("doorOpenSeconds",77)
                TrayRepository.savePreferences(settings)
                // Test-only stored selection; STOP must not open or reconnect any device.
                assertTrue(disk.edit().putString("wanted_address","AA:BB:CC:DD:EE:FF").putInt("retry_count",3).commit())
                assertEquals(Service.START_NOT_STICKY,service!!.onStartCommand(Intent().setAction(SmartTrayBluetoothService.STOP),0,1))
                assertFalse(disk.contains("wanted_address")); assertFalse(disk.contains("retry_count"))
                assertEquals("disconnected",TrayRepository.snapshot().getString("connection"))
                assertEquals("보존할 별칭",TrayRepository.snapshot().getJSONObject("preferences").getJSONArray("trays").getJSONObject(0).getString("alias"))
                assertEquals(77,TrayRepository.settingSnapshot().doorOpenSeconds)
                assertEquals(Service.START_NOT_STICKY,service!!.onStartCommand(null,0,2))
                assertEquals("disconnected",TrayRepository.snapshot().getString("connection"))
                assertFalse(TrayRepository.snapshot().getBoolean("protocolReady"))
            }
        } finally {
            i.runOnMainSync { previous?.let(TrayRepository::savePreferences) }
            context.unbindService(connection)
        }
    }
}
