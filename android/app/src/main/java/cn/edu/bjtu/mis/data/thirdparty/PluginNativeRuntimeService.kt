package cn.edu.bjtu.mis.data.thirdparty

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import cn.edu.bjtu.mis.MainActivity
import cn.edu.bjtu.mis.R

/** Visible owner for background network, battery, and sensor plugin subscriptions. */
class PluginNativeRuntimeService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "插件后台能力", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示持久 Android 原生 Capability 正在运行的插件"
                setShowBadge(false)
            },
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopAll = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, StopPluginAutomationReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        startForeground(
            NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_24)
                .setContentTitle(getString(R.string.plugin_automation_notification_title))
                .setContentText(getString(R.string.plugin_automation_notification_text))
                .setContentIntent(openApp)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    getString(R.string.plugin_automation_stop_all),
                    stopAll,
                )
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build(),
        )
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            val intent = Intent(context.applicationContext, PluginNativeRuntimeService::class.java)
            runCatching { context.applicationContext.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(Intent(context.applicationContext, PluginNativeRuntimeService::class.java))
        }

        private const val CHANNEL_ID = "plugin_native_runtime"
        private const val NOTIFICATION_ID = 24018
    }
}
