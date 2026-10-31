package kr.ac.gachon.smarttray

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import androidx.core.location.LocationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.ac.gachon.smarttray.bluetooth.TrayRepository
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Disposable emulator only: changes the test device's location setting, then restores it. */
@RunWith(AndroidJUnit4::class)
class LocationSetupTest {
    @Test fun initialSetupReadsNativeLocationAndReturnsFromSettingsThenLaunchesHome() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        fun shell(command: String) { i.uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        } }
        val manager = context.getSystemService(LocationManager::class.java)
        val previouslyEnabled = LocationManagerCompat.isLocationEnabled(manager)
        i.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        shell("cmd location set-location-enabled false")
        i.runOnMainSync {
            TrayRepository.initialize(context)
            val prefs = JSONObject(TrayRepository.snapshot().getJSONObject("preferences").toString())
            prefs.put("setupDone", false)
            prefs.getJSONArray("trays").getJSONObject(0).put("alias", "내 자리")
            TrayRepository.savePreferences(prefs)
        }
        var activity = i.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun js(source: String): String {
            val value = AtomicReference<String>(); val done = CountDownLatch(1)
            i.runOnMainSync { activity.bridge.webView.evaluateJavascript(source) { value.set(it); done.countDown() } }
            assertTrue(done.await(5, TimeUnit.SECONDS)); return value.get()
        }
        fun awaitTrue(source: String) {
            val deadline = System.currentTimeMillis() + 15000
            while (System.currentTimeMillis() < deadline) {
                if (js(source) == "true") return
                Thread.sleep(100)
            }
            fail("Condition did not become true: $source")
        }
        try {
            awaitTrue("!!window.SmartTrayApp && location.hash === '#connect' && !!document.querySelector('#location-setup')")
            assertEquals("0", js("document.querySelectorAll('[role=dialog]').length"))
            awaitTrue("SmartTrayApp.store.get().location.permission === 'granted' && SmartTrayApp.store.get().location.enabled === false")
            assertEquals("true", js("!!document.querySelector('[data-action=location-settings]')"))
            js("document.querySelector('[data-action=location-settings]').click()")
            var deadline = System.currentTimeMillis() + 5000
            while (i.uiAutomation.rootInActiveWindow?.packageName?.toString() != "com.android.settings" && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertEquals("com.android.settings", i.uiAutomation.rootInActiveWindow?.packageName?.toString())
            assertTrue(i.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            awaitTrue("document.visibilityState === 'visible' && SmartTrayApp.store.get().location.enabled === false")
            js("document.querySelector('[data-action=finish-setup]').click()")
            awaitTrue("location.hash === '#home' && SmartTrayApp.store.get().preferences.setupDone")
            assertEquals("내 자리", org.json.JSONTokener(js("SmartTrayApp.store.get().preferences.trays[0].alias")).nextValue())
            i.runOnMainSync { activity.finish() }
            i.waitForIdleSync()
            activity = i.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            awaitTrue("!!window.SmartTrayApp && location.hash === '#home' && !!document.querySelector('.readiness-card')")
            assertEquals("0", js("document.querySelectorAll('[role=dialog]').length"))
        } finally {
            i.runOnMainSync { activity.finish() }
            shell("cmd location set-location-enabled $previouslyEnabled")
        }
    }
}
