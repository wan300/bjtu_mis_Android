package cn.edu.bjtu.mis.ui.screens

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import cn.edu.bjtu.mis.data.repository.CourseResourceRepository
import cn.edu.bjtu.mis.data.repository.DocumentPreview
import cn.edu.bjtu.mis.data.repository.ModuleLoadStrategy
import cn.edu.bjtu.mis.model.CourseResourceCategory
import cn.edu.bjtu.mis.model.CourseResourceItem
import cn.edu.bjtu.mis.model.CourseResourcesData
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.ProgressiveModuleState
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.KeyValue
import cn.edu.bjtu.mis.ui.components.LoadState
import cn.edu.bjtu.mis.ui.components.LoadingOrError
import cn.edu.bjtu.mis.ui.components.ProgressiveStatus
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseResourcesScreen(
    repository: CourseResourceRepository,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    initialCourseId: String = "",
    initialFolderId: String = "0",
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var selectedCourseId by remember { mutableStateOf(initialCourseId) }
    var selectedCategoryKey by remember { mutableStateOf("all") }
    var folderId by remember { mutableStateOf(initialFolderId) }
    var search by remember { mutableStateOf("") }
    var state by remember { mutableStateOf(ProgressiveModuleState<CourseResourcesData>()) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var previewing by remember { mutableStateOf<String?>(null) }
    var previewTarget by remember { mutableStateOf<CourseResourcesPreviewTarget?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(
        nextFolder: String = folderId,
        nextCourse: String = selectedCourseId,
        nextCategory: String = selectedCategoryKey,
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ) {
        scope.launch {
            state = ProgressiveModuleState()
            error = null
            runCatching {
                repository.listingProgressive(
                    courseId = nextCourse.ifBlank { null },
                    folderId = nextFolder,
                    search = search.ifBlank { null },
                    categoryKey = nextCategory,
                    strategy = strategy,
                ).collect { next ->
                    next.envelope?.data?.let { data ->
                        selectedCourseId = data.selectedCourseId?.toString().orEmpty()
                        selectedCategoryKey = data.selectedCategoryKey
                        folderId = data.folderId
                    }
                    state = next
                }
            }.onFailure {
                state = state.copy(
                    loading = false,
                    complete = true,
                    errors = state.errors + (it.message ?: "加载失败"),
                )
            }
        }
    }

    fun downloadResource(resource: CourseResourceItem) {
        scope.launch {
            downloading = resource.rpId
            error = null
            runCatching { repository.download(resource.rpId, resource.name, resource.extension) }
                .onSuccess {
                    if (!openFile(context, it)) {
                        error = "已下载，但未找到可打开该文件的应用"
                    }
                }
                .onFailure { error = it.message ?: "下载失败" }
            downloading = null
        }
    }

    fun previewResource(resource: CourseResourceItem) {
        scope.launch {
            previewing = resource.rpId
            error = null
            runCatching { repository.preview(resource) }
                .onSuccess { preview -> previewTarget = CourseResourcesPreviewTarget(resource, preview) }
                .onFailure { error = it.message ?: "预览失败" }
            previewing = null
        }
    }
    LaunchedEffect(Unit) { load(strategy = initialLoadStrategy) }

    previewTarget?.let { target ->
        DocumentPreviewScreen(
            title = target.resource.name,
            subtitle = target.resource.teacherName,
            preview = target.preview,
            downloadBusy = downloading == target.resource.rpId,
            error = error,
            onClose = {
                previewTarget = null
                error = null
            },
            onDownload = { downloadResource(target.resource) },
        )
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            val data = state.envelope?.data
            CourseSelector(
                courses = data?.courses.orEmpty().map { it.courseId.toString() to listOfNotNull(it.courseName, it.teacherName).joinToString(" · ") },
                value = selectedCourseId,
                onValueChange = {
                    selectedCourseId = it
                    folderId = "0"
                    load(nextFolder = "0", nextCourse = it)
                },
            )
        }
        item {
            CourseResourceCategorySelector(
                categories = state.envelope?.data?.categories.orEmpty(),
                value = selectedCategoryKey,
                onValueChange = {
                    selectedCategoryKey = it
                    folderId = "0"
                    load(nextFolder = "0", nextCategory = it)
                },
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("搜索资源名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { load() }) { Text("搜索") }
                    OutlinedButton(onClick = {
                        search = ""
                        load()
                    }) { Text("清空") }
                    OutlinedButton(onClick = {
                        folderId = "0"
                        load("0")
                    }) { Text("回到根目录") }
                }
            }
        }
        if (!error.isNullOrBlank()) {
            item { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error) }
        }
        val envelope = state.envelope
        if (envelope == null) {
            item {
                LoadingOrError(
                    if (state.loading) LoadState.Loading else LoadState.Error(state.errors.joinToString("；").ifBlank { "加载失败" })
                )
            }
        } else {
                val data = envelope.data
                item { ProgressiveStatus(state) }
                item {
                    val currentTerm = data.currentTerm
                    val selectedCourse = data.courses.firstOrNull { it.courseId.toString() == selectedCourseId }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!currentTerm.isNullOrBlank()) {
                            AssistChip(onClick = {}, label = { Text(currentTerm) })
                        }
                        val categoryLabel = data.categories.firstOrNull { it.key == data.selectedCategoryKey }?.label
                        if (!categoryLabel.isNullOrBlank()) {
                            AssistChip(onClick = {}, label = { Text(categoryLabel) })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = {}, label = { Text("${data.resources.size} 个文件") })
                            AssistChip(onClick = {}, label = { Text("${data.folders.size} 个目录") })
                        }
                    }
                }
                if (data.folders.isEmpty() && data.resources.isEmpty() && !state.loading) {
                    item {
                        InfoCard("暂无课程资源") {
                            Text("当前课程或目录下没有可展示的资源。", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                items(data.folders, key = { "${it.categoryKey}:${it.folderId}" }) { folder ->
                    InfoCard(
                        title = folder.name,
                        subtitle = "${folder.categoryLabel} · 目录 ${folder.folderId}",
                        modifier = Modifier.clickable {
                            selectedCategoryKey = folder.categoryKey
                            folderId = folder.folderId
                            load(nextFolder = folder.folderId, nextCategory = folder.categoryKey)
                        },
                    ) {
                        Text("点击进入目录")
                    }
                }
                items(data.resources, key = { "${it.categoryKey}:${it.rpId}" }) { resource ->
                    val subtitle = listOf(resource.categoryLabel, resource.uploadedAt)
                        .filter { !it.isNullOrBlank() }
                        .joinToString(" · ")
                    InfoCard(resource.name, subtitle = subtitle.ifBlank { null }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                            KeyValue("类型", resource.extension, Modifier.weight(1f))
                            KeyValue("大小", resource.size?.let { "$it MB" }, Modifier.weight(1f))
                            KeyValue("下载", resource.downloadCount?.toString(), Modifier.weight(1f))
                        }
                        KeyValue("教师", resource.teacherName)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = previewing == null && downloading == null,
                                onClick = { previewResource(resource) },
                            ) {
                                Text(if (previewing == resource.rpId) "预览中" else "预览")
                            }
                            Button(
                                enabled = resource.canDownload && downloading == null && previewing == null,
                                onClick = { downloadResource(resource) },
                            ) {
                                Text(if (downloading == resource.rpId) "下载中" else "下载")
                            }
                        }
                    }
                }
            }
        }
}

@Composable
private fun CourseResourceCategorySelector(
    categories: List<CourseResourceCategory>,
    value: String,
    onValueChange: (String) -> Unit,
) {
    if (categories.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        categories.forEach { category ->
            FilterChip(
                selected = category.key == value,
                onClick = { onValueChange(category.key) },
                label = { Text(category.label) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseSelector(
    courses: List<Pair<String, String>>,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = courses.firstOrNull { it.first == value }?.second.orEmpty()
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("选择课程") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            courses.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onValueChange(id)
                    },
                )
            }
        }
    }
}

internal fun openFile(context: android.content.Context, file: File): Boolean {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val extension = file.extension.lowercase()
    val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, type)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return runCatching { context.startActivity(Intent.createChooser(intent, "打开文件")) }.isSuccess
}

private data class CourseResourcesPreviewTarget(
    val resource: CourseResourceItem,
    val preview: DocumentPreview,
)
