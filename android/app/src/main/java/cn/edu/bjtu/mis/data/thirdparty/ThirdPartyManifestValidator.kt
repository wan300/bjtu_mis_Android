package cn.edu.bjtu.mis.data.thirdparty

import cn.edu.bjtu.mis.data.AppJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.util.Locale

object ThirdPartyManifestValidator {
    private val ServiceIdPattern = Regex("^[a-z][a-z0-9_\\-.]{2,63}$")
    private val ConfigurationKeyPattern = Regex("^[A-Z][A-Z0-9_]{0,63}$")
    private val SemVerPattern = Regex(
        "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
            "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?" +
            "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$",
    )
    private val MarketplaceCategories =
        setOf("academic", "campus", "information", "productivity", "assistant", "other")
    private val ConfigurationTypes = setOf("text", "secret", "url", "number", "boolean", "select")
    private val CampusHosts = setOf(
        "123.121.147.7",
        "cas.bjtu.edu.cn",
        "mis.bjtu.edu.cn",
        "aa.bjtu.edu.cn",
        "mail.bjtu.edu.cn",
        "zhixing.bjtu.edu.cn",
        "job.bjtu.edu.cn",
        "bksycenter.bjtu.edu.cn",
    )
    private val ManifestFields = setOf(
        "schema_version",
        "id",
        "name",
        "version",
        "entrypoint",
        "icon",
        "capabilities",
        "origins",
        "data_schema_version",
        "migration_entrypoint",
        "configuration",
    )
    private val RequiredManifestFields = setOf(
        "schema_version",
        "id",
        "name",
        "version",
        "entrypoint",
        "icon",
        "capabilities",
    )
    private val MarketplaceFields =
        setOf("description", "author", "category", "tags", "license", "screenshots")

    fun decodeAndValidate(
        rawJson: String,
        packageRoot: File? = null,
        allowDevelopmentOrigins: Boolean = false,
    ): ThirdPartyServiceManifest {
        val rawManifest = decodeJsonObject(rawJson, THIRD_PARTY_MANIFEST_FILE_NAME)
        requireExactFields(rawManifest, THIRD_PARTY_MANIFEST_FILE_NAME, ManifestFields, RequiredManifestFields)
        validateRawObjectShape(
            rawManifest["capabilities"],
            "capabilities",
            allowed = setOf("required", "optional"),
            required = setOf("required"),
        )
        validateRawObjectShape(
            rawManifest["origins"],
            "origins",
            allowed = setOf("connect", "media", "frame", "navigation"),
            required = emptySet(),
            optionalObject = true,
        )
        requireNonEmptyArray(rawManifest["capabilities"]?.jsonObject?.get("required"), "capabilities.required")
        rawManifest["capabilities"]?.jsonObject?.get("optional")?.let {
            requireNonEmptyArray(it, "capabilities.optional")
        }
        rawManifest["origins"]?.let { origins ->
            val originObject = origins as? JsonObject
                ?: throw ThirdPartyServiceException("origins must be an object")
            if (originObject.isEmpty()) {
                throw ThirdPartyServiceException("origins must be omitted when no origins are declared")
            }
            originObject.forEach { (key, value) -> requireNonEmptyArray(value, "origins.$key") }
        }
        rawManifest["configuration"]?.let { configuration ->
            requireNonEmptyArray(configuration, "configuration")
            (configuration as JsonArray).forEachIndexed { index, value ->
                validateRawObjectShape(
                    value,
                    "configuration[$index]",
                    allowed = setOf(
                        "key",
                        "label",
                        "description",
                        "type",
                        "required",
                        "default",
                        "options",
                    ),
                    required = setOf("key", "label", "description", "type", "required"),
                )
            }
        }
        if (rawManifest["migration_entrypoint"] is JsonNull) {
            throw ThirdPartyServiceException(
                "migration_entrypoint must be omitted or contain a local asset path",
            )
        }

        val manifest = runCatching {
            AppJson.decodeFromString<ThirdPartyServiceManifest>(rawJson)
        }.getOrElse {
            throw ThirdPartyServiceException("$THIRD_PARTY_MANIFEST_FILE_NAME has invalid field types", it)
        }
        val declaresStorage = (
            manifest.capabilities.required + manifest.capabilities.optional
            ).any(ThirdPartyCapabilityRegistry::isStorageCapability)
        val declaresDataVersion = "data_schema_version" in rawManifest
        val declaresMigration = "migration_entrypoint" in rawManifest
        if (declaresStorage != declaresDataVersion) {
            throw ThirdPartyServiceException(
                "data_schema_version is required only when storage.kv@2 or storage.blob@1 is declared",
            )
        }
        if (declaresStorage && manifest.dataSchemaVersion < 1) {
            throw ThirdPartyServiceException("data_schema_version must be a positive integer")
        }
        if (!declaresStorage && declaresMigration) {
            throw ThirdPartyServiceException(
                "migration_entrypoint is only valid for plugins with persistent storage",
            )
        }
        if (manifest.dataSchemaVersion >= 2 && !declaresMigration) {
            throw ThirdPartyServiceException(
                "data_schema_version 2 or newer requires migration_entrypoint",
            )
        }
        return validate(manifest, packageRoot, allowDevelopmentOrigins)
    }

    fun decodeAndValidateMarketplace(rawJson: String): ThirdPartyMarketplaceMetadata {
        val rawMarketplace = decodeJsonObject(rawJson, THIRD_PARTY_MARKETPLACE_FILE_NAME)
        requireExactFields(
            rawMarketplace,
            THIRD_PARTY_MARKETPLACE_FILE_NAME,
            MarketplaceFields,
            setOf("description", "author", "category", "tags"),
        )
        rawMarketplace["screenshots"]?.let { screenshots ->
            (screenshots as? JsonArray
                ?: throw ThirdPartyServiceException("marketplace.screenshots must be an array"))
                .forEachIndexed { index, value ->
                    validateRawObjectShape(
                        value,
                        "screenshots[$index]",
                        allowed = setOf("src", "alt"),
                        required = setOf("src", "alt"),
                    )
                }
        }
        val marketplace = runCatching {
            AppJson.decodeFromString<ThirdPartyMarketplaceMetadata>(rawJson)
        }.getOrElse {
            throw ThirdPartyServiceException("$THIRD_PARTY_MARKETPLACE_FILE_NAME has invalid field types", it)
        }
        return validateMarketplace(marketplace)
    }

    fun attachMarketplace(
        manifest: ThirdPartyServiceManifest,
        marketplace: ThirdPartyMarketplaceMetadata?,
    ): ThirdPartyServiceManifest = manifest.copy(
        description = marketplace?.description.orEmpty(),
        author = marketplace?.author.orEmpty(),
        marketplace = marketplace,
    )

    fun validate(
        manifest: ThirdPartyServiceManifest,
        packageRoot: File? = null,
        allowDevelopmentOrigins: Boolean = false,
    ): ThirdPartyServiceManifest {
        if (manifest.schemaVersion != THIRD_PARTY_SERVICE_SCHEMA_VERSION) {
            throw ThirdPartyServiceException(
                "Unsupported plugin schema_version: ${manifest.schemaVersion}",
            )
        }
        if (!ServiceIdPattern.matches(manifest.id)) {
            throw ThirdPartyServiceException(
                "Plugin id must be 3-64 chars and contain only lowercase letters, digits, dot, underscore, or hyphen",
            )
        }
        requireText(manifest.name, "name", 80)
        requireText(manifest.version, "version", 40)
        if (!SemVerPattern.matches(manifest.version.trim())) {
            throw ThirdPartyServiceException("schema_version 3 version must use semantic versioning")
        }

        val requiredCapabilities =
            normalizeCapabilities(manifest.capabilities.required, "capabilities.required")
        val optionalOnlyRequired = requiredCapabilities.filter { capability ->
            ThirdPartyCapabilityRegistry.get(capability)?.optionalOnly == true
        }
        if (optionalOnlyRequired.isNotEmpty()) {
            throw ThirdPartyServiceException(
                "Capabilities may only appear in capabilities.optional: ${optionalOnlyRequired.joinToString()}",
            )
        }
        val optionalCapabilities =
            normalizeCapabilities(manifest.capabilities.optional, "capabilities.optional")
        if ("runtime.lifecycle@1" !in requiredCapabilities) {
            throw ThirdPartyServiceException(
                "runtime.lifecycle@1 must be declared in capabilities.required",
            )
        }
        val duplicatedCapabilities = requiredCapabilities.toSet().intersect(optionalCapabilities.toSet())
        if (duplicatedCapabilities.isNotEmpty()) {
            throw ThirdPartyServiceException(
                "Capabilities cannot appear in both required and optional: " +
                    duplicatedCapabilities.joinToString(),
            )
        }
        val runtimeFloor = ThirdPartyCapabilityRegistry.runtimeFloor(requiredCapabilities)
        if (runtimeFloor > THIRD_PARTY_RUNTIME_VERSION) {
            throw ThirdPartyServiceException(
                "Plugin requires runtime $runtimeFloor, host provides $THIRD_PARTY_RUNTIME_VERSION",
            )
        }

        val normalizedEntrypoint = validateAssetPath(manifest.entrypoint, "entrypoint")
        val normalizedIcon = validateAssetPath(manifest.icon, "icon")
        val normalizedMigrationEntrypoint = manifest.migrationEntrypoint
            ?.takeIf { it.isNotBlank() }
            ?.let { validateAssetPath(it, "migration_entrypoint") }
        if (!isSupportedThirdPartyIconPath(normalizedIcon)) {
            throw ThirdPartyServiceException("Plugin icon must be SVG, PNG, WebP, JPG, or JPEG")
        }

        val connectOrigins = normalizeOrigins(
            manifest.origins.connect,
            "origins.connect",
            allowDevelopmentOrigins,
            blockCampusHosts = true,
        )
        val mediaOrigins = normalizeOrigins(
            manifest.origins.media,
            "origins.media",
            allowDevelopmentOrigins,
            blockCampusHosts = true,
        )
        val frameOrigins = normalizeOrigins(
            manifest.origins.frame,
            "origins.frame",
            allowDevelopmentOrigins,
            blockCampusHosts = true,
        )
        val navigationOrigins = normalizeOrigins(
            manifest.origins.navigation,
            "origins.navigation",
            allowDevelopmentOrigins,
            blockCampusHosts = false,
        )
        val declaredCapabilities = (requiredCapabilities + optionalCapabilities).toSet()
        if (frameOrigins.isNotEmpty() && "remote.frame@1" !in declaredCapabilities) {
            throw ThirdPartyServiceException("origins.frame requires remote.frame@1")
        }
        if (
            navigationOrigins.isNotEmpty() &&
            "navigation.external@1" !in declaredCapabilities
        ) {
            throw ThirdPartyServiceException(
                "origins.navigation requires navigation.external@1",
            )
        }
        if (manifest.configuration.isNotEmpty() && "configuration.read@1" !in declaredCapabilities) {
            throw ThirdPartyServiceException("configuration requires configuration.read@1")
        }

        if (packageRoot != null) {
            val dist = File(packageRoot, "dist").canonicalFile
            if (!dist.isDirectory) {
                throw ThirdPartyServiceException("Plugin package is missing dist/ directory")
            }
            val entrypointFile = resolveAssetFile(dist, normalizedEntrypoint, "entrypoint")
            if (!entrypointFile.isFile) {
                throw ThirdPartyServiceException("Plugin entrypoint does not exist: ${manifest.entrypoint}")
            }
            val iconFile = resolveAssetFile(dist, normalizedIcon, "icon")
            if (!iconFile.isFile) {
                throw ThirdPartyServiceException("Plugin icon does not exist: ${manifest.icon}")
            }
            if (iconFile.length() !in 1..MAX_THIRD_PARTY_ICON_BYTES) {
                throw ThirdPartyServiceException("Plugin icon must be 1 byte to 1 MiB")
            }
            normalizedMigrationEntrypoint?.let { migrationEntrypoint ->
                if (!resolveAssetFile(dist, migrationEntrypoint, "migration_entrypoint").isFile) {
                    throw ThirdPartyServiceException(
                        "Plugin migration_entrypoint does not exist: $migrationEntrypoint",
                    )
                }
            }
        }

        val configuration = validateConfiguration(manifest.configuration)
        return manifest.copy(
            id = manifest.id.trim(),
            name = manifest.name.trim(),
            version = manifest.version.trim(),
            entrypoint = normalizedEntrypoint,
            icon = normalizedIcon,
            capabilities = ThirdPartyCapabilityDeclaration(
                required = requiredCapabilities,
                optional = optionalCapabilities,
            ),
            origins = ThirdPartyOriginDeclaration(
                connect = connectOrigins,
                media = mediaOrigins,
                frame = frameOrigins,
                navigation = navigationOrigins,
            ),
            migrationEntrypoint = normalizedMigrationEntrypoint,
            configuration = configuration,
        )
    }

    private fun validateMarketplace(
        marketplace: ThirdPartyMarketplaceMetadata,
    ): ThirdPartyMarketplaceMetadata {
        requireText(marketplace.description, "marketplace.description", 400)
        requireText(marketplace.author, "marketplace.author", 120)
        val category = marketplace.category.trim().lowercase()
        if (category !in MarketplaceCategories) {
            throw ThirdPartyServiceException("Unknown marketplace category: ${marketplace.category}")
        }
        if (marketplace.tags.size > 5) {
            throw ThirdPartyServiceException("marketplace.tags cannot contain more than 5 entries")
        }
        val tags = marketplace.tags.map(String::trim)
        if (tags.any { it.length !in 1..20 }) {
            throw ThirdPartyServiceException("marketplace tags must be 1-20 characters")
        }
        if (tags.map(String::lowercase).size != tags.map(String::lowercase).toSet().size) {
            throw ThirdPartyServiceException("marketplace.tags contains duplicates")
        }
        val license = marketplace.license?.trim()
        if (license != null && license.length !in 1..80) {
            throw ThirdPartyServiceException("marketplace.license must be 1-80 characters")
        }
        if (marketplace.screenshots.size > 8) {
            throw ThirdPartyServiceException("marketplace.screenshots cannot contain more than 8 entries")
        }
        val screenshots = marketplace.screenshots.map { screenshot ->
            val src = screenshot.src.trim()
            val alt = screenshot.alt.trim()
            if (src.isEmpty()) {
                throw ThirdPartyServiceException("marketplace screenshot src cannot be blank")
            }
            if (alt.length !in 1..160) {
                throw ThirdPartyServiceException("marketplace screenshot alt must be 1-160 characters")
            }
            screenshot.copy(src = src, alt = alt)
        }
        return marketplace.copy(
            description = marketplace.description.trim(),
            author = marketplace.author.trim(),
            category = category,
            tags = tags,
            license = license,
            screenshots = screenshots,
        )
    }

    private fun validateConfiguration(
        definitions: List<ThirdPartyConfigurationDefinition>,
    ): List<ThirdPartyConfigurationDefinition> {
        if (definitions.size > 32) {
            throw ThirdPartyServiceException("configuration cannot contain more than 32 entries")
        }
        val normalized = definitions.map { definition ->
            val key = definition.key.trim()
            if (!ConfigurationKeyPattern.matches(key)) {
                throw ThirdPartyServiceException("Invalid configuration key: ${definition.key}")
            }
            val label = definition.label.trim()
            val description = definition.description.trim()
            if (label.length !in 1..80) {
                throw ThirdPartyServiceException("configuration label must be 1-80 characters: $key")
            }
            if (description.length > 240) {
                throw ThirdPartyServiceException(
                    "configuration description cannot exceed 240 characters: $key",
                )
            }
            val type = definition.type.trim().lowercase()
            if (type !in ConfigurationTypes) {
                throw ThirdPartyServiceException(
                    "Unknown configuration type for $key: ${definition.type}",
                )
            }
            val options = definition.options.map(String::trim)
            if (type == "select") {
                if (
                    options.isEmpty() ||
                    options.size > 20 ||
                    options.any(String::isEmpty) ||
                    options.size != options.toSet().size
                ) {
                    throw ThirdPartyServiceException(
                        "select configuration requires 1-20 unique non-empty options: $key",
                    )
                }
            } else if (options.isNotEmpty()) {
                throw ThirdPartyServiceException(
                    "configuration options are only valid for select: $key",
                )
            }
            if (type == "secret" && definition.default != null) {
                throw ThirdPartyServiceException("secret configuration cannot declare a default: $key")
            }
            validateConfigurationDefault(key, type, definition.default, options)
            definition.copy(
                key = key,
                label = label,
                description = description,
                type = type,
                default = definition.default?.trim(),
                options = options,
            )
        }
        val duplicateKeys = normalized.groupingBy { it.key }.eachCount().filterValues { it > 1 }.keys
        if (duplicateKeys.isNotEmpty()) {
            throw ThirdPartyServiceException(
                "configuration contains duplicate keys: ${duplicateKeys.joinToString()}",
            )
        }
        return normalized
    }

    private fun validateConfigurationDefault(
        key: String,
        type: String,
        default: String?,
        options: List<String>,
    ) {
        val value = default?.trim() ?: return
        when (type) {
            "number" -> if (value.toDoubleOrNull() == null) {
                throw ThirdPartyServiceException("configuration default must be a number: $key")
            }
            "boolean" -> if (value != "true" && value != "false") {
                throw ThirdPartyServiceException("configuration default must be true or false: $key")
            }
            "url" -> {
                val uri = runCatching { URI(value) }.getOrNull()
                if (uri?.scheme?.lowercase() !in setOf("http", "https") || uri?.host.isNullOrBlank()) {
                    throw ThirdPartyServiceException(
                        "configuration default must be an HTTP/HTTPS URL: $key",
                    )
                }
            }
            "select" -> if (value !in options) {
                throw ThirdPartyServiceException(
                    "configuration default must be one of its options: $key",
                )
            }
        }
    }

    fun validateAssetPath(value: String, fieldName: String): String = normalizeAssetPath(value).also {
        val raw = value.trim()
        if (it.isBlank()) throw ThirdPartyServiceException("$fieldName cannot be blank")
        if (
            raw.startsWith("/") ||
            raw.contains("\\") ||
            raw.contains(":") ||
            it.split('/').any { part -> part == ".." }
        ) {
            throw ThirdPartyServiceException("$fieldName must be a relative path inside dist/")
        }
    }

    private fun resolveAssetFile(dist: File, relativePath: String, fieldName: String): File {
        val file = File(dist, relativePath).canonicalFile
        if (!file.toPath().startsWith(dist.toPath()) || file == dist) {
            throw ThirdPartyServiceException("$fieldName must stay inside dist/")
        }
        return file
    }

    fun normalizeOrigin(
        value: String,
        fieldName: String = "origin",
        allowDevelopmentOrigins: Boolean = false,
        blockCampusHosts: Boolean = true,
    ): String {
        val raw = value.trim()
        if (raw.isBlank()) throw ThirdPartyServiceException("$fieldName cannot contain blank values")
        val uri = runCatching { URI(raw) }.getOrElse {
            throw ThirdPartyServiceException("$fieldName contains invalid URL: $raw")
        }
        val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
        val host = uri.host?.lowercase(Locale.US).orEmpty()
        val localhostDevelopmentOrigin = scheme == "http" &&
            allowDevelopmentOrigins &&
            host in setOf("localhost", "127.0.0.1", "::1")
        if (scheme != "https" && !localhostDevelopmentOrigin) {
            throw ThirdPartyServiceException("$fieldName only supports HTTPS origins")
        }
        if (
            host.isBlank() ||
            uri.rawUserInfo != null ||
            !uri.rawQuery.isNullOrBlank() ||
            !uri.rawFragment.isNullOrBlank()
        ) {
            throw ThirdPartyServiceException(
                "$fieldName must contain origins without userinfo, query, or fragment: $raw",
            )
        }
        val path = uri.rawPath.orEmpty()
        if (path.isNotBlank() && path != "/") {
            throw ThirdPartyServiceException("$fieldName cannot include path: $raw")
        }
        if (!localhostDevelopmentOrigin && isPrivateOrLocalHost(host)) {
            throw ThirdPartyServiceException(
                "$fieldName cannot include private, loopback, link-local, or local hosts: $host",
            )
        }
        if (blockCampusHosts && isCampusHost(host)) {
            throw ThirdPartyServiceException(
                "$fieldName cannot include campus service hosts; use campus.request@1: $host",
            )
        }
        val defaultPort = if (scheme == "https") 443 else 80
        val port = uri.port
            .takeIf { it != -1 && it != defaultPort }
            ?.let { ":$it" }
            .orEmpty()
        return "$scheme://$host$port"
    }

    private fun normalizeCapabilities(values: List<String>, fieldName: String): List<String> {
        val normalized = values.map(String::trim)
        if (normalized.any(String::isBlank)) {
            throw ThirdPartyServiceException("$fieldName cannot contain blank values")
        }
        if (normalized.size != normalized.toSet().size) {
            throw ThirdPartyServiceException("$fieldName contains duplicates")
        }
        normalized.forEach(ThirdPartyCapabilityRegistry::requireKnown)
        return normalized
    }

    private fun normalizeOrigins(
        values: List<String>,
        fieldName: String,
        allowDevelopmentOrigins: Boolean,
        blockCampusHosts: Boolean,
    ): List<String> {
        val normalized = values.map { value ->
            normalizeOrigin(
                value,
                fieldName = fieldName,
                allowDevelopmentOrigins = allowDevelopmentOrigins,
                blockCampusHosts = blockCampusHosts,
            )
        }
        if (normalized.size != normalized.toSet().size) {
            throw ThirdPartyServiceException("$fieldName contains duplicates")
        }
        return normalized
    }

    private fun isCampusHost(host: String): Boolean =
        host in CampusHosts || host.endsWith(".bjtu.edu.cn")

    internal fun isPrivateOrLocalHost(host: String): Boolean {
        val normalized = host.removePrefix("[").removeSuffix("]").lowercase(Locale.US)
        if (
            normalized == "localhost" ||
            normalized.endsWith(".localhost") ||
            normalized.endsWith(".local") ||
            normalized.endsWith(".internal") ||
            normalized == "::" ||
            normalized == "::1"
        ) {
            return true
        }
        val firstIpv6Hextet = normalized
            .takeIf { ':' in it && !it.startsWith("::") }
            ?.substringBefore(':')
            ?.toIntOrNull(16)
        if (
            firstIpv6Hextet != null &&
            (
                (firstIpv6Hextet and 0xfe00) == 0xfc00 ||
                    (firstIpv6Hextet and 0xffc0) == 0xfe80 ||
                    (firstIpv6Hextet and 0xff00) == 0xff00
                )
        ) {
            return true
        }
        val address = runCatching {
            if (normalized.matches(Regex("^[0-9a-fA-F:.]+$"))) {
                InetAddress.getByName(normalized)
            } else {
                null
            }
        }.getOrNull() ?: return false
        return address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress ||
            (
                address.address.size == 4 &&
                    (address.address[0].toInt() and 0xff) == 100 &&
                    (address.address[1].toInt() and 0xc0) == 64
                )
    }

    private fun requireText(value: String, fieldName: String, maxLength: Int) {
        val normalized = value.trim()
        if (normalized.isBlank()) throw ThirdPartyServiceException("$fieldName cannot be blank")
        if (normalized.length > maxLength) {
            throw ThirdPartyServiceException("$fieldName cannot exceed $maxLength characters")
        }
    }

    private fun decodeJsonObject(rawJson: String, fileName: String): JsonObject =
        runCatching { AppJson.parseToJsonElement(rawJson).jsonObject }
            .getOrElse { throw ThirdPartyServiceException("$fileName is not a valid JSON object", it) }

    private fun requireExactFields(
        value: JsonObject,
        fieldName: String,
        allowed: Set<String>,
        required: Set<String>,
    ) {
        val unknown = value.keys - allowed
        if (unknown.isNotEmpty()) {
            throw ThirdPartyServiceException(
                "$fieldName contains unknown fields: ${unknown.sorted().joinToString()}",
            )
        }
        val missing = required - value.keys
        if (missing.isNotEmpty()) {
            throw ThirdPartyServiceException(
                "$fieldName is missing fields: ${missing.sorted().joinToString()}",
            )
        }
    }

    private fun validateRawObjectShape(
        value: JsonElement?,
        fieldName: String,
        allowed: Set<String>,
        required: Set<String>,
        optionalObject: Boolean = false,
    ) {
        if (value == null && optionalObject) return
        val objectValue = value as? JsonObject
            ?: throw ThirdPartyServiceException("$fieldName must be an object")
        requireExactFields(objectValue, fieldName, allowed, required)
    }

    private fun requireNonEmptyArray(value: JsonElement?, fieldName: String) {
        val array = value as? JsonArray
            ?: throw ThirdPartyServiceException("$fieldName must be an array")
        if (array.isEmpty()) {
            throw ThirdPartyServiceException("$fieldName must be omitted instead of empty")
        }
    }

    private fun normalizeAssetPath(value: String): String =
        value.trim().replace('\\', '/').split('/').filter(String::isNotBlank).joinToString("/")
}
