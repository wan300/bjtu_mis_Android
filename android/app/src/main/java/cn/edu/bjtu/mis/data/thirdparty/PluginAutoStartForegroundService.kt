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
import cn.edu.bjtu.mis.BjtuMisApplication
import cn.edu.bjtu.mis.R

/** Owns the visible lifetime of user-selected plugin app auto-start runtimes. */
class PluginAutoStartForegroundService : Service() {
    private val supervisor get() = (application as BjtuMisApplication).container.pluginAutomationSupervisor

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "插件自动启动", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示用户选择的插件后台自动启动运行状态"
                setShowBadge(false)
            },
        )
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopAll = PendingIntent.getBroadcast(this, 1,
            Intent(this, StopPluginAutomationReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            startForeground(NOTIFICATION_ID, Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.loading_mascot)
                .setContentTitle(getString(R.string.plugin_autostart_notification_title))
                .setContentText(getString(R.string.plugin_autostart_notification_text))
                .setContentIntent(openApp)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                    getString(R.string.plugin_automation_stop_all), stopAll)
                .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build())
        }.onSuccess {
            supervisor.onAutoStartForegroundReady()
        }.onFailure {
            supervisor.onAutoStartForegroundStopped("系统拒绝显示后台通知，插件自启未启动")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        supervisor.onAutoStartForegroundStopped()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "plugin_app_autostart"
        private const val NOTIFICATION_ID = 24019
        /** Null means the start request was accepted; the service must still acknowledge startForeground. */
        fun start(context: Context): String? {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.areNotificationsEnabled() ||
                manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
                return "请在系统设置中允许应用及插件自启通知后重试"
            }
            return runCatching {
                context.applicationContext.startForegroundService(
                    Intent(context.applicationContext, PluginAutoStartForegroundService::class.java))
                null
            }.getOrElse { "系统限制后台服务启动，请保持主应用可见后重试" }
        }
        fun stop(context: Context) = context.applicationContext.stopService(
            Intent(context.applicationContext, PluginAutoStartForegroundService::class.java))
    }
}
