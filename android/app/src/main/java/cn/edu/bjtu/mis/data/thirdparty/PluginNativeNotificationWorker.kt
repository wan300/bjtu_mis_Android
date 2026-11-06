package cn.edu.bjtu.mis.data.thirdparty

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import cn.edu.bjtu.mis.R

/** Posts a prevalidated, per-plugin local notification scheduled by the native provider. */
class PluginNativeNotificationWorker(
    context: Context,
    parameters: WorkerParameters,
) : Worker(context, parameters) {
    override fun doWork(): Result {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return Result.failure()
        val channel = inputData.getString("channel") ?: return Result.failure()
        val id = inputData.getString("id") ?: return Result.failure()
        val title = inputData.getString("title") ?: return Result.failure()
        val body = inputData.getString("body") ?: return Result.failure()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(channel) == null) {
            manager.createNotificationChannel(
                NotificationChannel(channel, "插件通知", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setShowBadge(false)
                },
            )
        }
        manager.notify(
            id.hashCode(),
            NotificationCompat.Builder(applicationContext, channel)
                .setSmallIcon(R.drawable.ic_notification_24)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .build(),
        )
        return Result.success()
    }
}
