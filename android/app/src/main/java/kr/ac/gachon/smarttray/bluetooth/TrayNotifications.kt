package kr.ac.gachon.smarttray.bluetooth

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kr.ac.gachon.smarttray.MainActivity
import kr.ac.gachon.smarttray.R

class TrayNotifications(private val context: Context) {
    companion object { const val SERVICE_ID = 100; const val CONNECTION = "tray_connection"; const val ALERTS = "tray_reminders" }
    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CONNECTION, "트레이 연결", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(ALERTS, "외출 전 확인", NotificationManager.IMPORTANCE_HIGH))
        }
    }
    fun appAllowed(): Boolean = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        && NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun reminderChannelBlocked(): Boolean = Build.VERSION.SDK_INT >= 26 &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(ALERTS)?.importance == NotificationManager.IMPORTANCE_NONE
    fun allowed(): Boolean = appAllowed() && !reminderChannelBlocked()
    private fun open(route: String, id: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).putExtra("tray_route", route)
            .putExtra("tray_notification_id", id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, id?.hashCode() ?: 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    fun connection(text: String): Notification {
        val stop = PendingIntent.getService(context, 1, Intent(context, SmartTrayBluetoothService::class.java)
            .setAction(SmartTrayBluetoothService.STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CONNECTION).setSmallIcon(R.drawable.ic_tray_notification)
            .setContentTitle("아마따 연결").setContentText(text).setContentIntent(open("home"))
            .setOngoing(true).setOnlyAlertOnce(true).addAction(0, "연결 해제", stop).build()
    }
    fun post(alert: AlertDecision): Boolean {
        if (!allowed()) return false
        val notice = TrayRepository.addNotification(alert)
        val id = notice.getString("id")
        val route = if (alert.kind == "umbrella") "umbrella" else when (alert.trayIds.size) { 0 -> "home"; 1 -> "detail/${alert.trayIds.first()}"; else -> "trays" }
        val intent = open(route, id)
        val notification = NotificationCompat.Builder(context, ALERTS).setSmallIcon(R.drawable.ic_tray_notification)
            .setContentTitle(alert.title).setContentText(alert.message).setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(intent)
            .addAction(0, if (alert.kind == "umbrella") "우산과 날씨 확인" else if (alert.trayIds.isEmpty()) "홈에서 확인" else "트레이 확인", intent).build()
        return try { NotificationManagerCompat.from(context).notify(id.hashCode(), notification); true }
        catch (_: SecurityException) { false }
    }
}
