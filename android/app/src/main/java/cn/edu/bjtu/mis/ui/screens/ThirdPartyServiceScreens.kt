package cn.edu.bjtu.mis.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.edu.bjtu.mis.data.thirdparty.CatalogPlugin
import cn.edu.bjtu.mis.data.thirdparty.CatalogPluginPage
import cn.edu.bjtu.mis.data.thirdparty.AndroidAccessibilityController
import cn.edu.bjtu.mis.data.thirdparty.AndroidNativeEventController
import cn.edu.bjtu.mis.data.thirdparty.AndroidNativeRuntimeController
import cn.edu.bjtu.mis.data.thirdparty.PluginWebViewPolicy
import cn.edu.bjtu.mis.data.thirdparty.PluginWebViewRuntimeEnvironment
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyCapabilityRegistry
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartySandboxResourceResolution
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartySensitiveActionConfirmer
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyCatalogRepository
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyService
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyServiceApiRegistry
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyServiceImportPreview
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyOriginDeclaration
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyServiceRepository
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyServiceSandbox
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyWebViewAccessPolicy
import cn.edu.bjtu.mis.data.thirdparty.thirdPartyServiceRoute
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.LoadState
import cn.edu.bjtu.mis.ui.components.LoadingOrError
import cn.edu.bjtu.mis.ui.components.SectionTitle
import cn.edu.bjtu.mis.ui.components.ThirdPartyPluginIcon
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.File

private const val ThirdPartyBridgeLogTag = "ThirdPartyBridge"

@Composable
fun ThirdPartyServicesScreen(
    repository: ThirdPartyServiceRepository,
    catalogRepository: ThirdPartyCatalogRepository,
    onOpenService: (String) -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showInstalled by remember { mutableStateOf(false) }
    var servicesState by remember { mutableStateOf<LoadState<List<ThirdPartyService>>>(LoadState.Loading) }
    var catalogState by remember { mutableStateOf<LoadState<CatalogPluginPage>>(LoadState.Loading) }
    var catalogUpdates by remember { mutableStateOf<Map<String, CatalogPlugin>>(emptyMap()) }
    var searchQuery by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf("") }
    var githubUrl by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var updateProgress by remember { mutableStateOf<String?>(null) }
    var pendingPreview by remember { mutableStateOf<ThirdPartyServiceImportPreview?>(null) }
    var readmePreview by remember { mutableStateOf<ThirdPartyServiceImportPreview?>(null) }
    var readmeState by remember { mutableStateOf<LoadState<String?>>(LoadState.Loading) }
    val readmeLoader = remember(scope, repository) {
        LatestRequestLoader<String?>(scope) { result ->
            readmeState = result.fold(
                onSuccess = { LoadState.Data(it) },
                onFailure = { LoadState.Error(it.message ?: "GitHub README 暂时不可用") },
            )
        }
    }
    var selectedCatalog by remember { mutableStateOf<CatalogPlugin?>(null) }
    var catalogReadmeState by remember { mutableStateOf<LoadState<String?>>(LoadState.Loading) }
    val catalogReadmeLoader = remember(scope, repository) {
        LatestRequestLoader<String?>(scope) { result ->
            catalogReadmeState = result.fold(
                onSuccess = { LoadState.Data(it) },
                onFailure = { LoadState.Error(it.message ?: "README 暂时不可用") },
            )
        }
    }
    val runtimeEnvironment = rememberPluginWebViewRuntimeEnvironment()

    fun load() {
        scope.launch {
            servicesState = LoadState.Loading
            runCatching { repository.listServices() }
                .onSuccess { services ->
                    servicesState = LoadState.Data(services)
                    catalogUpdates = runCatching { catalogRepository.resolveUpdates(services) }
                        .getOrDefault(emptyList())
                        .filter { it.updateAvailable }
                        .associateBy { it.id }
                }
                .onFailure { servicesState = LoadState.Error(it.message ?: "加载第三方服务失败") }
        }
    }

    fun loadCatalog() {
        scope.launch {
            catalogState = LoadState.Loading
            runCatching { catalogRepository.listPlugins(query = searchQuery, category = categoryFilter) }
                .onSuccess { catalogState = LoadState.Data(it) }
                .onFailure { catalogState = LoadState.Error(it.message ?: "插件大厅暂时不可用") }
        }
    }

    fun prepareImportOrUpdate(url: String) {
        scope.launch {
            busy = true
            message = null
            updateProgress = "正在下载并校验更新…"
            runCatching { repository.prepareImportFromGitHub(url) }
                .onSuccess { preview ->
                    if (preview.requiresUserConfirmation) {
                        pendingPreview = preview
                        updateProgress = null
                        message = if (preview.updatedExisting) {
                            "检测到权限或安全边界变化，请确认增量风险"
                        } else {
                            "预检完成，请确认 Capability 和允许来源"
                        }
                    } else {
                        updateProgress = "正在安装更新…"
                        runCatching { repository.commitPreparedImport(preview.token) }
                            .onSuccess { result ->
                                githubUrl = ""
                                readmePreview = null
                                message = if (result.updatedExisting) "已完成更新" else "已导入服务，请打开后确认 Capability"
                                load()
                            }
                            .onFailure { message = it.message ?: "安装失败" }
                        updateProgress = null
                    }
                }
                .onFailure {
                    updateProgress = null
                    message = it.message ?: "预检失败"
                }
            busy = false
        }
    }

    fun prepareCatalogInstall(plugin: CatalogPlugin) {
        scope.launch {
            busy = true
            message = null
            updateProgress = if (plugin.updateAvailable) "正在下载并校验更新…" else "正在下载并校验插件…"
            runCatching { repository.prepareInstallFromCatalog(plugin) }
                .onSuccess {
                    if (it.requiresUserConfirmation) {
                        pendingPreview = it
                        updateProgress = null
                        message = if (it.updatedExisting) {
                            "检测到权限或安全边界变化，请确认增量风险"
                        } else {
                            "平台快照和双重 digest 校验完成，请确认安装风险"
                        }
                    } else {
                        updateProgress = "正在安装更新…"
                        runCatching { repository.commitPreparedImport(it.token) }
                            .onSuccess { result ->
                                githubUrl = ""
                                readmePreview = null
                                message = if (result.updatedExisting) "已完成更新" else "已导入服务，请打开后确认 Capability"
                                load()
                            }
                            .onFailure { message = it.message ?: "安装失败" }
                        updateProgress = null
                    }
                }
                .onFailure {
                    updateProgress = null
                    message = it.message ?: "平台快照预检失败"
                }
            busy = false
        }
    }

    fun commitPreview(preview: ThirdPartyServiceImportPreview) {
        scope.launch {
            busy = true
            message = null
            updateProgress = if (preview.updatedExisting) "正在安装更新…" else "正在安装插件…"
            runCatching {
                repository.commitPreparedImport(
                    preview.token,
                    reviewedOrigins = preview.manifest.origins.takeIf { preview.updatedExisting },
                )
            }
                .onSuccess {
                    githubUrl = ""
                    pendingPreview = null
                    readmePreview = null
                    message = if (it.updatedExisting && it.service.needsReview) {
                        "已更新，请完成 Capability 授权"
                    } else if (it.updatedExisting) {
                        "已完成更新"
                    } else {
                        "已导入服务，请打开后确认 Capability"
                    }
                    load()
                }
                .onFailure { message = it.message ?: "导入失败" }
            updateProgress = null
            busy = false
        }
    }

    fun cancelPreview(preview: ThirdPartyServiceImportPreview) {
        repository.discardPreparedImport(preview.token)
        if (readmePreview?.token == preview.token) {
            readmeLoader.invalidate()
            readmePreview = null
        }
        pendingPreview = null
        message = "已取消导入"
    }

    fun loadReadme(preview: ThirdPartyServiceImportPreview) {
        readmeState = LoadState.Loading
        readmeLoader.load { repository.loadPreparedImportReadme(preview) }
    }

    fun showReadme(preview: ThirdPartyServiceImportPreview) {
        readmePreview = preview
        loadReadme(preview)
    }

    fun loadCatalogReadme(plugin: CatalogPlugin) {
        catalogReadmeState = LoadState.Loading
        catalogReadmeLoader.load { repository.loadCatalogPluginReadme(plugin) }
    }

    LaunchedEffect(Unit) {
        repository.cleanupStalePreparedImports()
        load()
        loadCatalog()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(14.dp),
    ) {
        item {
            InfoCard(
                title = "插件运行环境",
                subtitle = if (runtimeEnvironment.secureRuntimeAvailable) {
                    "当前设备可运行 contract_v1 插件"
                } else {
                    "当前设备缺少核心 WebView 安全能力；仍可安装或更新，但不能启用"
                },
            ) {
                PluginRuntimeEnvironmentSummary(runtimeEnvironment)
                OutlinedButton(
                    onClick = onOpenDiagnostics,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("查看插件运行环境诊断")
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { showInstalled = false }, enabled = showInstalled, modifier = Modifier.weight(1f)) {
                    Text("插件大厅")
                }
                Button(onClick = { showInstalled = true }, enabled = !showInstalled, modifier = Modifier.weight(1f)) {
                    Text("已安装")
                }
            }
        }
        updateProgress?.let { phase ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(phase, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (!showInstalled) {
            item {
                InfoCard(title = "搜索插件", subtitle = "断网时自动展示最近一次成功目录快照") {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("名称、作者、描述或插件 ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = categoryFilter,
                        onValueChange = { categoryFilter = it },
                        label = { Text("分类（可选）") },
                        placeholder = { Text("academic / campus / information / productivity / assistant / other") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { loadCatalog() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("搜索") }
                }
            }
            message?.let { currentMessage -> item {
                Text(currentMessage, color = if (currentMessage.contains("失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            pendingPreview?.let { preview -> item {
                ThirdPartyImportPreviewCard(
                    preview = preview,
                    busy = busy,
                    onConfirm = { commitPreview(preview) },
                    onCancel = { cancelPreview(preview) },
                    onShowReadme = { showReadme(preview) },
                )
            } }
            when (val current = catalogState) {
                LoadState.Loading, is LoadState.Error -> item { LoadingOrError(current) }
                is LoadState.Data -> {
                    if (current.value.fromCache) item {
                        Text("平台暂时不可用，当前展示本机缓存；已安装插件可继续运行。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (current.value.items.isEmpty()) item { InfoCard("暂无插件", subtitle = "可在 Web 插件大厅投稿公开 GitHub 仓库") {} }
                    items(current.value.items, key = { it.id }) { plugin ->
                        CatalogPluginCard(
                            plugin = plugin,
                            installed = (servicesState as? LoadState.Data<List<ThirdPartyService>>)?.value?.any { it.serviceId == plugin.id } == true,
                            busy = busy,
                            onInstall = { prepareCatalogInstall(plugin) },
                            onDetails = {
                                selectedCatalog = plugin
                                loadCatalogReadme(plugin)
                            },
                        )
                    }
                }
            }
        }
        if (showInstalled) {
        item {
            InfoCard(title = "高级 / 开发者导入", subtitle = "未经过平台自动校验；仓库根目录需包含 bjtu-plugin.json 和 dist/") {
                OutlinedTextField(
                    value = githubUrl,
                    onValueChange = { githubUrl = it },
                    label = { Text("GitHub 仓库链接") },
                    placeholder = { Text("https://github.com/owner/repo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = !busy && githubUrl.isNotBlank(),
                    onClick = { prepareImportOrUpdate(githubUrl) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(if (busy) "处理中" else "预检")
                }
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.contains("失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "警告：此方式直接下载 GitHub 内容，不具有平台归档 digest 背书。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        pendingPreview?.let { preview ->
            item {
                ThirdPartyImportPreviewCard(
                    preview = preview,
                    busy = busy,
                    onConfirm = { commitPreview(preview) },
                    onCancel = { cancelPreview(preview) },
                    onShowReadme = { showReadme(preview) },
                )
            }
        }
        when (val current = servicesState) {
            LoadState.Loading, is LoadState.Error -> item {
                LoadingOrError(current)
            }
            is LoadState.Data -> {
                if (current.value.isEmpty()) {
                    item {
                        InfoCard("尚未安装第三方服务", subtitle = "导入公开 GitHub 仓库后会显示在这里") {}
                    }
                } else {
                    items(current.value, key = { it.serviceId }) { service ->
                        ThirdPartyServiceManagementCard(
                            service = service,
                            updateAvailable = service.serviceId in catalogUpdates,
                            busy = busy,
                            onOpen = { onOpenService(thirdPartyServiceRoute(service.serviceId)) },
                            onUpdate = {
                                catalogUpdates[service.serviceId]?.let { prepareCatalogInstall(it) }
                                    ?: prepareImportOrUpdate(service.sourceUrl)
                            },
                            onConfigure = {
                                scope.launch {
                                    repository.requireReview(service.serviceId)
                                    onOpenService(thirdPartyServiceRoute(service.serviceId))
                                }
                            },
                            onStopAutomation = {
                                AndroidAccessibilityController.stopServiceRuntime(
                                    service.publisherSubjectId,
                                    service.serviceId,
                                )
                                AndroidNativeRuntimeController.revokeService(
                                    service.publisherSubjectId,
                                    service.serviceId,
                                )
                                message = "已停止 ${service.manifest.name} 的后台自动化"
                            },
                            onReviewAutomation = {
                                scope.launch {
                                    repository.requireReview(service.serviceId)
                                    onOpenService(thirdPartyServiceRoute(service.serviceId))
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    busy = true
                                    runCatching { repository.deleteService(service.serviceId) }
                                        .onFailure { message = it.message ?: "删除失败" }
                                    busy = false
                                    load()
                                }
                            },
                        )
                    }
                }
            }
        }
        }
    }

    readmePreview?.let { preview ->
        PluginReadmePreviewDialog(
            target = PluginReadmeTarget(preview.manifest.name, preview.githubOwner, preview.githubRepo, preview.commitSha),
            state = readmeState,
            onRetry = { loadReadme(preview) },
            onDismiss = {
                readmeLoader.invalidate()
                readmePreview = null
            },
        )
    }
    selectedCatalog?.let { plugin ->
        val repositoryPath = plugin.repositoryUrl.substringAfter("github.com/").trimEnd('/').split('/')
        PluginReadmePreviewDialog(
            target = PluginReadmeTarget(
                title = "${plugin.name} · README",
                owner = repositoryPath.getOrNull(0).orEmpty(),
                repository = repositoryPath.getOrNull(1).orEmpty(),
                commitSha = plugin.commitSha,
                requirements = buildList {
                    add("必需权限：${plugin.permissions.required.joinToString().ifBlank { "无" }}")
                    add("可选权限：${plugin.permissions.optional.joinToString().ifBlank { "无" }}")
                    add("Connect：${plugin.connectOrigins.joinToString().ifBlank { "无" }}")
                    add("Media：${plugin.mediaOrigins.joinToString().ifBlank { "无" }}")
                    add("Frame：${plugin.frameOrigins.joinToString().ifBlank { "无" }}")
                    add("Navigation：${plugin.navigationOrigins.joinToString().ifBlank { "无" }}")
                    if (plugin.configuration.isNotEmpty()) add("配置：${plugin.configuration.joinToString { it.label }}")
                },
            ),
            state = catalogReadmeState,
            onRetry = { loadCatalogReadme(plugin) },
            onDismiss = {
                catalogReadmeLoader.invalidate()
                selectedCatalog = null
            },
        )
    }
}

@Composable
private fun CatalogPluginCard(
    plugin: CatalogPlugin,
    installed: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onDetails: () -> Unit,
) {
    InfoCard(
        title = plugin.name,
        subtitle = "${plugin.version} · ${plugin.repository} · 未人工审核",
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ThirdPartyPluginIcon(
                source = plugin.iconSource,
                contentDescription = "${plugin.name} 图标",
                modifier = Modifier.size(64.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(plugin.description, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "分类：${plugin.category} · 标签：${plugin.tags.joinToString().ifBlank { "无" }}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text("Commit：${plugin.commitSha.take(12)}", style = MaterialTheme.typography.bodySmall)
        Text("归档 SHA-256：${plugin.archiveSha256.take(12)}…", style = MaterialTheme.typography.bodySmall)
        Text(
            "Runtime floor ${plugin.runtimeFloor} · ${plugin.contractProfile} · ${plugin.compatibilityState}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Publisher：${plugin.publisherSubjectId.ifBlank { "未知" }} · ${plugin.verificationLevel}",
            style = MaterialTheme.typography.bodySmall,
        )
        plugin.validationWarnings.forEach { warning ->
            Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onDetails, enabled = !busy, modifier = Modifier.weight(1f)) { Text("查看详情") }
            Button(onClick = onInstall, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(if (installed) "更新" else "安装")
            }
        }
    }
}

@Composable
private fun ThirdPartyImportPreviewCard(
    preview: ThirdPartyServiceImportPreview,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onShowReadme: () -> Unit,
) {
    val runtimeEnvironment = rememberPluginWebViewRuntimeEnvironment()
    InfoCard(
        title = if (preview.updatedExisting) "确认更新" else "确认导入",
        subtitle = "${preview.manifest.name} · ${preview.githubOwner}/${preview.githubRepo}@${preview.commitSha.take(8)}",
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ThirdPartyPluginIcon(
                source = preview.iconSource,
                contentDescription = "${preview.manifest.name} 图标",
                modifier = Modifier.size(64.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(preview.manifest.description, style = MaterialTheme.typography.bodyMedium)
                Text("版本：${preview.manifest.version}", style = MaterialTheme.typography.bodySmall)
                Text("作者：${preview.manifest.author}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Digest：${preview.packageDigestSha256.take(12)}", style = MaterialTheme.typography.bodySmall)
        Text(
            if (preview.platformVerified) "来源：平台不可变快照 · 未人工审核" else "来源：高级直链导入 · 未经平台校验",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        preview.archiveSha256?.let { Text("归档 SHA-256：${it.take(12)}…", style = MaterialTheme.typography.bodySmall) }
        Text(
            "包内容：${preview.packageFileCount} 个文件，${formatBytes(preview.packageBytes)}",
            style = MaterialTheme.typography.bodySmall,
        )
        PluginRuntimeEnvironmentSummary(runtimeEnvironment)
        if (!runtimeEnvironment.secureRuntimeAvailable) {
            Text(
                "当前设备允许完成安装或更新并保留插件包，但在 WebView 更新前不能启用或运行。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!preview.updatedExisting) {
            CapabilitySummary("Required capabilities", preview.manifest.requiredCapabilities)
            if (preview.manifest.optionalCapabilities.isNotEmpty()) {
                CapabilitySummary(
                    "Optional capabilities（首次安装默认关闭）",
                    preview.manifest.optionalCapabilities,
                )
            }
        }
        Text(
            "Publisher：${preview.publisherSubjectId} · ${preview.verificationLevel}",
            style = MaterialTheme.typography.bodySmall,
        )
        if (preview.updatedExisting) {
            if (preview.addedRequiredCapabilities.isNotEmpty()) {
                CapabilitySummary("新增 required capabilities", preview.addedRequiredCapabilities)
            }
            if (preview.addedOptionalCapabilities.isNotEmpty()) {
                CapabilitySummary("新增 optional capabilities（更新后保持关闭）", preview.addedOptionalCapabilities)
            }
            if (preview.removedCapabilities.isNotEmpty()) {
                CapabilitySummary("将自动撤销的 capabilities", preview.removedCapabilities)
            }
            OriginChangesSummary("新增", preview.addedOriginPolicies)
            OriginChangesSummary("移除", preview.removedOriginPolicies)
        } else {
            OriginPolicySummary(
                connect = preview.manifest.connectOrigins,
                media = preview.manifest.mediaOrigins,
                frame = preview.manifest.frameOrigins,
                navigation = preview.manifest.navigationOrigins,
                bridge = preview.manifest.bridgeOrigins,
            )
        }
        OutlinedButton(onClick = onShowReadme, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("查看 README")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onConfirm, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(if (preview.updatedExisting) "确认更新" else "确认导入")
            }
            OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("取消")
            }
        }
    }
}

@Composable
private fun CapabilitySummary(title: String, capabilities: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        if (capabilities.isEmpty()) {
            Text("无", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            capabilities.forEach { id ->
                val descriptor = ThirdPartyCapabilityRegistry.get(id)
                Text(
                    buildString {
                        append(id)
                        descriptor?.permissionTitle?.let { append(" · ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ValueSummary(title: String, values: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        if (values.isEmpty()) {
            Text("无", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            values.forEach { value ->
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OriginChangesSummary(title: String, origins: ThirdPartyOriginDeclaration) {
    listOf(
        "Connect（网络请求）" to origins.connect,
        "Media（图片与媒体）" to origins.media,
        "Frame（嵌入页面）" to origins.frame,
        "Navigation（外部导航）" to origins.navigation,
    ).forEach { (purpose, values) ->
        if (values.isNotEmpty()) ValueSummary("$title $purpose", values)
    }
}

@Composable
private fun OriginPolicySummary(
    connect: List<String>,
    media: List<String>,
    frame: List<String>,
    navigation: List<String>,
    bridge: List<String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Origin 策略", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        listOf(
            "Connect" to connect,
            "Media" to media,
            "Frame" to frame,
            "Navigation" to navigation,
            "Bridge" to bridge,
        ).forEach { (label, origins) ->
            Text(
                "$label：${origins.joinToString().ifBlank { "无" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (frame.isNotEmpty()) {
            Text(
                "远程 iframe 无原生桥、无顶层导航/下载/弹窗，第三方 Cookie 已关闭。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (bridge != listOf("self")) {
            Text(
                "Bridge 策略无效，客户端将拒绝运行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ThirdPartyServiceManagementCard(
    service: ThirdPartyService,
    updateAvailable: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onUpdate: () -> Unit,
    onConfigure: () -> Unit,
    onStopAutomation: () -> Unit,
    onReviewAutomation: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val canUpdateFromGitHub = service.sourceUrl.startsWith("https://github.com/")
    val hasAccessibilityGrant = service.grantedCapabilities.any { it.startsWith("android.accessibility.") }
    val hasAndroidGrant = service.grantedCapabilities.any { it.startsWith("android.") }
    InfoCard(
        title = service.manifest.name,
        subtitle = "${service.manifest.version} · ${service.githubOwner}/${service.githubRepo}",
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ThirdPartyPluginIcon(
                source = service.iconSource,
                contentDescription = "${service.manifest.name} 图标",
                modifier = Modifier.size(64.dp),
            )
            Text(
                service.manifest.description,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (service.needsReview || !service.enabled) {
                "需要确认 Capability · ${service.packageDigestSha256.take(12)}"
            } else if (updateAvailable) {
                "发现平台快照更新 · 当前 ${service.commitSha.take(8)}"
            } else {
                "已启用 · ${service.commitSha.take(8)} · ${service.packageDigestSha256.take(12)}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (service.needsReview || !service.enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onOpen, enabled = !busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Security, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 3.dp))
                Text(if (service.needsReview || !service.enabled) "授权" else "打开")
            }
            OutlinedButton(onClick = onUpdate, enabled = !busy && canUpdateFromGitHub, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 3.dp))
                Text(if (canUpdateFromGitHub) "更新" else "内置")
            }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(Icons.Filled.Delete, contentDescription = "删除")
            }
        }
        if (service.manifest.configuration.isNotEmpty()) {
            OutlinedButton(onClick = onConfigure, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("配置插件环境变量")
            }
        }
        if (hasAndroidGrant && service.canRun) {
            val connected = AndroidAccessibilityController.isConnected()
            val accessibilityPersistent = AndroidAccessibilityController.hasPersistentSubscriptions(
                service.publisherSubjectId,
                service.serviceId,
            )
            val nativePersistent = AndroidNativeEventController.hasPersistentSubscriptions(
                service.publisherSubjectId,
                service.serviceId,
            )
            val state = buildList {
                if (hasAccessibilityGrant) {
                    add(if (connected) "无障碍服务已连接" else "无障碍服务未连接")
                }
                if (nativePersistent) add("原生状态订阅运行中")
                if (accessibilityPersistent || nativePersistent) add("存在持久订阅") else add("暂无持久订阅")
            }.joinToString(" · ")
            Text(
                "后台自动化：$state",
                style = MaterialTheme.typography.bodySmall,
                color = if (!hasAccessibilityGrant || connected) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onStopAutomation, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("停止后台运行")
                }
                OutlinedButton(onClick = onReviewAutomation, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("撤销或调整授权")
                }
            }
            if (hasAccessibilityGrant && !connected) {
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("打开系统无障碍设置")
                }
            }
        }
    }
}

@Composable
fun ThirdPartyServiceRoute(
    serviceId: String,
    repository: ThirdPartyServiceRepository,
    apiRegistry: ThirdPartyServiceApiRegistry,
    resourceStore: cn.edu.bjtu.mis.data.thirdparty.ThirdPartyResourceStore,
    kvStore: cn.edu.bjtu.mis.data.thirdparty.ThirdPartyKvStore,
    onBackToServices: () -> Unit,
    onBackHandlerChanged: ((() -> Boolean)?) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var state by remember(serviceId) { mutableStateOf<LoadState<ThirdPartyService>>(LoadState.Loading) }

    fun load() {
        scope.launch {
            state = LoadState.Loading
            runCatching { repository.getService(serviceId) }
                .onSuccess { service ->
                    state = service?.let { LoadState.Data(it) } ?: LoadState.Error("第三方服务不存在")
                }
                .onFailure { state = LoadState.Error(it.message ?: "加载第三方服务失败") }
        }
    }

    LaunchedEffect(serviceId) { load() }

    when (val current = state) {
        LoadState.Loading, is LoadState.Error -> Box(Modifier.padding(14.dp)) {
            LoadingOrError(current)
        }
        is LoadState.Data -> {
            val service = current.value
            if (
                service.runtimeProfile !=
                cn.edu.bjtu.mis.data.thirdparty.ThirdPartyRuntimeProfile.ContractV1.value
            ) {
                ThirdPartyLegacyRescueScreen(service, onBackToServices)
            } else if (service.needsReview || !service.enabled) {
                ThirdPartyCapabilityReviewScreen(
                    service = service,
                    repository = repository,
                    onGrant = { granted ->
                        scope.launch {
                            state = LoadState.Loading
                            runCatching {
                                repository.grantCapabilities(service.serviceId, granted)
                            }
                                .onSuccess { state = LoadState.Data(it) }
                                .onFailure { state = LoadState.Error(it.message ?: "授权失败") }
                        }
                    },
                    onBackToServices = onBackToServices,
                )
            } else {
                ThirdPartyServiceWebViewScreen(
                    service = service,
                    apiRegistry = apiRegistry,
                    resourceStore = resourceStore,
                    kvStore = kvStore,
                    onCloseService = onBackToServices,
                    onBackHandlerChanged = onBackHandlerChanged,
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ThirdPartyLegacyRescueScreen(
    service: ThirdPartyService,
    onBackToServices: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InfoCard(
            title = "Legacy 只读救援模式",
            subtitle = "Manifest v1/v2 与 P0-A v3 已停用；旧包和 WebStorage 未删除。",
        ) {
            Text("此入口无原生桥、无网络，仅用于查看旧插件本地数据。")
            OutlinedButton(onClick = onBackToServices, modifier = Modifier.fillMaxWidth()) {
                Text("返回插件管理")
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.allowFileAccessFromFileURLs = false
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setSupportMultipleWindows(false)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    setDownloadListener { _, _, _, _, _ -> }
                    webViewClient = ThirdPartyLegacyRescueWebViewClient(service)
                    val legacyOrigin = ThirdPartyServiceSandbox.originFor(
                        service.serviceId,
                        service.rescueOriginSubject(),
                    )
                    val path = java.net.URI(
                        null,
                        null,
                        "/${service.manifest.entrypoint.trimStart('/')}",
                        null,
                    ).rawPath
                    loadUrl(legacyOrigin + path)
                }
            },
        )
    }
}

@Composable
private fun ThirdPartyCapabilityReviewScreen(
    service: ThirdPartyService,
    repository: ThirdPartyServiceRepository,
    onGrant: (Set<String>) -> Unit,
    onBackToServices: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val required = service.manifest.requiredCapabilities
    val optional = service.manifest.optionalCapabilities
    val androidSystemCapabilities = (required + optional)
        .filter { it.startsWith("android.") }
    var selected by remember(service.serviceId, service.commitSha) {
        mutableStateOf(service.reviewCapabilitySelection)
    }
    var configurationValues by remember(service.serviceId, service.commitSha) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var configurationError by remember(service.serviceId, service.commitSha) { mutableStateOf<String?>(null) }
    val runtimeEnvironment = rememberPluginWebViewRuntimeEnvironment()
    LaunchedEffect(service.serviceId, service.commitSha) {
        runCatching { repository.getConfiguration(service.serviceId) }
            .onSuccess { configurationValues = it }
            .onFailure { configurationError = it.message ?: "读取插件配置失败" }
    }
    val configurationComplete = service.manifest.configuration
        .filter { it.required }
        .all { !(configurationValues[it.key] ?: it.default).isNullOrBlank() }
    val canEnable = selected.containsAll(required) &&
        configurationComplete &&
        runtimeEnvironment.secureRuntimeAvailable

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(14.dp),
    ) {
        item {
            SectionTitle(
                title = "确认 Capability",
                subtitle = "${service.manifest.name} · ${service.githubOwner}/${service.githubRepo}@${service.commitSha.take(8)}",
            )
        }
        item {
            InfoCard(title = "服务信息", subtitle = service.manifest.description) {
                Text("作者：${service.manifest.author}", style = MaterialTheme.typography.bodySmall)
                Text("版本：${service.manifest.version}", style = MaterialTheme.typography.bodySmall)
                Text("来源：${service.sourceUrl}", style = MaterialTheme.typography.bodySmall)
                Text("Digest：${service.packageDigestSha256.take(12)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (androidSystemCapabilities.isNotEmpty()) {
            item {
                InfoCard(
                    title = "Android 原生能力授权",
                    subtitle = "这些能力在首次同意后会持续授权给插件，直到你在插件管理或系统设置中撤销",
                ) {
                    val accessibilityCapabilities = androidSystemCapabilities.filter {
                        it.startsWith("android.accessibility.") ||
                            it == "android.packages.read@1" ||
                            it == "android.settings.open@1"
                    }
                    val nativeCapabilities = androidSystemCapabilities - accessibilityCapabilities.toSet()
                    if (accessibilityCapabilities.isNotEmpty()) {
                        Text(
                            "插件可读取无障碍事件和窗口节点、操作其他应用、查询已安装应用或打开系统设置。密码和敏感输入值始终脱敏。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (nativeCapabilities.isNotEmpty()) {
                        Text(
                            "插件可使用受限设备状态、文件/媒体、通知、定位、日历、相机、录音、传感器或生物识别能力。系统运行时权限、选择器和前台页面要求仍然生效；后台不能启动系统 UI、定位或录音。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    androidSystemCapabilities.forEach { capability ->
                        Text(capability, style = MaterialTheme.typography.bodySmall)
                    }
                    if (androidSystemCapabilities.any { it.startsWith("android.accessibility.") }) {
                        Text(
                            if (AndroidAccessibilityController.isConnected()) {
                                "系统无障碍服务：已连接"
                            } else {
                                "系统无障碍服务：未连接"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (AndroidAccessibilityController.isConnected()) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("打开系统无障碍设置")
                        }
                    }
                }
            }
        }
        item {
            CapabilityGroupCard(
                title = "Required capabilities",
                subtitle = "不同意这些能力则无法启用插件",
                capabilities = required,
                selected = selected,
                onSelectedChange = { id, checked ->
                    selected = if (checked) selected + id else selected - id
                },
            )
        }
        if (optional.isNotEmpty()) {
            item {
                CapabilityGroupCard(
                    title = "Optional capabilities",
                    subtitle = "首次安装默认关闭；关闭后对应 SDK 调用返回 permission_denied",
                    capabilities = optional,
                    selected = selected,
                    onSelectedChange = { id, checked ->
                        selected = if (checked) selected + id else selected - id
                    },
                )
            }
        }
        if (service.manifest.configuration.isNotEmpty()) {
            item {
                InfoCard(title = "插件配置", subtitle = "配置值仅保存在本机，并使用 Android Keystore / AES-GCM 加密") {
                    service.manifest.configuration.forEach { definition ->
                        OutlinedTextField(
                            value = configurationValues[definition.key].orEmpty(),
                            onValueChange = { value -> configurationValues = configurationValues + (definition.key to value) },
                            label = { Text("${definition.label}${if (definition.required) " *" else ""}") },
                            supportingText = { Text("${definition.key} · ${definition.description}") },
                            visualTransformation = if (definition.type == "secret") PasswordVisualTransformation() else VisualTransformation.None,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    configurationError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            InfoCard(title = "Origin 与桥接边界", subtitle = "远程内容永远不能调用原生桥") {
                OriginPolicySummary(
                    connect = service.manifest.connectOrigins,
                    media = service.manifest.mediaOrigins,
                    frame = service.manifest.frameOrigins,
                    navigation = service.manifest.navigationOrigins,
                    bridge = service.manifest.bridgeOrigins,
                )
            }
        }
        item {
            InfoCard(
                title = "当前插件运行环境",
                subtitle = if (runtimeEnvironment.secureRuntimeAvailable) {
                    "核心 WebView 安全能力可用"
                } else {
                    "缺少 DOCUMENT_START_SCRIPT 或 WEB_MESSAGE_LISTENER，已禁止启用"
                },
            ) {
                PluginRuntimeEnvironmentSummary(runtimeEnvironment)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = canEnable,
                    onClick = {
                        scope.launch {
                            configurationError = null
                            runCatching { repository.saveConfiguration(service.serviceId, configurationValues) }
                                .onSuccess { onGrant(selected) }
                                .onFailure { configurationError = it.message ?: "保存插件配置失败" }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("同意并启用")
                }
                OutlinedButton(onClick = onBackToServices, modifier = Modifier.fillMaxWidth()) {
                    Text("返回服务管理")
                }
                if (!canEnable) {
                    Text(
                        when {
                            !runtimeEnvironment.secureRuntimeAvailable ->
                                "请更新系统 WebView；插件包已保留，但当前设备不能启用。"
                            !configurationComplete -> "请先填写全部必填配置。"
                            else -> "Required capabilities 未全部同意，插件无法启用。"
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
fun ThirdPartyRuntimeDiagnosticsScreen() {
    val environment = rememberPluginWebViewRuntimeEnvironment()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(14.dp),
    ) {
        item {
            SectionTitle(
                title = "插件运行环境诊断",
                subtitle = "状态从当前 WebView provider 实时读取，不会持久化为永久不兼容",
            )
        }
        item {
            InfoCard(
                title = if (environment.secureRuntimeAvailable) "运行时可用" else "运行时已安全关闭",
                subtitle = "仅 DOCUMENT_START_SCRIPT 或 WEB_MESSAGE_LISTENER 缺失时拒绝运行",
            ) {
                PluginRuntimeEnvironmentSummary(environment)
            }
        }
        item {
            InfoCard(
                title = "二进制数据路径",
                subtitle = "插件生成数据按握手协商传输；网络图片不经过 JavaScript/Base64",
            ) {
                Text(
                    "network.request resource → native resource handle → cache.promote",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Base64URL compatibility mode 使用 48 KiB 原始分片、无填充编码和逐片 ACK。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun rememberPluginWebViewRuntimeEnvironment(): PluginWebViewRuntimeEnvironment {
    val lifecycleOwner = LocalLifecycleOwner.current
    var environment by remember { mutableStateOf(PluginWebViewPolicy.runtimeEnvironment()) }
    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                environment = PluginWebViewPolicy.runtimeEnvironment()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return environment
}

@Composable
private fun PluginRuntimeEnvironmentSummary(
    environment: PluginWebViewRuntimeEnvironment,
) {
    Text(
        "WebView provider: ${environment.providerDisplay}",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Binary transport: ${environment.binaryTransportDisplay}",
        style = MaterialTheme.typography.bodySmall,
    )
    FeatureStatus("DOCUMENT_START_SCRIPT", environment.documentStartScriptSupported)
    FeatureStatus("WEB_MESSAGE_LISTENER", environment.webMessageListenerSupported)
    FeatureStatus("WEB_MESSAGE_ARRAY_BUFFER", environment.webMessageArrayBufferSupported)
}

@Composable
private fun FeatureStatus(name: String, supported: Boolean) {
    Text(
        "$name: ${if (supported) "supported" else "missing"}",
        style = MaterialTheme.typography.bodySmall,
        color = if (supported) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.error
        },
    )
}

@Composable
private fun CapabilityGroupCard(
    title: String,
    subtitle: String,
    capabilities: List<String>,
    selected: Set<String>,
    onSelectedChange: (String, Boolean) -> Unit,
) {
    InfoCard(title = title, subtitle = subtitle) {
        capabilities.forEach { id ->
            val descriptor = ThirdPartyCapabilityRegistry.get(id)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(
                    checked = id in selected,
                    onCheckedChange = { onSelectedChange(id, it) },
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        id,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        buildString {
                            append(descriptor?.stability ?: "unknown")
                            descriptor?.permissionTitle?.let { append(" · ").append(it) }
                            descriptor?.permissionDescription?.let { append("：").append(it) }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ThirdPartyServiceWebViewScreen(
    service: ThirdPartyService,
    apiRegistry: ThirdPartyServiceApiRegistry,
    resourceStore: cn.edu.bjtu.mis.data.thirdparty.ThirdPartyResourceStore,
    kvStore: cn.edu.bjtu.mis.data.thirdparty.ThirdPartyKvStore,
    onCloseService: () -> Unit,
    onBackHandlerChanged: ((() -> Boolean)?) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val darkTheme = isSystemInDarkTheme()
    val composeScope = rememberCoroutineScope()
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var runtimeHostRef by remember {
        mutableStateOf<cn.edu.bjtu.mis.data.thirdparty.PluginRuntimeHost?>(null)
    }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    val bridgeScope = remember(service.serviceId, service.commitSha) { CoroutineScope(SupervisorJob()) }
    val confirmer = remember(service.serviceId, service.commitSha) {
        ThirdPartySensitiveActionConfirmer { title, message ->
            val deferred = CompletableDeferred<Boolean>()
            composeScope.launch {
                pendingConfirmation = PendingConfirmation(title, message, deferred)
            }
            deferred.await()
        }
    }

    DisposableEffect(bridgeScope) {
        onDispose {
            runtimeHostRef?.close()
            runtimeHostRef = null
            bridgeScope.cancel()
        }
    }

    DisposableEffect(service, onBackHandlerChanged) {
        onBackHandlerChanged {
            val view = webViewRef
            if (view != null) {
                composeScope.launch {
                    val handled = runtimeHostRef
                        ?.lifecycleDispatcher()
                        ?.back()
                        ?: false
                    if (!handled) {
                        if (view.canGoBack()) view.goBack() else onCloseService()
                    }
                }
                true
            } else {
                false
            }
        }
        onDispose { onBackHandlerChanged(null) }
    }

    DisposableEffect(webViewRef, lifecycleOwner, darkTheme) {
        val view = webViewRef
        if (view == null) return@DisposableEffect onDispose { }
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                runtimeHostRef?.lifecycleDispatcher()?.resume()
                dispatchContractRuntimeEnvironment(runtimeHostRef, view, context, darkTheme)
            }

            override fun onPause(owner: LifecycleOwner) {
                runtimeHostRef?.lifecycleDispatcher()?.pause()
            }
        }
        val layoutListener = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            dispatchContractRuntimeEnvironment(runtimeHostRef, view, context, darkTheme)
        }
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                dispatchContractNetworkEvent(runtimeHostRef, connectivity)
            }

            override fun onLost(network: Network) {
                dispatchContractNetworkEvent(runtimeHostRef, connectivity)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                dispatchContractNetworkEvent(runtimeHostRef, connectivity)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        view.addOnLayoutChangeListener(layoutListener)
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            dispatchContractRuntimeEnvironment(runtimeHostRef, view, context, darkTheme, insets)
            insets
        }
        runCatching {
            connectivity.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
        }
        dispatchContractRuntimeEnvironment(runtimeHostRef, view, context, darkTheme)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view.removeOnLayoutChangeListener(layoutListener)
            ViewCompat.setOnApplyWindowInsetsListener(view, null)
            runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        }
    }

    pendingConfirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                pending.decision.complete(false)
                pendingConfirmation = null
            },
            title = { Text(pending.title) },
            text = { Text(pending.message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending.decision.complete(true)
                        pendingConfirmation = null
                    },
                ) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pending.decision.complete(false)
                        pendingConfirmation = null
                    },
                ) {
                    Text("取消")
                }
            },
        )
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                webViewRef = this
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                val runtimeHost = cn.edu.bjtu.mis.data.thirdparty.PluginRuntimeHost(
                    service = service,
                    apiRegistry = apiRegistry,
                    resourceStore = resourceStore,
                    kvStore = kvStore,
                    confirmer = confirmer,
                    scope = bridgeScope,
                    openExternal = { url ->
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    onCloseService = onCloseService,
                    onMainPageReady = { view ->
                        dispatchContractRuntimeEnvironment(
                            runtimeHostRef,
                            view,
                            context,
                            darkTheme,
                        )
                    },
                )
                runtimeHostRef = runtimeHost
                runtimeHost.attach(this)
            }
        },
    )
}

private data class PendingConfirmation(
    val title: String,
    val message: String,
    val decision: CompletableDeferred<Boolean>,
)

private class ThirdPartyLegacyRescueWebViewClient(
    private val service: ThirdPartyService,
) : WebViewClient() {
    private val installRoot = File(service.installDir)
    private val rescueOriginSubject = service.rescueOriginSubject()

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !ThirdPartyServiceSandbox.isServiceSandboxUrl(
            request.url.toString(),
            service.serviceId,
            rescueOriginSubject,
        )

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse = when (
        val resolution = ThirdPartyServiceSandbox.resolveLocalResource(
            url = request.url.toString(),
            serviceId = service.serviceId,
            publisherSubjectId = rescueOriginSubject,
            installDir = installRoot,
            entrypoint = service.manifest.entrypoint,
        )
    ) {
        is ThirdPartySandboxResourceResolution.Found -> {
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(resolution.resource.file.extension.lowercase())
                ?: "application/octet-stream"
            WebResourceResponse(
                mime,
                if (mime.startsWith("text/") || mime.contains("javascript")) "UTF-8" else null,
                200,
                "OK",
                LegacyRescueHeaders,
                resolution.resource.file.inputStream(),
            )
        }
        ThirdPartySandboxResourceResolution.NotFound -> rescueError(404, "Not Found")
        ThirdPartySandboxResourceResolution.Blocked,
        ThirdPartySandboxResourceResolution.NotSandboxUrl -> rescueError(403, "Forbidden")
    }

    private fun rescueError(status: Int, reason: String) = WebResourceResponse(
        "text/plain",
        "UTF-8",
        status,
        reason,
        LegacyRescueHeaders,
        ByteArrayInputStream(reason.toByteArray()),
    )

    private companion object {
        val LegacyRescueHeaders = mapOf(
            "Cache-Control" to "no-store",
            "Content-Security-Policy" to
                "default-src 'self' data: blob:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'self'",
            "Permissions-Policy" to "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
            "Referrer-Policy" to "no-referrer",
            "X-Content-Type-Options" to "nosniff",
        )
    }
}

private fun ThirdPartyService.rescueOriginSubject(): String =
    publisherSubjectId.takeIf(String::isNotBlank) ?: commitSha

private fun dispatchContractRuntimeEnvironment(
    host: cn.edu.bjtu.mis.data.thirdparty.PluginRuntimeHost?,
    view: WebView,
    context: Context,
    darkTheme: Boolean,
    insets: WindowInsetsCompat? = ViewCompat.getRootWindowInsets(view),
) {
    val dispatcher = host?.lifecycleDispatcher() ?: return
    val reducedMotion = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)
    val highContrast = runCatching {
        Settings.Secure.getInt(
            context.contentResolver,
            "high_text_contrast_enabled",
            0,
        ) == 1
    }.getOrDefault(false)
    dispatcher.theme(
        colorScheme = if (darkTheme) "dark" else "light",
        reducedMotion = reducedMotion,
        highContrast = highContrast,
    )
    val configuration = context.resources.configuration
    val safeInsets = insets?.getInsets(
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
    )
    val imeInsets = insets?.getInsets(WindowInsetsCompat.Type.ime())
    dispatcher.resize(
        viewportWidthPx = view.width,
        viewportHeightPx = view.height,
        density = context.resources.displayMetrics.density,
        fontScale = configuration.fontScale,
        orientation = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            "landscape"
        } else {
            "portrait"
        },
        safeAreaTopPx = safeInsets?.top ?: 0,
        safeAreaRightPx = safeInsets?.right ?: 0,
        safeAreaBottomPx = safeInsets?.bottom ?: 0,
        safeAreaLeftPx = safeInsets?.left ?: 0,
        imeHeightPx = imeInsets?.bottom ?: 0,
    )
    dispatchContractNetworkEvent(
        host,
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager,
    )
}

private fun dispatchContractNetworkEvent(
    host: cn.edu.bjtu.mis.data.thirdparty.PluginRuntimeHost?,
    connectivity: ConnectivityManager,
) {
    val capabilities = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
    val transport = when {
        capabilities == null -> "offline"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        else -> "other"
    }
    host?.lifecycleDispatcher()?.network(
        online = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
        validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        metered = connectivity.isActiveNetworkMetered,
        transportName = transport,
    )
}

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MiB"
        bytes >= 1024L -> "${bytes / 1024L} KiB"
        else -> "$bytes B"
    }
