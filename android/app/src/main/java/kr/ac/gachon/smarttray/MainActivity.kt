package kr.ac.gachon.smarttray

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import com.getcapacitor.BridgeActivity
import kr.ac.gachon.smarttray.bluetooth.TrayRepository

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(SmartTrayPlugin::class.java)
        TrayRepository.initialize(applicationContext)
        TrayRepository.openNotification(intent.getStringExtra("tray_route"), intent.getStringExtra("tray_notification_id"))
        super.onCreate(savedInstanceState)
        // Wait for BridgeActivity to apply its NoActionBar theme before touching decorView.
        // SystemBars owns the insets for our contained WebView.
        enableEdgeToEdge()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        TrayRepository.openNotification(intent.getStringExtra("tray_route"), intent.getStringExtra("tray_notification_id"))
    }
}
