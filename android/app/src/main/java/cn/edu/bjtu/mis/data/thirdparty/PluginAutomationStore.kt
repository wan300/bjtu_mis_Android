package cn.edu.bjtu.mis.data.thirdparty

import android.content.Context
import cn.edu.bjtu.mis.data.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

@Serializable
data class PluginAutomationSubscriptionRecord(
    val subscriptionId: String,
    val publisherSubjectId: String,
    val serviceId: String,
    val eventTypes: Set<String> = emptySet(),
    val packageNames: Set<String> = emptySet(),
    val includeSource: Boolean = false,
    val persistent: Boolean = true,
    val capability: String = "android.accessibility.events@1",
    val sensor: String? = null,
    val rateHz: Int? = null,
    val task: PluginBackgroundTaskRecord? = null,
)

interface PluginAutomationStore {
    fun list(): List<PluginAutomationSubscriptionRecord>
    fun save(record: PluginAutomationSubscriptionRecord)
    fun remove(publisherSubjectId: String, serviceId: String, subscriptionId: String): Boolean
    fun removeCapability(publisherSubjectId: String, serviceId: String, capability: String)
    fun removeService(publisherSubjectId: String, serviceId: String)
    fun clear()
}

class FilePluginAutomationStore(
    private val root: File,
    private val cipher: ThirdPartyKvCipher,
) : PluginAutomationStore {
    constructor(context: Context) : this(
        root = File(context.filesDir, "plugin-automation"),
        cipher = AndroidKeystoreThirdPartyKvCipher("bjtu_mis_plugin_automation_key"),
    )

    private val lock = Any()

    override fun list(): List<PluginAutomationSubscriptionRecord> = synchronized(lock) {
        root.listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isFile)
            .mapNotNull(::read)
            .flatten()
            .toList()
    }

    override fun save(record: PluginAutomationSubscriptionRecord) = synchronized(lock) {
        val records = read(fileFor(record.publisherSubjectId, record.serviceId)).orEmpty()
            .filterNot { it.subscriptionId == record.subscriptionId } + record
        write(record.publisherSubjectId, record.serviceId, records)
    }

    override fun remove(
        publisherSubjectId: String,
        serviceId: String,
        subscriptionId: String,
    ): Boolean = synchronized(lock) {
        val file = fileFor(publisherSubjectId, serviceId)
        val records = read(file).orEmpty()
        val next = records.filterNot { it.subscriptionId == subscriptionId }
        if (next.size == records.size) return@synchronized false
        if (next.isEmpty()) removeService(publisherSubjectId, serviceId) else write(publisherSubjectId, serviceId, next)
        true
    }

    override fun removeCapability(
        publisherSubjectId: String,
        serviceId: String,
        capability: String,
    ): Unit = synchronized(lock) {
        val file = fileFor(publisherSubjectId, serviceId)
        val next = read(file).orEmpty().filterNot { it.capability == capability }
        if (next.isEmpty()) removeService(publisherSubjectId, serviceId) else write(publisherSubjectId, serviceId, next)
        Unit
    }

    override fun removeService(publisherSubjectId: String, serviceId: String): Unit = synchronized(lock) {
        val file = fileFor(publisherSubjectId, serviceId)
        if (file.exists() && !file.delete()) {
            write(publisherSubjectId, serviceId, emptyList())
        }
        Unit
    }

    override fun clear(): Unit = synchronized(lock) {
        root.listFiles().orEmpty().filter { it.isFile && it.extension == "bin" }.forEach { file ->
            val barrier = barrierFor(file)
            FileOutputStream(barrier).use { output -> output.write(1); output.fd.sync() }
            // Keep the tombstone until the old ciphertext is gone, including interrupted cleanup.
            if (file.delete()) barrier.delete()
        }
    }

    private fun fileFor(publisherSubjectId: String, serviceId: String): File {
        val name = MessageDigest.getInstance("SHA-256")
            .digest("$publisherSubjectId\u0000$serviceId".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(root, "$name.bin")
    }

    private fun associatedData(publisherSubjectId: String, serviceId: String): ByteArray =
        "plugin-automation-v1\u0000$publisherSubjectId\u0000$serviceId".toByteArray()

    private fun read(file: File): List<PluginAutomationSubscriptionRecord>? {
        if (!file.isFile) return null
        // Interrupted writes/revocations must never resurrect a previous active task snapshot.
        if (barrierFor(file).exists()) return null
        return runCatching {
            val encrypted = file.readBytes()
            val envelope = AppJson.decodeFromString<AutomationEnvelope>(
                cipher.decrypt(encrypted, FILE_ASSOCIATED_DATA).toString(Charsets.UTF_8),
            )
            val plaintext = cipher.decrypt(
                envelope.payload,
                associatedData(envelope.publisherSubjectId, envelope.serviceId),
            )
            val records = AppJson.decodeFromString<List<PluginAutomationSubscriptionRecord>>(
                plaintext.toString(Charsets.UTF_8),
            )
            require(file.canonicalFile == fileFor(envelope.publisherSubjectId, envelope.serviceId).canonicalFile)
            require(records.all { record ->
                record.publisherSubjectId == envelope.publisherSubjectId &&
                    record.serviceId == envelope.serviceId
            })
            records
        }.getOrNull()
    }

    private fun write(
        publisherSubjectId: String,
        serviceId: String,
        records: List<PluginAutomationSubscriptionRecord>,
    ) {
        root.mkdirs()
        val destination = fileFor(publisherSubjectId, serviceId)
        val barrier = barrierFor(destination)
        FileOutputStream(barrier).use { output -> output.write(1); output.fd.sync() }
        val plaintext = AppJson.encodeToString(records).toByteArray(Charsets.UTF_8)
        val envelope = AutomationEnvelope(
            publisherSubjectId = publisherSubjectId,
            serviceId = serviceId,
            payload = cipher.encrypt(plaintext, associatedData(publisherSubjectId, serviceId)),
        )
        val encrypted = cipher.encrypt(
            AppJson.encodeToString(envelope).toByteArray(Charsets.UTF_8),
            FILE_ASSOCIATED_DATA,
        )
        val temporary = File(root, ".${destination.name}.${System.nanoTime()}.tmp")
        temporary.writeBytes(encrypted)
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
        check(barrier.delete()) { "Automation store requires recovery" }
    }

    private fun barrierFor(file: File) = File(root, ".${file.name}.pending")

    @Serializable
    private data class AutomationEnvelope(
        val publisherSubjectId: String,
        val serviceId: String,
        val payload: ByteArray,
    )

    private companion object {
        val FILE_ASSOCIATED_DATA = "plugin-automation-file-v1".toByteArray()
    }
}
