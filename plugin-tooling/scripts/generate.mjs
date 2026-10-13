import { readFile, mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const toolingRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repositoryRoot = path.resolve(toolingRoot, '..');
const registryPath = path.join(toolingRoot, 'contracts', 'capability-contracts.json');
const registry = JSON.parse(await readFile(registryPath, 'utf8'));
const checkOnly = process.argv.includes('--check');

validateRegistry(registry);

const outputs = new Map();
const manifestSchema = buildManifestSchema(registry);
const marketplaceSchema = buildMarketplaceSchema(registry);
const manifestSchemaText = json(manifestSchema);
const marketplaceSchemaText = json(marketplaceSchema);

outputs.set('docs/third-party-service-manifest.schema.json', manifestSchemaText);
outputs.set('web/assets/schemas/third-party-service-manifest.schema.json', manifestSchemaText);
outputs.set('docs/bjtu-marketplace.schema.json', marketplaceSchemaText);
outputs.set('web/assets/schemas/bjtu-marketplace.schema.json', marketplaceSchemaText);
outputs.set('plugin-tooling/packages/plugin-sdk/src/generated/contracts.ts', generateTypeScript(registry));
outputs.set(
  'android/app/src/main/java/cn/edu/bjtu/mis/data/thirdparty/generated/GeneratedCapabilityContracts.kt',
  generateKotlin(registry)
);
outputs.set(
  'web/platform/src/generated/plugin-contract.ts',
  generatePlatformContract(registry)
);
outputs.set('docs/generated/plugin-capability-api.md', generateMarkdown(registry));

const drift = [];
for (const [relativePath, content] of outputs) {
  const absolutePath = path.join(repositoryRoot, relativePath);
  if (checkOnly) {
    const current = await readFile(absolutePath, 'utf8').catch(() => null);
    if (current !== content) drift.push(relativePath);
  } else {
    const current = await readFile(absolutePath, 'utf8').catch(() => null);
    if (current === content) continue;
    await mkdir(path.dirname(absolutePath), { recursive: true });
    await writeFile(absolutePath, content, 'utf8');
  }
}

if (drift.length) {
  process.stderr.write(
    `Generated contract artifacts are out of date:\n${drift.map((item) => `- ${item}`).join('\n')}\n` +
      'Run npm run generate from plugin-tooling and commit every generated artifact.\n'
  );
  process.exitCode = 1;
} else {
  process.stdout.write(
    checkOnly
      ? `Contract generation check passed (${outputs.size} artifacts).\n`
      : `Generated ${outputs.size} contract artifacts.\n`
  );
}

function validateRegistry(value) {
  if (!value || typeof value !== 'object') throw new Error('Contract registry must be an object.');
  if (value.contractProfile !== 'contract_v1') throw new Error('Unsupported contract profile.');
  if (value.schemaVersion !== 3 || value.protocolVersion !== 2) {
    throw new Error('contract_v1 requires Manifest schema 3 and protocol 2.');
  }
  for (const [name, limit] of Object.entries(value.packageLimits ?? {})) {
    if (!Number.isSafeInteger(limit) || limit <= 0) {
      throw new Error(`Package limit ${name} must be a positive safe integer.`);
    }
  }
  for (const requiredLimit of ['archiveBytes', 'extractedBytes', 'files', 'iconBytes']) {
    if (!(requiredLimit in (value.packageLimits ?? {}))) {
      throw new Error(`Missing package limit: ${requiredLimit}`);
    }
  }
  const ids = new Set();
  const routes = new Set();
  for (const capability of value.capabilities ?? []) {
    if (!/^[a-z][A-Za-z0-9.]*@[1-9][0-9]*$/.test(capability.id)) {
      throw new Error(`Invalid capability id: ${capability.id}`);
    }
    if (ids.has(capability.id)) throw new Error(`Duplicate capability id: ${capability.id}`);
    if (capability.optionalOnly === true && (capability.methods?.length ?? 0) > 0) {
      throw new Error(`Optional-only capability ${capability.id} cannot declare methods.`);
    }
    ids.add(capability.id);
    const methodNames = new Set();
    for (const method of capability.methods ?? []) {
      if (!/^[a-z][A-Za-z0-9]*$/.test(method.name)) {
        throw new Error(`Invalid method name: ${capability.id}/${method.name}`);
      }
      if (methodNames.has(method.name)) {
        throw new Error(`Duplicate method: ${capability.id}/${method.name}`);
      }
      methodNames.add(method.name);
      const route = `${capability.id}#${method.name}`;
      if (routes.has(route)) throw new Error(`Duplicate route: ${route}`);
      routes.add(route);
      for (const code of method.errors ?? []) {
        if (!value.errors.includes(code)) {
          throw new Error(`Unknown error ${code} on ${route}`);
        }
      }
    }
    const eventNames = new Set();
    for (const event of capability.events ?? []) {
      if (!/^[a-z][A-Za-z0-9]*$/.test(event.name)) {
        throw new Error(`Invalid event name: ${capability.id}/${event.name}`);
      }
      if (eventNames.has(event.name)) {
        throw new Error(`Duplicate event: ${capability.id}/${event.name}`);
      }
      eventNames.add(event.name);
      expandSchema(event.data, value);
    }
    if (capability.confirmation === 'eachCall' && capability.idempotency !== 'required') {
      throw new Error(`Command capability ${capability.id} must require idempotency.`);
    }
  }
  if (!ids.has('runtime.lifecycle@1')) throw new Error('runtime.lifecycle@1 is required.');
}

function buildManifestSchema(value) {
  const capabilityIds = value.capabilities.map((item) => item.id);
  const requiredCapabilityIds = value.capabilities
    .filter((item) => item.optionalOnly !== true)
    .map((item) => item.id);
  const dataCapabilities = ['storage.kv@2', 'storage.blob@1'];
  const capabilityContains = (ids) => ({
    anyOf: [
      {
        properties: {
          required: {
            contains: {
              enum: ids
            }
          }
        },
        required: ['required']
      },
      {
        properties: {
          optional: {
            contains: {
              enum: ids
            }
          }
        },
        required: ['optional']
      }
    ]
  });
  return {
    $schema: 'https://json-schema.org/draft/2020-12/schema',
    $id: 'https://bjtu.cc/schemas/bjtu-plugin.schema.json',
    title: 'BJTU MIS Plugin Manifest v3 contract_v1',
    type: 'object',
    additionalProperties: false,
    required: ['schema_version', 'id', 'name', 'version', 'entrypoint', 'icon', 'capabilities'],
    properties: {
      schema_version: {
        const: value.schemaVersion
      },
      id: {
        type: 'string',
        pattern: '^[a-z][a-z0-9_.-]{2,63}$'
      },
      name: {
        type: 'string',
        minLength: 1,
        maxLength: 80
      },
      version: {
        type: 'string',
        maxLength: 40,
        pattern:
          '^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?(\\+[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?$'
      },
      entrypoint: {
        $ref: '#/$defs/asset_path'
      },
      icon: {
        allOf: [
          {
            $ref: '#/$defs/asset_path'
          },
          {
            pattern: '\\.(?:[sS][vV][gG]|[pP][nN][gG]|[wW][eE][bB][pP]|[jJ][pP][eE]?[gG])$'
          }
        ]
      },
      capabilities: {
        type: 'object',
        additionalProperties: false,
        required: ['required'],
        properties: {
          required: {
            type: 'array',
            minItems: 1,
            uniqueItems: true,
            contains: {
              const: 'runtime.lifecycle@1'
            },
            items: {
              enum: requiredCapabilityIds
            }
          },
          optional: {
            type: 'array',
            minItems: 1,
            uniqueItems: true,
            items: {
              enum: capabilityIds
            }
          }
        }
      },
      origins: {
        type: 'object',
        additionalProperties: false,
        minProperties: 1,
        properties: {
          connect: {
            $ref: '#/$defs/origin_array'
          },
          media: {
            $ref: '#/$defs/origin_array'
          },
          frame: {
            $ref: '#/$defs/origin_array'
          },
          navigation: {
            $ref: '#/$defs/origin_array'
          }
        }
      },
      data_schema_version: {
        type: 'integer',
        minimum: 1
      },
      migration_entrypoint: {
        $ref: '#/$defs/asset_path'
      },
      configuration: {
        type: 'array',
        minItems: 1,
        maxItems: 32,
        items: {
          $ref: '#/$defs/configuration'
        }
      }
    },
    allOf: [
      {
        if: {
          properties: {
            capabilities: capabilityContains(dataCapabilities)
          }
        },
        then: {
          required: ['data_schema_version']
        },
        else: {
          not: {
            anyOf: [
              {
                required: ['data_schema_version']
              },
              {
                required: ['migration_entrypoint']
              }
            ]
          }
        }
      },
      {
        if: {
          properties: {
            data_schema_version: {
              minimum: 2
            }
          },
          required: ['data_schema_version']
        },
        then: {
          required: ['migration_entrypoint']
        }
      },
      {
        if: {
          properties: {
            origins: {
              required: ['frame']
            }
          },
          required: ['origins']
        },
        then: {
          properties: {
            capabilities: capabilityContains(['remote.frame@1'])
          }
        }
      },
      {
        if: {
          properties: {
            origins: {
              required: ['navigation']
            }
          },
          required: ['origins']
        },
        then: {
          properties: {
            capabilities: capabilityContains(['navigation.external@1'])
          }
        }
      },
      {
        if: {
          required: ['configuration']
        },
        then: {
          properties: {
            capabilities: capabilityContains(['configuration.read@1'])
          }
        }
      }
    ],
    $defs: {
      asset_path: {
        type: 'string',
        minLength: 1,
        not: {
          anyOf: [
            {
              pattern: '^/'
            },
            {
              pattern: '(^|/)\\.\\.(/|$)'
            },
            {
              pattern: '\\\\'
            },
            {
              pattern: ':'
            }
          ]
        }
      },
      origin_array: {
        type: 'array',
        minItems: 1,
        uniqueItems: true,
        items: {
          type: 'string',
          pattern: '^https://[A-Za-z0-9.-]+(:[0-9]+)?$'
        }
      },
      configuration: {
        type: 'object',
        additionalProperties: false,
        required: ['key', 'label', 'description', 'type', 'required'],
        properties: {
          key: {
            type: 'string',
            pattern: '^[A-Z][A-Z0-9_]{0,63}$'
          },
          label: {
            type: 'string',
            minLength: 1,
            maxLength: 80
          },
          description: {
            type: 'string',
            maxLength: 240
          },
          type: {
            enum: value.configurationTypes
          },
          required: {
            type: 'boolean'
          },
          default: {
            type: 'string'
          },
          options: {
            type: 'array',
            minItems: 1,
            maxItems: 20,
            uniqueItems: true,
            items: {
              type: 'string',
              minLength: 1
            }
          }
        }
      }
    }
  };
}

function buildMarketplaceSchema(value) {
  return {
    $schema: 'https://json-schema.org/draft/2020-12/schema',
    $id: 'https://bjtu.cc/schemas/bjtu-marketplace.schema.json',
    title: 'BJTU MIS Plugin Marketplace Metadata',
    type: 'object',
    additionalProperties: false,
    required: ['description', 'author', 'category', 'tags'],
    properties: {
      description: {
        type: 'string',
        minLength: 1,
        maxLength: 400
      },
      author: {
        type: 'string',
        minLength: 1,
        maxLength: 120
      },
      category: {
        enum: value.marketplaceCategories
      },
      tags: {
        type: 'array',
        maxItems: 5,
        uniqueItems: true,
        items: {
          type: 'string',
          minLength: 1,
          maxLength: 20
        }
      },
      license: {
        type: 'string',
        minLength: 1,
        maxLength: 80
      },
      screenshots: {
        type: 'array',
        maxItems: 8,
        items: {
          type: 'object',
          additionalProperties: false,
          required: ['src', 'alt'],
          properties: {
            src: {
              type: 'string',
              minLength: 1
            },
            alt: {
              type: 'string',
              minLength: 1,
              maxLength: 160
            }
          }
        }
      }
    }
  };
}

function generateTypeScript(value) {
  const lines = [
    '/* This file is generated from plugin-tooling/contracts/capability-contracts.json. */',
    '/* Do not edit by hand. Run `npm run generate` from plugin-tooling. */',
    '',
    `export const CONTRACT_PROFILE = ${JSON.stringify(value.contractProfile)} as const;`,
    `export const MANIFEST_SCHEMA_VERSION = ${value.schemaVersion} as const;`,
    `export const PROTOCOL_VERSION = ${value.protocolVersion} as const;`,
    `export const RUNTIME_FLOOR = ${value.runtimeFloor} as const;`,
    `export const PACKAGE_LIMITS = ${JSON.stringify(value.packageLimits, null, 2)} as const;`,
    `export const PLUGIN_ERROR_CODES = ${JSON.stringify(value.errors, null, 2)} as const;`,
    `export type PluginErrorCode = (typeof PLUGIN_ERROR_CODES)[number];`,
    `export const CAPABILITY_IDS = ${JSON.stringify(value.capabilities.map((item) => item.id), null, 2)} as const;`,
    `export type CapabilityId = (typeof CAPABILITY_IDS)[number];`,
    '',
    'export interface CapabilityMethodMap {'
  ];
  for (const capability of value.capabilities) {
    for (const method of capability.methods) {
      const request = schemaToTypeScript(expandSchema(method.request, value));
      const response = schemaToTypeScript(expandSchema(method.response, value));
      lines.push(
        `  ${JSON.stringify(`${capability.id}#${method.name}`)}: { request: ${request}; response: ${response} };`
      );
    }
  }
  lines.push('}', '', 'export interface CapabilityEventMap {');
  for (const capability of value.capabilities) {
    for (const event of capability.events ?? []) {
      const data = schemaToTypeScript(expandSchema(event.data, value));
      lines.push(
        `  ${JSON.stringify(`${capability.id}#${event.name}`)}: { data: ${data}; requiresAcknowledgement: ${event.requiresAcknowledgement === true} };`
      );
    }
  }
  const mockResponses = Object.fromEntries(
    value.capabilities.flatMap((capability) =>
      capability.methods.map((method) => [
        `${capability.id}#${method.name}`,
        mockValueForSchema(expandSchema(method.response, value))
      ])
    )
  );
  lines.push(
    '}',
    '',
    'export type CapabilityRoute = keyof CapabilityMethodMap;',
    'export type CapabilityRequest<Route extends CapabilityRoute> = CapabilityMethodMap[Route]["request"];',
    'export type CapabilityResponse<Route extends CapabilityRoute> = CapabilityMethodMap[Route]["response"];',
    'export type CapabilityEventRoute = keyof CapabilityEventMap;',
    'export type CapabilityEventData<Route extends CapabilityEventRoute> = CapabilityEventMap[Route]["data"];',
    'export type CapabilityEventAcknowledgement<Route extends CapabilityEventRoute> = CapabilityEventMap[Route]["requiresAcknowledgement"];',
    '',
    `export const CAPABILITY_MOCK_RESPONSES = ${JSON.stringify(mockResponses, null, 2)} as const;`,
    '',
    `export const CAPABILITY_REGISTRY = ${JSON.stringify(value, null, 2)} as const;`
  );
  return `${lines.join('\n')}\n`;
}

function generateKotlin(value) {
  const descriptors = value.capabilities
    .map((capability) => {
      const features = capability.support.webViewFeatures
        .map((feature) => kotlinString(feature))
        .join(', ');
      return [
        '        GeneratedCapabilityDescriptor(',
        `            id = ${kotlinString(capability.id)},`,
        `            stability = ${kotlinString(capability.stability)},`,
        `            runtimeFloor = ${capability.runtimeFloor},`,
        `            optionalOnly = ${capability.optionalOnly === true},`,
        `            permission = ${capability.permission ? kotlinString(capability.permission.id) : 'null'},`,
        `            permissionTitle = ${capability.permission ? kotlinString(capability.permission.title) : 'null'},`,
        `            permissionDescription = ${capability.permission ? kotlinString(capability.permission.description) : 'null'},`,
        `            confirmation = ${kotlinString(capability.confirmation)},`,
        `            idempotencyRequired = ${capability.idempotency === 'required'},`,
        `            quotaJson = ${capability.quota ? kotlinString(JSON.stringify(capability.quota)) : 'null'},`,
        `            timeoutMs = ${capability.timeoutMs}L,`,
        `            maxTimeoutMs = ${(capability.maxTimeoutMs ?? capability.timeoutMs)}L,`,
        `            androidMinApi = ${capability.support.androidMinApi},`,
        `            webViewFeatures = setOf(${features}),`,
        '        )'
      ].join('\n');
    })
    .join(',\n');
  const routes = [];
  const events = [];
  for (const capability of value.capabilities) {
    for (const method of capability.methods) {
      const requestSchema = expandSchema(method.request, value);
      const responseSchema = expandSchema(method.response, value);
      const required = (requestSchema.required ?? []).map(kotlinString).join(', ');
      const properties = Object.entries(requestSchema.properties ?? {})
        .map(([name, property]) => `${kotlinString(name)} to ${kotlinString(schemaType(property))}`)
        .join(', ');
      const responseRequired = (responseSchema.required ?? []).map(kotlinString).join(', ');
      const responseProperties = Object.entries(responseSchema.properties ?? {})
        .map(([name, property]) => `${kotlinString(name)} to ${kotlinString(schemaType(property))}`)
        .join(', ');
      const errors = methodErrorCodes(capability, method);
      routes.push(
        [
          `        ${kotlinString(`${capability.id}#${method.name}`)} to GeneratedCapabilityRoute(`,
          `            capability = ${kotlinString(capability.id)},`,
          `            method = ${kotlinString(method.name)},`,
          `            requiredFields = setOf(${required}),`,
          `            propertyTypes = mapOf(${properties}),`,
          `            additionalProperties = ${requestSchema.additionalProperties !== false},`,
          `            responseType = ${kotlinString(schemaType(responseSchema))},`,
          `            responseRequiredFields = setOf(${responseRequired}),`,
          `            responsePropertyTypes = mapOf(${responseProperties}),`,
          `            responseAdditionalProperties = ${responseSchema.additionalProperties !== false},`,
          `            requestSchema = Json.parseToJsonElement(${kotlinString(JSON.stringify(requestSchema))}).jsonObject,`,
          `            responseSchema = Json.parseToJsonElement(${kotlinString(JSON.stringify(responseSchema))}).jsonObject,`,
          `            errors = setOf(${errors.map(kotlinString).join(', ')}),`,
          '        )'
        ].join('\n')
      );
    }
    for (const event of capability.events ?? []) {
      const eventSchema = expandSchema(event.data, value);
      events.push(
        [
          `        ${kotlinString(`${capability.id}#${event.name}`)} to GeneratedCapabilityEvent(`,
          `            capability = ${kotlinString(capability.id)},`,
          `            event = ${kotlinString(event.name)},`,
          `            dataSchema = Json.parseToJsonElement(${kotlinString(JSON.stringify(eventSchema))}).jsonObject,`,
          `            requiresAcknowledgement = ${event.requiresAcknowledgement === true},`,
          '        )'
        ].join('\n')
      );
    }
  }
  return `/* This file is generated from plugin-tooling/contracts/capability-contracts.json. */
/* Do not edit by hand. Run \`npm run generate\` from plugin-tooling. */
package cn.edu.bjtu.mis.data.thirdparty.generated

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

data class GeneratedCapabilityDescriptor(
    val id: String,
    val stability: String,
    val runtimeFloor: Int,
    val optionalOnly: Boolean,
    val permission: String?,
    val permissionTitle: String?,
    val permissionDescription: String?,
    val confirmation: String,
    val idempotencyRequired: Boolean,
    val quotaJson: String?,
    val timeoutMs: Long,
    val maxTimeoutMs: Long,
    val androidMinApi: Int,
    val webViewFeatures: Set<String>,
)

data class GeneratedCapabilityRoute(
    val capability: String,
    val method: String,
    val requiredFields: Set<String>,
    val propertyTypes: Map<String, String>,
    val additionalProperties: Boolean,
    val responseType: String,
    val responseRequiredFields: Set<String>,
    val responsePropertyTypes: Map<String, String>,
    val responseAdditionalProperties: Boolean,
    val requestSchema: JsonObject,
    val responseSchema: JsonObject,
    val errors: Set<String>,
)

data class GeneratedCapabilityEvent(
    val capability: String,
    val event: String,
    val dataSchema: JsonObject,
    val requiresAcknowledgement: Boolean,
)

object GeneratedCapabilityContracts {
    const val CONTRACT_PROFILE = ${kotlinString(value.contractProfile)}
    const val MANIFEST_SCHEMA_VERSION = ${value.schemaVersion}
    const val PROTOCOL_VERSION = ${value.protocolVersion}
    const val RUNTIME_FLOOR = ${value.runtimeFloor}

    val errorCodes: Set<String> = setOf(${value.errors.map(kotlinString).join(', ')})

    val capabilities: List<GeneratedCapabilityDescriptor> = listOf(
${descriptors}
    )

    val capabilityIds: Set<String> = capabilities.mapTo(linkedSetOf()) { it.id }

    val routes: Map<String, GeneratedCapabilityRoute> = mapOf(
${routes.join(',\n')}
    )

    val events: Map<String, GeneratedCapabilityEvent> = mapOf(
${events.join(',\n')}
    )

    fun descriptor(capability: String): GeneratedCapabilityDescriptor? =
        capabilities.firstOrNull { it.id == capability }

    fun route(capability: String, method: String): GeneratedCapabilityRoute? =
        routes["$capability#$method"]

    fun eventDescriptor(capability: String, event: String): GeneratedCapabilityEvent? =
        events["$capability#$event"]

    fun validateRequest(
        capability: String,
        method: String,
        params: JsonObject,
    ): List<String> {
        val route = route(capability, method)
            ?: return listOf("Unknown capability route: $capability#$method")
        return validateSchema(params, route.requestSchema, "Request")
    }

    fun validateResponse(
        capability: String,
        method: String,
        result: JsonElement,
    ): List<String> {
        val route = route(capability, method)
            ?: return listOf("Unknown capability route: $capability#$method")
        return validateSchema(result, route.responseSchema, "Response")
    }

    fun validateEvent(
        capability: String,
        event: String,
        data: JsonElement,
    ): List<String> {
        val descriptor = eventDescriptor(capability, event)
            ?: return listOf("Unknown capability event: $capability#$event")
        return validateSchema(data, descriptor.dataSchema, "Event")
    }

    private fun validateSchema(
        value: JsonElement,
        schema: JsonObject,
        path: String,
    ): List<String> {
        val errors = mutableListOf<String>()
        val expectedTypes = when (val type = schema["type"]) {
            is JsonPrimitive -> listOfNotNull(type.contentOrNull)
            is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }
        if (expectedTypes.isNotEmpty() && expectedTypes.none { matchesType(value, it) }) {
            return listOf("$path must be \${expectedTypes.joinToString("|")}")
        }
        schema["const"]?.let { expected ->
            if (value != expected) errors += "$path must equal $expected"
        }
        (schema["enum"] as? JsonArray)?.let { choices ->
            if (value !in choices) errors += "$path must be one of the declared enum values"
        }
        when (value) {
            is JsonObject -> {
                val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                val required = (schema["required"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
                required.filterNot(value::containsKey).forEach {
                    errors += "$path missing required field: $it"
                }
                if ((schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false) {
                    (value.keys - properties.keys).sorted().forEach {
                        errors += "$path has unknown field: $it"
                    }
                }
                properties.forEach { (name, childSchema) ->
                    val child = value[name] ?: return@forEach
                    val childObject = childSchema as? JsonObject ?: return@forEach
                    errors += validateSchema(child, childObject, "$path.$name")
                }
            }
            is JsonArray -> {
                val minimum = (schema["minItems"] as? JsonPrimitive)?.intOrNull
                val maximum = (schema["maxItems"] as? JsonPrimitive)?.intOrNull
                if (minimum != null && value.size < minimum) {
                    errors += "$path must contain at least $minimum items"
                }
                if (maximum != null && value.size > maximum) {
                    errors += "$path must contain at most $maximum items"
                }
                if ((schema["uniqueItems"] as? JsonPrimitive)?.booleanOrNull == true &&
                    value.size != value.toSet().size
                ) {
                    errors += "$path must contain unique items"
                }
                (schema["items"] as? JsonObject)?.let { itemSchema ->
                    value.forEachIndexed { index, child ->
                        errors += validateSchema(child, itemSchema, "$path[$index]")
                    }
                }
            }
            is JsonPrimitive -> {
                if (value.isString) {
                    val text = value.content
                    val minimum = (schema["minLength"] as? JsonPrimitive)?.intOrNull
                    val maximum = (schema["maxLength"] as? JsonPrimitive)?.intOrNull
                    if (minimum != null && text.length < minimum) {
                        errors += "$path must contain at least $minimum characters"
                    }
                    if (maximum != null && text.length > maximum) {
                        errors += "$path must contain at most $maximum characters"
                    }
                    (schema["pattern"] as? JsonPrimitive)?.contentOrNull?.let { pattern ->
                        if (!Regex(pattern).containsMatchIn(text)) {
                            errors += "$path does not match the required pattern"
                        }
                    }
                } else {
                    value.doubleOrNull?.let { number ->
                        val minimum = (schema["minimum"] as? JsonPrimitive)?.doubleOrNull
                        val maximum = (schema["maximum"] as? JsonPrimitive)?.doubleOrNull
                        if (minimum != null && number < minimum) {
                            errors += "$path must be at least $minimum"
                        }
                        if (maximum != null && number > maximum) {
                            errors += "$path must be at most $maximum"
                        }
                    }
                }
            }
            JsonNull -> Unit
        }
        return errors
    }

    private fun matchesType(value: JsonElement, expectedType: String): Boolean =
        expectedType.split('|').any { type ->
            when (type) {
                "any" -> true
                "null" -> value is JsonNull
                "object" -> value is JsonObject
                "array" -> value is JsonArray
                "string" -> value is JsonPrimitive && value.isString
                "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
                "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
                "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
                else -> true
            }
        }
}
`;
}

function generatePlatformContract(value) {
  return `/* This file is generated from plugin-tooling/contracts/capability-contracts.json. */
/* Do not edit by hand. Run \`npm run generate\` from plugin-tooling. */

export const PLUGIN_CONTRACT_PROFILE = ${JSON.stringify(value.contractProfile)} as const;
export const PLUGIN_PROTOCOL_VERSION = ${value.protocolVersion} as const;
export const PLUGIN_PACKAGE_LIMITS = ${JSON.stringify(value.packageLimits, null, 2)} as const;
`;
}

function generateMarkdown(value) {
  const lines = [
    '<!-- Generated from plugin-tooling/contracts/capability-contracts.json. Do not edit. -->',
    '',
    '# BJTU Plugin Capability API',
    '',
    `Contract profile: \`${value.contractProfile}\` · Protocol: \`v${value.protocolVersion}\` · Runtime floor: \`${value.runtimeFloor}\``,
    '',
    'SDK calls use camelCase. Read methods return `{ data, meta }`; `meta` contains `syncedAt`, `source`, `coverage`, and `fromCache`.',
    '',
    '| Capability | Stability | Permission | Confirmation | Timeout | Methods |',
    '| --- | --- | --- | --- | ---: | --- |'
  ];
  for (const capability of value.capabilities) {
    lines.push(
      `| \`${capability.id}\` | ${capability.stability} | ${capability.permission ? `\`${capability.permission.id}\`` : 'none'} | ${capability.confirmation} | ${capability.timeoutMs} ms | ${capability.methods.map((method) => `\`${method.name}\``).join(', ') || 'declaration only'} |`
    );
  }
  for (const capability of value.capabilities) {
    lines.push('', `## ${capability.id}`, '');
    if (capability.permission) {
      lines.push(`${capability.permission.title}：${capability.permission.description}`, '');
    }
    lines.push(
      `Stability: **${capability.stability}** · confirmation: **${capability.confirmation}** · idempotency: **${capability.idempotency}**`
    );
    if ((capability.events ?? []).length) {
      lines.push(
        '',
        `Events: ${capability.events.map((event) => `\`${event.name}\`${event.requiresAcknowledgement ? ' (acknowledged)' : ''}`).join(', ')}.`
      );
    }
    for (const method of capability.methods) {
      lines.push(
        '',
        `### ${method.name}`,
        '',
        'Request schema:',
        '',
        '```json',
        JSON.stringify(method.request, null, 2),
        '```',
        '',
        'Response schema:',
        '',
        '```json',
        JSON.stringify(method.response, null, 2),
        '```',
        '',
        `Errors: ${(methodErrorCodes(capability, method).length ? methodErrorCodes(capability, method) : ['none']).map((code) => `\`${code}\``).join(', ')}.`
      );
    }
  }
  return `${lines.join('\n')}\n`;
}

function resolveSchema(schema, value) {
  if (!schema || typeof schema !== 'object') return {};
  if (typeof schema.$ref === 'string' && schema.$ref.startsWith('#/schemas/')) {
    const name = schema.$ref.slice('#/schemas/'.length);
    const target = value.schemas[name];
    if (!target) throw new Error(`Unknown registry schema reference: ${schema.$ref}`);
    return target;
  }
  return schema;
}

function methodErrorCodes(capability, method) {
  return [...new Set([
    ...(capability.permission ? ['permission_denied'] : []),
    ...(capability.timeoutMs > 0 ? ['request_timeout'] : []),
    ...(method.errors ?? [])
  ])];
}

function expandSchema(schema, value, stack = []) {
  if (Array.isArray(schema)) {
    return schema.map((item) => expandSchema(item, value, stack));
  }
  if (!schema || typeof schema !== 'object') return schema;
  if (typeof schema.$ref === 'string' && schema.$ref.startsWith('#/schemas/')) {
    if (stack.includes(schema.$ref)) {
      throw new Error(`Recursive registry schema reference is unsupported: ${schema.$ref}`);
    }
    const resolved = expandSchema(resolveSchema(schema, value), value, [...stack, schema.$ref]);
    const siblings = Object.fromEntries(
      Object.entries(schema)
        .filter(([key]) => key !== '$ref')
        .map(([key, child]) => [key, expandSchema(child, value, stack)])
    );
    return { ...resolved, ...siblings };
  }
  return Object.fromEntries(
    Object.entries(schema).map(([key, child]) => [key, expandSchema(child, value, stack)])
  );
}

function schemaToTypeScript(schema) {
  if (!schema || Object.keys(schema).length === 0) return 'unknown';
  if (schema.$ref) return 'unknown';
  if (Array.isArray(schema.enum)) return schema.enum.map((item) => JSON.stringify(item)).join(' | ');
  if (Array.isArray(schema.type)) {
    return schema.type.map((type) => schemaToTypeScript({ ...schema, type })).join(' | ');
  }
  switch (schema.type) {
    case 'null':
      return 'null';
    case 'string':
      return 'string';
    case 'integer':
    case 'number':
      return 'number';
    case 'boolean':
      return 'boolean';
    case 'array':
      return `Array<${schemaToTypeScript(schema.items ?? {})}>`;
    case 'object': {
      const properties = schema.properties ?? {};
      if (Object.keys(properties).length === 0) {
        return schema.additionalProperties === false ? 'Record<string, never>' : 'Record<string, unknown>';
      }
      const required = new Set(schema.required ?? []);
      const fields = Object.entries(properties).map(
        ([name, property]) =>
          `${JSON.stringify(name)}${required.has(name) ? '' : '?'}: ${schemaToTypeScript(property)}`
      );
      if (schema.additionalProperties !== false) fields.push('[key: string]: unknown');
      return `{ ${fields.join('; ')} }`;
    }
    default:
      return 'unknown';
  }
}

function mockValueForSchema(schema) {
  if (!schema || typeof schema !== 'object') return null;
  if ('const' in schema) return schema.const;
  if (Array.isArray(schema.enum) && schema.enum.length) return schema.enum[0];
  const declaredType = Array.isArray(schema.type)
    ? (schema.type.find((type) => type !== 'null') ?? schema.type[0])
    : schema.type;
  switch (declaredType) {
    case 'null':
      return null;
    case 'string':
      return 'example';
    case 'integer':
    case 'number':
      return Math.max(0, schema.minimum ?? 0);
    case 'boolean':
      return false;
    case 'array':
      return Array.from(
        { length: schema.minItems ?? 0 },
        () => mockValueForSchema(schema.items ?? {})
      );
    case 'object':
      return Object.fromEntries(
        (schema.required ?? []).map((name) => [
          name,
          mockValueForSchema(schema.properties?.[name] ?? {})
        ])
      );
    default:
      return null;
  }
}

function schemaType(schema) {
  if (!schema || typeof schema !== 'object' || Object.keys(schema).length === 0) return 'any';
  if (schema.$ref) return 'any';
  if (Array.isArray(schema.type)) return schema.type.join('|');
  return schema.type ?? 'any';
}

function kotlinString(value) {
  return JSON.stringify(String(value)).replaceAll('$', '\\$');
}

function json(value) {
  return `${JSON.stringify(value, null, 2)}\n`;
}
