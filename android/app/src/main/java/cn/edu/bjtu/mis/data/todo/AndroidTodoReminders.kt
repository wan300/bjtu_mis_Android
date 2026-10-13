package cn.edu.bjtu.mis.data.todo

import android.Manifest
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
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cn.edu.bjtu.mis.BjtuMisApplication
import cn.edu.bjtu.mis.MainActivity
import cn.edu.bjtu.mis.R
import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.model.ModuleKeys
import cn.edu.bjtu.mis.model.UserTodoItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val Context.todoRemindersDataStore by preferencesDataStore("native_todo_reminders")

class DataStoreTodoReminderStore(context: Context) : TodoReminderStore {
    private val dataStore = context.applicationContext.todoRemindersDataStore
    private val key = stringPreferencesKey("records")
    override val records = dataStore.data.map { preferences ->
        preferences[key]?.let { AppJson.decodeFromString<List<TodoReminder>>(it) }
            .orEmpty().associateBy { it.todoId }
    }
    override suspend fun read(): Map<Long, TodoReminder> = records.first()
    override suspend fun put(record: TodoReminder) {
        dataStore.edit { preferences ->
            val records = preferences[key]?.let { AppJson.decodeFromString<List<TodoReminder>>(it) }
                .orEmpty().associateBy { it.todoId }.toMutableMap()
            records[record.todoId] = record
            preferences[key] = AppJson.encodeToString(records.values.toList())
        }
    }
    override suspend fun remove(todoId: Long) {
        dataStore.edit { preferences ->
            val records = preferences[key]?.let { AppJson.decodeFromString<List<TodoReminder>>(it) }.orEmpty()
            preferences[key] = AppJson.encodeToString(records.filterNot { it.todoId == todoId })
        }
    }
}

class AndroidTodoReminderScheduler(context: Context) : TodoReminderScheduler {
    private val context = context.applicationContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun ensureChannel() {
        manager.createNotificationChannel(NotificationChannel(ChannelId, "原生待办提醒", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun notificationsEnabled(): Boolean {
        ensureChannel()
        return (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,
            Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager.getNotificationChannel(ChannelId)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    override suspend fun schedule(record: TodoReminder) {
        val work = OneTimeWorkRequestBuilder<TodoReminderWorker>()
            .setInputData(Data.Builder().putLong("todoId", record.todoId).putString("token", record.token).build())
            .setInitialDelay((record.triggerAtMs - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .addTag(WorkTag)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(record.todoId), ExistingWorkPolicy.REPLACE, work).awaitCompletion()
    }

    override suspend fun cancel(todoId: Long) {
        manager.cancel(workName(todoId), 0)
        WorkManager.getInstance(context).cancelUniqueWork(workName(todoId)).awaitCompletion()
    }

    override fun notify(todo: UserTodoItem): Boolean = try {
        if (!notificationsEnabled()) false else {
            val intent = Intent(context, MainActivity::class.java)
                .setAction("cn.edu.bjtu.mis.OPEN_TODO_CALENDAR")
                .putExtra(MainActivity.EXTRA_OPEN_ROUTE, ModuleKeys.Calendar)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pendingIntent = PendingIntent.getActivity(context, 3302, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val publicVersion = NotificationCompat.Builder(context, ChannelId)
                .setSmallIcon(R.drawable.ic_menu_24).setContentTitle("待办提醒").build()
            val notification = NotificationCompat.Builder(context, ChannelId)
                .setSmallIcon(R.drawable.ic_menu_24).setContentTitle("待办提醒")
                .setContentText(todo.title).setContentIntent(pendingIntent).setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(publicVersion).build()
            manager.notify(workName(todo.id), 0, notification)
            true
        }
    } catch (_: SecurityException) { false }

    companion object {
        const val WorkTag = "native-todo-reminders"
        private const val ChannelId = "native_todo_reminders"
        private fun workName(id: Long) = "native-todo-reminder-$id"
    }
}

private suspend fun Operation.awaitCompletion(): Unit = suspendCancellableCoroutine { continuation ->
    result.addListener({
        try {
            result.get()
            if (continuation.isActive) continuation.resume(Unit)
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }, Executor { it.run() })
}

class TodoReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("todoId", 0)
        val token = inputData.getString("token") ?: return Result.failure()
        if (id <= 0) return Result.failure()
        val reminders = (applicationContext as BjtuMisApplication).container.todoReminders
        return if (reminders.deliver(id, token) == "early") Result.retry() else Result.success()
    }
}
