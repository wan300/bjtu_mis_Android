package cn.edu.bjtu.mis.data.thirdparty

import cn.edu.bjtu.mis.data.AppJson
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThirdPartyManifestSchemaSyncTest {
    @Test
    fun generatedSchemasMatchCapabilityRegistry() {
        val docsSchema = repoFile("docs/third-party-service-manifest.schema.json")
        val webSchema = repoFile("web/assets/schemas/third-party-service-manifest.schema.json")
        val docsMarketplaceSchema = repoFile("docs/bjtu-marketplace.schema.json")
        val webMarketplaceSchema = repoFile("web/assets/schemas/bjtu-marketplace.schema.json")

        assertTrue("Missing docs schema", docsSchema.isFile)
        assertTrue("Missing web schema", webSchema.isFile)
        assertEquals(docsSchema.readText(), webSchema.readText())
        assertTrue("Missing docs marketplace schema", docsMarketplaceSchema.isFile)
        assertTrue("Missing web marketplace schema", webMarketplaceSchema.isFile)
        assertEquals(docsMarketplaceSchema.readText(), webMarketplaceSchema.readText())

        val schema = AppJson.parseToJsonElement(docsSchema.readText()).jsonObject
        val enumValues = schema["properties"]!!.jsonObject["capabilities"]!!
            .jsonObject["properties"]!!
            .jsonObject["required"]!!
            .jsonObject["items"]!!
            .jsonObject["enum"]!!
            .jsonArray
            .map { it.jsonPrimitive.contentOrNull.orEmpty() }
            .toSet()

        assertEquals(
            ThirdPartyCapabilityRegistry.capabilities
                .filterNot { it.optionalOnly }
                .map { it.id }
                .toSet(),
            enumValues,
        )
    }

    private fun repoFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("../$relativePath"),
            File("../../$relativePath"),
        )
        return candidates.firstOrNull { it.isFile } ?: candidates.last()
    }
}
