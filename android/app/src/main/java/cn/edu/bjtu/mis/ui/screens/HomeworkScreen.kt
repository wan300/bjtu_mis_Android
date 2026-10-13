package cn.edu.bjtu.mis.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.edu.bjtu.mis.data.homework.HomeworkStatusKind
import cn.edu.bjtu.mis.data.homework.findHomeworkByIdentity
import cn.edu.bjtu.mis.data.homework.homeworkIdentityKey
import cn.edu.bjtu.mis.data.homework.homeworkMatchesStatusFilter
import cn.edu.bjtu.mis.data.homework.homeworkStatusKind
import cn.edu.bjtu.mis.data.repository.HomeworkAttachmentPreview
import cn.edu.bjtu.mis.data.repository.HomeworkAttachmentRepository
import cn.edu.bjtu.mis.data.repository.ModuleLoadStrategy
import cn.edu.bjtu.mis.data.repository.ModuleRepository
import cn.edu.bjtu.mis.model.HomeworkAttachment
import cn.edu.bjtu.mis.model.HomeworkItem
import cn.edu.bjtu.mis.model.HomeworkUploadFile
import cn.edu.bjtu.mis.model.ModuleKeys
import cn.edu.bjtu.mis.openwebui.NativeAgentHomeworkHandoff
import cn.edu.bjtu.mis.openwebui.NativeAgentHomeworkHandoffStore
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.KeyValue
import cn.edu.bjtu.mis.ui.components.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeworkScreen(
    repository: ModuleRepository,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    onNavigate: (String) -> Unit,
    onOpenHomeworkDetail: (HomeworkItem) -> Unit,
) {
    var status by remember { mutableStateOf("all") }
    var expiredStatus by remember { mutableStateOf("expired") }
    val realNow = LocalDateTime.now()

    ProgressiveDataScreen(
        title = "作业",
        initialLoadStrategy = initialLoadStrategy,
        loader = { strategy -> repository.homeworkProgressive("all", strategy) },
    ) { progressiveState, envelope ->
        val data = envelope.data
        val currentTerm = data.currentTerm
        val activeFilter = if (status == "expired") expiredStatus else status
        val displayItems = if (activeFilter != "all") {
            data.items.filter { homeworkMatchesStatusFilter(it, activeFilter, realNow) }
        } else {
            data.items
        }
        val groups = groupHomeworkItems(displayItems, realNow)
        item {
            SecondaryModuleLinks(
                title = "作业相关",
                links = listOf(ModuleKeys.OpenWebUiAgent to "作业助手"),
                onNavigate = onNavigate,
            )
        }
        if (!currentTerm.isNullOrBlank()) {
            item {
                AssistChip(onClick = {}, label = { Text(currentTerm) })
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "全部", "open" to "待完成", "done" to "已完成").forEach { (key, label) ->
                    FilterChip(selected = status == key, onClick = { status = key }, label = { Text(label) })
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = status == "expired",
                    onClick = { status = "expired" },
                    label = { Text("已过期") },
                )
                if (status == "expired") {
                    listOf(
                        "expired" to "全部过期",
                        HomeworkStatusKind.ExpiredCanSubmit.code to "可补交",
                        HomeworkStatusKind.ExpiredClosed.code to "不可补交",
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = expiredStatus == key,
                            onClick = { expiredStatus = key },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }
        if (groups.isEmpty() && !progressiveState.loading) {
            item {
                InfoCard("暂无作业") {
                    Text("当前没有可展示的作业记录。", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            groups.forEach { group ->
                item(key = "homework-group-${group.courseId}-${group.courseName}") {
                    SectionTitle(
                        title = group.courseName,
                        subtitle = buildHomeworkGroupSubtitle(group),
                    )
                }
                items(group.items, key = { it.homeworkId ?: (it.title + it.courseId).hashCode() }) { item ->
                    HomeworkSummaryCard(
                        item = item,
                        now = realNow,
                        onClick = { onOpenHomeworkDetail(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeworkSummaryCard(
    item: HomeworkItem,
    now: LocalDateTime,
    onClick: () -> Unit,
) {
    InfoCard(
        title = item.title,
        modifier = Modifier.clickable(onClick = onClick),
        subtitle = item.course,
        trailing = {
            if (item.attachments.isNotEmpty()) {
                AssistChip(onClick = {}, label = { Text("${item.attachments.size} 个附件") })
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
            KeyValue("开始", item.openedAt, Modifier.weight(1f))
            KeyValue("截止", item.dueAt, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
            KeyValue("状态", homeworkStatusLabel(item, now), Modifier.weight(1f))
            KeyValue("提交时间", item.submittedAt ?: "未提交", Modifier.weight(1f))
        }
        KeyValue("内容", item.contentExcerpt)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeworkDetailScreen(
    initialHomework: HomeworkItem,
    repository: ModuleRepository,
    attachmentRepository: HomeworkAttachmentRepository,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    onOpenAgent: () -> Unit,
) {
    val identity = remember(initialHomework) { homeworkIdentityKey(initialHomework) }
    var homework by remember(identity) { mutableStateOf(initialHomework) }
    var refreshNonce by remember(identity) { mutableStateOf(0) }
    var refreshError by remember(identity) { mutableStateOf<String?>(null) }
    var submitTarget by remember { mutableStateOf<HomeworkItem?>(null) }
    var resubmitConfirmTarget by remember { mutableStateOf<HomeworkItem?>(null) }
    var submitContent by remember { mutableStateOf("") }
    var pickedFiles by remember { mutableStateOf<List<HomeworkPickedFile>>(emptyList()) }
    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    var attachmentBusyKey by remember { mutableStateOf<String?>(null) }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    var previewTarget by remember { mutableStateOf<HomeworkAttachmentPreviewTarget?>(null) }
    val realNow = LocalDateTime.now()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pickedFiles = uris.map { context.describeHomeworkFile(it) }
    }

    fun openSubmitDialog(item: HomeworkItem) {
        submitTarget = item
        submitContent = ""
        pickedFiles = emptyList()
        submitError = null
    }

    fun previewAttachment(item: HomeworkItem, attachment: HomeworkAttachment) {
        val homeworkId = item.homeworkId ?: return
        val busyKey = homeworkAttachmentActionKey("preview", attachment)
        scope.launch {
            attachmentBusyKey = busyKey
            attachmentError = null
            runCatching {
                attachmentRepository.preview(homeworkId, attachment.attachmentId, attachment.filename)
            }.onSuccess { preview ->
                previewTarget = HomeworkAttachmentPreviewTarget(item, attachment, preview)
            }.onFailure { error ->
                attachmentError = error.message ?: "预览附件失败"
            }
            attachmentBusyKey = null
        }
    }

    fun downloadAttachment(item: HomeworkItem, attachment: HomeworkAttachment) {
        val homeworkId = item.homeworkId ?: return
        val busyKey = homeworkAttachmentActionKey("download", attachment)
        scope.launch {
            attachmentBusyKey = busyKey
            attachmentError = null
            runCatching {
                attachmentRepository.download(homeworkId, attachment.attachmentId, attachment.filename)
            }.onSuccess { file ->
                if (!openFile(context, file)) {
                    attachmentError = "已下载，但未找到可打开该文件的应用"
                }
            }.onFailure { error ->
                attachmentError = error.message ?: "下载附件失败"
            }
            attachmentBusyKey = null
        }
    }

    LaunchedEffect(identity, refreshNonce) {
        refreshError = null
        runCatching { repository.homework("all", strategy = if (refreshNonce == 0) initialLoadStrategy else ModuleLoadStrategy.NetworkFirst).data.items }
            .onSuccess { items ->
                homework = findHomeworkByIdentity(items, identity) ?: homework
            }
            .onFailure { error ->
                refreshError = error.message ?: "作业刷新失败"
            }
    }

    previewTarget?.let { target ->
        HomeworkAttachmentPreviewScreen(
            target = target,
            busyKey = attachmentBusyKey,
            error = attachmentError,
            onClose = {
                previewTarget = null
                attachmentError = null
            },
            onDownload = { downloadAttachment(target.homework, target.attachment) },
        )
        return
    }

    resubmitConfirmTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { resubmitConfirmTarget = null },
            title = { Text("确认重新提交") },
            text = { Text("该作业已于 ${target.submittedAt.orEmpty().ifBlank { "此前" }} 提交。继续操作会再次提交作业。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        resubmitConfirmTarget = null
                        openSubmitDialog(target)
                    },
                ) {
                    Text("继续")
                }
            },
            dismissButton = {
                TextButton(onClick = { resubmitConfirmTarget = null }) {
                    Text("取消")
                }
            },
        )
    }

    submitTarget?.let { target ->
        HomeworkSubmitDialog(
            homework = target,
            content = submitContent,
            pickedFiles = pickedFiles,
            submitting = submitting,
            error = submitError,
            onContentChange = { submitContent = it },
            onPickFiles = { filePicker.launch(arrayOf("*/*")) },
            onRemoveFile = { file -> pickedFiles = pickedFiles.filterNot { it.uri == file.uri } },
            onDismiss = {
                if (!submitting) {
                    submitTarget = null
                    submitError = null
                    pickedFiles = emptyList()
                    submitContent = ""
                }
            },
            onSubmit = {
                val homeworkId = target.homeworkId ?: return@HomeworkSubmitDialog
                submitting = true
                submitError = null
                scope.launch {
                    runCatching {
                        val uploads = pickedFiles.map { context.readHomeworkUploadFile(it) }
                        repository.submitHomework(
                            homeworkId = homeworkId,
                            courseId = target.courseId,
                            content = submitContent,
                            files = uploads,
                        )
                    }.onSuccess { response ->
                        submitting = false
                        submitTarget = null
                        pickedFiles = emptyList()
                        submitContent = ""
                        homework = homework.copy(
                            submittedAt = response.submittedAt ?: homework.submittedAt,
                            status = "done",
                        )
                        refreshNonce += 1
                    }.onFailure { error ->
                        submitting = false
                        submitError = homeworkSubmitErrorMessage(error)
                    }
                }
            },
        )
    }

    val itemStatus = homeworkStatusKind(homework, realNow)
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        if (!refreshError.isNullOrBlank()) {
            item {
                Text(
                    refreshError.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (!attachmentError.isNullOrBlank()) {
            item {
                Text(
                    attachmentError.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            InfoCard("作业信息", subtitle = homework.course) {
                Text(
                    homework.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ModuleStatusPill(
                        text = homeworkStatusLabel(homework, realNow),
                        color = homeworkStatusColor(itemStatus),
                    )
                    homework.submissionStatus?.takeIf { it.isNotBlank() }?.let {
                        ModuleStatusPill(text = it, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                    KeyValue("开始", homework.openedAt, Modifier.weight(1f))
                    KeyValue("截止", homework.dueAt, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                    KeyValue("提交时间", homework.submittedAt ?: "未提交", Modifier.weight(1f))
                    KeyValue("类型", homeworkTypeLabel(homework), Modifier.weight(1f))
                }
            }
        }
        item {
            InfoCard("内容") {
                Text(
                    homeworkDetailRequirement(homework),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        item {
            InfoCard("附件", subtitle = "${homework.attachments.size} 个") {
                HomeworkAttachmentsSection(
                    attachments = homework.attachments,
                    busyKey = attachmentBusyKey,
                    showHeader = false,
                    onPreview = { previewAttachment(homework, it) },
                    onDownload = { downloadAttachment(homework, it) },
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    enabled = homework.homeworkId != null,
                    onClick = {
                        NativeAgentHomeworkHandoffStore.set(NativeAgentHomeworkHandoff(homework))
                        onOpenAgent()
                    },
                ) {
                    Text("Agent 协助")
                }
                Button(
                    enabled = homework.homeworkId != null &&
                        homework.canSubmit &&
                        itemStatus != HomeworkStatusKind.ExpiredClosed &&
                        !submitting,
                    onClick = {
                        if (itemStatus == HomeworkStatusKind.Done) {
                            resubmitConfirmTarget = homework
                        } else {
                            openSubmitDialog(homework)
                        }
                    },
                ) {
                    Text(homeworkSubmitButtonLabel(itemStatus))
                }
            }
        }
    }
}

@Composable
private fun homeworkStatusColor(status: HomeworkStatusKind): Color =
    when (status) {
        HomeworkStatusKind.Done -> Color(0xFF2AA876)
        HomeworkStatusKind.Open -> MaterialTheme.colorScheme.primary
        HomeworkStatusKind.ExpiredCanSubmit -> Color(0xFFFF8A00)
        HomeworkStatusKind.ExpiredClosed -> MaterialTheme.colorScheme.error
    }

private fun homeworkDetailRequirement(item: HomeworkItem): String =
    item.requirementText?.takeIf { it.isNotBlank() }
        ?: item.contentExcerpt?.takeIf { it.isNotBlank() }
        ?: "暂无内容"

private fun homeworkTypeLabel(item: HomeworkItem): String =
    buildList {
        if (item.isGroup) add("小组作业")
        if (item.returnNum > 0) add("已退回 ${item.returnNum} 次")
    }.joinToString(" · ").ifBlank { "普通作业" }

@Composable
internal fun HomeworkAttachmentsSection(
    attachments: List<HomeworkAttachment>,
    busyKey: String?,
    showHeader: Boolean = true,
    onPreview: (HomeworkAttachment) -> Unit,
    onDownload: (HomeworkAttachment) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (showHeader) 8.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showHeader) {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("附件", style = MaterialTheme.typography.titleSmall)
                AssistChip(onClick = {}, label = { Text("${attachments.size} 个") })
            }
        }
        if (attachments.isEmpty()) {
            Text(
                "暂无附件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else attachments.forEach { attachment ->
            val previewKey = homeworkAttachmentActionKey("preview", attachment)
            val downloadKey = homeworkAttachmentActionKey("download", attachment)
            val previewBusy = busyKey == previewKey
            val downloadBusy = busyKey == downloadKey
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    attachment.filename,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                attachment.size?.takeIf { it.isNotBlank() }?.let { size ->
                    Text(
                        size,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        enabled = busyKey == null && attachment.attachmentId.isNotBlank(),
                        onClick = { onPreview(attachment) },
                    ) {
                        Text(if (previewBusy) "预览中" else "预览")
                    }
                    Button(
                        enabled = busyKey == null && attachment.attachmentId.isNotBlank(),
                        onClick = { onDownload(attachment) },
                    ) {
                        Text(if (downloadBusy) "下载中" else "下载")
                    }
                }
            }
        }
    }
}

internal fun homeworkAttachmentActionKey(action: String, attachment: HomeworkAttachment): String =
    "$action:${attachment.attachmentId}:${attachment.filename}"

private fun homeworkSubmitErrorMessage(error: Throwable): String {
    val message = error.message?.takeIf { it.isNotBlank() } ?: return "提交失败"
    val maxLength = 1200
    return if (message.length <= maxLength) {
        message
    } else {
        message.take(maxLength) + "\n…更多诊断信息请查看 Logcat：adb logcat -s VeProvider"
    }
}

internal data class HomeworkAttachmentPreviewTarget(
    val homework: HomeworkItem,
    val attachment: HomeworkAttachment,
    val preview: HomeworkAttachmentPreview,
)

@Composable
internal fun HomeworkAttachmentPreviewScreen(
    target: HomeworkAttachmentPreviewTarget,
    busyKey: String?,
    error: String?,
    onClose: () -> Unit,
    onDownload: () -> Unit,
) {
    val downloadBusy = busyKey == homeworkAttachmentActionKey("download", target.attachment)
    DocumentPreviewScreen(
        title = target.attachment.filename,
        subtitle = target.homework.title,
        preview = target.preview,
        downloadBusy = downloadBusy,
        error = error,
        onClose = onClose,
        onDownload = onDownload,
    )
}

private data class HomeworkCourseGroup(
    val courseId: String,
    val courseName: String,
    val items: List<HomeworkItem>,
    val total: Int,
    val openCount: Int,
    val expiredCount: Int,
)

private data class HomeworkPickedFile(
    val uri: Uri,
    val name: String,
    val mimeType: String?,
    val size: Long?,
)

private fun groupHomeworkItems(items: List<HomeworkItem>, now: LocalDateTime): List<HomeworkCourseGroup> =
    items
        .groupBy { item ->
            val courseId = item.courseId.toString()
            val courseName = item.course.trim().ifBlank { "未命名课程" }
            courseId to courseName
        }
        .map { (key, groupItems) ->
            val sortedItems = groupItems.sortedWith(
                compareBy<HomeworkItem> { it.dueAt ?: "9999" }
                    .thenBy { it.title },
            )
            HomeworkCourseGroup(
                courseId = key.first,
                courseName = key.second,
                items = sortedItems,
                total = sortedItems.size,
                openCount = sortedItems.count { homeworkStatusKind(it, now) == HomeworkStatusKind.Open },
                expiredCount = sortedItems.count {
                    val status = homeworkStatusKind(it, now)
                    status == HomeworkStatusKind.ExpiredCanSubmit || status == HomeworkStatusKind.ExpiredClosed
                },
            )
        }
        .sortedBy { it.courseName }

private fun buildHomeworkGroupSubtitle(group: HomeworkCourseGroup): String =
    buildList {
        add("共 ${group.total} 条")
        if (group.openCount > 0) add("待完成 ${group.openCount} 条")
        if (group.expiredCount > 0) add("已过期 ${group.expiredCount} 条")
    }.joinToString(" · ")

private fun homeworkStatusLabel(item: HomeworkItem, now: LocalDateTime): String =
    homeworkStatusKind(item, now).label

private fun homeworkSubmitButtonLabel(status: HomeworkStatusKind): String =
    when (status) {
        HomeworkStatusKind.Done -> "重新提交"
        HomeworkStatusKind.ExpiredCanSubmit -> "补交作业"
        HomeworkStatusKind.ExpiredClosed -> "不可补交"
        HomeworkStatusKind.Open -> "提交作业"
    }

@Composable
private fun HomeworkSubmitDialog(
    homework: HomeworkItem,
    content: String,
    pickedFiles: List<HomeworkPickedFile>,
    submitting: Boolean,
    error: String?,
    onContentChange: (String) -> Unit,
    onPickFiles: () -> Unit,
    onRemoveFile: (HomeworkPickedFile) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (homework.submittedAt.isNullOrBlank()) "提交作业" else "重新提交作业") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(homework.title, style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = content,
                    onValueChange = onContentChange,
                    label = { Text("提交内容") },
                    minLines = 4,
                    maxLines = 8,
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    enabled = !submitting,
                    onClick = onPickFiles,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (pickedFiles.isEmpty()) "选择附件" else "重新选择附件")
                }
                pickedFiles.forEach { file ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                file.size?.let { formatHomeworkFileSize(it) } ?: "未知大小",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(enabled = !submitting, onClick = { onRemoveFile(file) }) {
                            Text("移除")
                        }
                    }
                }
                if (!error.isNullOrBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(enabled = !submitting && homework.homeworkId != null, onClick = onSubmit) {
                Text(if (submitting) "提交中" else "提交")
            }
        },
        dismissButton = {
            TextButton(enabled = !submitting, onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

private fun Context.describeHomeworkFile(uri: Uri): HomeworkPickedFile {
    var name: String? = null
    var size: Long? = null
    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) name = cursor.getString(nameIndex)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
        }
    }
    return HomeworkPickedFile(
        uri = uri,
        name = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment",
        mimeType = contentResolver.getType(uri),
        size = size,
    )
}

private suspend fun Context.readHomeworkUploadFile(file: HomeworkPickedFile): HomeworkUploadFile =
    withContext(Dispatchers.IO) {
        val bytes = contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
            ?: throw IOException("无法读取附件 ${file.name}")
        HomeworkUploadFile(
            filename = file.name,
            content = bytes,
            contentType = file.mimeType ?: contentResolver.getType(file.uri),
        )
    }

private fun formatHomeworkFileSize(bytes: Long): String =
    when {
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
