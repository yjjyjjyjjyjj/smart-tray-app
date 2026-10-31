package kr.ac.gachon.smarttray

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Run on a disposable emulator; exercises the real WebView bridge and OS notification. */
@RunWith(AndroidJUnit4::class)
class NativeBridgeTest {
    @Test fun packagedUiLoadsNativeAdapterAndPostsSystemNotification() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            fun js(source: String): String {
                val value = AtomicReference<String>(); val done = CountDownLatch(1)
                instrumentation.runOnMainSync { activity.bridge.webView.evaluateJavascript(source) { value.set(it); done.countDown() } }
                assertTrue("WebView evaluation timed out", done.await(5, TimeUnit.SECONDS))
                return value.get()
            }
            fun awaitTrue(source: String) {
                val end = System.currentTimeMillis() + 15000
                while (System.currentTimeMillis() < end) {
                    if (js(source) == "true") return
                    Thread.sleep(100)
                }
                fail("Condition did not become true: $source")
            }
            awaitTrue("!!window.SmartTrayApp && !!window.SmartTrayNative && SmartTrayNative.available && SmartTrayApp.transport.isNative && !!document.querySelector('h1')")
            assertEquals("null", js("SmartTrayApp.store.get().lastReceived"))
            assertEquals("false", js("SmartTrayApp.store.get().demo"))
            js("window.nativeSmoke = ''; SmartTrayNative.plugin.getState().then(s => { window.nativeSmoke = s.connection + ':' + s.protocolReady; }).catch(e => {window.nativeSmoke=String(e);});")
            awaitTrue("window.nativeSmoke === 'disconnected:false'")
            js("window.nativeNotice = false; SmartTrayNative.plugin.previewNotification().then(r => { window.nativeNotice = r.posted; }).catch(e => {window.nativeNotice=String(e);});")
            awaitTrue("window.nativeNotice === true")
            val manager = context.getSystemService(NotificationManager::class.java)
            val expectedId = org.json.JSONTokener(js("SmartTrayApp.store.get().notifications[0].id")).nextValue().toString().hashCode()
            val notificationDeadline = System.currentTimeMillis() + 5000
            // notify() queues delivery to system_server; it is not visible synchronously.
            while (manager.activeNotifications.none { it.id == expectedId && it.notification.channelId == "tray_reminders" } && System.currentTimeMillis() < notificationDeadline) Thread.sleep(100)
            assertTrue("Android reminder notification missing", manager.activeNotifications.any { it.id == expectedId && it.notification.channelId == "tray_reminders" })
            assertEquals("0", js("document.querySelectorAll('#notification-root .android-notification').length"))
            manager.activeNotifications.first { it.id == expectedId && it.notification.channelId == "tray_reminders" }.notification.contentIntent.send()
            awaitTrue("location.hash === '#trays' && !document.querySelector('.onboarding-dialog')")
            awaitTrue("SmartTrayApp.store.get().notifications[0].read === true")
            val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Native screenshot unavailable" }
            try {
                // Shared QA output survives the test runner uninstalling its app package.
                val output = if (Build.VERSION.SDK_INT >= 29) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "smart-tray-native-final.png")
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SmartTrayQA")
                    }
                    val uri = checkNotNull(context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                    checkNotNull(context.contentResolver.openOutputStream(uri))
                } else java.io.File(context.getExternalFilesDir(null), "native-smoke.png").outputStream()
                output.use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally {
                screenshot.recycle()
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
