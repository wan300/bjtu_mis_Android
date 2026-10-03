package cn.edu.bjtu.mis.data.thirdparty

import cn.edu.bjtu.mis.data.AppJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.LinkedHashMap

class ThirdPartyServiceRepository(
    private val dao: ThirdPartyServiceDao,
    private val installer: ThirdPartyServiceInstaller,
    private val bundledProvider: ThirdPartyBundledServiceProvider? = null,
    private val configurationStore: ThirdPartyConfigurationStore = InMemoryThirdPartyConfigurationStore(),
    private val kvStore: ThirdPartyKvStore? = null,
    private val resourceStore: ThirdPartyResourceStore? = null,
    private val commandReceiptStore: PluginCommandReceiptStore? = null,
    private val migrationRunner: ThirdPartyDataMigrationRunner? = null,
    private val webStorageCleaner: ThirdPartyWebStorageCleaner = NoOpThirdPartyWebStorageCleaner,
) {
    private val bundledInstallMutex = Mutex()
    private val cleanupMutex = Mutex()
    private val readmeCacheMutex = Mutex()
    private val readmeCache = object : LinkedHashMap<ReadmeCacheKey, ReadmeCacheValue>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ReadmeCacheKey, ReadmeCacheValue>?): Boolean =
            size > ReadmeCacheLimit
    }
    private var bundledInstallChecked = false

    suspend fun ensureBundledServicesInstalled() {
        retryPendingCleanup()
        val provider = bundledProvider ?: return
        bundledInstallMutex.withLock {
            if (!bundledInstallChecked) {
                val existingServices = dao.listServices()
                val obsoleteBundledIds = existingServices
                    .filter { service ->
                        service.sourceUrl.startsWith(BundledThirdPartySourceUrlPrefix) &&
                            service.serviceId !in provider.bundledServiceIds
                    }
                    .map { it.serviceId }
                    .toSet()
                obsoleteBundledIds.forEach { serviceId ->
                    deleteService(serviceId)
                }
                val existingById = existingServices
                    .filterNot { it.serviceId in obsoleteBundledIds }
                    .associateBy { it.serviceId }
                val installed = provider.installMissingOrUpdated(existingById)
                installed.forEach { bundled ->
                    val existing = existingById[bundled.packageInfo.manifest.id]
                    val defaultGranted = bundled.defaultGrantedCapabilitiesForInstalledManifest()
                    val required = bundled.packageInfo.manifest.requiredCapabilities.toSet()
                    val defaultEnabled = defaultGranted.containsAll(required)
                    dao.saveService(
                        bundled.packageInfo.toEntity(
                            existing = existing,
                            now = nowIso(),
                            grantedCapabilities = defaultGranted,
                            enabled = defaultEnabled,
                            needsReview = !defaultEnabled,
                        )
                    )
                }
                bundledInstallChecked = true
            }
        }
    }

    suspend fun listServices(): List<ThirdPartyService> {
        ensureBundledServicesInstalled()
        return dao.listServices().map { it.toModel() }
    }

    suspend fun getService(serviceId: String): ThirdPartyService? {
        ensureBundledServicesInstalled()
        return dao.getService(serviceId)?.toModel()
    }

    suspend fun prepareImportFromGitHub(sourceUrl: String): ThirdPartyServiceImportPreview {
        val prepared = installer.prepareFromGitHub(sourceUrl)
        return runCatching {
            val existing = dao.getService(prepared.manifest.id)
            validateReplacement(prepared, existing)
            prepared.toPreview(existing)
        }.getOrElse { error ->
            installer.discardPreparedImport(prepared.token)
            throw error
        }
    }

    suspend fun prepareInstallFromCatalog(plugin: CatalogPlugin): ThirdPartyServiceImportPreview {
        val prepared = installer.prepareFromCatalog(plugin)
        return runCatching {
            val existing = dao.getService(prepared.manifest.id)
            validateReplacement(prepared, existing)
            prepared.toPreview(existing)
        }.getOrElse { error ->
            installer.discardPreparedImport(prepared.token)
            throw error
        }
    }

    suspend fun commitPreparedImport(
        token: String,
        reviewedOrigins: ThirdPartyOriginDeclaration? = null,
    ): ThirdPartyServiceInstallResult {
        val prepared = installer.preparedPackage(token)
            ?: throw ThirdPartyServiceException("插件预检包已失效，请重新导入")
        if (reviewedOrigins != null && reviewedOrigins != prepared.manifest.origins) {
            throw ThirdPartyServiceException("已确认的 Origin 与预检包不一致，请重新审阅")
        }
        val existing = dao.getService(prepared.manifest.id)
        validateReplacement(prepared, existing)
        val previousManifest = existing?.let { runCatching { AppJson.decodeFromString<ThirdPartyServiceManifest>(it.manifestJson) }.getOrNull() }
        val previousService = existing?.toModel()
        val previousConfiguration = configurationStore.load(prepared.manifest.id)
        val namespace = ThirdPartyKvNamespace(
            publisherSubjectId = prepared.publisherSubjectId,
            pluginId = prepared.manifest.id,
        )
        var kvSnapshotCreated = false
        var blobSnapshotCreated = false
        if (existing != null) {
            val requiresMigration = prepared.manifest.dataSchemaVersion > existing.dataSchemaVersion
            val store = kvStore
            if (requiresMigration && store == null) {
                throw ThirdPartyServiceException("当前客户端未配置安全 app.storage，不能执行数据 schema 迁移")
            }
            if (store != null) {
                store.snapshot(namespace)
                kvSnapshotCreated = true
                if (requiresMigration) {
                    val runner = migrationRunner
                        ?: throw ThirdPartyServiceException("当前客户端未配置安全数据迁移 runtime")
                    try {
                        store.beginShadow(namespace)
                        val committed = runner.migrate(prepared, namespace, store)
                        if (!committed) {
                            throw ThirdPartyServiceException("插件数据迁移未在 30 秒内显式提交")
                        }
                        store.commitShadow(namespace)
                    } catch (error: Exception) {
                        runCatching { store.discardShadow(namespace) }
                            .onFailure(error::addSuppressed)
                        runCatching { store.restoreSnapshot(namespace) }
                            .onFailure(error::addSuppressed)
                        throw ThirdPartyServiceException("插件数据迁移失败，已恢复旧 KV", error)
                    }
                }
            }
            resourceStore?.snapshotBlobIndex(namespace)
            blobSnapshotCreated = resourceStore != null
        }
        val installed = try {
            installer.commitPreparedImport(token)
        } catch (error: Exception) {
            if (kvSnapshotCreated) {
                runCatching { kvStore?.restoreSnapshot(namespace) }
                    .onFailure(error::addSuppressed)
            }
            if (blobSnapshotCreated) {
                runCatching { resourceStore?.restoreBlobIndex(namespace) }
                    .onFailure(error::addSuppressed)
            }
            throw error
        }
        val now = nowIso()
        val declaredCapabilities = (
            installed.manifest.requiredCapabilities + installed.manifest.optionalCapabilities
            ).toSet()
        val previousDeclaredCapabilities = previousService?.manifest?.let {
            (it.requiredCapabilities + it.optionalCapabilities).toSet()
        }.orEmpty()
        val preservedCapabilities =
            previousService?.grantedCapabilities.orEmpty() intersect declaredCapabilities
        val addedOriginPolicies = addedThirdPartyOriginPolicies(
            previousService?.manifest?.origins ?: ThirdPartyOriginDeclaration(),
            installed.manifest.origins,
        )
        val requiresSecurityReview = previousService == null || requiresThirdPartyUpdateConfirmation(
            ThirdPartyUpdateReviewInput(
                existingNeedsReview = previousService?.needsReview == true,
                existingRuntimeProfile = existing?.runtimeProfile,
                addedRequiredCapabilities = installed.manifest.requiredCapabilities.toSet() - previousDeclaredCapabilities,
                addedOptionalCapabilities = installed.manifest.optionalCapabilities.toSet() - previousDeclaredCapabilities,
                removedAndroidCapabilities = previousDeclaredCapabilities.filter { it.startsWith("android.") }.toSet() - declaredCapabilities,
                addedOrigins = if (reviewedOrigins == null) addedOriginPolicies.all.toSet() else emptySet(),
            ),
        )
        val entity = installed.toEntity(
            existing = existing,
            now = now,
            grantedCapabilities = preservedCapabilities,
            enabled = previousService?.enabled == true &&
                !requiresSecurityReview &&
                preservedCapabilities.containsAll(installed.manifest.requiredCapabilities),
            needsReview = requiresSecurityReview,
        )
        val mergedConfiguration = mergeThirdPartyConfiguration(
            previousManifest?.configuration.orEmpty(),
            installed.manifest.configuration,
            previousConfiguration,
        )
        try {
            configurationStore.save(installed.manifest.id, mergedConfiguration)
            dao.saveService(entity)
        } catch (error: Exception) {
            runCatching {
                configurationStore.save(installed.manifest.id, previousConfiguration)
            }.onFailure(error::addSuppressed)
            if (kvSnapshotCreated) {
                runCatching { kvStore?.restoreSnapshot(namespace) }
                    .onFailure(error::addSuppressed)
            }
            if (blobSnapshotCreated) {
                runCatching { resourceStore?.restoreBlobIndex(namespace) }
                    .onFailure(error::addSuppressed)
            }
            throw error
        }
        previousService?.let { PluginSessionKeepAlive.revoke(it.publisherSubjectId, it.serviceId) }
        installer.pruneInstalledVersions(
            installed.manifest.id,
            setOfNotNull(installed.commitSha, existing?.commitSha),
        )
        if (previousService != null &&
            (requiresSecurityReview || !entity.enabled || addedOriginPolicies.all.isNotEmpty())) {
            revokeAllAndroidAutomation(
                previousService.publisherSubjectId,
                previousService.serviceId,
            )
        } else if (
            previousService != null &&
            (
                "android.accessibility.events@1" !in entity.toModel().grantedCapabilities ||
                    "android.accessibility.events@1" !in declaredCapabilities
                )
        ) {
            AndroidAccessibilityController.revokeAccessibility(
                previousService.publisherSubjectId,
                previousService.serviceId,
            )
        }
        return ThirdPartyServiceInstallResult(
            service = entity.toModel(),
            updatedExisting = existing != null,
        )
    }

    suspend fun importFromGitHub(sourceUrl: String): ThirdPartyServiceInstallResult {
        val preview = prepareImportFromGitHub(sourceUrl)
        return commitPreparedImport(preview.token)
    }

    fun discardPreparedImport(token: String) {
        installer.discardPreparedImport(token)
    }

    fun cleanupStalePreparedImports() {
        installer.cleanupStalePreparedImports()
    }

    suspend fun grantCapabilities(
        serviceId: String,
        grantedCapabilities: Set<String>,
    ): ThirdPartyService {
        val service = getService(serviceId)
            ?: throw ThirdPartyServiceException("插件不存在：$serviceId")
        return grantCapabilities(service, grantedCapabilities)
    }

    /**
     * Keeps README content in memory only, keyed by the same repository revision that was
     * downloaded during import preflight. Missing README files are also cached for the session.
     */
    suspend fun loadPreparedImportReadme(preview: ThirdPartyServiceImportPreview): String? {
        val key = ReadmeCacheKey(preview.githubOwner, preview.githubRepo, preview.commitSha)
        readmeCacheMutex.withLock {
            readmeCache[key]?.let { cached ->
                return cached.markdown
            }
        }
        val readme = installer.fetchReadme(
            source = GitHubRepositoryRef(
                owner = preview.githubOwner,
                repo = preview.githubRepo,
                canonicalUrl = preview.sourceUrl,
            ),
            commitSha = preview.commitSha,
        )
        readmeCacheMutex.withLock {
            readmeCache[key] = ReadmeCacheValue(readme)
        }
        return readme
    }

    suspend fun loadCatalogPluginReadme(plugin: CatalogPlugin): String? {
        val source = ThirdPartyServiceInstaller.parseGitHubRepositoryUrl(plugin.repositoryUrl)
        val key = ReadmeCacheKey(source.owner, source.repo, plugin.commitSha)
        readmeCacheMutex.withLock { readmeCache[key]?.let { return it.markdown } }
        val readme = installer.fetchReadme(source, plugin.commitSha)
        readmeCacheMutex.withLock { readmeCache[key] = ReadmeCacheValue(readme) }
        return readme
    }

    private suspend fun grantCapabilities(
        service: ThirdPartyService,
        grantedCapabilities: Set<String>,
    ): ThirdPartyService {
        if (service.runtimeProfile != ThirdPartyRuntimeProfile.ContractV1.value) {
            throw ThirdPartyServiceException("旧版插件仅可救援数据，不能授予运行权限")
        }
        val required = service.manifest.requiredCapabilities.toSet()
        val declared = (
            service.manifest.requiredCapabilities + service.manifest.optionalCapabilities
            ).toSet()
        val normalized =
            grantedCapabilities.map(String::trim).filter(String::isNotBlank).toSet()
        if (!normalized.containsAll(required)) {
            throw ThirdPartyServiceException("必须授权的 capabilities 未全部同意")
        }
        val unknown = normalized - declared
        if (unknown.isNotEmpty()) {
            throw ThirdPartyServiceException(
                "授权包含插件未声明的 capabilities：${unknown.joinToString()}",
            )
        }
        normalized.forEach(ThirdPartyCapabilityRegistry::requireKnown)
        val missingConfiguration =
            missingConfigurationKeys(service.manifest, configurationStore.load(service.serviceId))
        if (missingConfiguration.isNotEmpty()) {
            throw ThirdPartyServiceException("请先填写必填插件配置：${missingConfiguration.joinToString()}")
        }
        dao.updateGrantState(
            serviceId = service.serviceId,
            grantedCapabilitiesJson = AppJson.encodeToString(normalized.sorted()),
            enabled = true,
            needsReview = false,
            updatedAt = nowIso(),
        )
        if ("android.session.keepAlive@1" !in normalized) {
            PluginSessionKeepAlive.revoke(service.publisherSubjectId, service.serviceId)
        }
        if ("android.accessibility.events@1" !in normalized) {
            AndroidAccessibilityController.revokeAccessibility(
                service.publisherSubjectId,
                service.serviceId,
            )
        }
        return getService(service.serviceId)
            ?: throw ThirdPartyServiceException("插件不存在：${service.serviceId}")
    }

    suspend fun getConfiguration(serviceId: String): Map<String, String> {
        val service = getService(serviceId) ?: throw ThirdPartyServiceException("第三方服务不存在：$serviceId")
        val stored = configurationStore.load(serviceId)
        return service.manifest.configuration.associate { definition ->
            definition.key to (stored[definition.key] ?: definition.default.orEmpty())
        }
    }

    suspend fun saveConfiguration(serviceId: String, values: Map<String, String>): ThirdPartyService {
        val service = getService(serviceId) ?: throw ThirdPartyServiceException("第三方服务不存在：$serviceId")
        val definitions = service.manifest.configuration.associateBy { it.key }
        val unknown = values.keys - definitions.keys
        if (unknown.isNotEmpty()) throw ThirdPartyServiceException("包含插件未声明的配置键：${unknown.joinToString()}")
        val normalized = values.mapValues { (key, value) -> normalizeConfigurationValue(definitions.getValue(key), value) }
            .filterValues(String::isNotBlank)
        configurationStore.save(serviceId, normalized)
        val complete = missingConfigurationKeys(service.manifest, normalized).isEmpty()
        val requiredCapabilities = service.manifest.requiredCapabilities.toSet()
        val enabled = service.runtimeProfile == ThirdPartyRuntimeProfile.ContractV1.value &&
            !service.needsReview &&
            complete &&
            service.grantedCapabilities.containsAll(requiredCapabilities)
        dao.updateGrantState(
            serviceId = serviceId,
            grantedCapabilitiesJson = AppJson.encodeToString(service.grantedCapabilities.sorted()),
            enabled = enabled,
            needsReview = service.needsReview,
            updatedAt = nowIso(),
        )
        if (!enabled) {
            revokeAllAndroidAutomation(
                service.publisherSubjectId,
                service.serviceId,
            )
        }
        return getService(serviceId) ?: throw ThirdPartyServiceException("第三方服务不存在：$serviceId")
    }

    suspend fun requireReview(serviceId: String) {
        val service = getService(serviceId) ?: throw ThirdPartyServiceException("第三方服务不存在：$serviceId")
        revokeAllAndroidAutomation(
            service.publisherSubjectId,
            service.serviceId,
        )
        dao.updateGrantState(
            serviceId = serviceId,
            grantedCapabilitiesJson = AppJson.encodeToString(service.grantedCapabilities.sorted()),
            enabled = false,
            needsReview = true,
            updatedAt = nowIso(),
        )
        PluginSessionKeepAlive.revoke(service.publisherSubjectId, service.serviceId)
    }

    fun configurationValue(service: ThirdPartyService, key: String): String? {
        val definition = service.manifest.configuration.firstOrNull { it.key == key } ?: return null
        return configurationStore.load(service.serviceId)[key] ?: definition.default
    }

    fun missingConfigurationKeys(service: ThirdPartyService): List<String> =
        missingConfigurationKeys(service.manifest, configurationStore.load(service.serviceId))

    suspend fun deleteService(serviceId: String) {
        val existing = dao.getService(serviceId) ?: return
        revokeAllAndroidAutomation(
            existing.publisherSubjectId,
            existing.serviceId,
        )
        val webStorageSubject = existing.publisherSubjectId
            .takeIf(String::isNotBlank)
            ?: existing.commitSha
        val tombstone = ThirdPartyCleanupTombstoneEntity(
            serviceId = serviceId,
            publisherSubjectId = existing.publisherSubjectId,
            webStorageOrigin = ThirdPartyServiceSandbox.originFor(serviceId, webStorageSubject),
            createdAt = nowIso(),
        )
        dao.deleteServiceAndScheduleCleanup(tombstone)
        PluginSessionKeepAlive.revoke(existing.publisherSubjectId, existing.serviceId)
        cleanupTombstone(tombstone)
    }

    private suspend fun retryPendingCleanup() {
        cleanupMutex.withLock {
            dao.listCleanupTombstones().forEach { cleanupTombstone(it) }
        }
    }

    private suspend fun cleanupTombstone(tombstone: ThirdPartyCleanupTombstoneEntity) {
        var failure: Throwable? = null
        fun recordFailure(error: Throwable) {
            if (failure == null) {
                failure = error
            } else {
                failure?.addSuppressed(error)
            }
        }

        runCatching { configurationStore.remove(tombstone.serviceId) }
            .onFailure(::recordFailure)
        tombstone.publisherSubjectId.takeIf(String::isNotBlank)?.let { publisher ->
            val namespace = ThirdPartyKvNamespace(publisher, tombstone.serviceId)
            runCatching {
                kvStore?.deleteNamespace(namespace)
            }.onFailure(::recordFailure)
            runCatching { resourceStore?.deleteNamespace(namespace) }
                .onFailure(::recordFailure)
            runCatching { commandReceiptStore?.deleteNamespace(namespace) }
                .onFailure(::recordFailure)
        }
        runCatching { webStorageCleaner.deleteOrigin(tombstone.webStorageOrigin) }
            .onFailure(::recordFailure)
        runCatching { installer.cleanupDeletedServiceArtifacts(tombstone.serviceId) }
            .onFailure(::recordFailure)

        if (failure == null) {
            dao.deleteCleanupTombstone(tombstone.serviceId)
        }
    }

    suspend fun rollbackService(serviceId: String): ThirdPartyService {
        val current = dao.getService(serviceId)
            ?: throw ThirdPartyServiceException("第三方服务不存在：$serviceId")
        val snapshot = current.previousVersionJson?.let {
            runCatching { AppJson.decodeFromString<ThirdPartyInstalledVersionSnapshot>(it) }.getOrNull()
        } ?: throw ThirdPartyServiceException("插件没有可回滚的上一版本")
        if (!java.io.File(snapshot.installDir).isDirectory) {
            throw ThirdPartyServiceException("上一版本插件包已丢失，无法回滚")
        }
        if (snapshot.publisherSubjectId != current.publisherSubjectId) {
            throw ThirdPartyServiceException("上一版本 publisher subject 不匹配")
        }
        val namespace = ThirdPartyKvNamespace(current.publisherSubjectId, serviceId)
        val store = kvStore
        var currentKvShadowed = false
        if (store != null) {
            try {
                store.beginShadow(namespace)
                currentKvShadowed = true
                if (!store.restoreSnapshot(namespace)) {
                    throw ThirdPartyServiceException("上一版本 KV 快照不存在，无法原子回滚")
                }
            } catch (error: Exception) {
                if (currentKvShadowed) {
                    runCatching { store.discardShadow(namespace) }
                        .onFailure(error::addSuppressed)
                }
                throw error
            }
        }
        val blobIndexSwapped = if (resourceStore != null) {
            resourceStore.swapBlobIndexWithSnapshot(namespace)
        } else {
            false
        }
        val now = nowIso()
        val restoresContractRuntime =
            snapshot.runtimeProfile == ThirdPartyRuntimeProfile.ContractV1.value
        val restored = ThirdPartyServiceEntity(
            serviceId = serviceId,
            name = snapshot.manifest.name,
            description = snapshot.manifest.description,
            version = snapshot.version,
            author = snapshot.manifest.author,
            sourceUrl = snapshot.sourceUrl,
            githubOwner = snapshot.githubOwner,
            githubRepo = snapshot.githubRepo,
            defaultBranch = snapshot.defaultBranch,
            commitSha = snapshot.commitSha,
            packageDigestSha256 = snapshot.packageDigestSha256,
            manifestJson = AppJson.encodeToString(snapshot.manifest),
            grantedPermissionsJson = AppJson.encodeToString(
                ThirdPartyCapabilityRegistry.permissionsFor(snapshot.grantedCapabilities).sorted(),
            ),
            grantedCapabilitiesJson = AppJson.encodeToString(snapshot.grantedCapabilities.sorted()),
            allowedOriginsJson = AppJson.encodeToString(snapshot.allowedOrigins),
            publisherSubjectId = snapshot.publisherSubjectId,
            dataSchemaVersion = snapshot.dataSchemaVersion,
            runtimeProfile = snapshot.runtimeProfile,
            runtimeFloor = snapshot.runtimeFloor,
            compatibilityState = if (restoresContractRuntime) {
                ThirdPartyCompatibilityState.Compatible.value
            } else {
                ThirdPartyCompatibilityState.LegacyDisabled.value
            },
            verificationLevel = snapshot.verificationLevel,
            marketplaceJson = snapshot.marketplace?.let { AppJson.encodeToString(it) },
            previousVersionJson = null,
            installDir = snapshot.installDir,
            entrypoint = snapshot.manifest.entrypoint,
            icon = snapshot.manifest.icon,
            enabled = restoresContractRuntime && snapshot.enabled,
            needsReview = !restoresContractRuntime || snapshot.needsReview,
            installedAt = current.installedAt,
            updatedAt = now,
        )
        try {
            dao.saveService(restored)
        } catch (error: Exception) {
            if (currentKvShadowed && store != null) {
                runCatching { store.commitShadow(namespace) }
                    .onFailure(error::addSuppressed)
            }
            if (blobIndexSwapped) {
                runCatching { resourceStore?.swapBlobIndexWithSnapshot(namespace) }
                    .onFailure(error::addSuppressed)
            }
            throw ThirdPartyServiceException("无法原子回滚插件版本与数据", error)
        }
        if (currentKvShadowed && store != null) {
            store.discardShadow(namespace)
        }
        PluginSessionKeepAlive.revoke(current.publisherSubjectId, current.serviceId)
        val restoredService = restored.toModel()
        val restoredDeclared = restoredService.manifest.requiredCapabilities +
            restoredService.manifest.optionalCapabilities
        if (!restoredService.canRun) {
            revokeAllAndroidAutomation(
                restoredService.publisherSubjectId,
                restoredService.serviceId,
            )
        } else if (
            "android.accessibility.events@1" !in restoredService.grantedCapabilities ||
                "android.accessibility.events@1" !in restoredDeclared
        ) {
            AndroidAccessibilityController.revokeAccessibility(
                restoredService.publisherSubjectId,
                restoredService.serviceId,
            )
        }
        installer.pruneInstalledVersions(serviceId, setOf(snapshot.commitSha, current.commitSha))
        return restoredService
    }

    private fun PreparedThirdPartyServicePackage.toPreview(
        existing: ThirdPartyServiceEntity?,
    ): ThirdPartyServiceImportPreview {
        val previous = existing?.toModel()
        val previousRequired = previous?.manifest?.requiredCapabilities.orEmpty().toSet()
        val previousOptional = previous?.manifest?.optionalCapabilities.orEmpty().toSet()
        val previousOrigins = previous?.manifest?.origins ?: ThirdPartyOriginDeclaration()
        return ThirdPartyServiceImportPreview(
            token = token,
            manifest = manifest,
            sourceUrl = source.canonicalUrl,
            githubOwner = source.owner,
            githubRepo = source.repo,
            defaultBranch = defaultBranch,
            commitSha = commitSha,
            packageDigestSha256 = packageDigestSha256,
            packageBytes = packageBytes,
            packageFileCount = packageFileCount,
            updatedExisting = existing != null,
            archiveSha256 = archiveSha256,
            platformVerified = platformVerified,
            iconSource = resolveLocalThirdPartyIconSource(stagingDir, manifest.icon),
            publisherSubjectId = publisherSubjectId,
            verificationLevel = verificationLevel,
            previousService = previous,
            addedRequiredCapabilities =
                (manifest.requiredCapabilities.toSet() - previousRequired).sorted(),
            addedOptionalCapabilities =
                (manifest.optionalCapabilities.toSet() - previousOptional).sorted(),
            removedCapabilities = (
                (previousRequired + previousOptional) -
                    (manifest.requiredCapabilities + manifest.optionalCapabilities).toSet()
                ).sorted(),
            addedOrigins = addedThirdPartyOriginPolicies(previousOrigins, manifest.origins).all.sorted(),
            removedOrigins = addedThirdPartyOriginPolicies(manifest.origins, previousOrigins).all.sorted(),
        )
    }

    private fun validateReplacement(
        prepared: PreparedThirdPartyServicePackage,
        existing: ThirdPartyServiceEntity?,
    ) {
        if (prepared.manifest.id in bundledProvider?.bundledServiceIds.orEmpty()) {
            throw ThirdPartyServiceException("内置插件不能被大厅或开发者导入覆盖")
        }
        if (existing == null) return
        if (
            existing.runtimeProfile !in setOf(
                ThirdPartyRuntimeProfile.ContractV1.value,
                ThirdPartyRuntimeProfile.LegacyV3P0a.value,
            )
        ) {
            throw ThirdPartyServiceException(
                "legacy_v1_v2 插件不能原位更新；请使用无桥、无网络救援入口处理旧数据",
            )
        }
        if (existing.publisherSubjectId != prepared.publisherSubjectId) {
            throw ThirdPartyServiceException("检测到 publisher subject 变化，拒绝原位更新")
        }
        if (prepared.manifest.dataSchemaVersion < existing.dataSchemaVersion) {
            throw ThirdPartyServiceException("data_schema_version 不得降低")
        }
        if (
            prepared.manifest.dataSchemaVersion > existing.dataSchemaVersion &&
            prepared.manifest.migrationEntrypoint.isNullOrBlank()
        ) {
            throw ThirdPartyServiceException("提升 data_schema_version 必须提供 migration_entrypoint")
        }
    }

    private fun InstalledThirdPartyServicePackage.toEntity(
        existing: ThirdPartyServiceEntity?,
        now: String,
        grantedCapabilities: Set<String> = emptySet(),
        enabled: Boolean = false,
        needsReview: Boolean = true,
    ): ThirdPartyServiceEntity =
        ThirdPartyServiceEntity(
            serviceId = manifest.id,
            name = manifest.name,
            description = manifest.description,
            version = manifest.version,
            author = manifest.author,
            sourceUrl = source.canonicalUrl,
            githubOwner = source.owner,
            githubRepo = source.repo,
            defaultBranch = defaultBranch,
            commitSha = commitSha,
            packageDigestSha256 = packageDigestSha256,
            manifestJson = AppJson.encodeToString(manifest),
            grantedPermissionsJson = AppJson.encodeToString(
                ThirdPartyCapabilityRegistry.permissionsFor(grantedCapabilities).sorted(),
            ),
            grantedCapabilitiesJson = AppJson.encodeToString(grantedCapabilities.sorted()),
            allowedOriginsJson = AppJson.encodeToString(manifest.remoteOrigins),
            publisherSubjectId = publisherSubjectId,
            dataSchemaVersion = manifest.dataSchemaVersion,
            runtimeProfile = ThirdPartyRuntimeProfile.ContractV1.value,
            runtimeFloor = ThirdPartyCapabilityRegistry.runtimeFloor(
                manifest.requiredCapabilities,
            ),
            compatibilityState = ThirdPartyCompatibilityState.Compatible.value,
            verificationLevel = verificationLevel,
            marketplaceJson = manifest.marketplace?.let { AppJson.encodeToString(it) },
            previousVersionJson = existing?.toModel()?.toVersionSnapshot()?.let {
                AppJson.encodeToString(it)
            },
            installDir = installDir.absolutePath,
            entrypoint = manifest.entrypoint,
            icon = manifest.icon,
            enabled = enabled && missingConfigurationKeys(manifest, configurationStore.load(manifest.id)).isEmpty(),
            needsReview = needsReview,
            installedAt = existing?.installedAt ?: now,
            updatedAt = now,
        )

    private fun ThirdPartyServiceEntity.toModel(): ThirdPartyService {
        val decodedManifest = runCatching {
            AppJson.decodeFromString<ThirdPartyServiceManifest>(manifestJson)
        }.getOrElse {
            ThirdPartyServiceManifest(
                id = serviceId,
                name = name,
                version = version,
                entrypoint = entrypoint,
                icon = icon,
            )
        }
        val marketplace = marketplaceJson?.let { json ->
            runCatching {
                AppJson.decodeFromString<ThirdPartyMarketplaceMetadata>(json)
            }.getOrNull()
        }
        val manifest = decodedManifest.copy(
            name = decodedManifest.name.ifBlank { name },
            description = marketplace?.description ?: description,
            author = marketplace?.author ?: author,
            marketplace = marketplace,
        )
        val granted = runCatching {
            AppJson.decodeFromString<List<String>>(grantedCapabilitiesJson).toSet()
        }.getOrDefault(emptySet())
        val origins = runCatching {
            AppJson.decodeFromString<List<String>>(allowedOriginsJson)
        }.getOrDefault(manifest.remoteOrigins)
        val previousVersion = previousVersionJson?.let { json ->
            runCatching { AppJson.decodeFromString<ThirdPartyInstalledVersionSnapshot>(json) }.getOrNull()
        }
        return ThirdPartyService(
            serviceId = serviceId,
            manifest = manifest,
            sourceUrl = sourceUrl,
            githubOwner = githubOwner,
            githubRepo = githubRepo,
            defaultBranch = defaultBranch,
            commitSha = commitSha,
            packageDigestSha256 = packageDigestSha256,
            installDir = installDir,
            grantedCapabilities = granted,
            allowedOrigins = origins,
            enabled = enabled,
            needsReview = needsReview,
            installedAt = installedAt,
            updatedAt = updatedAt,
            publisherSubjectId = publisherSubjectId,
            dataSchemaVersion = dataSchemaVersion,
            runtimeProfile = runtimeProfile,
            runtimeFloor = runtimeFloor,
            compatibilityState = compatibilityState,
            verificationLevel = verificationLevel,
            previousVersion = previousVersion,
        )
    }

    private fun ThirdPartyService.toVersionSnapshot(): ThirdPartyInstalledVersionSnapshot =
        ThirdPartyInstalledVersionSnapshot(
            version = manifest.version,
            commitSha = commitSha,
            packageDigestSha256 = packageDigestSha256,
            installDir = installDir,
            manifest = manifest,
            marketplace = manifest.marketplace,
            dataSchemaVersion = dataSchemaVersion,
            sourceUrl = sourceUrl,
            githubOwner = githubOwner,
            githubRepo = githubRepo,
            defaultBranch = defaultBranch,
            publisherSubjectId = publisherSubjectId,
            grantedCapabilities = grantedCapabilities,
            allowedOrigins = allowedOrigins,
            enabled = enabled,
            needsReview = needsReview,
            runtimeProfile = runtimeProfile,
            runtimeFloor = runtimeFloor,
            verificationLevel = verificationLevel,
        )

    private fun revokeAllAndroidAutomation(publisherSubjectId: String, serviceId: String) {
        PluginSessionKeepAlive.revoke(publisherSubjectId, serviceId)
        AndroidAccessibilityController.revokeService(publisherSubjectId, serviceId)
        AndroidNativeRuntimeController.revokeService(publisherSubjectId, serviceId)
    }
}

private data class ReadmeCacheKey(
    val owner: String,
    val repo: String,
    val commitSha: String,
)

private data class ReadmeCacheValue(val markdown: String?)

private const val ReadmeCacheLimit = 10

private fun missingConfigurationKeys(
    manifest: ThirdPartyServiceManifest,
    storedValues: Map<String, String>,
): List<String> = manifest.configuration.filter { definition ->
    definition.required && (storedValues[definition.key] ?: definition.default).isNullOrBlank()
}.map { it.key }

private fun normalizeConfigurationValue(
    definition: ThirdPartyConfigurationDefinition,
    rawValue: String,
): String {
    val value = rawValue.trim()
    if (value.isBlank()) return ""
    return when (definition.type) {
        "boolean" -> value.lowercase().takeIf { it == "true" || it == "false" }
            ?: throw ThirdPartyServiceException("${definition.label} 必须是 true 或 false")
        "number" -> value.takeIf { it.toDoubleOrNull()?.isFinite() == true }
            ?: throw ThirdPartyServiceException("${definition.label} 必须是数字")
        "url" -> runCatching { java.net.URI(value) }.getOrNull()
            ?.takeIf { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() }
            ?.toString()
            ?: throw ThirdPartyServiceException("${definition.label} 必须是有效的 HTTP/HTTPS URL")
        "select" -> value.takeIf { it in definition.options }
            ?: throw ThirdPartyServiceException("${definition.label} 不在允许选项中")
        else -> value
    }
}

private const val BundledThirdPartySourceUrlPrefix = "asset://third-party-services/"

private fun nowIso(): String = OffsetDateTime.now(ZoneOffset.UTC).toString()

private fun InstalledBundledThirdPartyService.defaultGrantedCapabilitiesForInstalledManifest(): Set<String> {
    val declared = (
        packageInfo.manifest.requiredCapabilities +
            packageInfo.manifest.optionalCapabilities
        ).toSet()
    val requested = defaultGrantedPermissions
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()
    return declared.filterTo(linkedSetOf()) { capability ->
        val permission = ThirdPartyCapabilityRegistry.requireKnown(capability).permission
        permission == null || permission in requested
    }
}
