package cn.edu.bjtu.mis.data.thirdparty

import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.network.AppCookieJar
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ThirdPartyServiceRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun previewDoesNotWriteRoomAndCommitPersistsService() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeThirdPartyServiceDao()
            val repository = repository(server, dao)
            enqueueGithubPackage(server)

            val preview = repository.prepareImportFromGitHub("https://github.com/alice/demo")

            assertEquals("bjtu.demo", preview.manifest.id)
            assertNull(dao.saved["bjtu.demo"])

            val result = repository.commitPreparedImport(preview.token)

            assertEquals("bjtu.demo", result.service.serviceId)
            assertTrue(result.service.needsReview)
            assertFalse(result.service.enabled)
            assertTrue(result.service.grantedPermissions.isEmpty())
            assertTrue(result.service.packageDigestSha256.matches(Regex("^[a-f0-9]{64}$")))
            assertEquals(result.service.serviceId, dao.saved["bjtu.demo"]?.serviceId)
        }
    }

    @Test
    fun updateRetainsStillDeclaredAuthorizationAndRevokesRemovedPermissions() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeThirdPartyServiceDao()
            dao.saved["bjtu.demo"] = existingAuthorizedEntity()
            val repository = repository(server, dao)
            enqueueGithubPackage(server)

            val preview = repository.prepareImportFromGitHub("https://github.com/alice/demo")
            val result = repository.commitPreparedImport(preview.token)

            assertTrue(result.updatedExisting)
            assertFalse(result.service.needsReview)
            assertTrue(result.service.enabled)
            assertEquals(setOf("identity.profile.read"), result.service.grantedPermissions)
            assertEquals("2026-06-01T00:00:00Z", result.service.installedAt)
        }
    }

    @Test
    fun listServicesReturnsEmptyWhenNoImportedOrBundledServices() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeThirdPartyServiceDao()
            val repository = repository(server, dao)

            val services = repository.listServices()

            assertTrue(services.isEmpty())
        }
    }

    @Test
    fun sameDomainWithNewOriginPurposeRequiresReviewInPreviewAndCommit() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeThirdPartyServiceDao()
            dao.saved["bjtu.demo"] = existingAuthorizedEntity().copy(
                manifestJson = validManifest(
                    origins = ThirdPartyOriginDeclaration(media = listOf("https://api.example.com")),
                ),
            )
            val repository = repository(server, dao)
            enqueueGithubPackage(server)

            val preview = repository.prepareImportFromGitHub("https://github.com/alice/demo")
            assertTrue("A new connect use of an existing media domain needs review", preview.requiresUserConfirmation)

            val result = repository.commitPreparedImport(preview.token)
            assertTrue(result.service.needsReview)
            assertFalse(result.service.enabled)
        }
    }

    @Test
    fun removingOriginPurposeDoesNotRequireConfirmation() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeThirdPartyServiceDao()
            dao.saved["bjtu.demo"] = existingAuthorizedEntity()
            val repository = repository(server, dao)
            enqueueGithubPackage(server, manifest = validManifest(origins = ThirdPartyOriginDeclaration()))

            val preview = repository.prepareImportFromGitHub("https://github.com/alice/demo")
            assertFalse(preview.requiresUserConfirmation)
            val result = repository.commitPreparedImport(preview.token)
            assertFalse(result.service.needsReview)
            assertTrue(result.service.enabled)
        }
    }

    @Test
    fun reviewedOriginOnlyUpdateRemainsEnabledWithoutAnotherGrant() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeThirdPartyServiceDao()
            val existing = existingAuthorizedEntity().copy(
                manifestJson = validManifest(origins = ThirdPartyOriginDeclaration(
                    media = listOf("https://api.example.com"),
                )),
            )
            dao.saved["bjtu.demo"] = existing
            val repository = repository(server, dao)
            enqueueGithubPackage(server)

            val preview = repository.prepareImportFromGitHub("https://github.com/alice/demo")
            assertTrue(preview.requiresUserConfirmation)
            val updated = repository.commitPreparedImport(
                preview.token,
                reviewedOrigins = preview.manifest.origins,
            ).service

            assertTrue(updated.enabled)
            assertFalse(updated.needsReview)
            assertEquals(setOf("runtime.lifecycle@1", "identity.profile@1"), updated.grantedCapabilities)
        }
    }

    @Test
    fun originConfirmationMustMatchThePreparedPackage() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeThirdPartyServiceDao()
            val existing = existingAuthorizedEntity()
            dao.saved["bjtu.demo"] = existing
            val repository = repository(server, dao)
            enqueueGithubPackage(server)
            val preview = repository.prepareImportFromGitHub("https://github.com/alice/demo")

            val result = runCatching {
                repository.commitPreparedImport(preview.token, reviewedOrigins = ThirdPartyOriginDeclaration())
            }

            assertTrue(result.exceptionOrNull() is ThirdPartyServiceException)
            assertEquals(existing, dao.saved["bjtu.demo"])
        }
    }

    @Test
    fun confirmingOriginsDoesNotAuthorizeNewCapabilities() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeThirdPartyServiceDao()
            dao.saved["bjtu.demo"] = existingAuthorizedEntity()
            val repository = repository(server, dao)
            enqueueGithubPackage(server, manifest = validManifest(
                extraOptionalCapabilities = listOf("android.device.info@1"),
            ))
            val preview = repository.prepareImportFromGitHub("https://github.com/alice/demo")

            val updated = repository.commitPreparedImport(
                preview.token,
                reviewedOrigins = preview.manifest.origins,
            ).service

            assertTrue(updated.needsReview)
            assertFalse(updated.enabled)
            assertFalse("android.device.info@1" in updated.grantedCapabilities)
        }
    }

    @Test
    fun originConfirmationDoesNotBypassAnExistingReview() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeThirdPartyServiceDao()
            dao.saved["bjtu.demo"] = existingAuthorizedEntity().copy(needsReview = true, enabled = false)
            val repository = repository(server, dao)
            enqueueGithubPackage(server)
            val preview = repository.prepareImportFromGitHub("https://github.com/alice/demo")

            val updated = repository.commitPreparedImport(
                preview.token,
                reviewedOrigins = preview.manifest.origins,
            ).service

            assertTrue(updated.needsReview)
            assertFalse(updated.enabled)
        }
    }

    @Test
    fun originConfirmationDoesNotAuthorizeAFirstInstall() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeThirdPartyServiceDao()
            val repository = repository(server, dao)
            enqueueGithubPackage(server)
            val preview = repository.prepareImportFromGitHub("https://github.com/alice/demo")

            val installed = repository.commitPreparedImport(
                preview.token,
                reviewedOrigins = preview.manifest.origins,
            ).service

            assertTrue(installed.needsReview)
            assertFalse(installed.enabled)
            assertTrue(installed.grantedCapabilities.isEmpty())
        }
    }

    @Test
    fun listServicesRemovesObsoleteBundledServicesThatAreNoLongerShipped() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeThirdPartyServiceDao()
            val servicesRoot = temp.newFolder("services-${System.nanoTime()}")
            val legacyInstallDir = File(servicesRoot, "installed/com.jijiang.campus-service/legacy").apply {
                mkdirs()
                resolve("index.html").writeText("<html></html>")
            }
            dao.saved["com.jijiang.campus-service"] = ThirdPartyServiceEntity(
                serviceId = "com.jijiang.campus-service",
                name = "Legacy bundled service",
                description = "Legacy bundled service",
                version = "1.0.0",
                author = "bundled",
                sourceUrl = "asset://third-party-services/com.jijiang.campus-service",
                githubOwner = "bundled",
                githubRepo = "legacy-bundled",
                defaultBranch = "bundled",
                commitSha = "legacy",
                packageDigestSha256 = "b".repeat(64),
                manifestJson = validManifest(),
                grantedPermissionsJson = AppJson.encodeToString(emptyList<String>()),
                allowedOriginsJson = AppJson.encodeToString(emptyList<String>()),
                installDir = legacyInstallDir.absolutePath,
                entrypoint = "index.html",
                icon = "icon.svg",
                enabled = false,
                needsReview = true,
                installedAt = "2026-06-01T00:00:00Z",
                updatedAt = "2026-06-01T00:00:00Z",
            )
            val repository = repository(
                server = server,
                dao = dao,
                servicesRoot = servicesRoot,
                bundledProvider = EmptyBundledProvider,
            )

            val services = repository.listServices()

            assertTrue(services.isEmpty())
            assertNull(dao.saved["com.jijiang.campus-service"])
            assertFalse(File(servicesRoot, "installed/com.jijiang.campus-service").exists())
        }
    }

    @Test
    fun listServicesInstallsBundledServiceFromProviderPendingUserGrant() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeThirdPartyServiceDao()
            val installDir = temp.newFolder("bundled-demo-install").apply {
                resolve("index.html").writeText("<html></html>")
            }
            val bundledProvider = FakeBundledProvider(installDir)
            val repository = ThirdPartyServiceRepository(
                dao = dao,
                installer = ThirdPartyServiceInstaller(
                    client = BjtuHttpClient(AppCookieJar()),
                    servicesRoot = temp.newFolder("services-${System.nanoTime()}"),
                    apiBaseUrl = server.url("/").toString().trimEnd('/'),
                ),
                bundledProvider = bundledProvider,
            )

            val services = repository.listServices()

            assertEquals(1, services.size)
            val service = services.single()
            assertEquals("bjtu.bundled.demo", service.serviceId)
            assertEquals("asset://third-party-services/bjtu.bundled.demo", service.sourceUrl)
            assertTrue(service.grantedPermissions.isEmpty())
            assertFalse(service.enabled)
            assertTrue(service.needsReview)
            assertEquals(1, bundledProvider.calls)

            repository.listServices()

            assertEquals(1, bundledProvider.calls)
        }
    }

    @Test
    fun deleteRemovesDatabaseConfigurationAndInstalledDirectory() = runBlocking {
        MockWebServer().use { server ->
            val servicesRoot = temp.newFolder("services-${System.nanoTime()}")
            val installedDir = File(servicesRoot, "installed/bjtu.demo/oldcommit").apply {
                mkdirs()
                resolve("index.html").writeText("<html></html>")
            }
            val dao = FakeThirdPartyServiceDao().apply {
                saved["bjtu.demo"] = existingAuthorizedEntity().copy(installDir = installedDir.absolutePath)
            }
            val configurationStore = InMemoryThirdPartyConfigurationStore().apply {
                save("bjtu.demo", mapOf("TOKEN" to "secret"))
            }
            val repository = repository(server, dao, servicesRoot, configurationStore = configurationStore)

            repository.deleteService("bjtu.demo")

            assertNull(dao.saved["bjtu.demo"])
            assertTrue(configurationStore.load("bjtu.demo").isEmpty())
            assertFalse(File(servicesRoot, "installed/bjtu.demo").exists())
        }
    }

    @Test
    fun deleteRestoresConfigurationAndDirectoryWhenDatabaseDeleteFails() = runBlocking {
        MockWebServer().use { server ->
            val servicesRoot = temp.newFolder("services-${System.nanoTime()}")
            val installedDir = File(servicesRoot, "installed/bjtu.demo/oldcommit").apply {
                mkdirs()
                resolve("index.html").writeText("<html></html>")
            }
            val dao = FakeThirdPartyServiceDao().apply {
                saved["bjtu.demo"] = existingAuthorizedEntity().copy(installDir = installedDir.absolutePath)
                failDelete = true
            }
            val configurationStore = InMemoryThirdPartyConfigurationStore().apply {
                save("bjtu.demo", mapOf("TOKEN" to "secret"))
            }
            val repository = repository(server, dao, servicesRoot, configurationStore = configurationStore)

            val result = runCatching { repository.deleteService("bjtu.demo") }

            assertTrue(result.isFailure)
            assertTrue(dao.saved.containsKey("bjtu.demo"))
            assertEquals(mapOf("TOKEN" to "secret"), configurationStore.load("bjtu.demo"))
            assertTrue(File(servicesRoot, "installed/bjtu.demo/oldcommit/index.html").isFile)
            assertTrue(dao.cleanupTombstones.isEmpty())
        }
    }

    @Test
    fun preparedReadmeIsCachedByPinnedRepositoryRevision() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeThirdPartyServiceDao()
            val repository = repository(server, dao)
            enqueueGithubPackage(server)
            val preview = repository.prepareImportFromGitHub("https://github.com/alice/demo")
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/markdown")
                    .setBody("# Demo README"),
            )

            assertEquals("# Demo README", repository.loadPreparedImportReadme(preview))
            assertEquals("# Demo README", repository.loadPreparedImportReadme(preview))
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun failedPhysicalCleanupLeavesTombstoneAndRetriesOnNextStartup() = runBlocking {
        MockWebServer().use { server ->
            val servicesRoot = temp.newFolder("services-${System.nanoTime()}")
            val installedDir = File(servicesRoot, "installed/bjtu.demo/oldcommit").apply {
                mkdirs()
                resolve("index.html").writeText("<html></html>")
            }
            val dao = FakeThirdPartyServiceDao().apply {
                saved["bjtu.demo"] = existingAuthorizedEntity().copy(installDir = installedDir.absolutePath)
            }
            val cleaner = FakeThirdPartyWebStorageCleaner(fail = true)
            val repository = repository(
                server = server,
                dao = dao,
                servicesRoot = servicesRoot,
                webStorageCleaner = cleaner,
            )

            repository.deleteService("bjtu.demo")

            assertNull(dao.saved["bjtu.demo"])
            assertTrue(dao.cleanupTombstones.containsKey("bjtu.demo"))
            assertEquals(
                ThirdPartyServiceSandbox.originFor("bjtu.demo", "github-owner:12345"),
                cleaner.origins.single(),
            )

            cleaner.fail = false
            repository.listServices()

            assertTrue(dao.cleanupTombstones.isEmpty())
            assertEquals(2, cleaner.origins.size)
        }
    }

    @Test
    fun dataSchemaMigrationUsesShadowKvAndRollbackRestoresPreviousPackageAndData() = runBlocking {
        MockWebServer().use { server ->
            val servicesRoot = temp.newFolder("services-${System.nanoTime()}")
            val dao = FakeThirdPartyServiceDao().apply {
                saved["bjtu.demo"] = existingAuthorizedEntity()
            }
            val kvStore = FileThirdPartyKvStore(
                temp.newFolder("repository-migration-kv"),
                PassthroughKvCipher,
            )
            val namespace = ThirdPartyKvNamespace("github-owner:12345", "bjtu.demo")
            kvStore.set(namespace, "schema", JsonPrimitive(1))
            val migrationRunner = ThirdPartyDataMigrationRunner { _, migrationNamespace, store ->
                store.set(
                    migrationNamespace,
                    "schema",
                    JsonPrimitive(2),
                    ThirdPartyKvSpace.Shadow,
                )
                true
            }
            val repository = repository(
                server = server,
                dao = dao,
                servicesRoot = servicesRoot,
                kvStore = kvStore,
                migrationRunner = migrationRunner,
            )
            enqueueGithubPackage(
                server,
                manifest = validManifest(
                    version = "2.0.0",
                    dataSchemaVersion = 2,
                    migrationEntrypoint = "migration.html",
                ),
                includeMigration = true,
            )

            val preview = repository.prepareImportFromGitHub("https://github.com/alice/demo")
            val updated = repository.commitPreparedImport(preview.token).service

            assertEquals(2, updated.dataSchemaVersion)
            assertEquals("2", (kvStore.get(namespace, "schema") as JsonPrimitive).content)
            assertEquals("1.0.0", updated.previousVersion?.version)

            dao.failNextSave = true
            val failedRollback = runCatching { repository.rollbackService("bjtu.demo") }
            assertTrue(failedRollback.isFailure)
            assertEquals("2.0.0", dao.saved.getValue("bjtu.demo").version)
            assertEquals("2", (kvStore.get(namespace, "schema") as JsonPrimitive).content)

            val rolledBack = repository.rollbackService("bjtu.demo")

            assertEquals("1.0.0", rolledBack.manifest.version)
            assertEquals(1, rolledBack.dataSchemaVersion)
            assertEquals("1", (kvStore.get(namespace, "schema") as JsonPrimitive).content)
            assertEquals(setOf("identity.profile.read"), rolledBack.grantedPermissions)
        }
    }

    @Test
    fun updateRejectsDataSchemaDowngradeAndPublisherSubjectChange() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeThirdPartyServiceDao().apply {
                saved["bjtu.demo"] = existingAuthorizedEntity().copy(dataSchemaVersion = 2)
            }
            val repository = repository(server, dao)
            enqueueGithubPackage(server)

            val downgrade = runCatching {
                repository.prepareImportFromGitHub("https://github.com/alice/demo")
            }

            assertTrue(downgrade.isFailure)
        }

        MockWebServer().use { server ->
            val dao = FakeThirdPartyServiceDao().apply {
                saved["bjtu.demo"] = existingAuthorizedEntity()
            }
            val repository = repository(server, dao)
            enqueueGithubPackage(server, ownerId = 67890)

            val publisherChange = runCatching {
                repository.prepareImportFromGitHub("https://github.com/alice/demo")
            }

            assertTrue(publisherChange.isFailure)
        }
    }

    @Test
    fun legacyP0aUpgradePreservesStableOriginConfigurationKvAndBlobThenRollsBackToRescue() =
        runBlocking {
            MockWebServer().use { server ->
                val configuration = listOf(
                    ThirdPartyConfigurationDefinition(
                        key = "TOKEN",
                        label = "Token",
                        type = "secret",
                    ),
                )
                val oldManifest = validManifest(
                    version = "1.0.0",
                    configuration = configuration,
                    extraOptionalCapabilities = listOf("storage.blob@1"),
                )
                val oldEntity = existingAuthorizedEntity().copy(
                    manifestJson = oldManifest,
                    runtimeProfile = ThirdPartyRuntimeProfile.LegacyV3P0a.value,
                    compatibilityState = ThirdPartyCompatibilityState.LegacyDisabled.value,
                    enabled = true,
                    needsReview = false,
                )
                val dao = FakeThirdPartyServiceDao().apply {
                    saved["bjtu.demo"] = oldEntity
                }
                val configurationStore = InMemoryThirdPartyConfigurationStore().apply {
                    save("bjtu.demo", mapOf("TOKEN" to "preserved-secret"))
                }
                val kvStore = FileThirdPartyKvStore(
                    temp.newFolder("legacy-p0a-kv"),
                    PassthroughKvCipher,
                )
                val resourceStore = FileThirdPartyResourceStore(
                    temp.newFolder("legacy-p0a-resources"),
                    PassthroughKvCipher,
                    limits = ThirdPartyResourceLimits(safetyBytes = 0),
                )
                val namespace = ThirdPartyKvNamespace("github-owner:12345", "bjtu.demo")
                kvStore.set(namespace, "preserved", JsonPrimitive("yes"))
                val blob = resourceStore.putBlob(
                    namespace,
                    ByteArrayInputStream("preserved-blob".toByteArray()),
                    "text/plain",
                )
                val repository = repository(
                    server = server,
                    dao = dao,
                    configurationStore = configurationStore,
                    kvStore = kvStore,
                    resourceStore = resourceStore,
                )
                val originBefore = ThirdPartyServiceSandbox.originFor(
                    oldEntity.serviceId,
                    oldEntity.publisherSubjectId,
                )

                val rescueOnly = repository.getService("bjtu.demo")!!
                assertFalse(rescueOnly.canRun)

                enqueueGithubPackage(
                    server,
                    manifest = validManifest(
                        version = "2.0.0",
                        configuration = configuration,
                        extraOptionalCapabilities = listOf("storage.blob@1"),
                    ),
                )
                val preview = repository.prepareImportFromGitHub("https://github.com/alice/demo")
                val upgraded = repository.commitPreparedImport(preview.token).service

                assertEquals(ThirdPartyRuntimeProfile.ContractV1.value, upgraded.runtimeProfile)
                assertFalse(upgraded.enabled)
                assertTrue(upgraded.needsReview)
                assertEquals(originBefore, ThirdPartyServiceSandbox.originFor(
                    upgraded.serviceId,
                    upgraded.publisherSubjectId,
                ))
                assertEquals(
                    mapOf("TOKEN" to "preserved-secret"),
                    configurationStore.load("bjtu.demo"),
                )
                assertEquals(
                    "yes",
                    (kvStore.get(namespace, "preserved") as JsonPrimitive).content,
                )
                assertEquals(blob.handle, resourceStore.describe(namespace, blob.handle)?.handle)

                val rolledBack = repository.rollbackService("bjtu.demo")

                assertEquals(ThirdPartyRuntimeProfile.LegacyV3P0a.value, rolledBack.runtimeProfile)
                assertFalse(rolledBack.canRun)
                assertFalse(rolledBack.enabled)
                assertTrue(rolledBack.needsReview)
                assertEquals(
                    "yes",
                    (kvStore.get(namespace, "preserved") as JsonPrimitive).content,
                )
                assertEquals(blob.handle, resourceStore.describe(namespace, blob.handle)?.handle)
            }
        }

    @Test
    fun legacyV1V2CannotUpgradeInPlace() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeThirdPartyServiceDao().apply {
                saved["bjtu.demo"] = existingAuthorizedEntity().copy(
                    runtimeProfile = ThirdPartyRuntimeProfile.LegacyV1V2.value,
                    compatibilityState = ThirdPartyCompatibilityState.LegacyDisabled.value,
                    enabled = false,
                )
            }
            val repository = repository(server, dao)
            enqueueGithubPackage(server)

            val result = runCatching {
                repository.prepareImportFromGitHub("https://github.com/alice/demo")
            }

            assertTrue(result.isFailure)
        }
    }

    private fun repository(
        server: MockWebServer,
        dao: FakeThirdPartyServiceDao,
        servicesRoot: File = temp.newFolder("services-${System.nanoTime()}"),
        bundledProvider: ThirdPartyBundledServiceProvider? = null,
        configurationStore: ThirdPartyConfigurationStore = InMemoryThirdPartyConfigurationStore(),
        webStorageCleaner: ThirdPartyWebStorageCleaner = NoOpThirdPartyWebStorageCleaner,
        kvStore: ThirdPartyKvStore? = null,
        resourceStore: ThirdPartyResourceStore? = null,
        migrationRunner: ThirdPartyDataMigrationRunner? = null,
    ): ThirdPartyServiceRepository =
        ThirdPartyServiceRepository(
            dao = dao,
            installer = ThirdPartyServiceInstaller(
                client = BjtuHttpClient(AppCookieJar()),
                servicesRoot = servicesRoot,
                apiBaseUrl = server.url("/").toString().trimEnd('/'),
            ),
            bundledProvider = bundledProvider,
            configurationStore = configurationStore,
            webStorageCleaner = webStorageCleaner,
            kvStore = kvStore,
            resourceStore = resourceStore,
            migrationRunner = migrationRunner,
        )

    private fun enqueueGithubPackage(
        server: MockWebServer,
        manifest: String = validManifest(),
        ownerId: Long = 12345,
        includeMigration: Boolean = false,
    ) {
        val entries = mutableListOf(
            "repo-main/$THIRD_PARTY_MANIFEST_FILE_NAME" to manifest,
            "repo-main/dist/index.html" to "<html></html>",
            "repo-main/dist/icon.svg" to "<svg></svg>",
        )
        if (includeMigration) {
            entries += "repo-main/dist/migration.html" to "<html></html>"
        }
        val zip = serviceZip(*entries.toTypedArray())
        server.enqueue(json("""{"default_branch":"main","owner":{"id":$ownerId}}"""))
        server.enqueue(json("""{"object":{"sha":"abc1234def5678"}}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(zip.readBytes())))
    }

    private fun existingAuthorizedEntity(): ThirdPartyServiceEntity =
        ThirdPartyServiceEntity(
            serviceId = "bjtu.demo",
            name = "Demo",
            description = "Demo service",
            version = "1.0.0",
            author = "Alice",
            sourceUrl = "https://github.com/alice/demo",
            githubOwner = "alice",
            githubRepo = "demo",
            defaultBranch = "main",
            commitSha = "oldcommit",
            packageDigestSha256 = "old-digest",
            manifestJson = validManifest(),
            grantedPermissionsJson = AppJson.encodeToString(listOf("identity.profile.read")),
            grantedCapabilitiesJson = AppJson.encodeToString(
                listOf("runtime.lifecycle@1", "identity.profile@1"),
            ),
            allowedOriginsJson = AppJson.encodeToString(listOf("https://api.example.com")),
            publisherSubjectId = "github-owner:12345",
            dataSchemaVersion = 1,
            runtimeProfile = ThirdPartyRuntimeProfile.ContractV1.value,
            runtimeFloor = THIRD_PARTY_RUNTIME_VERSION,
            compatibilityState = ThirdPartyRuntimeProfile.ContractV1.value,
            verificationLevel = "unverified",
            installDir = temp.newFolder("old-install-${System.nanoTime()}").absolutePath,
            entrypoint = "index.html",
            icon = "icon.svg",
            enabled = true,
            needsReview = false,
            installedAt = "2026-06-01T00:00:00Z",
            updatedAt = "2026-06-01T00:00:00Z",
        )

    private fun validManifest(
        version: String = "1.0.0",
        dataSchemaVersion: Int = 1,
        migrationEntrypoint: String? = null,
        configuration: List<ThirdPartyConfigurationDefinition> = emptyList(),
        extraOptionalCapabilities: List<String> = emptyList(),
        origins: ThirdPartyOriginDeclaration = ThirdPartyOriginDeclaration(
            connect = listOf("https://api.example.com"),
        ),
    ): String =
        AppJson.encodeToString(
            ThirdPartyServiceManifest(
                schemaVersion = 3,
                id = "bjtu.demo",
                name = "Demo",
                version = version,
                capabilities = ThirdPartyCapabilityDeclaration(
                    required = buildList {
                        add("runtime.lifecycle@1")
                        add("identity.profile@1")
                        if (configuration.isNotEmpty()) add("configuration.read@1")
                    },
                    optional = (
                        listOf("academic.timetable@1", "storage.kv@2") +
                            extraOptionalCapabilities
                        ).distinct(),
                ),
                origins = origins,
                dataSchemaVersion = dataSchemaVersion,
                migrationEntrypoint = migrationEntrypoint,
                configuration = configuration,
                entrypoint = "index.html",
                icon = "icon.svg",
                description = "Demo service",
                author = "Alice",
                marketplace = ThirdPartyMarketplaceMetadata(
                    description = "Demo service",
                    author = "Alice",
                    category = "other",
                ),
            )
        )

    private fun serviceZip(vararg entries: Pair<String, String>): File {
        val file = temp.newFile("service-${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun json(body: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(body)
}

private class FakeBundledProvider(
    private val installDir: File,
) : ThirdPartyBundledServiceProvider {
    var calls = 0
    override val bundledServiceIds: Set<String> = setOf("bjtu.bundled.demo")

    override suspend fun installMissingOrUpdated(
        existingServices: Map<String, ThirdPartyServiceEntity>,
    ): List<InstalledBundledThirdPartyService> {
        calls += 1
        if (existingServices.containsKey("bjtu.bundled.demo")) return emptyList()
        return listOf(
            InstalledBundledThirdPartyService(
                packageInfo = InstalledThirdPartyServicePackage(
                    manifest = ThirdPartyServiceManifest(
                        schemaVersion = 3,
                        id = "bjtu.bundled.demo",
                        name = "Bundled Demo",
                        version = "1.0.0",
                        capabilities = ThirdPartyCapabilityDeclaration(
                            required = listOf("runtime.lifecycle@1", "identity.profile@1"),
                        ),
                        origins = ThirdPartyOriginDeclaration(
                            connect = listOf("https://api.example.com"),
                        ),
                        dataSchemaVersion = 1,
                        entrypoint = "index.html",
                        icon = "icon.svg",
                        description = "Bundled demo service",
                        author = "bundled-demo",
                        marketplace = ThirdPartyMarketplaceMetadata(
                            description = "Bundled demo service",
                            author = "bundled-demo",
                            category = "other",
                        ),
                    ),
                    source = GitHubRepositoryRef(
                        owner = "bundled",
                        repo = "bundled-demo",
                        canonicalUrl = "asset://third-party-services/bjtu.bundled.demo",
                    ),
                    defaultBranch = "bundled",
                    commitSha = "abcdef1234567890abcdef1234567890abcdef12",
                    packageDigestSha256 = "a".repeat(64),
                    packageBytes = 128,
                    packageFileCount = 1,
                    installDir = installDir,
                ),
                defaultGrantedPermissions = emptySet(),
            )
        )
    }
}

private object EmptyBundledProvider : ThirdPartyBundledServiceProvider {
    override val bundledServiceIds: Set<String> = emptySet()

    override suspend fun installMissingOrUpdated(
        existingServices: Map<String, ThirdPartyServiceEntity>,
    ): List<InstalledBundledThirdPartyService> = emptyList()
}

private class FakeThirdPartyServiceDao : ThirdPartyServiceDao {
    val saved = linkedMapOf<String, ThirdPartyServiceEntity>()
    val cleanupTombstones = linkedMapOf<String, ThirdPartyCleanupTombstoneEntity>()
    var failDelete = false
    var failNextSave = false

    override suspend fun saveService(service: ThirdPartyServiceEntity) {
        if (failNextSave) {
            failNextSave = false
            throw IllegalStateException("simulated database save failure")
        }
        saved[service.serviceId] = service
    }

    override suspend fun listServices(): List<ThirdPartyServiceEntity> =
        saved.values.toList()

    override suspend fun getService(serviceId: String): ThirdPartyServiceEntity? =
        saved[serviceId]

    override suspend fun deleteService(serviceId: String) {
        if (failDelete) throw IllegalStateException("simulated database delete failure")
        saved.remove(serviceId)
    }

    override suspend fun saveCleanupTombstone(tombstone: ThirdPartyCleanupTombstoneEntity) {
        cleanupTombstones[tombstone.serviceId] = tombstone
    }

    override suspend fun listCleanupTombstones(): List<ThirdPartyCleanupTombstoneEntity> =
        cleanupTombstones.values.toList()

    override suspend fun deleteCleanupTombstone(serviceId: String) {
        cleanupTombstones.remove(serviceId)
    }

    override suspend fun deleteServiceAndScheduleCleanup(tombstone: ThirdPartyCleanupTombstoneEntity) {
        val previous = cleanupTombstones[tombstone.serviceId]
        try {
            saveCleanupTombstone(tombstone)
            deleteService(tombstone.serviceId)
        } catch (error: Exception) {
            if (previous == null) {
                cleanupTombstones.remove(tombstone.serviceId)
            } else {
                cleanupTombstones[tombstone.serviceId] = previous
            }
            throw error
        }
    }

    override suspend fun updateGrantState(
        serviceId: String,
        grantedCapabilitiesJson: String,
        enabled: Boolean,
        needsReview: Boolean,
        updatedAt: String,
    ) {
        saved[serviceId] = saved.getValue(serviceId).copy(
            grantedCapabilitiesJson = grantedCapabilitiesJson,
            enabled = enabled,
            needsReview = needsReview,
            updatedAt = updatedAt,
        )
    }
}

private class FakeThirdPartyWebStorageCleaner(
    var fail: Boolean,
) : ThirdPartyWebStorageCleaner {
    val origins = mutableListOf<String>()

    override suspend fun deleteOrigin(origin: String) {
        origins += origin
        if (fail) throw IllegalStateException("simulated WebStorage cleanup failure")
    }
}

private object PassthroughKvCipher : ThirdPartyKvCipher {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        plaintext.copyOf()

    override fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray =
        payload.copyOf()
}
