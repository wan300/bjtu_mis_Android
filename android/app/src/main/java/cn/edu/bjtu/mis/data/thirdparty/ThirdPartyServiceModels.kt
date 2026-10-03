@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package cn.edu.bjtu.mis.data.thirdparty

import cn.edu.bjtu.mis.data.thirdparty.generated.GeneratedCapabilityContracts
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

const val THIRD_PARTY_SERVICE_SCHEMA_VERSION = 3
const val THIRD_PARTY_RUNTIME_VERSION = 3
const val THIRD_PARTY_BRIDGE_PROTOCOL_VERSION = 2
const val THIRD_PARTY_CONTRACT_PROFILE = "contract_v1"
const val THIRD_PARTY_MANIFEST_FILE_NAME = "bjtu-plugin.json"
const val THIRD_PARTY_MARKETPLACE_FILE_NAME = "bjtu-marketplace.json"
const val THIRD_PARTY_DEVELOPMENT_FILE_NAME = "bjtu-plugin.dev.json"

val THIRD_PARTY_RUNTIME_CAPABILITIES: Set<String> =
    GeneratedCapabilityContracts.capabilityIds

@Serializable
data class ThirdPartyCapabilityDeclaration(
    val required: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val optional: List<String> = emptyList(),
)

@Serializable
data class ThirdPartyOriginDeclaration(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val connect: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val media: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val frame: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val navigation: List<String> = emptyList(),
) {
    val all: List<String>
        get() = (connect + media + frame + navigation).distinct()
}

@Serializable
data class ThirdPartyServiceManifest(
    val schemaVersion: Int = 0,
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val entrypoint: String = "",
    val icon: String = "",
    val capabilities: ThirdPartyCapabilityDeclaration = ThirdPartyCapabilityDeclaration(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val origins: ThirdPartyOriginDeclaration = ThirdPartyOriginDeclaration(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val dataSchemaVersion: Int = 0,
    val migrationEntrypoint: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val configuration: List<ThirdPartyConfigurationDefinition> = emptyList(),
    @Transient val description: String = "",
    @Transient val author: String = "",
    @Transient val marketplace: ThirdPartyMarketplaceMetadata? = null,
) {
    val requiredCapabilities: List<String>
        get() = capabilities.required

    val optionalCapabilities: List<String>
        get() = capabilities.optional

    val connectOrigins: List<String>
        get() = origins.connect

    val mediaOrigins: List<String>
        get() = origins.media

    val frameOrigins: List<String>
        get() = origins.frame

    val navigationOrigins: List<String>
        get() = origins.navigation

    val remoteOrigins: List<String>
        get() = origins.all

    /**
     * Derived compatibility view for persisted legacy records and fine-grained
     * campus policy checks. Authorization is reviewed and stored by capability ID.
     */
    val permissions: ThirdPartyServicePermissionDeclaration
        get() = ThirdPartyServicePermissionDeclaration(
            required = ThirdPartyCapabilityRegistry.permissionsFor(requiredCapabilities).sorted(),
            optional = ThirdPartyCapabilityRegistry.permissionsFor(optionalCapabilities).sorted(),
        )

    /** The bridge origin is a host invariant and is never author controlled. */
    val bridgeOrigins: List<String>
        get() = listOf("self")

    val runtimeVersion: Int
        get() = THIRD_PARTY_RUNTIME_VERSION

    val minRuntimeVersion: Int
        get() = ThirdPartyCapabilityRegistry.runtimeFloor(requiredCapabilities)
}

@Serializable
data class ThirdPartyMarketplaceScreenshot(
    val src: String = "",
    val alt: String = "",
)

@Serializable
data class ThirdPartyMarketplaceMetadata(
    val description: String = "",
    val author: String = "",
    val category: String = "",
    val tags: List<String> = emptyList(),
    val license: String? = null,
    val screenshots: List<ThirdPartyMarketplaceScreenshot> = emptyList(),
)

@Serializable
data class ThirdPartyConfigurationDefinition(
    val key: String = "",
    val label: String = "",
    val description: String = "",
    val type: String = "text",
    val required: Boolean = false,
    val default: String? = null,
    val options: List<String> = emptyList(),
)

/**
 * Legacy compatibility model. The contract manifest never serializes this shape;
 * it is derived from capability descriptors for migration and policy checks.
 */
@Serializable
data class ThirdPartyServicePermissionDeclaration(
    val required: List<String> = emptyList(),
    val optional: List<String> = emptyList(),
)

data class ThirdPartyService(
    val serviceId: String,
    val manifest: ThirdPartyServiceManifest,
    val sourceUrl: String,
    val githubOwner: String,
    val githubRepo: String,
    val defaultBranch: String,
    val commitSha: String,
    val packageDigestSha256: String,
    val installDir: String,
    val grantedCapabilities: Set<String>,
    val allowedOrigins: List<String>,
    val enabled: Boolean,
    val needsReview: Boolean,
    val installedAt: String,
    val updatedAt: String,
    val publisherSubjectId: String = "",
    val dataSchemaVersion: Int = 0,
    val runtimeProfile: String = ThirdPartyRuntimeProfile.LegacyV1V2.value,
    val runtimeFloor: Int = 0,
    val compatibilityState: String = runtimeProfile,
    val verificationLevel: String = "legacy",
    val previousVersion: ThirdPartyInstalledVersionSnapshot? = null,
) {
    val route: String = thirdPartyServiceRoute(serviceId)

    val iconSource: ThirdPartyIconSource?
        get() = resolveLocalThirdPartyIconSource(java.io.File(installDir), manifest.icon)

    val grantedPermissions: Set<String>
        get() = ThirdPartyCapabilityRegistry.permissionsFor(grantedCapabilities)

    val reviewCapabilitySelection: Set<String>
        get() = manifest.requiredCapabilities.toSet() +
            (grantedCapabilities intersect manifest.optionalCapabilities.toSet())

    val canRun: Boolean
        get() = enabled &&
            !needsReview &&
            runtimeProfile == ThirdPartyRuntimeProfile.ContractV1.value &&
            runtimeFloor <= THIRD_PARTY_RUNTIME_VERSION &&
            grantedCapabilities.containsAll(manifest.requiredCapabilities)
}

enum class ThirdPartyRuntimeProfile(val value: String) {
    ContractV1("contract_v1"),
    LegacyV3P0a("legacy_v3_p0a"),
    LegacyV1V2("legacy_v1_v2"),
}

/**
 * Kept for source compatibility while call sites migrate from the old
 * compatibility flag to the explicit runtime profile.
 */
enum class ThirdPartyCompatibilityState(val value: String) {
    Compatible("compatible"),
    ContractV1("contract_v1"),
    LegacyV3P0a("legacy_v3_p0a"),
    LegacyV1V2("legacy_v1_v2"),
    LegacyDisabled("legacy_disabled"),
    RuntimeUnsupported("runtime_unsupported"),
}

@Serializable
data class ThirdPartyInstalledVersionSnapshot(
    val version: String,
    val commitSha: String,
    val packageDigestSha256: String,
    val installDir: String,
    val manifest: ThirdPartyServiceManifest,
    val marketplace: ThirdPartyMarketplaceMetadata? = null,
    val dataSchemaVersion: Int,
    val sourceUrl: String,
    val githubOwner: String,
    val githubRepo: String,
    val defaultBranch: String,
    val publisherSubjectId: String,
    val grantedCapabilities: Set<String> = emptySet(),
    val allowedOrigins: List<String> = manifest.remoteOrigins,
    val enabled: Boolean,
    val needsReview: Boolean,
    val runtimeProfile: String = ThirdPartyRuntimeProfile.LegacyV3P0a.value,
    val runtimeFloor: Int = 1,
    val verificationLevel: String = "legacy",
)

data class ThirdPartyServiceInstallResult(
    val service: ThirdPartyService,
    val updatedExisting: Boolean,
)

data class ThirdPartyServiceImportPreview(
    val token: String,
    val manifest: ThirdPartyServiceManifest,
    val sourceUrl: String,
    val githubOwner: String,
    val githubRepo: String,
    val defaultBranch: String,
    val commitSha: String,
    val packageDigestSha256: String,
    val packageBytes: Long,
    val packageFileCount: Int,
    val updatedExisting: Boolean,
    val archiveSha256: String? = null,
    val platformVerified: Boolean = false,
    val iconSource: ThirdPartyIconSource? = null,
    val publisherSubjectId: String = "",
    val verificationLevel: String = "unverified",
    val previousService: ThirdPartyService? = null,
    val addedRequiredCapabilities: List<String> = emptyList(),
    val addedOptionalCapabilities: List<String> = emptyList(),
    val removedCapabilities: List<String> = emptyList(),
    val addedOrigins: List<String> = emptyList(),
    val removedOrigins: List<String> = emptyList(),
) {
    val addedOriginPolicies: ThirdPartyOriginDeclaration
        get() = addedThirdPartyOriginPolicies(
            previousService?.manifest?.origins ?: ThirdPartyOriginDeclaration(),
            manifest.origins,
        )

    val removedOriginPolicies: ThirdPartyOriginDeclaration
        get() = addedThirdPartyOriginPolicies(
            manifest.origins,
            previousService?.manifest?.origins ?: ThirdPartyOriginDeclaration(),
        )

    val requiresUserConfirmation: Boolean
        get() = !updatedExisting || requiresThirdPartyUpdateConfirmation(
            ThirdPartyUpdateReviewInput(
                existingNeedsReview = previousService?.needsReview == true,
                existingRuntimeProfile = previousService?.runtimeProfile,
                addedRequiredCapabilities = addedRequiredCapabilities.toSet(),
                addedOptionalCapabilities = addedOptionalCapabilities.toSet(),
                removedAndroidCapabilities = removedCapabilities.filter { it.startsWith("android.") }.toSet(),
                addedOrigins = addedOriginPolicies.all.toSet(),
            ),
        )

    val addedRequiredPermissions: List<String>
        get() = ThirdPartyCapabilityRegistry.permissionsFor(addedRequiredCapabilities).sorted()

    val addedOptionalPermissions: List<String>
        get() = ThirdPartyCapabilityRegistry.permissionsFor(addedOptionalCapabilities).sorted()

    val removedPermissions: List<String>
        get() = ThirdPartyCapabilityRegistry.permissionsFor(removedCapabilities).sorted()
}

@Serializable
data class CatalogPublisherIdentity(
    @SerialName("subjectId") val subjectId: String? = null,
    @SerialName("ownerId") val ownerId: String? = null,
    val login: String? = null,
    val type: String? = null,
    @SerialName("transferStatus") val transferStatus: String = "none",
)

@Serializable
data class CatalogPlugin(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val version: String = "",
    val author: String = "",
    val category: String = "other",
    val tags: List<String> = emptyList(),
    val license: String? = null,
    val screenshots: List<ThirdPartyMarketplaceScreenshot> = emptyList(),
    @SerialName("repositoryUrl") val repositoryUrl: String = "",
    val repository: String = "",
    @SerialName("commitSha") val commitSha: String = "",
    @SerialName("archiveSha256") val archiveSha256: String = "",
    @SerialName("packageDigestSha256") val packageDigestSha256: String = "",
    @SerialName("packageBytes") val packageBytes: Long = 0,
    @SerialName("packageFileCount") val packageFileCount: Int = 0,
    @SerialName("schemaVersion") val schemaVersion: Int = 0,
    @SerialName("contractProfile") val contractProfile: String = "",
    @SerialName("runtimeFloor") val runtimeFloor: Int = 0,
    val capabilities: ThirdPartyCapabilityDeclaration = ThirdPartyCapabilityDeclaration(),
    val origins: ThirdPartyOriginDeclaration = ThirdPartyOriginDeclaration(),
    @SerialName("dataSchemaVersion") val dataSchemaVersion: Int? = null,
    @SerialName("migrationEntrypoint") val migrationEntrypoint: String? = null,
    @SerialName("publisherSubjectId") val publisherSubjectId: String = "",
    @SerialName("publisherIdentity") val publisherIdentity: CatalogPublisherIdentity = CatalogPublisherIdentity(),
    @SerialName("compatibilityState") val compatibilityState: String = ThirdPartyCompatibilityState.LegacyDisabled.value,
    val configuration: List<ThirdPartyConfigurationDefinition> = emptyList(),
    @SerialName("validationWarnings") val validationWarnings: List<String> = emptyList(),
    @SerialName("verificationLevel") val verificationLevel: String = "automated",
    @SerialName("publishedAt") val publishedAt: String = "",
    @SerialName("iconUrl") val iconUrl: String = "",
    @SerialName("artifactUrl") val artifactUrl: String = "",
    @SerialName("updateAvailable") val updateAvailable: Boolean = false,
    @SerialName("publisherMismatch") val publisherMismatch: Boolean = false,
    @SerialName("replacesLegacyP0a") val replacesLegacyP0a: Boolean = false,
) {
    val publisherOwnerLogin: String
        get() = publisherIdentity.login.orEmpty()

    val iconSource: ThirdPartyIconSource?
        get() = resolveRemoteThirdPartyIconSource(iconUrl)

    val requiredCapabilities: List<String>
        get() = capabilities.required

    val optionalCapabilities: List<String>
        get() = capabilities.optional

    val permissions: ThirdPartyServicePermissionDeclaration
        get() = ThirdPartyServicePermissionDeclaration(
            required = ThirdPartyCapabilityRegistry.permissionsFor(requiredCapabilities).sorted(),
            optional = ThirdPartyCapabilityRegistry.permissionsFor(optionalCapabilities).sorted(),
        )

    val connectOrigins: List<String>
        get() = origins.connect

    val mediaOrigins: List<String>
        get() = origins.media

    val frameOrigins: List<String>
        get() = origins.frame

    val navigationOrigins: List<String>
        get() = origins.navigation

    val bridgeOrigins: List<String>
        get() = listOf("self")

    val runtimeVersion: Int
        get() = THIRD_PARTY_RUNTIME_VERSION

    val minRuntimeVersion: Int
        get() = runtimeFloor
}

@Serializable
data class CatalogPluginPage(
    val items: List<CatalogPlugin> = emptyList(),
    @SerialName("nextCursor") val nextCursor: String? = null,
    @Transient val fromCache: Boolean = false,
)

@Serializable
data class CatalogUpdateRequest(
    val installed: List<CatalogInstalledVersion>,
)

@Serializable
data class CatalogInstalledVersion(
    val id: String,
    @SerialName("commitSha") val commitSha: String,
    @SerialName("publisherSubjectId") val publisherSubjectId: String,
    @SerialName("contractProfile") val contractProfile: String,
)

@Serializable
data class CatalogUpdateResponse(
    val items: List<CatalogPlugin> = emptyList(),
)

data class GitHubRepositoryRef(
    val owner: String,
    val repo: String,
    val canonicalUrl: String,
    val ownerId: String? = null,
)

open class ThirdPartyServiceException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun thirdPartyServiceRoute(serviceId: String): String = "$THIRD_PARTY_SERVICE_ROUTE_PREFIX$serviceId"

fun thirdPartyServiceIdFromRoute(route: String): String? =
    route.takeIf { it.startsWith(THIRD_PARTY_SERVICE_ROUTE_PREFIX) }
        ?.removePrefix(THIRD_PARTY_SERVICE_ROUTE_PREFIX)
        ?.takeIf { it.isNotBlank() }

const val THIRD_PARTY_SERVICES_ROUTE = "third_party_services"
const val THIRD_PARTY_DIAGNOSTICS_ROUTE = "third_party_diagnostics"
const val THIRD_PARTY_SERVICE_ROUTE_PREFIX = "third_party_service/"
