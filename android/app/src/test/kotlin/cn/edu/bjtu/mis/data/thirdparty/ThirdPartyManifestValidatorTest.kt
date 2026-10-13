package cn.edu.bjtu.mis.data.thirdparty

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdPartyManifestValidatorTest {
    @Test
    fun appAutostartIsOnlyAllowedAsOptionalDeclaration() {
        val manifest = ThirdPartyManifestValidator.decodeAndValidate(
            minimalManifest(optional = """["android.app.autoStart@1"]"""),
        )
        assertEquals(listOf("android.app.autoStart@1"), manifest.optionalCapabilities)
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.decodeAndValidate(
                minimalManifest(required = """["runtime.lifecycle@1","android.app.autoStart@1"]"""),
            )
        }
    }

    @Test
    fun acceptsMinimalContractManifestAndDerivesHostInvariants() {
        val manifest = ThirdPartyManifestValidator.decodeAndValidate(minimalManifest())

        assertEquals("bjtu.demo", manifest.id)
        assertEquals(listOf("runtime.lifecycle@1"), manifest.requiredCapabilities)
        assertEquals(2, manifest.minRuntimeVersion)
        assertEquals(listOf("self"), manifest.bridgeOrigins)
        assertTrue(manifest.optionalCapabilities.isEmpty())
        assertTrue(manifest.remoteOrigins.isEmpty())
        assertNull(manifest.marketplace)
    }

    @Test
    fun rejectsP0aAndAuthorControlledSecurityFields() {
        listOf(
            "\"permissions\":{\"required\":[],\"optional\":[]}",
            "\"runtime_version\":1",
            "\"min_runtime_version\":1",
            "\"bridge_origins\":[\"self\"]",
            "\"marketplace\":{\"category\":\"other\",\"tags\":[]}",
        ).forEach { field ->
            assertThrows(ThirdPartyServiceException::class.java) {
                ThirdPartyManifestValidator.decodeAndValidate(
                    minimalManifest().dropLast(1) + ",$field}",
                )
            }
        }
    }

    @Test
    fun requiresLifecycleAndRejectsDuplicateOrUnknownCapabilities() {
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.decodeAndValidate(
                minimalManifest(required = """["identity.profile@1"]"""),
            )
        }
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.decodeAndValidate(
                minimalManifest(
                    required = """["runtime.lifecycle@1","identity.profile@1"]""",
                    optional = """["identity.profile@1"]""",
                ),
            )
        }
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.decodeAndValidate(
                minimalManifest(required = """["runtime.lifecycle@1","unknown@1"]"""),
            )
        }
    }

    @Test
    fun storageControlsDataSchemaAndMigrationEntrypoint() {
        val storageV1 = ThirdPartyManifestValidator.decodeAndValidate(
            minimalManifest(
                required = """["runtime.lifecycle@1","storage.kv@2"]""",
                tail = ""","data_schema_version":1""",
            ),
        )
        assertEquals(1, storageV1.dataSchemaVersion)

        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.decodeAndValidate(
                minimalManifest(required = """["runtime.lifecycle@1","storage.kv@2"]"""),
            )
        }
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.decodeAndValidate(
                minimalManifest(tail = ""","data_schema_version":1"""),
            )
        }
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.decodeAndValidate(
                minimalManifest(
                    required = """["runtime.lifecycle@1","storage.blob@1"]""",
                    tail = ""","data_schema_version":2""",
                ),
            )
        }
    }

    @Test
    fun configurationFramesAndNavigationRequireCapabilities() {
        val configuration =
            ""","configuration":[{"key":"API_URL","label":"API","description":"","type":"url","required":true}]"""
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.decodeAndValidate(minimalManifest(tail = configuration))
        }
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.decodeAndValidate(
                minimalManifest(tail = ""","origins":{"frame":["https://frame.example.com"]}"""),
            )
        }
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.decodeAndValidate(
                minimalManifest(
                    tail = ""","origins":{"navigation":["https://navigate.example.com"]}""",
                ),
            )
        }
    }

    @Test
    fun normalizesOriginsAndBlocksCampusAndPrivateTargets() {
        val manifest = ThirdPartyManifestValidator.decodeAndValidate(
            minimalManifest(
                required = """["runtime.lifecycle@1","network.request@1"]""",
                tail = ""","origins":{"connect":["https://API.Example.com/"]}""",
            ),
        )
        assertEquals(listOf("https://api.example.com"), manifest.connectOrigins)
        val explicitDefaultPort = ThirdPartyManifestValidator.decodeAndValidate(
            minimalManifest(
                required = """["runtime.lifecycle@1","network.request@1"]""",
                tail = ""","origins":{"connect":["https://api.example.com:443"]}""",
            ),
        )
        assertEquals(
            listOf("https://api.example.com"),
            explicitDefaultPort.connectOrigins,
        )

        listOf(
            "https://mis.bjtu.edu.cn",
            "https://192.168.1.10",
            "https://100.64.0.1",
            "https://127.0.0.1",
            "https://[fd00::1]",
        ).forEach { origin ->
            assertThrows(ThirdPartyServiceException::class.java) {
                ThirdPartyManifestValidator.decodeAndValidate(
                    minimalManifest(tail = ""","origins":{"connect":["$origin"]}"""),
                )
            }
        }
    }

    @Test
    fun localhostHttpRequiresExplicitDeveloperMode() {
        val raw = minimalManifest(
            tail = ""","origins":{"connect":["http://localhost:4173"]}""",
        )
        assertThrows(ThirdPartyServiceException::class.java) {
            ThirdPartyManifestValidator.decodeAndValidate(raw)
        }
        val manifest = ThirdPartyManifestValidator.decodeAndValidate(
            raw,
            allowDevelopmentOrigins = true,
        )
        assertEquals(listOf("http://localhost:4173"), manifest.connectOrigins)
    }

    @Test
    fun validatesAssetsInsideDist() {
        val packageRoot = createTempDirectory("plugin-contract").toFile()
        try {
            val dist = File(packageRoot, "dist").apply { mkdirs() }
            File(dist, "index.html").writeText("<html></html>")
            File(dist, "icon.svg").writeText("<svg></svg>")

            ThirdPartyManifestValidator.decodeAndValidate(minimalManifest(), packageRoot)

            File(dist, "icon.svg").writeBytes(ByteArray(0))
            assertThrows(ThirdPartyServiceException::class.java) {
                ThirdPartyManifestValidator.decodeAndValidate(minimalManifest(), packageRoot)
            }
        } finally {
            packageRoot.deleteRecursively()
        }
    }

    @Test
    fun validatesSeparateMarketplaceMetadata() {
        val metadata = ThirdPartyManifestValidator.decodeAndValidateMarketplace(
            """
            {
              "description": "Demo plugin",
              "author": "Alice",
              "category": "Productivity",
              "tags": ["API", "效率"],
              "license": "MIT",
              "screenshots": [{"src":"shots/home.png","alt":"Home screen"}]
            }
            """.trimIndent(),
        )

        assertEquals("productivity", metadata.category)
        assertEquals("Alice", metadata.author)
        assertEquals(1, metadata.screenshots.size)
    }

    @Test
    fun rejectsInvalidIdsSemverPathsAndEmptyOptionalObjects() {
        listOf(
            minimalManifest(id = "Bad Id"),
            minimalManifest(version = "latest"),
            minimalManifest(entrypoint = "../index.html"),
            minimalManifest(icon = "icon.txt"),
            minimalManifest(optional = "[]"),
            minimalManifest(tail = ""","origins":{}"""),
        ).forEach { raw ->
            assertThrows(ThirdPartyServiceException::class.java) {
                ThirdPartyManifestValidator.decodeAndValidate(raw)
            }
        }
    }

    private fun minimalManifest(
        id: String = "bjtu.demo",
        version: String = "1.0.0",
        entrypoint: String = "index.html",
        icon: String = "icon.svg",
        required: String = """["runtime.lifecycle@1"]""",
        optional: String? = null,
        tail: String = "",
    ): String =
        """
        {
          "schema_version": 3,
          "id": "$id",
          "name": "Demo",
          "version": "$version",
          "entrypoint": "$entrypoint",
          "icon": "$icon",
          "capabilities": {
            "required": $required${optional?.let { ""","optional":$it""" }.orEmpty()}
          }$tail
        }
        """.trimIndent()
}
