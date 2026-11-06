package cn.edu.bjtu.mis.data.thirdparty

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import cn.edu.bjtu.mis.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/** Implements the constitution-listed non-accessibility Android Capability family. */
class AndroidNativeCapabilityProvider(
    private val context: Context,
    private val resourceStore: ThirdPartyResourceStore,
    private val automationStore: PluginAutomationStore,
    private val networkProvider: PluginNetworkProvider? = null,
) : PluginCapabilityProvider {
    private data class Recording(
        val recorder: MediaRecorder,
        val file: File,
        val identity: PluginAutomationIdentity,
        val runtimeId: String,
    )

    private val recordings = ConcurrentHashMap<String, Recording>()
    private val rateLimiters = ConcurrentHashMap<String, FixedWindowRateLimiter>()

    init {
        AndroidNativeEventController.configure(context, automationStore)
        AndroidNativeRuntimeController.configure(::stopService)
    }

    override val capabilityIds: Set<String> = setOf(
        "android.credentials.import@1",
        "android.device.info@1",
        "android.network.status@1",
        "android.battery.status@1",
        "android.haptics.perform@1",
        "android.files.pick@1",
        "android.files.save@1",
        "android.media.pick@1",
        "android.share.open@1",
        "android.notifications.post@1",
        "android.location.read@1",
        "android.calendar.read@1",
        "android.calendar.write@1",
        "android.camera.capture@1",
        "android.audio.record@1",
        "android.sensors.read@1",
        "android.biometric.verify@1",
    )

    override suspend fun invoke(call: PluginCapabilityCall): JsonElement = when (call.capability) {
        "android.credentials.import@1" -> importCredentials(call)
        "android.device.info@1" -> deviceInfo()
        "android.network.status@1" -> network(call)
        "android.battery.status@1" -> battery(call)
        "android.haptics.perform@1" -> haptics(call)
        "android.files.pick@1" -> filesPick(call)
        "android.files.save@1" -> filesSave(call)
        "android.media.pick@1" -> mediaPick(call)
        "android.share.open@1" -> share(call)
        "android.notifications.post@1" -> notifications(call)
        "android.location.read@1" -> location(call)
        "android.calendar.read@1" -> calendarRead(call)
        "android.calendar.write@1" -> calendarWrite(call)
        "android.camera.capture@1" -> camera(call)
        "android.audio.record@1" -> audio(call)
        "android.sensors.read@1" -> sensors(call)
        "android.biometric.verify@1" -> biometric(call)
        else -> throw PluginRuntimeException("invalid_request", "Unknown Android native capability")
    }

    fun stopService(publisherSubjectId: String, serviceId: String) {
        val identity = PluginAutomationIdentity(publisherSubjectId, serviceId)
        recordings.entries.removeIf { (_, recording) ->
            if (recording.identity != identity) return@removeIf false
            runCatching { recording.recorder.stop() }
            recording.recorder.release()
            recording.file.delete()
            true
        }
        AndroidNativeEventController.revoke(identity)
    }

    private fun deviceInfo(): JsonObject = buildJsonObject {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        put("platform", "android")
        put("sdkInt", Build.VERSION.SDK_INT)
        put("manufacturer", Build.MANUFACTURER.take(120))
        put("model", Build.MODEL.take(160))
        put("locale", Locale.getDefault().toLanguageTag().take(64))
        put("timezone", ZoneId.systemDefault().id.take(128))
        put("appVersion", packageInfo.versionName.orEmpty().take(120))
    }

    private suspend fun network(call: PluginCapabilityCall): JsonElement = when (call.method) {
        "getStatus" -> networkStatus()
        "getAddresses" -> networkAddresses(call)
        "subscribe" -> AndroidNativeEventController.subscribe(call, "android.network.status@1")
        "unsubscribe" -> AndroidNativeEventController.unsubscribe(call)
        else -> invalidMethod(call)
    }

    private suspend fun networkAddresses(call: PluginCapabilityCall): JsonObject {
        requireForeground(call)
        if (!cn.edu.bjtu.mis.data.sync.AndroidSessionKeepAlive.foreground) throw PluginRuntimeException("foreground_required", "Visible activity required")
        take("${call.service.publisherSubjectId}:${call.service.serviceId}:addresses", 6)
        if (!call.confirmer.confirm("共享网络地址", "允许插件“${call.service.manifest.name}”读取当前 IPv4 地址？公网地址查询将访问插件声明的服务。")) {
            throw PluginRuntimeException("user_cancelled", "Network address query cancelled")
        }
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val local = manager?.getLinkProperties(manager.activeNetwork)?.linkAddresses
            ?.map { it.address }?.filterIsInstance<java.net.Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }?.hostAddress
        val publicUrl = call.params.string("publicIpUrl")
        val publicIp = if (publicUrl != null) {
            val provider = networkProvider ?: throw PluginRuntimeException("capability_unavailable", "Network provider unavailable")
            val response = provider.execute(call.service, PluginNetworkRequest(
                requestId = "address-${call.requestId}", method = "GET", url = publicUrl,
            ))
            if (response.status !in 200..299) throw PluginRuntimeException("http_error", "IP endpoint failed")
            pluginPublicIp(response.text.orEmpty())
                ?: throw PluginRuntimeException("invalid_request", "IP endpoint must return a plain public IP address")
        } else null
        return buildJsonObject {
            local?.let { put("localIpv4", it) }; publicIp?.let { put("publicIp", it) }
        }
    }

    private suspend fun importCredentials(call: PluginCapabilityCall): JsonObject {
        requireForeground(call)
        if (!cn.edu.bjtu.mis.data.sync.AndroidSessionKeepAlive.foreground) throw PluginRuntimeException("foreground_required", "Visible activity required")
        take("${call.service.publisherSubjectId}:${call.service.serviceId}:credential-import", 6)
        val packageName = call.params.requiredString("packageName")
        val credentials = PluginCredentialImport.validate(packageName,
            call.params["credentials"] as JsonObject, context.packageName)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = "plugin_credential_import"
        manager.createNotificationChannel(NotificationChannel(channel, "插件登录态导入", NotificationManager.IMPORTANCE_LOW))
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) {
            throw PluginRuntimeException("capability_unavailable", "Enable credential import notifications first")
        }
        val notificationId = UUID.randomUUID().hashCode()
        try {
            manager.notify(notificationId, NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification_24).setContentTitle("插件正在请求导入登录态")
                .setContentText("请在前台确认；来源包名：$packageName").setOngoing(true).build())
            if (!call.confirmer.confirm("导入登录态", "将应用自行提供的 $packageName 登录态交给插件“${call.service.manifest.name}”？\n字段：${credentials.keys.sorted().joinToString()}\n宿主不会读取其他应用或校园登录凭据。")) {
                throw PluginRuntimeException("user_cancelled", "Credential import cancelled")
            }
            if (!cn.edu.bjtu.mis.data.sync.AndroidSessionKeepAlive.foreground || !manager.areNotificationsEnabled()) {
                throw PluginRuntimeException("foreground_required", "Keep the app visible during credential import")
            }
            // Deliberately no idempotency receipt or persistent host copy containing these secrets.
            return credentials
        } finally { manager.cancel(notificationId) }
    }

    private fun battery(call: PluginCapabilityCall): JsonElement = when (call.method) {
        "getStatus" -> batteryStatus()
        "subscribe" -> AndroidNativeEventController.subscribe(call, "android.battery.status@1")
        "unsubscribe" -> AndroidNativeEventController.unsubscribe(call)
        else -> invalidMethod(call)
    }

    private fun haptics(call: PluginCapabilityCall): JsonElement {
        take("${call.service.serviceId}:haptics", 60)
        val durationMs = call.params.requiredInt("durationMs").coerceIn(1, 1_000)
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        } ?: throw PluginRuntimeException("capability_unavailable", "Vibrator is unavailable")
        if (!vibrator.hasVibrator()) throw PluginRuntimeException("capability_unavailable", "Vibrator is unavailable")
        vibrator.vibrate(android.os.VibrationEffect.createOneShot(durationMs.toLong(), android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        return buildJsonObject { put("performed", true); put("durationMs", durationMs) }
    }

    private suspend fun filesPick(call: PluginCapabilityCall): JsonElement {
        requireForeground(call)
        val mimeTypes = call.params.stringList("mimeTypes").ifEmpty { listOf("*/*") }
        val result = PluginNativeInteractionBroker.launch(
            context,
            PluginNativeInteractionBroker.Request.OpenDocument(
                mimeTypes = mimeTypes,
                multiple = call.params.boolean("multiple") == true,
            ),
        )
        return selectedResources(call, result.uris)
    }

    private suspend fun filesSave(call: PluginCapabilityCall): JsonElement {
        requireForeground(call)
        val namespace = namespace(call)
        val handle = call.params.requiredString("handle")
        val source = resourceStore.describe(namespace, handle)
            ?.takeIf { it.kind == ThirdPartyResourceKind.Blob }
            ?: throw PluginRuntimeException("invalid_request", "Unknown plugin blob handle")
        if (source.size > MAX_NATIVE_FILE_BYTES) throw PluginRuntimeException("resource_too_large", "File exceeds 64 MiB")
        val result = PluginNativeInteractionBroker.launch(
            context,
            PluginNativeInteractionBroker.Request.CreateDocument(
                fileName = safeFileName(call.params.requiredString("fileName")),
                mimeType = call.params.requiredString("mimeType"),
            ),
        )
        val target = result.uris.singleOrNull()
            ?: throw PluginRuntimeException("user_cancelled", "No output document was selected")
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(target, "wt")?.use { output ->
                resourceStore.open(namespace, handle).input.use { input -> input.copyTo(output) }
            } ?: throw PluginRuntimeException("capability_unavailable", "Unable to write selected document")
        }
        return buildJsonObject { put("saved", true); put("size", source.size) }
    }

    private suspend fun mediaPick(call: PluginCapabilityCall): JsonElement {
        requireForeground(call)
        val result = PluginNativeInteractionBroker.launch(
            context,
            PluginNativeInteractionBroker.Request.PickMedia(
                mediaType = call.params.string("mediaType") ?: "image",
                multiple = call.params.boolean("multiple") == true,
            ),
        )
        return selectedResources(call, result.uris)
    }

    private suspend fun share(call: PluginCapabilityCall): JsonElement {
        requireForeground(call)
        val text = call.params.string("text")
        val url = call.params.string("url")
        val handle = call.params.string("handle")
        if (text == null && url == null && handle == null) {
            throw PluginRuntimeException("invalid_request", "Share requires text, url, or handle")
        }
        val stream = handle?.let { materializeShareBlob(call, it) }
        val result = PluginNativeInteractionBroker.launch(
            context,
            PluginNativeInteractionBroker.Request.Share(call.params.string("title"), text, url, stream),
        )
        return buildJsonObject { put("opened", result.opened) }
    }

    private suspend fun notifications(call: PluginCapabilityCall): JsonElement = when (call.method) {
        "getStatus" -> notificationStatus()
        "show" -> {
            ensureNotificationPermission(call)
            take("${call.service.serviceId}:notifications", 60, 60 * 60_000L)
            val id = call.params.requiredString("id")
            showNotification(call.service.serviceId, id, call.params.requiredString("title"), call.params.string("body").orEmpty())
            buildJsonObject { put("shown", true); put("id", id) }
        }
        "schedule" -> {
            ensureNotificationPermission(call)
            take("${call.service.serviceId}:notifications", 60, 60 * 60_000L)
            val id = call.params.requiredString("id")
            val delay = (call.params.requiredLong("triggerAtMs") - System.currentTimeMillis()).coerceAtLeast(0)
            val work = OneTimeWorkRequest.Builder(PluginNativeNotificationWorker::class.java)
                .setInitialDelay(delay, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setInputData(
                    Data.Builder()
                        .putString("channel", channelId(call.service.serviceId))
                        .putString("id", notificationKey(call.service.serviceId, id))
                        .putString("title", call.params.requiredString("title"))
                        .putString("body", call.params.string("body").orEmpty())
                        .build(),
                ).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "plugin-native-notification:${notificationKey(call.service.serviceId, id)}",
                ExistingWorkPolicy.REPLACE,
                work,
            )
            buildJsonObject { put("scheduled", true); put("id", id) }
        }
        "cancel" -> {
            val id = call.params.requiredString("id")
            WorkManager.getInstance(context).cancelUniqueWork("plugin-native-notification:${notificationKey(call.service.serviceId, id)}")
            context.getSystemService(NotificationManager::class.java).cancel(notificationKey(call.service.serviceId, id).hashCode())
            buildJsonObject { put("cancelled", true); put("id", id) }
        }
        else -> invalidMethod(call)
    }

    private suspend fun location(call: PluginCapabilityCall): JsonElement = when (call.method) {
        "getStatus" -> buildJsonObject {
            put("granted", hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) || hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))
            val manager = context.getSystemService(LocationManager::class.java)
            put("enabled", manager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true || manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true)
        }
        "getCurrent" -> {
            requireForeground(call)
            ensurePermissions(call, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            take("${call.service.serviceId}:location", 12)
            currentLocation(call)
        }
        else -> invalidMethod(call)
    }

    private suspend fun calendarRead(call: PluginCapabilityCall): JsonElement {
        if (call.method != "list") return invalidMethod(call)
        ensurePermissions(call, arrayOf(Manifest.permission.READ_CALENDAR))
        val start = call.params.requiredLong("startMs")
        val end = call.params.requiredLong("endMs")
        if (end < start || end - start > MAX_CALENDAR_RANGE_MS) throw PluginRuntimeException("invalid_request", "Calendar range must be within 366 days")
        val limit = (call.params.int("limit") ?: 50).coerceIn(1, 200)
        return withContext(Dispatchers.IO) {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                android.content.ContentUris.appendId(it, start)
                android.content.ContentUris.appendId(it, end)
            }.build()
            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.DESCRIPTION,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
            )
            val events = buildJsonArray {
                context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
                    var count = 0
                    while (cursor.moveToNext() && count++ < limit) {
                        add(buildJsonObject {
                            put("id", cursor.getLong(0).toString())
                            put("title", cursor.getString(1).orEmpty().take(1024))
                            cursor.getString(2)?.take(4096)?.let { put("description", it) }
                            cursor.getString(3)?.take(1024)?.let { put("location", it) }
                            put("startMs", cursor.getLong(4))
                            put("endMs", cursor.getLong(5))
                            put("allDay", cursor.getInt(6) == 1)
                        })
                    }
                }
            }
            buildJsonObject { put("events", events) }
        }
    }

    private suspend fun calendarWrite(call: PluginCapabilityCall): JsonElement {
        ensurePermissions(call, arrayOf(Manifest.permission.WRITE_CALENDAR))
        return withContext(Dispatchers.IO) {
            when (call.method) {
                "create" -> {
                    val start = call.params.requiredLong("startMs")
                    val end = call.params.requiredLong("endMs")
                    if (end < start) throw PluginRuntimeException("invalid_request", "Calendar end must follow start")
                    val calendarId = writableCalendarId() ?: throw PluginRuntimeException("capability_unavailable", "No writable calendar")
                    val values = ContentValues().apply {
                        put(CalendarContract.Events.CALENDAR_ID, calendarId)
                        put(CalendarContract.Events.TITLE, call.params.requiredString("title"))
                        put(CalendarContract.Events.DESCRIPTION, call.params.string("description").orEmpty())
                        put(CalendarContract.Events.EVENT_LOCATION, call.params.string("location").orEmpty())
                        put(CalendarContract.Events.DTSTART, start)
                        put(CalendarContract.Events.DTEND, end)
                        put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
                        put(CalendarContract.Events.ALL_DAY, if (call.params.boolean("allDay") == true) 1 else 0)
                    }
                    val created = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                        ?: throw PluginRuntimeException("capability_unavailable", "Unable to create calendar event")
                    buildJsonObject { put("id", created.lastPathSegment.orEmpty()) }
                }
                "update" -> {
                    val values = calendarValues(call)
                    val id = call.params.requiredString("id").toLongOrNull()
                        ?: throw PluginRuntimeException("invalid_request", "Invalid calendar event id")
                    val updated = context.contentResolver.update(android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), values, null, null)
                    buildJsonObject { put("updated", updated) }
                }
                "delete" -> {
                    val id = call.params.requiredString("id").toLongOrNull()
                        ?: throw PluginRuntimeException("invalid_request", "Invalid calendar event id")
                    val deleted = context.contentResolver.delete(android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
                    buildJsonObject { put("deleted", deleted) }
                }
                else -> invalidMethod(call)
            }
        }
    }

    private suspend fun camera(call: PluginCapabilityCall): JsonElement {
        if (call.method != "capturePhoto") return invalidMethod(call)
        requireForeground(call)
        val file = File(context.cacheDir, "plugin-native-captures/${UUID.randomUUID()}.jpg")
        val result = try {
            PluginNativeInteractionBroker.launch(context, PluginNativeInteractionBroker.Request.CapturePhoto(file))
        } catch (error: Exception) {
            file.delete()
            throw error
        }
        val uri = result.uris.singleOrNull() ?: throw PluginRuntimeException("user_cancelled", "No photo was captured")
        return try {
            val resource = putUriBlob(call, uri, "image/jpeg")
            buildJsonObject { put("handle", resource.handle); put("mimeType", "image/jpeg"); put("size", resource.size) }
        } finally {
            file.delete()
        }
    }

    private suspend fun audio(call: PluginCapabilityCall): JsonElement = when (call.method) {
        "start" -> {
            requireForeground(call)
            ensurePermissions(call, arrayOf(Manifest.permission.RECORD_AUDIO))
            val recordingId = call.params.requiredString("recordingId")
            val key = recordingKey(call, recordingId)
            if (recordings.containsKey(key)) throw PluginRuntimeException("invalid_request", "Recording is already active")
            val file = File(context.cacheDir, "plugin-native-recordings/${UUID.randomUUID()}.m4a").also { it.parentFile?.mkdirs() }
            val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            try {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setOutputFile(file.absolutePath)
                recorder.prepare()
                recorder.start()
                recordings[key] = Recording(recorder, file, call.identity(), call.runtimeId)
            } catch (error: Exception) {
                recorder.release()
                file.delete()
                throw PluginRuntimeException("capability_unavailable", "Unable to start audio recording")
            }
            buildJsonObject { put("recordingId", recordingId); put("started", true) }
        }
        "stop" -> {
            requireForeground(call)
            val recordingId = call.params.requiredString("recordingId")
            val recording = recordings.remove(recordingKey(call, recordingId))
                ?: throw PluginRuntimeException("invalid_request", "Unknown recording")
            try {
                runCatching { recording.recorder.stop() }
                recording.recorder.release()
                val resource = withContext(Dispatchers.IO) {
                    recording.file.inputStream().use { input -> resourceStore.putBlob(namespace(call), input, "audio/mp4") }
                }
                buildJsonObject {
                    put("recordingId", recordingId)
                    put("handle", resource.handle)
                    put("mimeType", "audio/mp4")
                    put("size", resource.size)
                }
            } finally {
                recording.file.delete()
            }
        }
        else -> invalidMethod(call)
    }

    private fun sensors(call: PluginCapabilityCall): JsonElement = when (call.method) {
        "list" -> AndroidNativeEventController.listSensors()
        "subscribe" -> AndroidNativeEventController.subscribe(call, "android.sensors.read@1")
        "unsubscribe" -> AndroidNativeEventController.unsubscribe(call)
        else -> invalidMethod(call)
    }

    private suspend fun biometric(call: PluginCapabilityCall): JsonElement = when (call.method) {
        "getStatus" -> buildJsonObject { put("available", Build.VERSION.SDK_INT >= 28) }
        "verify" -> {
            requireForeground(call)
            if (Build.VERSION.SDK_INT < 28) throw PluginRuntimeException("capability_unavailable", "Biometric verification requires Android 9+")
            take("${call.service.serviceId}:biometric", 12)
            val result = PluginNativeInteractionBroker.launch(
                context,
                PluginNativeInteractionBroker.Request.Biometric(call.params.requiredString("title"), call.params.string("subtitle")),
            )
            buildJsonObject { put("verified", result.verified == true) }
        }
        else -> invalidMethod(call)
    }

    private fun networkStatus(): JsonObject {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork)
        val transport = when {
            capabilities == null -> "none"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        return buildJsonObject {
            put("online", capabilities != null)
            put("validated", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            put("metered", manager?.isActiveNetworkMetered == true)
            put("transport", transport)
        }
    }

    private fun batteryStatus(): JsonObject {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val value = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else 0
        val label = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "notCharging"
            else -> "unknown"
        }
        return buildJsonObject { put("level", value); put("charging", label == "charging" || label == "full"); put("status", label) }
    }

    private fun notificationStatus(): JsonObject {
        val manager = context.getSystemService(NotificationManager::class.java)
        return buildJsonObject {
            put("granted", Build.VERSION.SDK_INT < 33 || hasPermission(Manifest.permission.POST_NOTIFICATIONS))
            put("enabled", manager.areNotificationsEnabled())
        }
    }

    private suspend fun ensureNotificationPermission(call: PluginCapabilityCall) {
        if (Build.VERSION.SDK_INT >= 33) ensurePermissions(call, arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }

    private suspend fun ensurePermissions(call: PluginCapabilityCall, permissions: Array<String>) {
        if (permissions.any(::hasPermission)) return
        requireForeground(call)
        val granted = PluginNativeInteractionBroker.launch(context, PluginNativeInteractionBroker.Request.Permissions(permissions)).granted == true
        if (!granted && permissions.none(::hasPermission)) {
            throw PluginRuntimeException("permission_denied", "Android runtime permission was denied")
        }
    }

    private suspend fun currentLocation(call: PluginCapabilityCall): JsonObject = suspendCancellableCoroutine { continuation ->
        val manager = context.getSystemService(LocationManager::class.java)
        if (manager == null) {
            continuation.resumeWith(Result.failure(PluginRuntimeException("capability_unavailable", "Location service is unavailable")))
            return@suspendCancellableCoroutine
        }
        val high = call.params.boolean("highAccuracy") == true
        val provider = when {
            high && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider == null) {
            continuation.resumeWith(Result.failure(PluginRuntimeException("capability_unavailable", "No location provider is enabled")))
            return@suspendCancellableCoroutine
        }
        val handler = Handler(Looper.getMainLooper())
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                manager.removeUpdates(this)
                if (continuation.isActive) continuation.resume(locationJson(location))
            }
        }
        val timeout = Runnable {
            manager.removeUpdates(listener)
            if (continuation.isActive) continuation.resumeWith(Result.failure(PluginRuntimeException("request_timeout", "Location request timed out", retryable = true)))
        }
        val timeoutMs = (call.params.int("timeoutMs") ?: 15_000).coerceIn(1_000, 60_000)
        handler.postDelayed(timeout, timeoutMs.toLong())
        try {
            @Suppress("DEPRECATION") manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        } catch (error: SecurityException) {
            handler.removeCallbacks(timeout)
            continuation.resumeWith(Result.failure(PluginRuntimeException("permission_denied", "Location permission is unavailable")))
        }
        continuation.invokeOnCancellation { manager.removeUpdates(listener); handler.removeCallbacks(timeout) }
    }

    private fun locationJson(location: Location): JsonObject = buildJsonObject {
        put("latitude", location.latitude)
        put("longitude", location.longitude)
        if (location.hasAccuracy()) put("accuracy", location.accuracy.toDouble())
        put("time", location.time)
    }

    private fun calendarValues(call: PluginCapabilityCall): ContentValues = ContentValues().apply {
        call.params.string("title")?.let { put(CalendarContract.Events.TITLE, it) }
        call.params.string("description")?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        call.params.string("location")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        call.params.long("startMs")?.let { put(CalendarContract.Events.DTSTART, it) }
        call.params.long("endMs")?.let { put(CalendarContract.Events.DTEND, it) }
        if (call.params.boolean("allDay") != null) put(CalendarContract.Events.ALL_DAY, if (call.params.boolean("allDay") == true) 1 else 0)
    }

    private fun writableCalendarId(): Long? = context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(CalendarContract.Calendars._ID),
        "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
        arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
        null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

    private suspend fun selectedResources(call: PluginCapabilityCall, uris: List<android.net.Uri>): JsonObject {
        if (uris.isEmpty()) throw PluginRuntimeException("user_cancelled", "No file was selected")
        if (uris.size > 16) throw PluginRuntimeException("quota_exceeded", "At most 16 files may be selected")
        val items = buildJsonArray {
            uris.forEach { uri ->
                val resource = putUriBlob(call, uri, context.contentResolver.getType(uri) ?: "application/octet-stream")
                val description = describeUri(uri)
                add(buildJsonObject {
                    put("handle", resource.handle)
                    put("name", description.first)
                    put("mimeType", resource.mediaType)
                    put("size", resource.size)
                })
            }
        }
        return buildJsonObject { put("items", items) }
    }

    private suspend fun putUriBlob(call: PluginCapabilityCall, uri: android.net.Uri, mediaType: String): ThirdPartyResourceDescriptor =
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                resourceStore.putBlob(namespace(call), LimitedInputStream(input, MAX_NATIVE_FILE_BYTES), mediaType)
            } ?: throw PluginRuntimeException("capability_unavailable", "Unable to open selected file")
        }

    private fun describeUri(uri: android.net.Uri): Pair<String, Long?> {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)?.take(255).orEmpty().ifBlank { "selected-file" }
                    return name to cursor.getLong(1).takeIf { !cursor.isNull(1) }
                }
            }
        return "selected-file" to null
    }

    private suspend fun materializeShareBlob(call: PluginCapabilityCall, handle: String): android.net.Uri {
        val source = resourceStore.describe(namespace(call), handle)
            ?.takeIf { it.kind == ThirdPartyResourceKind.Blob }
            ?: throw PluginRuntimeException("invalid_request", "Unknown plugin blob handle")
        if (source.size > MAX_NATIVE_FILE_BYTES) throw PluginRuntimeException("resource_too_large", "Share file exceeds 64 MiB")
        val file = File(context.cacheDir, "plugin-native-share/${UUID.randomUUID()}").also { it.parentFile?.mkdirs() }
        withContext(Dispatchers.IO) {
            file.outputStream().use { output -> resourceStore.open(namespace(call), handle).input.use { it.copyTo(output) } }
        }
        return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun showNotification(serviceId: String, id: String, title: String, body: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = channelId(serviceId)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(channel) == null) {
            manager.createNotificationChannel(NotificationChannel(channel, "插件通知", NotificationManager.IMPORTANCE_DEFAULT).apply { setShowBadge(false) })
        }
        manager.notify(
            notificationKey(serviceId, id).hashCode(),
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification_24)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun requireForeground(call: PluginCapabilityCall) {
        if (call.backgroundRuntime) throw PluginRuntimeException("foreground_required", "This Android system UI capability requires the plugin foreground page")
    }

    private fun take(key: String, limit: Int, windowMs: Long = 60_000L) {
        val limiter = rateLimiters.getOrPut(key) { FixedWindowRateLimiter(limit, windowMs) }
        if (!limiter.tryAcquire(SystemClock.elapsedRealtime())) throw PluginRuntimeException("quota_exceeded", "Android capability quota exceeded")
    }

    private fun hasPermission(permission: String): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun namespace(call: PluginCapabilityCall) = ThirdPartyKvNamespace(call.service.publisherSubjectId, call.service.serviceId)
    private fun recordingKey(call: PluginCapabilityCall, recordingId: String) = "${call.service.publisherSubjectId}:${call.service.serviceId}:${call.runtimeId}:$recordingId"
    private fun channelId(serviceId: String) = "plugin_${serviceId.hashCode().toUInt().toString(16)}"
    private fun notificationKey(serviceId: String, id: String) = "$serviceId:$id"
    private fun safeFileName(value: String): String = value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(160).ifBlank { "plugin-export" }
    private fun invalidMethod(call: PluginCapabilityCall): Nothing = throw PluginRuntimeException("invalid_request", "Unknown method: ${call.capability}#${call.method}")

    private companion object {
        const val MAX_NATIVE_FILE_BYTES = 64L * 1024 * 1024
        const val MAX_CALENDAR_RANGE_MS = 366L * 24 * 60 * 60 * 1000
    }
}

/** Lets repository cleanup cancel foreground-only native work without retaining a provider instance. */
internal object AndroidNativeRuntimeController {
    @Volatile private var serviceStopper: ((String, String) -> Unit)? = null

    fun configure(stopper: (String, String) -> Unit) {
        serviceStopper = stopper
    }

    fun revokeService(publisherSubjectId: String, serviceId: String) {
        serviceStopper?.invoke(publisherSubjectId, serviceId)
            ?: AndroidNativeEventController.revoke(
                PluginAutomationIdentity(publisherSubjectId, serviceId),
            )
    }
}

internal class UnavailableAndroidNativeCapabilityProvider : PluginCapabilityProvider {
    override val capabilityIds: Set<String> = ANDROID_NATIVE_CAPABILITY_IDS

    override suspend fun invoke(call: PluginCapabilityCall): JsonElement =
        throw PluginRuntimeException("capability_unavailable", "Android native capability provider is unavailable")
}

internal val ANDROID_NATIVE_CAPABILITY_IDS: Set<String> = setOf(
    "android.credentials.import@1",
    "android.device.info@1",
    "android.network.status@1",
    "android.battery.status@1",
    "android.haptics.perform@1",
    "android.files.pick@1",
    "android.files.save@1",
    "android.media.pick@1",
    "android.share.open@1",
    "android.notifications.post@1",
    "android.location.read@1",
    "android.calendar.read@1",
    "android.calendar.write@1",
    "android.camera.capture@1",
    "android.audio.record@1",
    "android.sensors.read@1",
    "android.biometric.verify@1",
)

private class LimitedInputStream(private val delegate: InputStream, private val limit: Long) : InputStream() {
    private var total = 0L
    override fun read(): Int = delegate.read().also { if (it >= 0) count(1) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length).also { if (it > 0) count(it) }
    override fun close() = delegate.close()
    private fun count(read: Int) {
        total += read
        if (total > limit) throw PluginRuntimeException("resource_too_large", "Selected file exceeds 64 MiB")
    }
}

/** Event fan-out for the three non-sensitive system state streams. */
internal object AndroidNativeEventController : SensorEventListener {
    private data class Subscription(
        val record: PluginAutomationSubscriptionRecord,
        val ownerRuntimeId: String,
        val limiter: FixedWindowRateLimiter,
        var lastSensorAt: Long = 0L,
    )

    private data class RuntimeSink(
        val runtimeId: String,
        val background: Boolean,
        val attachedOrder: Long,
        val send: (PluginRuntimeEvent) -> Unit,
    )

    private lateinit var appContext: Context
    private var store: PluginAutomationStore? = null
    private var supervisor: PluginAutomationSupervisor? = null
    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val runtimeSinks = ConcurrentHashMap<PluginAutomationIdentity, ConcurrentHashMap<String, RuntimeSink>>()
    private val sinkOrder = AtomicLong()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var batteryReceiver: BroadcastReceiver? = null

    fun configure(context: Context, automationStore: PluginAutomationStore) {
        appContext = context.applicationContext
        store = automationStore
        refreshForegroundService()
    }

    fun configureSupervisor(value: PluginAutomationSupervisor) {
        supervisor = value
        refreshForegroundService()
        store?.list()
            ?.filter { it.capability in PERSISTENT_NATIVE_EVENT_CAPABILITIES }
            ?.map { PluginAutomationIdentity(it.publisherSubjectId, it.serviceId) }
            ?.distinct()
            ?.forEach { supervisor?.ensureService(it.publisherSubjectId, it.serviceId) }
    }

    fun hasPersistentSubscriptions(publisherSubjectId: String, serviceId: String): Boolean =
        store?.list()?.any {
            it.publisherSubjectId == publisherSubjectId &&
                it.serviceId == serviceId &&
                it.capability in PERSISTENT_NATIVE_EVENT_CAPABILITIES
        } == true

    /** Rehydrates durable records when a stable-origin WebView runtime becomes available. */
    fun attachRuntime(
        publisherSubjectId: String,
        serviceId: String,
        runtimeId: String,
        backgroundRuntime: Boolean,
        eventSink: (PluginRuntimeEvent) -> Unit,
        grantedCapabilities: Set<String> = PERSISTENT_NATIVE_EVENT_CAPABILITIES,
    ) {
        if (!::appContext.isInitialized) return
        val identity = PluginAutomationIdentity(publisherSubjectId, serviceId)
        runtimeSinks.getOrPut(identity) { ConcurrentHashMap() }[runtimeId] = RuntimeSink(
            runtimeId = runtimeId,
            background = backgroundRuntime,
            attachedOrder = sinkOrder.incrementAndGet(),
            send = eventSink,
        )
        store?.list()
            ?.asSequence()
            ?.filter {
                it.persistent &&
                    it.publisherSubjectId == publisherSubjectId &&
                    it.serviceId == serviceId &&
                    it.capability in PERSISTENT_NATIVE_EVENT_CAPABILITIES &&
                    it.capability in grantedCapabilities
            }
            ?.forEach { record ->
                subscriptions.putIfAbsent(
                    record.subscriptionId,
                    Subscription(
                        record = record,
                        ownerRuntimeId = runtimeId,
                        limiter = FixedWindowRateLimiter(if (record.sensor == null) 60 else 20, 1_000L),
                    ),
                )
            }
        if (subscriptions.isNotEmpty()) registerSources()
    }

    fun detachRuntime(runtimeId: String) {
        runtimeSinks.entries.forEach { (identity, sinks) ->
            sinks.remove(runtimeId)
            if (sinks.isEmpty()) runtimeSinks.remove(identity, sinks)
        }
        subscriptions.entries.removeIf { (_, subscription) ->
            !subscription.record.persistent && subscription.ownerRuntimeId == runtimeId
        }
        if (subscriptions.isEmpty()) unregisterSources()
    }

    fun subscribe(call: PluginCapabilityCall, capability: String): JsonObject {
        val persistent = call.params.boolean("persistent") == true
        val identity = call.identity()
        val sensor = if (capability == "android.sensors.read@1") call.params.requiredString("sensor") else null
        val rateHz = if (capability == "android.sensors.read@1") (call.params.int("rateHz") ?: 5).coerceIn(1, 20) else null
        val restored = if (persistent) store?.list()?.firstOrNull {
            it.publisherSubjectId == identity.publisherSubjectId &&
                it.serviceId == identity.serviceId &&
                it.capability == capability &&
                it.sensor == sensor
        } else null
        val existing = subscriptions.values.count {
            it.record.publisherSubjectId == identity.publisherSubjectId &&
                it.record.serviceId == identity.serviceId
        }
        if (restored == null && existing >= 16) {
            throw PluginRuntimeException("quota_exceeded", "A plugin may have at most 16 Android state subscriptions")
        }
        val record = PluginAutomationSubscriptionRecord(
            subscriptionId = restored?.subscriptionId ?: UUID.randomUUID().toString(),
            publisherSubjectId = identity.publisherSubjectId,
            serviceId = identity.serviceId,
            persistent = persistent,
            capability = capability,
            sensor = sensor,
            rateHz = rateHz,
        )
        attachRuntime(
            publisherSubjectId = identity.publisherSubjectId,
            serviceId = identity.serviceId,
            runtimeId = call.runtimeId,
            backgroundRuntime = call.backgroundRuntime,
            eventSink = call.eventSink,
            grantedCapabilities = setOf(capability),
        )
        subscriptions[record.subscriptionId] = Subscription(
            record = record,
            ownerRuntimeId = call.runtimeId,
            limiter = FixedWindowRateLimiter(if (sensor == null) 60 else 20, 1_000L),
        )
        if (persistent) {
            store?.save(record)
            refreshForegroundService()
            supervisor?.ensureService(identity.publisherSubjectId, identity.serviceId)
        }
        registerSources()
        return buildJsonObject {
            put("subscriptionId", record.subscriptionId)
            put("persistent", persistent)
            rateHz?.let { put("rateHz", it) }
        }
    }

    fun unsubscribe(call: PluginCapabilityCall): JsonObject {
        val id = call.params.requiredString("subscriptionId")
        val identity = call.identity()
        val removed = subscriptions[id]?.record?.takeIf { it.publisherSubjectId == identity.publisherSubjectId && it.serviceId == identity.serviceId }
            ?.let { subscriptions.remove(id) != null } ?: false
        val persisted = store?.remove(identity.publisherSubjectId, identity.serviceId, id) == true
        if (subscriptions.isEmpty()) unregisterSources()
        refreshForegroundService()
        return buildJsonObject { put("deleted", removed || persisted) }
    }

    fun revoke(identity: PluginAutomationIdentity) {
        subscriptions.entries.removeIf { it.value.record.publisherSubjectId == identity.publisherSubjectId && it.value.record.serviceId == identity.serviceId }
        runtimeSinks.remove(identity)
        store?.removeCapability(identity.publisherSubjectId, identity.serviceId, "android.network.status@1")
        store?.removeCapability(identity.publisherSubjectId, identity.serviceId, "android.battery.status@1")
        store?.removeCapability(identity.publisherSubjectId, identity.serviceId, "android.sensors.read@1")
        if (subscriptions.isEmpty()) unregisterSources()
        refreshForegroundService()
    }

    fun listSensors(): JsonObject {
        val manager = appContext.getSystemService(SensorManager::class.java)
        val names = SENSOR_TYPES.filter { (_, type) -> manager.getDefaultSensor(type) != null }.keys
        return buildJsonObject { put("sensors", buildJsonArray { names.forEach { add(JsonPrimitive(it)) } }) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val name = SENSOR_TYPES.entries.firstOrNull { it.value == event.sensor.type }?.key ?: return
        val now = SystemClock.elapsedRealtime()
        subscriptions.values.filter { it.record.capability == "android.sensors.read@1" && it.record.sensor == name }.forEach { subscription ->
            val interval = 1_000L / (subscription.record.rateHz ?: 5)
            if (now - subscription.lastSensorAt < interval || !subscription.limiter.tryAcquire(now)) return@forEach
            subscription.lastSensorAt = now
            preferredSink(subscription.record)?.send?.invoke(
                PluginRuntimeEvent(
                    capability = "android.sensors.read@1",
                    event = "changed",
                    data = buildJsonObject {
                        put("subscriptionId", subscription.record.subscriptionId)
                        put("sensor", name)
                        put("values", buildJsonArray { event.values.take(3).forEach { add(JsonPrimitive(it)) } })
                        put("timestampMs", now)
                    },
                ),
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun registerSources() {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        if (networkCallback == null) {
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = emitNetwork()
                override fun onLost(network: Network) = emitNetwork()
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = emitNetwork()
            }.also { connectivity.registerDefaultNetworkCallback(it) }
        }
        if (batteryReceiver == null) {
            batteryReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) = emitBattery()
            }.also { receiver -> appContext.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
        }
        val manager = appContext.getSystemService(SensorManager::class.java)
        subscriptions.values.mapNotNull { it.record.sensor }.distinct().forEach { name ->
            SENSOR_TYPES[name]?.let { manager.getDefaultSensor(it)?.let { sensor -> manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME) } }
        }
    }

    private fun unregisterSources() {
        if (::appContext.isInitialized) {
            networkCallback?.let { appContext.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
            networkCallback = null
            batteryReceiver?.let { runCatching { appContext.unregisterReceiver(it) } }
            batteryReceiver = null
            appContext.getSystemService(SensorManager::class.java).unregisterListener(this)
        }
    }

    fun stopAll() {
        subscriptions.clear()
        runtimeSinks.clear()
        store?.clear()
        unregisterSources()
        PluginNativeRuntimeService.stop(appContext)
    }

    private fun refreshForegroundService() {
        if (!::appContext.isInitialized) return
        val hasPersistent = store?.list()?.any { it.capability in PERSISTENT_NATIVE_EVENT_CAPABILITIES } == true
        if (hasPersistent) PluginNativeRuntimeService.start(appContext) else PluginNativeRuntimeService.stop(appContext)
    }

    private fun emitNetwork() {
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        val transport = when {
            capabilities == null -> "none"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val data = buildJsonObject { put("online", capabilities != null); put("validated", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true); put("metered", manager.isActiveNetworkMetered); put("transport", transport) }
        emit("android.network.status@1", data)
    }

    private fun emitBattery() {
        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val label = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "notCharging"
            else -> "unknown"
        }
        emit("android.battery.status@1", buildJsonObject { put("level", (level * 100 / scale).coerceIn(0, 100)); put("charging", label == "charging" || label == "full"); put("status", label) })
    }

    private fun emit(capability: String, data: JsonObject) {
        val now = SystemClock.elapsedRealtime()
        subscriptions.values.filter { it.record.capability == capability && it.limiter.tryAcquire(now) }.forEach { subscription ->
            preferredSink(subscription.record)?.send?.invoke(
                PluginRuntimeEvent(capability = capability, event = "changed", data = data),
            )
        }
    }

    private fun preferredSink(record: PluginAutomationSubscriptionRecord): RuntimeSink? =
        runtimeSinks[PluginAutomationIdentity(record.publisherSubjectId, record.serviceId)]
            ?.values
            ?.filterNot(RuntimeSink::background)
            ?.maxByOrNull(RuntimeSink::attachedOrder)
            ?: runtimeSinks[PluginAutomationIdentity(record.publisherSubjectId, record.serviceId)]
                ?.values
                ?.maxByOrNull(RuntimeSink::attachedOrder)

    private val SENSOR_TYPES = linkedMapOf(
        "accelerometer" to Sensor.TYPE_ACCELEROMETER,
        "gyroscope" to Sensor.TYPE_GYROSCOPE,
        "magneticField" to Sensor.TYPE_MAGNETIC_FIELD,
        "light" to Sensor.TYPE_LIGHT,
        "pressure" to Sensor.TYPE_PRESSURE,
    )

    private val PERSISTENT_NATIVE_EVENT_CAPABILITIES = setOf(
        "android.network.status@1",
        "android.battery.status@1",
        "android.sensors.read@1",
    )
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
private fun JsonObject.requiredString(name: String): String = string(name) ?: throw PluginRuntimeException("invalid_request", "Missing $name")
private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
private fun JsonObject.requiredInt(name: String): Int = int(name) ?: throw PluginRuntimeException("invalid_request", "Missing $name")
private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull
private fun JsonObject.requiredLong(name: String): Long = long(name) ?: throw PluginRuntimeException("invalid_request", "Missing $name")
private fun JsonObject.boolean(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull
private fun JsonObject.stringList(name: String): List<String> = (this[name] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }.orEmpty()
private fun PluginCapabilityCall.identity(): PluginAutomationIdentity = PluginAutomationIdentity(service.publisherSubjectId, service.serviceId)
