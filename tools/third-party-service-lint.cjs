#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const MANIFEST_FILE = 'bjtu-plugin.json';
const MARKETPLACE_FILE = 'bjtu-marketplace.json';
const DEV_FILE = 'bjtu-plugin.dev.json';
const MANIFEST_FIELDS = new Set([
  'schema_version',
  'id',
  'name',
  'version',
  'entrypoint',
  'icon',
  'capabilities',
  'origins',
  'data_schema_version',
  'migration_entrypoint',
  'configuration'
]);
const REQUIRED_FIELDS = [
  'schema_version',
  'id',
  'name',
  'version',
  'entrypoint',
  'icon',
  'capabilities'
];
const LEGACY_FIELDS = new Set([
  'permissions',
  'runtime_version',
  'min_runtime_version',
  'bridge_origins',
  'marketplace',
  'description',
  'author',
  'required_capabilities',
  'optional_capabilities',
  'connect_origins',
  'media_origins',
  'frame_origins',
  'navigation_origins',
  'allowed_origins'
]);
const CAMPUS_HOSTS = new Set([
  '123.121.147.7',
  'cas.bjtu.edu.cn',
  'mis.bjtu.edu.cn',
  'aa.bjtu.edu.cn',
  'mail.bjtu.edu.cn',
  'zhixing.bjtu.edu.cn',
  'job.bjtu.edu.cn',
  'bksycenter.bjtu.edu.cn'
]);
const TEXT_EXTENSIONS = new Set(['.html', '.htm', '.js', '.mjs', '.cjs', '.css', '.json']);

function main() {
  const args = process.argv.slice(2);
  const roots = args.filter((value) => !value.startsWith('-'));
  if (!roots.length) {
    process.stderr.write(
      'Usage: node tools/third-party-service-lint.cjs <plugin-root> [...] [--source] [--marketplace] [--dev]\n'
    );
    process.exitCode = 2;
    return;
  }
  const repositoryRoot = path.resolve(__dirname, '..');
  const schemaResult = { errors: [], warnings: [] };
  const schema = loadManifestSchema(repositoryRoot, schemaResult);
  if (!schema) {
    for (const error of schemaResult.errors) process.stderr.write(`error: ${error}\n`);
    process.exitCode = 1;
    return;
  }
  let failed = false;
  for (const root of roots) {
    const result = {
      errors: [...schemaResult.errors],
      warnings: [...schemaResult.warnings]
    };
    lintPlugin(path.resolve(root), result, schema, {
      requireMarketplace: args.includes('--marketplace'),
      allowDevelopmentOrigins: args.includes('--dev'),
      requireDist: !args.includes('--source')
    });
    for (const warning of result.warnings) process.stderr.write(`warning ${root}: ${warning}\n`);
    for (const error of result.errors) process.stderr.write(`error ${root}: ${error}\n`);
    if (result.errors.length) failed = true;
    else process.stdout.write(`ok ${root}\n`);
  }
  if (failed) process.exitCode = 1;
}

function loadManifestSchema(repositoryRoot, result) {
  const root = resolveRepositoryRoot(repositoryRoot);
  try {
    const manifestSchema = JSON.parse(
      fs.readFileSync(path.join(root, 'docs', 'third-party-service-manifest.schema.json'), 'utf8')
    );
    const marketplaceSchema = JSON.parse(
      fs.readFileSync(path.join(root, 'docs', 'bjtu-marketplace.schema.json'), 'utf8')
    );
    const registry = JSON.parse(
      fs.readFileSync(path.join(root, 'plugin-tooling', 'contracts', 'capability-contracts.json'), 'utf8')
    );
    return {
      permissions: registry.capabilities
        .map((item) => item.permission && item.permission.id)
        .filter(Boolean),
      capabilities: manifestSchema.properties.capabilities.properties.optional.items.enum,
      categories: marketplaceSchema.properties.category.enum,
      configurationTypes: manifestSchema.$defs.configuration.properties.type.enum,
      contractProfile: registry.contractProfile,
      protocolVersion: registry.protocolVersion,
      runtimeFloor: registry.runtimeFloor,
      packageLimits: registry.packageLimits,
      contracts: registry.capabilities
    };
  } catch (error) {
    result.errors.push(`Cannot load generated contract schemas: ${error.message}`);
    return null;
  }
}

function lintPlugin(root, result, schema, options = {}) {
  const manifestPath = path.join(root, MANIFEST_FILE);
  if (!isFile(manifestPath)) {
    if (isFile(path.join(root, 'bjtu-service.json'))) {
      result.errors.push('P0-A bjtu-service.json is rescue-only; run `bjtu migrate` and publish bjtu-plugin.json.');
    } else {
      result.errors.push(`Plugin root is missing ${MANIFEST_FILE}.`);
    }
    return result;
  }
  const manifest = readJson(manifestPath, result);
  if (!isObject(manifest)) {
    result.errors.push(`${MANIFEST_FILE} must be a JSON object.`);
    return result;
  }
  validateManifestShape(manifest, result);
  if (manifest.schema_version !== 3) result.errors.push('schema_version must be 3.');
  validateText(manifest.id, 'id', 64, result, /^[a-z][a-z0-9_.-]{2,63}$/);
  validateText(manifest.name, 'name', 80, result);
  validateText(
    manifest.version,
    'version',
    40,
    result,
    /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/
  );
  const entrypoint = validateAssetPath(manifest.entrypoint, 'entrypoint', result);
  const icon = validateAssetPath(manifest.icon, 'icon', result);
  if (icon && !/\.(svg|png|webp|jpe?g)$/i.test(icon)) {
    result.errors.push('Plugin icon must be SVG, PNG, WebP, JPG, or JPEG.');
  }

  const capabilityDeclaration = isObject(manifest.capabilities) ? manifest.capabilities : null;
  if (!capabilityDeclaration) {
    result.errors.push('capabilities must be an object.');
    return result;
  }
  validateObjectShape(
    capabilityDeclaration,
    'capabilities',
    new Set(['required', 'optional']),
    new Set(['required']),
    result
  );
  const requiredCapabilities = normalizeStringArray(
    capabilityDeclaration.required,
    'capabilities.required',
    result
  );
  const optionalCapabilities = capabilityDeclaration.optional === undefined
    ? []
    : normalizeStringArray(capabilityDeclaration.optional, 'capabilities.optional', result);
  if (!requiredCapabilities.length) result.errors.push('capabilities.required must not be empty.');
  if (capabilityDeclaration.optional !== undefined && !optionalCapabilities.length) {
    result.errors.push('capabilities.optional must be omitted when empty.');
  }
  for (const capability of [...requiredCapabilities, ...optionalCapabilities]) {
    if (!schema.capabilities.includes(capability)) result.errors.push(`Unknown capability: ${capability}`);
  }
  for (const capability of requiredCapabilities) {
    if (schema.contracts.find((item) => item.id === capability)?.optionalOnly === true) {
      result.errors.push(`${capability} may only appear in capabilities.optional.`);
    }
  }
  const duplicates = requiredCapabilities.filter((item) => optionalCapabilities.includes(item));
  if (duplicates.length) {
    result.errors.push(`Capabilities cannot be both required and optional: ${[...new Set(duplicates)].join(', ')}`);
  }
  if (!requiredCapabilities.includes('runtime.lifecycle@1')) {
    result.errors.push('runtime.lifecycle@1 must be in capabilities.required.');
  }
  if (optionalCapabilities.length) {
    result.warnings.push('Optional capabilities are disabled on first install until the user grants them.');
  }
  const allCapabilities = [...requiredCapabilities, ...optionalCapabilities];

  const normalizedOrigins = { connect: [], media: [], frame: [], navigation: [] };
  if (manifest.origins !== undefined) {
    if (!isObject(manifest.origins) || !Object.keys(manifest.origins).length) {
      result.errors.push('origins must be omitted when empty.');
    } else {
      validateObjectShape(
        manifest.origins,
        'origins',
        new Set(['connect', 'media', 'frame', 'navigation']),
        new Set(),
        result
      );
      for (const kind of Object.keys(normalizedOrigins)) {
        if (manifest.origins[kind] === undefined) continue;
        const values = normalizeStringArray(manifest.origins[kind], `origins.${kind}`, result);
        if (!values.length) result.errors.push(`origins.${kind} must be omitted when empty.`);
        normalizedOrigins[kind] = values
          .map((origin) =>
            normalizeOrigin(
              origin,
              result,
              `origins.${kind}`,
              kind !== 'navigation',
              options.allowDevelopmentOrigins === true
            )
          )
          .filter(Boolean);
      }
    }
  }
  if (normalizedOrigins.frame.length && !allCapabilities.includes('remote.frame@1')) {
    result.errors.push('origins.frame requires remote.frame@1.');
  }
  if (
    normalizedOrigins.navigation.length &&
    !allCapabilities.includes('navigation.external@1')
  ) {
    result.errors.push('origins.navigation requires navigation.external@1.');
  }
  if (allCapabilities.includes('network.request@1') && !normalizedOrigins.connect.length) {
    result.warnings.push('network.request@1 has no origins.connect entries and cannot reach public origins.');
  }

  const usesData = allCapabilities.some((item) =>
    item === 'storage.kv@2' || item === 'storage.blob@1'
  );
  if (usesData) {
    if (!Number.isSafeInteger(manifest.data_schema_version) || manifest.data_schema_version < 1) {
      result.errors.push('KV/Blob capabilities require a positive data_schema_version.');
    }
    if (manifest.data_schema_version > 1 && manifest.migration_entrypoint === undefined) {
      result.errors.push('data_schema_version > 1 requires migration_entrypoint.');
    }
  } else if (
    manifest.data_schema_version !== undefined ||
    manifest.migration_entrypoint !== undefined
  ) {
    result.errors.push('data_schema_version and migration_entrypoint are only valid with KV/Blob.');
  }
  const migrationEntrypoint = manifest.migration_entrypoint === undefined
    ? null
    : validateAssetPath(manifest.migration_entrypoint, 'migration_entrypoint', result);
  validateConfiguration(manifest.configuration, allCapabilities, schema, result);

  const marketplacePath = path.join(root, MARKETPLACE_FILE);
  if (isFile(marketplacePath)) {
    const marketplace = readJson(marketplacePath, result);
    if (isObject(marketplace)) validateMarketplace(marketplace, schema, result);
    else result.errors.push(`${MARKETPLACE_FILE} must be a JSON object.`);
  } else if (options.requireMarketplace) {
    result.errors.push(`${MARKETPLACE_FILE} is required for marketplace submission.`);
  }

  if (options.requireDist === false) return result;
  const dist = path.join(root, 'dist');
  if (!isDirectory(dist)) {
    result.errors.push('Plugin package is missing dist/.');
    return result;
  }
  const packageFiles = [
    manifestPath,
    ...(isFile(path.join(root, MARKETPLACE_FILE)) ? [path.join(root, MARKETPLACE_FILE)] : []),
    ...walk(dist)
  ];
  if (packageFiles.length > schema.packageLimits.files) {
    result.errors.push(`Plugin package exceeds the ${schema.packageLimits.files} file limit.`);
  }
  const packageBytes = packageFiles.reduce((total, file) => total + fs.statSync(file).size, 0);
  if (packageBytes > schema.packageLimits.extractedBytes) {
    result.errors.push(
      `Plugin package exceeds the ${schema.packageLimits.extractedBytes} byte extracted limit.`
    );
  }
  for (const [field, relative] of [
    ['entrypoint', entrypoint],
    ['icon', icon],
    ['migration_entrypoint', migrationEntrypoint]
  ]) {
    if (!relative) continue;
    const target = resolveInside(dist, relative, field, result);
    if (target && !isFile(target)) result.errors.push(`${field} does not exist inside dist/: ${relative}`);
  }
  const iconFile = icon && resolveInside(dist, icon, 'icon', result);
  if (iconFile && isFile(iconFile)) {
    const size = fs.statSync(iconFile).size;
    if (size < 1 || size > schema.packageLimits.iconBytes) {
      result.errors.push('Plugin icon must be between 1 byte and 1 MiB.');
    }
  }
  if (isFile(path.join(dist, DEV_FILE))) {
    result.errors.push(`${DEV_FILE} must never be included in dist/.`);
  }
  scanRemoteScripts(dist, result);
  scanIframeSandboxes(dist, normalizedOrigins.frame, result);
  scanExternalOrigins(
    dist,
    new Set(Object.values(normalizedOrigins).flat()),
    result
  );
  return result;
}

function validateManifestShape(manifest, result) {
  for (const field of Object.keys(manifest)) {
    if (!MANIFEST_FIELDS.has(field)) {
      result.errors.push(
        LEGACY_FIELDS.has(field)
          ? `${MANIFEST_FILE} contains removed P0-A field: ${field}`
          : `${MANIFEST_FILE} contains unknown field: ${field}`
      );
    }
  }
  for (const field of REQUIRED_FIELDS) {
    if (!Object.prototype.hasOwnProperty.call(manifest, field)) {
      result.errors.push(`${MANIFEST_FILE} is missing required field: ${field}`);
    }
  }
}

function validateMarketplace(value, schema, result) {
  validateObjectShape(
    value,
    MARKETPLACE_FILE,
    new Set(['description', 'author', 'category', 'tags', 'license', 'screenshots']),
    new Set(['description', 'author', 'category', 'tags']),
    result
  );
  validateText(value.description, 'marketplace.description', 400, result);
  validateText(value.author, 'marketplace.author', 120, result);
  if (typeof value.category !== 'string' || !schema.categories.includes(value.category)) {
    result.errors.push(`Unknown marketplace category: ${String(value.category ?? '')}`);
  }
  const tags = normalizeStringArray(value.tags, 'marketplace.tags', result);
  if (tags.length > 5 || tags.some((tag) => tag.length > 20)) {
    result.errors.push('marketplace.tags allows at most 5 unique values of 1-20 characters.');
  }
  if (value.license !== undefined) validateText(value.license, 'marketplace.license', 80, result);
  if (value.screenshots !== undefined) {
    if (!Array.isArray(value.screenshots) || value.screenshots.length > 8) {
      result.errors.push('marketplace.screenshots must be an array with at most 8 entries.');
    } else {
      value.screenshots.forEach((screenshot, index) => {
        if (!isObject(screenshot)) {
          result.errors.push(`marketplace.screenshots[${index}] must be an object.`);
          return;
        }
        validateObjectShape(
          screenshot,
          `marketplace.screenshots[${index}]`,
          new Set(['src', 'alt']),
          new Set(['src', 'alt']),
          result
        );
        validateAssetPath(screenshot.src, `marketplace.screenshots[${index}].src`, result);
        validateText(screenshot.alt, `marketplace.screenshots[${index}].alt`, 160, result);
      });
    }
  }
}

function validateConfiguration(value, capabilities, schema, result) {
  if (value === undefined) return;
  if (!Array.isArray(value) || !value.length) {
    result.errors.push('configuration must be omitted when empty.');
    return;
  }
  if (value.length > 32) result.errors.push('configuration cannot contain more than 32 entries.');
  if (!capabilities.includes('configuration.read@1')) {
    result.errors.push('configuration requires configuration.read@1.');
  }
  const keys = [];
  value.forEach((definition, index) => {
    if (!isObject(definition)) {
      result.errors.push(`configuration[${index}] must be an object.`);
      return;
    }
    validateObjectShape(
      definition,
      `configuration[${index}]`,
      new Set(['key', 'label', 'description', 'type', 'required', 'default', 'options']),
      new Set(['key', 'label', 'description', 'type', 'required']),
      result
    );
    const key = validateText(
      definition.key,
      `configuration[${index}].key`,
      64,
      result,
      /^[A-Z][A-Z0-9_]{0,63}$/
    );
    keys.push(key);
    validateText(definition.label, `configuration[${index}].label`, 80, result);
    if (typeof definition.description !== 'string' || definition.description.length > 240) {
      result.errors.push(`configuration[${index}].description is invalid.`);
    }
    if (!schema.configurationTypes.includes(definition.type)) {
      result.errors.push(`configuration[${index}].type is unknown.`);
    }
    if (typeof definition.required !== 'boolean') {
      result.errors.push(`configuration[${index}].required must be a boolean.`);
    }
    if (definition.type === 'secret' && definition.default !== undefined) {
      result.errors.push(`configuration[${index}].default is forbidden for secret values.`);
    }
  });
  const duplicates = keys.filter((key, index) => key && keys.indexOf(key) !== index);
  if (duplicates.length) {
    result.errors.push(`configuration contains duplicate keys: ${[...new Set(duplicates)].join(', ')}`);
  }
}

function validateText(value, field, maxLength, result, pattern) {
  if (
    typeof value !== 'string' ||
    !value.trim() ||
    value.length > maxLength ||
    (pattern && !pattern.test(value))
  ) {
    result.errors.push(`${field} is invalid or exceeds ${maxLength} characters.`);
    return '';
  }
  return value;
}

function validateAssetPath(value, field, result) {
  const text = validateText(value, field, 240, result);
  if (
    text.startsWith('/') ||
    text.includes('\\') ||
    text.includes(':') ||
    text.split('/').includes('..')
  ) {
    result.errors.push(`${field} must be a relative path inside dist/.`);
    return '';
  }
  return text;
}

function normalizeStringArray(value, field, result) {
  if (!Array.isArray(value)) {
    result.errors.push(`${field} must be an array.`);
    return [];
  }
  const normalized = [];
  value.forEach((item, index) => {
    if (typeof item !== 'string' || !item.trim()) {
      result.errors.push(`${field}[${index}] must be a string.`);
      return;
    }
    normalized.push(item.trim());
  });
  if (new Set(normalized).size !== normalized.length) {
    result.errors.push(`${field} contains duplicates.`);
  }
  return normalized;
}

function validateObjectShape(value, field, allowed, required, result) {
  if (!isObject(value)) {
    result.errors.push(`${field} must be an object.`);
    return;
  }
  for (const key of Object.keys(value)) {
    if (!allowed.has(key)) result.errors.push(`${field} contains unknown field: ${key}`);
  }
  for (const key of required) {
    if (!Object.prototype.hasOwnProperty.call(value, key)) {
      result.errors.push(`${field} is missing required field: ${key}`);
    }
  }
}

function normalizeOrigin(value, result, field = 'origin', blockCampusHosts = true, allowDevelopmentOrigins = false) {
  try {
    const url = new URL(value);
    const host = url.hostname.toLowerCase();
    const development =
      allowDevelopmentOrigins &&
      url.protocol === 'http:' &&
      ['localhost', '127.0.0.1', '[::1]', '::1'].includes(host);
    if (
      (!development && url.protocol !== 'https:') ||
      url.username ||
      url.password ||
      url.search ||
      url.hash ||
      url.pathname !== '/'
    ) {
      result.errors.push(`${field} entry must be an HTTPS origin without path/query/userinfo: ${value}`);
      return null;
    }
    if (!development && isPrivateOrLocalHost(host)) {
      result.errors.push(`${field} cannot include private, loopback, link-local, or local hosts: ${host}`);
      return null;
    }
    if (blockCampusHosts && (CAMPUS_HOSTS.has(host) || host.endsWith('.bjtu.edu.cn'))) {
      result.errors.push(`${field} cannot include campus service hosts; use campus.request@1: ${host}`);
      return null;
    }
    return `${url.protocol}//${url.host.toLowerCase()}`;
  } catch {
    result.errors.push(`Invalid ${field} entry: ${value}`);
    return null;
  }
}

function isPrivateOrLocalHost(host) {
  const normalized = host.replace(/^\[|\]$/g, '').toLowerCase();
  if (
    normalized === 'localhost' ||
    normalized.endsWith('.localhost') ||
    normalized.endsWith('.local') ||
    normalized.endsWith('.internal') ||
    normalized === '::' ||
    normalized === '::1'
  ) return true;
  if (normalized.includes(':')) {
    const first = normalized.startsWith('::')
      ? Number.NaN
      : Number.parseInt(normalized.split(':', 1)[0], 16);
    return !Number.isNaN(first) &&
      ((first & 0xfe00) === 0xfc00 || (first & 0xffc0) === 0xfe80 || (first & 0xff00) === 0xff00);
  }
  const parts = normalized.split('.').map(Number);
  if (parts.length !== 4 || parts.some((item) => !Number.isInteger(item) || item < 0 || item > 255)) {
    return false;
  }
  return parts[0] === 0 ||
    parts[0] === 10 ||
    parts[0] === 127 ||
    (parts[0] === 100 && parts[1] >= 64 && parts[1] <= 127) ||
    (parts[0] === 169 && parts[1] === 254) ||
    (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) ||
    (parts[0] === 192 && parts[1] === 168) ||
    parts[0] >= 224;
}

function scanRemoteScripts(dist, result) {
  for (const file of walk(dist).filter((item) => /\.html?$/i.test(item))) {
    const content = fs.readFileSync(file, 'utf8');
    if (/<script\b[^>]*\bsrc\s*=\s*["']https?:\/\//i.test(content)) {
      result.errors.push(`${path.relative(dist, file)} loads remote executable JavaScript.`);
    }
  }
}

function scanIframeSandboxes(dist, frameOrigins, result) {
  const declared = new Set(frameOrigins);
  for (const file of walk(dist).filter((item) => /\.html?$/i.test(item))) {
    const content = fs.readFileSync(file, 'utf8');
    for (const match of content.matchAll(/<iframe\b[^>]*>/gi)) {
      const tag = match[0];
      const source = /\bsrc\s*=\s*["']([^"']+)["']/i.exec(tag)?.[1] || '';
      const origin = /^https?:\/\//i.test(source) ? originFromUrl(source) : null;
      if (origin && !declared.has(origin)) {
        result.errors.push(`${path.relative(dist, file)} embeds undeclared iframe origin ${origin}.`);
      }
      if (!origin) continue;
      const sandbox = /\bsandbox\s*=\s*["']([^"']*)["']/i.exec(tag)?.[1];
      if (sandbox === undefined) {
        result.errors.push(`${path.relative(dist, file)} remote iframe must declare a sandbox attribute.`);
        continue;
      }
      const tokens = new Set(sandbox.toLowerCase().split(/\s+/).filter(Boolean));
      const forbidden = [
        'allow-downloads',
        'allow-modals',
        'allow-popups',
        'allow-popups-to-escape-sandbox',
        'allow-presentation',
        'allow-top-navigation',
        'allow-top-navigation-by-user-activation'
      ].filter((token) => tokens.has(token));
      if (forbidden.length) {
        result.errors.push(`${path.relative(dist, file)} remote iframe sandbox grants forbidden tokens: ${forbidden.join(', ')}.`);
      }
      const missing = ['allow-scripts', 'allow-forms', 'allow-same-origin'].filter(
        (token) => !tokens.has(token)
      );
      if (missing.length) {
        result.errors.push(`${path.relative(dist, file)} remote iframe sandbox must include ${missing.join(', ')}.`);
      }
    }
  }
}

function scanExternalOrigins(dist, allowedOrigins, result) {
  for (const file of walk(dist)) {
    if (!TEXT_EXTENSIONS.has(path.extname(file).toLowerCase())) continue;
    const content = fs.readFileSync(file, 'utf8');
    for (const match of content.matchAll(/https?:\/\/[A-Za-z0-9.-]+(?::\d+)?(?:\/[^\s"'<>)]*)?/g)) {
      const origin = originFromUrl(match[0]);
      if (origin && !allowedOrigins.has(origin)) {
        result.warnings.push(`${path.relative(dist, file)} references undeclared origin ${origin}.`);
      }
    }
  }
}

function originFromUrl(value) {
  try {
    const url = new URL(value);
    return `${url.protocol}//${url.host.toLowerCase()}`;
  } catch {
    return null;
  }
}

function resolveInside(root, relative, field, result) {
  const target = path.resolve(root, ...relative.split('/'));
  const resolvedRoot = path.resolve(root);
  if (target !== resolvedRoot && !target.startsWith(`${resolvedRoot}${path.sep}`)) {
    result.errors.push(`${field} resolves outside dist/.`);
    return null;
  }
  return target;
}

function resolveRepositoryRoot(value) {
  const candidates = [
    path.resolve(value),
    path.resolve(value, '..'),
    path.resolve(__dirname, '..')
  ];
  return candidates.find((candidate) =>
    isFile(path.join(candidate, 'plugin-tooling', 'contracts', 'capability-contracts.json'))
  ) || path.resolve(__dirname, '..');
}

function readJson(file, result) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    result.errors.push(`Invalid JSON in ${file}: ${error.message}`);
    return null;
  }
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function isFile(file) {
  try {
    return fs.statSync(file).isFile();
  } catch {
    return false;
  }
}

function isDirectory(directory) {
  try {
    return fs.statSync(directory).isDirectory();
  } catch {
    return false;
  }
}

function walk(root) {
  const output = [];
  for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
    const fullPath = path.join(root, entry.name);
    if (entry.isDirectory()) output.push(...walk(fullPath));
    else if (entry.isFile()) output.push(fullPath);
  }
  return output;
}

if (require.main === module) main();

module.exports = {
  lintPlugin,
  loadManifestSchema,
  normalizeOrigin,
  walk
};
