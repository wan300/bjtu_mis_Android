import { readFile, readdir, stat } from 'node:fs/promises';
import path from 'node:path';
import {
  CAPABILITY_IDS,
  CAPABILITY_REGISTRY,
  MANIFEST_SCHEMA_VERSION,
  PACKAGE_LIMITS,
  type CapabilityId
} from '@bjtu-mis/plugin-sdk';

const MANIFEST_FILE = 'bjtu-plugin.json';
const MARKETPLACE_FILE = 'bjtu-marketplace.json';
const DEV_FILE = 'bjtu-plugin.dev.json';
const FORBIDDEN_MANIFEST_FIELDS = new Set([
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
const REQUIRED_MANIFEST_FIELDS = [
  'schema_version',
  'id',
  'name',
  'version',
  'entrypoint',
  'icon',
  'capabilities'
];
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

export interface PluginManifest {
  schema_version: 3;
  id: string;
  name: string;
  version: string;
  entrypoint: string;
  icon: string;
  capabilities: {
    required: CapabilityId[];
    optional?: CapabilityId[];
  };
  origins?: {
    connect?: string[];
    media?: string[];
    frame?: string[];
    navigation?: string[];
  };
  data_schema_version?: number;
  migration_entrypoint?: string;
  configuration?: unknown[];
}

export interface MarketplaceMetadata {
  description: string;
  author: string;
  category: string;
  tags: string[];
  license?: string;
  screenshots?: Array<{ src: string; alt: string }>;
}

export interface LintIssue {
  code: string;
  message: string;
  file?: string;
}

export interface LintResult {
  ok: boolean;
  errors: LintIssue[];
  warnings: LintIssue[];
  manifest?: PluginManifest;
  marketplace?: MarketplaceMetadata;
  runtimeFloor?: number;
}

export async function lintPlugin(
  pluginRoot: string,
  options: { requireMarketplace?: boolean; requireDist?: boolean } = {}
): Promise<LintResult> {
  const root = path.resolve(pluginRoot);
  const errors: LintIssue[] = [];
  const warnings: LintIssue[] = [];
  const rawManifest = await readJson(path.join(root, MANIFEST_FILE), errors);
  let manifest: PluginManifest | undefined;
  if (isObject(rawManifest)) {
    const errorCount = errors.length;
    validateManifest(rawManifest, errors, warnings);
    if (errors.length === errorCount) {
      manifest = rawManifest as unknown as PluginManifest;
    }
  }
  const marketplacePath = path.join(root, MARKETPLACE_FILE);
  const rawMarketplace = await readOptionalJson(marketplacePath, errors);
  let marketplace: MarketplaceMetadata | undefined;
  if (rawMarketplace !== undefined) {
    if (isObject(rawMarketplace)) {
      const errorCount = errors.length;
      validateMarketplace(rawMarketplace, errors);
      if (errors.length === errorCount) {
        marketplace = rawMarketplace as unknown as MarketplaceMetadata;
      }
    } else {
      errors.push(issue('invalid_marketplace', `${MARKETPLACE_FILE} must be a JSON object.`, MARKETPLACE_FILE));
    }
  } else if (options.requireMarketplace) {
    errors.push(issue('marketplace_required', `${MARKETPLACE_FILE} is required for marketplace submission.`, MARKETPLACE_FILE));
  }

  if (manifest && (options.requireDist ?? true)) {
    await validatePackageFiles(root, manifest, marketplace, errors, warnings);
  }
  const runtimeFloor = manifest
    ? Math.max(
        CAPABILITY_REGISTRY.runtimeFloor,
        ...[...manifest.capabilities.required, ...(manifest.capabilities.optional ?? [])]
          .map((id) => CAPABILITY_REGISTRY.capabilities.find((item) => item.id === id)?.runtimeFloor ?? 0)
      )
    : undefined;
  return {
    ok: errors.length === 0,
    errors,
    warnings,
    manifest,
    marketplace,
    runtimeFloor
  };
}

function validateManifest(
  manifest: Record<string, unknown>,
  errors: LintIssue[],
  warnings: LintIssue[]
): void {
  const keys = Object.keys(manifest);
  for (const field of keys.filter((key) => !MANIFEST_FIELDS.has(key))) {
    const code = FORBIDDEN_MANIFEST_FIELDS.has(field) ? 'legacy_field' : 'unknown_field';
    errors.push(issue(code, `bjtu-plugin.json contains forbidden or unknown field: ${field}`, MANIFEST_FILE));
  }
  for (const field of REQUIRED_MANIFEST_FIELDS.filter((key) => !(key in manifest))) {
    errors.push(issue('missing_field', `bjtu-plugin.json is missing required field: ${field}`, MANIFEST_FILE));
  }
  if (manifest.schema_version !== MANIFEST_SCHEMA_VERSION) {
    errors.push(issue('schema_version', `schema_version must be ${MANIFEST_SCHEMA_VERSION}.`, MANIFEST_FILE));
  }
  validateString(manifest.id, 'id', errors, 64, /^[a-z][a-z0-9_.-]{2,63}$/);
  validateString(manifest.name, 'name', errors, 80);
  validateString(
    manifest.version,
    'version',
    errors,
    40,
    /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/
  );
  validateAssetPath(manifest.entrypoint, 'entrypoint', errors);
  const icon = validateAssetPath(manifest.icon, 'icon', errors);
  if (icon && !/\.(svg|png|webp|jpe?g)$/i.test(icon)) {
    errors.push(issue('icon_type', 'icon must be SVG, PNG, WebP, JPG, or JPEG.', MANIFEST_FILE));
  }

  const capabilities = manifest.capabilities;
  if (!isObject(capabilities)) {
    errors.push(issue('capabilities_shape', 'capabilities must be an object.', MANIFEST_FILE));
    return;
  }
  const capabilityKeys = Object.keys(capabilities);
  for (const key of capabilityKeys.filter((value) => !['required', 'optional'].includes(value))) {
    errors.push(issue('capabilities_field', `capabilities contains unknown field: ${key}`, MANIFEST_FILE));
  }
  const required = validateCapabilities(capabilities.required, 'capabilities.required', errors, true);
  for (const id of required) {
    if (CAPABILITY_REGISTRY.capabilities.some((item) => item.id === id && 'optionalOnly' in item && item.optionalOnly === true)) {
      errors.push(issue('capability_optional_only', `${id} may only appear in capabilities.optional.`, MANIFEST_FILE));
    }
  }
  const optional = capabilities.optional === undefined
    ? []
    : validateCapabilities(capabilities.optional, 'capabilities.optional', errors, false);
  const duplicate = required.filter((id) => optional.includes(id));
  if (duplicate.length) {
    errors.push(issue('capability_duplicate', `Capabilities cannot be both required and optional: ${duplicate.join(', ')}`, MANIFEST_FILE));
  }
  if (!required.includes('runtime.lifecycle@1')) {
    errors.push(issue('lifecycle_required', 'runtime.lifecycle@1 must be in capabilities.required.', MANIFEST_FILE));
  }

  const origins = manifest.origins;
  if (origins !== undefined) {
    if (!isObject(origins) || Object.keys(origins).length === 0) {
      errors.push(issue('origins_shape', 'origins must be omitted when empty.', MANIFEST_FILE));
    } else {
      const keys = Object.keys(origins);
      for (const key of keys.filter((value) => !['connect', 'media', 'frame', 'navigation'].includes(value))) {
        errors.push(issue('origins_field', `origins contains unknown field: ${key}`, MANIFEST_FILE));
      }
      for (const key of ['connect', 'media', 'frame', 'navigation'] as const) {
        if (origins[key] !== undefined) {
          validateOrigins(origins[key], `origins.${key}`, errors, key !== 'navigation');
        }
      }
      if (origins.frame !== undefined && ![...required, ...optional].includes('remote.frame@1')) {
        errors.push(issue('frame_capability', 'origins.frame requires remote.frame@1.', MANIFEST_FILE));
      }
      if (
        origins.navigation !== undefined &&
        ![...required, ...optional].includes('navigation.external@1')
      ) {
        errors.push(
          issue(
            'navigation_capability',
            'origins.navigation requires navigation.external@1.',
            MANIFEST_FILE
          )
        );
      }
    }
  }

  const allCapabilities = [...required, ...optional];
  const usesData = allCapabilities.some((id) => id === 'storage.kv@2' || id === 'storage.blob@1');
  if (usesData) {
    if (!Number.isInteger(manifest.data_schema_version) || Number(manifest.data_schema_version) < 1) {
      errors.push(issue('data_schema_required', 'KV/Blob capabilities require a positive data_schema_version.', MANIFEST_FILE));
    }
    if (Number(manifest.data_schema_version) > 1 && manifest.migration_entrypoint === undefined) {
      errors.push(issue('migration_required', 'data_schema_version > 1 requires migration_entrypoint.', MANIFEST_FILE));
    }
  } else if (manifest.data_schema_version !== undefined || manifest.migration_entrypoint !== undefined) {
    errors.push(issue('data_schema_forbidden', 'data_schema_version and migration_entrypoint are only valid with KV/Blob.', MANIFEST_FILE));
  }
  if (manifest.migration_entrypoint !== undefined) {
    validateAssetPath(manifest.migration_entrypoint, 'migration_entrypoint', errors);
  }
  if (manifest.configuration !== undefined) {
    if (!Array.isArray(manifest.configuration) || manifest.configuration.length === 0) {
      errors.push(issue('configuration_shape', 'configuration must be omitted when empty.', MANIFEST_FILE));
    } else {
      if (manifest.configuration.length > 32) {
        errors.push(
          issue(
            'configuration_limit',
            'configuration cannot contain more than 32 entries.',
            MANIFEST_FILE
          )
        );
      }
      const keys: string[] = [];
      manifest.configuration.forEach((definition, index) => {
        if (!isObject(definition)) {
          errors.push(
            issue(
              'configuration_item',
              `configuration[${index}] must be an object.`,
              MANIFEST_FILE
            )
          );
          return;
        }
        const allowed = new Set([
          'key',
          'label',
          'description',
          'type',
          'required',
          'default',
          'options'
        ]);
        for (const key of Object.keys(definition).filter((value) => !allowed.has(value))) {
          errors.push(
            issue(
              'configuration_field',
              `configuration[${index}] contains unknown field: ${key}`,
              MANIFEST_FILE
            )
          );
        }
        for (const key of ['key', 'label', 'description', 'type', 'required'].filter(
          (value) => !Object.prototype.hasOwnProperty.call(definition, value)
        )) {
          errors.push(
            issue(
              'configuration_field',
              `configuration[${index}] is missing required field: ${key}`,
              MANIFEST_FILE
            )
          );
        }
        const key = validateString(
          definition.key,
          `configuration[${index}].key`,
          errors,
          64,
          /^[A-Z][A-Z0-9_]{0,63}$/
        );
        if (key) keys.push(key);
        validateString(definition.label, `configuration[${index}].label`, errors, 80);
        if (
          typeof definition.description !== 'string' ||
          definition.description.length > 240
        ) {
          errors.push(
            issue(
              'configuration_description',
              `configuration[${index}].description is invalid.`,
              MANIFEST_FILE
            )
          );
        }
        if (
          typeof definition.type !== 'string' ||
          !(CAPABILITY_REGISTRY.configurationTypes as readonly string[]).includes(
            definition.type
          )
        ) {
          errors.push(
            issue(
              'configuration_type',
              `configuration[${index}].type is unknown.`,
              MANIFEST_FILE
            )
          );
        }
        if (typeof definition.required !== 'boolean') {
          errors.push(
            issue(
              'configuration_required',
              `configuration[${index}].required must be a boolean.`,
              MANIFEST_FILE
            )
          );
        }
        if (definition.default !== undefined && typeof definition.default !== 'string') {
          errors.push(
            issue(
              'configuration_default',
              `configuration[${index}].default must be a string.`,
              MANIFEST_FILE
            )
          );
        }
        if (definition.type === 'secret' && definition.default !== undefined) {
          errors.push(
            issue(
              'configuration_secret_default',
              `configuration[${index}].default is forbidden for secret values.`,
              MANIFEST_FILE
            )
          );
        }
        if (definition.options !== undefined) {
          const options = stringArray(
            definition.options,
            `configuration[${index}].options`,
            errors,
            MANIFEST_FILE
          );
          if (options.length < 1 || options.length > 20) {
            errors.push(
              issue(
                'configuration_options',
                `configuration[${index}].options requires 1-20 unique values.`,
                MANIFEST_FILE
              )
            );
          }
        }
      });
      const duplicates = keys.filter((key, index) => keys.indexOf(key) !== index);
      if (duplicates.length) {
        errors.push(
          issue(
            'configuration_duplicate',
            `configuration contains duplicate keys: ${[...new Set(duplicates)].join(', ')}`,
            MANIFEST_FILE
          )
        );
      }
    }
    if (!allCapabilities.includes('configuration.read@1')) {
      errors.push(
        issue(
          'configuration_capability',
          'configuration requires configuration.read@1.',
          MANIFEST_FILE
        )
      );
    }
  }
  const hasConnectOrigins =
    isObject(origins) && Array.isArray(origins.connect) && origins.connect.length > 0;
  if (allCapabilities.includes('network.request@1') && !hasConnectOrigins) {
    warnings.push(
      issue(
        'network_without_origins',
        'network.request@1 has no origins.connect entries and cannot reach public origins.',
        MANIFEST_FILE
      )
    );
  }
  if (optional.length) {
    warnings.push(
      issue(
        'optional_default_off',
        'Optional capabilities are disabled on first install until the user grants them.',
        MANIFEST_FILE
      )
    );
  }
}

function validateMarketplace(value: Record<string, unknown>, errors: LintIssue[]): void {
  const allowed = new Set(['description', 'author', 'category', 'tags', 'license', 'screenshots']);
  for (const field of Object.keys(value).filter((key) => !allowed.has(key))) {
    errors.push(issue('marketplace_field', `${MARKETPLACE_FILE} contains unknown field: ${field}`, MARKETPLACE_FILE));
  }
  validateString(value.description, 'description', errors, 400, undefined, MARKETPLACE_FILE);
  validateString(value.author, 'author', errors, 120, undefined, MARKETPLACE_FILE);
  if (
    typeof value.category !== 'string' ||
    !(CAPABILITY_REGISTRY.marketplaceCategories as readonly string[]).includes(value.category)
  ) {
    errors.push(issue('marketplace_category', 'marketplace category is invalid.', MARKETPLACE_FILE));
  }
  const tags = stringArray(value.tags, 'tags', errors, MARKETPLACE_FILE);
  if (tags.length > 5 || tags.some((tag) => tag.length > 20)) {
    errors.push(issue('marketplace_tags', 'marketplace tags allow at most 5 unique 1-20 character values.', MARKETPLACE_FILE));
  }
  if (value.license !== undefined) validateString(value.license, 'license', errors, 80, undefined, MARKETPLACE_FILE);
  if (value.screenshots !== undefined) {
    if (!Array.isArray(value.screenshots) || value.screenshots.length > 8) {
      errors.push(
        issue(
          'marketplace_screenshots',
          'screenshots must be an array with at most 8 entries.',
          MARKETPLACE_FILE
        )
      );
    } else {
      value.screenshots.forEach((screenshot, index) => {
        if (!isObject(screenshot)) {
          errors.push(
            issue(
              'marketplace_screenshot',
              `screenshots[${index}] must be an object.`,
              MARKETPLACE_FILE
            )
          );
          return;
        }
        const unknown = Object.keys(screenshot).filter(
          (key) => !['src', 'alt'].includes(key)
        );
        const missing = ['src', 'alt'].filter(
          (key) => !Object.prototype.hasOwnProperty.call(screenshot, key)
        );
        for (const key of unknown) {
          errors.push(
            issue(
              'marketplace_screenshot_field',
              `screenshots[${index}] contains unknown field: ${key}`,
              MARKETPLACE_FILE
            )
          );
        }
        for (const key of missing) {
          errors.push(
            issue(
              'marketplace_screenshot_field',
              `screenshots[${index}] is missing required field: ${key}`,
              MARKETPLACE_FILE
            )
          );
        }
        validateAssetPath(
          screenshot.src,
          `screenshots[${index}].src`,
          errors,
          MARKETPLACE_FILE
        );
        validateString(
          screenshot.alt,
          `screenshots[${index}].alt`,
          errors,
          160,
          undefined,
          MARKETPLACE_FILE
        );
      });
    }
  }
}

async function validatePackageFiles(
  root: string,
  manifest: PluginManifest,
  marketplace: MarketplaceMetadata | undefined,
  errors: LintIssue[],
  warnings: LintIssue[]
): Promise<void> {
  const dist = path.join(root, 'dist');
  const distInfo = await stat(dist).catch(() => null);
  if (!distInfo?.isDirectory()) {
    errors.push(issue('dist_missing', 'Plugin package is missing dist/.', 'dist'));
    return;
  }
  for (const [field, relative] of [
    ['entrypoint', manifest.entrypoint],
    ['icon', manifest.icon],
    ...(manifest.migration_entrypoint
      ? ([['migration_entrypoint', manifest.migration_entrypoint]] as const)
      : [])
  ] as const) {
    const target = path.resolve(dist, relative);
    if (!isInside(dist, target) || !(await stat(target).catch(() => null))?.isFile()) {
      errors.push(issue('asset_missing', `${field} does not exist inside dist/: ${relative}`, `dist/${relative}`));
    }
  }
  const devInsideDist = await stat(path.join(dist, DEV_FILE)).catch(() => null);
  if (devInsideDist) {
    errors.push(issue('dev_config_packaged', `${DEV_FILE} must never be copied into dist/.`, `dist/${DEV_FILE}`));
  }
  const files = await walk(dist, errors);
  const rootFiles = [
    path.join(root, MANIFEST_FILE),
    ...(marketplace ? [path.join(root, MARKETPLACE_FILE)] : [])
  ];
  const packageFiles = [...rootFiles, ...files];
  if (packageFiles.length > PACKAGE_LIMITS.files) {
    errors.push(
      issue(
        'package_file_limit',
        `Plugin package exceeds the ${PACKAGE_LIMITS.files} file limit.`,
        'dist'
      )
    );
  }
  const packageBytes = (
    await Promise.all(
      packageFiles.map(async (file) => (await stat(file).catch(() => null))?.size ?? 0)
    )
  ).reduce((total, size) => total + size, 0);
  if (packageBytes > PACKAGE_LIMITS.extractedBytes) {
    errors.push(
      issue(
        'package_size_limit',
        `Plugin package exceeds the ${PACKAGE_LIMITS.extractedBytes} byte extracted limit.`,
        'dist'
      )
    );
  }
  const iconFile = path.resolve(dist, manifest.icon);
  if (isInside(dist, iconFile)) {
    const iconSize = (await stat(iconFile).catch(() => null))?.size;
    if (
      iconSize !== undefined &&
      (iconSize < 1 || iconSize > PACKAGE_LIMITS.iconBytes)
    ) {
      errors.push(
        issue(
          'icon_size',
          `Plugin icon must be between 1 and ${PACKAGE_LIMITS.iconBytes} bytes.`,
          `dist/${manifest.icon}`
        )
      );
    }
  }
  for (const [index, screenshot] of (marketplace?.screenshots ?? []).entries()) {
    if (!isObject(screenshot) || typeof screenshot.src !== 'string') continue;
    const target = path.resolve(dist, screenshot.src);
    if (!isInside(dist, target) || !(await stat(target).catch(() => null))?.isFile()) {
      errors.push(
        issue(
          'screenshot_missing',
          `Marketplace screenshot does not exist inside dist/: ${screenshot.src}`,
          `dist/${screenshot.src}`
        )
      );
    }
  }
  const declaredOrigins = new Set(
    Object.values(manifest.origins ?? {})
      .flat()
      .map(canonicalOrigin)
      .filter((origin): origin is string => origin !== null)
  );
  const frameOrigins = new Set(
    (manifest.origins?.frame ?? [])
      .map(canonicalOrigin)
      .filter((origin): origin is string => origin !== null)
  );
  for (const file of files) {
    if (!/\.(html?|js|mjs|css)$/i.test(file)) continue;
    const content = await readFile(file, 'utf8');
    if (/<script\b[^>]*\bsrc\s*=\s*["']https?:\/\//i.test(content)) {
      errors.push(issue('remote_script', 'Published plugins must bundle executable JavaScript locally.', path.relative(root, file)));
    }
    for (const match of content.matchAll(
      /https?:\/\/[A-Za-z0-9.-]+(?::\d+)?(?:\/[^\s"'<>)]*)?/g
    )) {
      const origin = canonicalOrigin(match[0]);
      if (origin && !declaredOrigins.has(origin)) {
        warnings.push(
          issue(
            'undeclared_origin',
            `File references undeclared origin ${origin}.`,
            path.relative(root, file)
          )
        );
      }
    }
    if (/\.html?$/i.test(file)) {
      validateRemoteIframes(
        content,
        frameOrigins,
        path.relative(root, file),
        errors
      );
    }
  }
}

function validateCapabilities(
  value: unknown,
  field: string,
  errors: LintIssue[],
  requiredList: boolean
): CapabilityId[] {
  const values = stringArray(value, field, errors, MANIFEST_FILE);
  if (requiredList && values.length === 0) {
    errors.push(issue('capabilities_empty', `${field} must not be empty.`, MANIFEST_FILE));
  }
  if (!requiredList && values.length === 0) {
    errors.push(issue('optional_empty', 'capabilities.optional must be omitted when empty.', MANIFEST_FILE));
  }
  for (const id of values) {
    if (!(CAPABILITY_IDS as readonly string[]).includes(id)) {
      errors.push(issue('unknown_capability', `Unknown capability: ${id}`, MANIFEST_FILE));
    }
  }
  return values.filter((id): id is CapabilityId =>
    (CAPABILITY_IDS as readonly string[]).includes(id)
  );
}

function validateOrigins(
  value: unknown,
  field: string,
  errors: LintIssue[],
  blockCampus: boolean
): string[] {
  const origins = stringArray(value, field, errors, MANIFEST_FILE);
  const normalized: string[] = [];
  if (!origins.length) {
    errors.push(issue('origin_empty', `${field} must be omitted when empty.`, MANIFEST_FILE));
  }
  for (const origin of origins) {
    try {
      const url = new URL(origin);
      const host = url.hostname.toLowerCase();
      if (
        url.protocol !== 'https:' ||
        url.username ||
        url.password ||
        url.pathname !== '/' ||
        url.search ||
        url.hash
      ) {
        throw new Error('not an HTTPS origin');
      }
      if (isPrivateOrLocalHost(host)) {
        errors.push(issue('private_origin', `${field} blocks private, loopback and link-local host: ${host}`, MANIFEST_FILE));
      }
      if (blockCampus && (CAMPUS_HOSTS.has(host) || host.endsWith('.bjtu.edu.cn'))) {
        errors.push(issue('campus_origin', `${field} blocks campus host; use campus.request@1: ${host}`, MANIFEST_FILE));
      }
      normalized.push(`${url.protocol}//${url.host.toLowerCase()}`);
    } catch {
      errors.push(issue('origin_invalid', `${field} entry must be a canonical HTTPS origin: ${origin}`, MANIFEST_FILE));
    }
  }
  if (new Set(normalized).size !== normalized.length) {
    errors.push(
      issue(
        'origin_duplicate',
        `${field} contains duplicate origins after canonicalization.`,
        MANIFEST_FILE
      )
    );
  }
  return normalized;
}

function validateString(
  value: unknown,
  field: string,
  errors: LintIssue[],
  maxLength: number,
  pattern?: RegExp,
  file = MANIFEST_FILE
): string {
  if (typeof value !== 'string' || !value.trim() || value.length > maxLength || (pattern && !pattern.test(value))) {
    errors.push(issue('invalid_string', `${field} is invalid or exceeds ${maxLength} characters.`, file));
    return '';
  }
  return value;
}

function validateAssetPath(
  value: unknown,
  field: string,
  errors: LintIssue[],
  file = MANIFEST_FILE
): string {
  const text = validateString(value, field, errors, 240, undefined, file);
  if (
    text.startsWith('/') ||
    text.includes('\\') ||
    text.includes(':') ||
    text.split('/').includes('..')
  ) {
    errors.push(issue('asset_path', `${field} must be a relative path inside dist/.`, file));
    return '';
  }
  return text;
}

function stringArray(
  value: unknown,
  field: string,
  errors: LintIssue[],
  file: string
): string[] {
  if (!Array.isArray(value) || value.some((item) => typeof item !== 'string' || !item.trim())) {
    errors.push(issue('array_shape', `${field} must be an array of non-empty strings.`, file));
    return [];
  }
  const normalized = value.map((item) => String(item).trim());
  if (new Set(normalized).size !== normalized.length) {
    errors.push(issue('array_duplicate', `${field} contains duplicates.`, file));
  }
  return normalized;
}

function canonicalOrigin(value: string): string | null {
  try {
    const url = new URL(value);
    if (!['http:', 'https:'].includes(url.protocol)) return null;
    return `${url.protocol}//${url.host.toLowerCase()}`;
  } catch {
    return null;
  }
}

function validateRemoteIframes(
  content: string,
  declaredOrigins: ReadonlySet<string>,
  file: string,
  errors: LintIssue[]
): void {
  for (const match of content.matchAll(/<iframe\b[^>]*>/gi)) {
    const tag = match[0];
    const source = /\bsrc\s*=\s*["']([^"']+)["']/i.exec(tag)?.[1] ?? '';
    if (!/^https?:\/\//i.test(source)) continue;
    const origin = canonicalOrigin(source);
    if (origin && !declaredOrigins.has(origin)) {
      errors.push(
        issue(
          'iframe_origin',
          `Remote iframe embeds undeclared origin ${origin}.`,
          file
        )
      );
    }
    const sandbox = /\bsandbox\s*=\s*["']([^"']*)["']/i.exec(tag)?.[1];
    if (sandbox === undefined) {
      errors.push(
        issue('iframe_sandbox', 'Remote iframe must declare a sandbox attribute.', file)
      );
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
      errors.push(
        issue(
          'iframe_sandbox',
          `Remote iframe sandbox grants forbidden tokens: ${forbidden.join(', ')}.`,
          file
        )
      );
    }
    const missing = ['allow-scripts', 'allow-forms', 'allow-same-origin'].filter(
      (token) => !tokens.has(token)
    );
    if (missing.length) {
      errors.push(
        issue(
          'iframe_sandbox',
          `Remote iframe sandbox must include ${missing.join(', ')}.`,
          file
        )
      );
    }
  }
}

function isPrivateOrLocalHost(host: string): boolean {
  const normalized = host.replace(/^\[|\]$/g, '').toLowerCase();
  if (
    normalized === 'localhost' ||
    normalized.endsWith('.localhost') ||
    normalized.endsWith('.local') ||
    normalized.endsWith('.internal') ||
    normalized === '::' ||
    normalized === '::1'
  ) {
    return true;
  }
  if (normalized.includes(':')) {
    const first = normalized.startsWith('::')
      ? Number.NaN
      : Number.parseInt(normalized.split(':', 1)[0]!, 16);
    return (
      !Number.isNaN(first) &&
      ((first & 0xfe00) === 0xfc00 || (first & 0xffc0) === 0xfe80 || (first & 0xff00) === 0xff00)
    );
  }
  const parts = normalized.split('.').map(Number);
  if (parts.length !== 4 || parts.some((part) => !Number.isInteger(part) || part < 0 || part > 255)) {
    return false;
  }
  return (
    parts[0] === 0 ||
    parts[0] === 10 ||
    parts[0] === 127 ||
    (parts[0] === 100 && parts[1]! >= 64 && parts[1]! <= 127) ||
    (parts[0] === 169 && parts[1] === 254) ||
    (parts[0] === 172 && parts[1]! >= 16 && parts[1]! <= 31) ||
    (parts[0] === 192 && parts[1] === 168) ||
    parts[0]! >= 224
  );
}

async function readJson(file: string, errors: LintIssue[]): Promise<unknown> {
  try {
    return JSON.parse(await readFile(file, 'utf8')) as unknown;
  } catch (error) {
    errors.push(issue('invalid_json', `Cannot read ${path.basename(file)}: ${error instanceof Error ? error.message : String(error)}`, path.basename(file)));
    return undefined;
  }
}

async function readOptionalJson(file: string, errors: LintIssue[]): Promise<unknown> {
  try {
    return JSON.parse(await readFile(file, 'utf8')) as unknown;
  } catch (error) {
    if (isNodeError(error) && error.code === 'ENOENT') return undefined;
    errors.push(issue('invalid_json', `Cannot read ${path.basename(file)}: ${error instanceof Error ? error.message : String(error)}`, path.basename(file)));
    return null;
  }
}

async function walk(root: string, errors: LintIssue[] = []): Promise<string[]> {
  const output: string[] = [];
  for (const entry of await readdir(root, { withFileTypes: true })) {
    const fullPath = path.join(root, entry.name);
    if (entry.isSymbolicLink()) {
      errors.push(
        issue(
          'package_symlink',
          'Plugin packages cannot contain symbolic links.',
          fullPath
        )
      );
    } else if (entry.isDirectory()) output.push(...(await walk(fullPath, errors)));
    else if (entry.isFile()) output.push(fullPath);
  }
  return output;
}

function isInside(root: string, target: string): boolean {
  const relative = path.relative(path.resolve(root), path.resolve(target));
  return relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative);
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function isNodeError(error: unknown): error is NodeJS.ErrnoException {
  return error instanceof Error && 'code' in error;
}

function issue(code: string, message: string, file?: string): LintIssue {
  return { code, message, file };
}
