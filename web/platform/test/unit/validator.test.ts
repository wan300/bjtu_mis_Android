import assert from 'node:assert/strict';
import { createWriteStream, promises as fs } from 'node:fs';
import { createRequire } from 'node:module';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import yazl from 'yazl';
import { computeDistDigest, extractZipSecure, validateAndBuildPackage } from '../../src/validator.js';

interface LintResult { errors: string[]; warnings: string[] }
interface LintSchema {
  permissions: string[];
  capabilities: string[];
  categories: string[];
  configurationTypes: string[];
}
interface LintModule {
  lintPlugin(root: string, result: LintResult, schema: LintSchema, options?: { requireDist?: boolean }): LintResult;
  loadManifestSchema(repositoryRoot: string, result: LintResult): LintSchema | null;
}

test('platform lint accepts optional app autostart and rejects required', async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'bjtu-autostart-platform-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  const require = createRequire(import.meta.url);
  const lint = require(path.resolve('..', '..', 'tools', 'third-party-service-lint.cjs')) as LintModule;
  const loadResult: LintResult = { errors: [], warnings: [] };
  const schema = lint.loadManifestSchema(path.resolve('..'), loadResult);
  assert.ok(schema);
  const manifest = {
    schema_version: 3, id: 'bjtu.autostart', name: 'Autostart', version: '1.0.0',
    entrypoint: 'index.html', icon: 'icon.svg',
    capabilities: { required: ['runtime.lifecycle@1'], optional: ['android.app.autoStart@1'] }
  };
  const file = path.join(root, 'bjtu-plugin.json');
  await fs.writeFile(file, JSON.stringify(manifest));
  const optional: LintResult = { errors: [], warnings: [] };
  lint.lintPlugin(root, optional, schema, { requireDist: false });
  assert.deepEqual(optional.errors, []);
  manifest.capabilities.required.push('android.app.autoStart@1');
  manifest.capabilities.optional.pop();
  delete (manifest.capabilities as { optional?: string[] }).optional;
  await fs.writeFile(file, JSON.stringify(manifest));
  const required: LintResult = { errors: [], warnings: [] };
  lint.lintPlugin(root, required, schema, { requireDist: false });
  assert.ok(required.errors.some((error) => error.includes('may only appear in capabilities.optional')));
});

test('dist digest is stable and sensitive to content and path changes', async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'bjtu-plugin-digest-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  await fs.mkdir(path.join(root, 'assets'), { recursive: true });
  await fs.writeFile(path.join(root, 'index.html'), '<h1>hello</h1>');
  await fs.writeFile(path.join(root, 'assets', 'app.js'), 'console.log(1)');
  const first = await computeDistDigest(root);
  const second = await computeDistDigest(root);
  assert.deepEqual(second, first);
  assert.equal(first.fileCount, 2);
  await fs.writeFile(path.join(root, 'assets', 'app.js'), 'console.log(2)');
  assert.notEqual((await computeDistDigest(root)).sha256, first.sha256);
});

test('builds a canonical contract_v1 artifact with separate marketplace metadata', async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'bjtu-plugin-package-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  const sourceZip = path.join(root, 'source.zip');
  const zip = new yazl.ZipFile();
  const manifest = {
    schema_version: 3,
    id: 'bjtu.demo',
    name: 'Demo',
    version: '1.0.0',
    entrypoint: 'index.html',
    icon: 'icon.svg',
    capabilities: {
      required: ['runtime.lifecycle@1']
    }
  };
  const marketplace = {
    description: 'Demo plugin',
    author: 'Alice',
    category: 'other',
    tags: [],
    license: 'MIT',
    screenshots: []
  };
  zip.addBuffer(Buffer.from(JSON.stringify(manifest)), 'repository/bjtu-plugin.json');
  zip.addBuffer(Buffer.from(JSON.stringify(marketplace)), 'repository/bjtu-marketplace.json');
  zip.addBuffer(Buffer.from('<html></html>'), 'repository/dist/index.html');
  zip.addBuffer(Buffer.from('<svg></svg>'), 'repository/dist/icon.svg');
  const output = createWriteStream(sourceZip);
  zip.outputStream.pipe(output);
  zip.end();
  await new Promise<void>((resolve, reject) => {
    output.on('close', resolve);
    output.on('error', reject);
  });

  const artifact = path.join(root, 'artifact.zip');
  const result = await validateAndBuildPackage({
    sourceZip,
    workRoot: path.join(root, 'work'),
    repositoryRoot: path.resolve('..', '..'),
    artifactPath: artifact,
    iconPath: path.join(root, 'icon.svg')
  });
  assert.match(result.archiveSha256, /^[a-f0-9]{64}$/);
  assert.equal(result.packageFileCount, 2);
  assert.equal(result.contractProfile, 'contract_v1');
  assert.equal(result.runtimeFloor, 2);
  assert.deepEqual(result.capabilities.required, ['runtime.lifecycle@1']);
  assert.equal(result.marketplace.author, 'Alice');

  const unpacked = path.join(root, 'unpacked');
  await extractZipSecure(artifact, unpacked);
  const files = (await fs.readdir(unpacked, { recursive: true, withFileTypes: true }))
    .filter((entry) => entry.isFile())
    .map((entry) => path.relative(unpacked, path.join(entry.parentPath, entry.name)).split(path.sep).join('/'))
    .sort();
  assert.deepEqual(files, [
    'bjtu-marketplace.json',
    'bjtu-plugin.json',
    'dist/icon.svg',
    'dist/index.html'
  ]);
});

test('schema loading failure stops before plugin linting emits cascading diagnostics', async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'bjtu-plugin-missing-contract-schema-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  const repositoryRoot = path.join(root, 'repository');
  await fs.mkdir(path.join(repositoryRoot, 'tools'), { recursive: true });
  await fs.copyFile(
    path.resolve('..', '..', 'tools', 'third-party-service-lint.cjs'),
    path.join(repositoryRoot, 'tools', 'third-party-service-lint.cjs')
  );

  const sourceZip = path.join(root, 'source.zip');
  const zip = new yazl.ZipFile();
  zip.addBuffer(Buffer.from(JSON.stringify({
    schema_version: 3,
    id: 'bjtu.demo',
    name: 'Demo',
    version: '1.0.0',
    entrypoint: 'index.html',
    icon: 'icon.svg',
    capabilities: { required: ['runtime.lifecycle@1'] }
  })), 'repository/bjtu-plugin.json');
  zip.addBuffer(Buffer.from(JSON.stringify({
    description: 'Demo plugin',
    author: 'Alice',
    category: 'other',
    tags: []
  })), 'repository/bjtu-marketplace.json');
  zip.addBuffer(Buffer.from('<html></html>'), 'repository/dist/index.html');
  zip.addBuffer(Buffer.from('<svg></svg>'), 'repository/dist/icon.svg');
  const output = createWriteStream(sourceZip);
  zip.outputStream.pipe(output);
  zip.end();
  await new Promise<void>((resolve, reject) => {
    output.on('close', resolve);
    output.on('error', reject);
  });

  await assert.rejects(
    validateAndBuildPackage({
      sourceZip,
      workRoot: path.join(root, 'work'),
      repositoryRoot,
      artifactPath: path.join(root, 'artifact.zip'),
      iconPath: path.join(root, 'icon.svg')
    }),
    (error: unknown) => {
      assert.ok(error instanceof Error);
      assert.match(error.message, /Cannot load generated contract schemas/);
      assert.doesNotMatch(error.message, /Unknown capability/);
      assert.doesNotMatch(error.message, /Unknown marketplace category/);
      assert.doesNotMatch(error.message, /0 file limit|0 byte extracted limit/);
      assert.doesNotMatch(error.message, /Plugin icon must be between/);
      return true;
    }
  );
});

test('rejects archives whose directory entries exceed the global entry limit', async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'bjtu-plugin-entry-limit-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  const sourceZip = path.join(root, 'directories.zip');
  const zip = new yazl.ZipFile();
  for (let index = 0; index <= 1000; index += 1) zip.addEmptyDirectory(`directory-${index}`);
  const output = createWriteStream(sourceZip);
  zip.outputStream.pipe(output);
  zip.end();
  await new Promise<void>((resolve, reject) => {
    output.on('close', resolve);
    output.on('error', reject);
  });

  await assert.rejects(
    extractZipSecure(sourceZip, path.join(root, 'extracted')),
    /条目数量超过 1000/
  );
});

test('lint rejects non-string values instead of publishing the original malformed arrays', async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'bjtu-plugin-lint-types-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  await fs.mkdir(path.join(root, 'dist'));
  await fs.writeFile(path.join(root, 'dist', 'index.html'), '<html></html>');
  await fs.writeFile(path.join(root, 'dist', 'icon.svg'), '<svg></svg>');
  await fs.writeFile(path.join(root, 'bjtu-plugin.json'), JSON.stringify({
    schema_version: 3,
    id: 'bjtu.demo',
    name: 'Demo',
    version: '1.0.0',
    entrypoint: 'index.html',
    icon: 'icon.svg',
    capabilities: {
      required: ['runtime.lifecycle@1', 42],
      optional: []
    },
    origins: {
      connect: [false, 'https://[fd00::1]', 'https://100.64.0.1', 'http://localhost:4173']
    },
    bridge_origins: ['self'],
    permissions: { required: [42], optional: [], credential_passthrough: true },
  }));
  await fs.writeFile(path.join(root, 'bjtu-marketplace.json'), JSON.stringify({
    description: 'Demo plugin',
    author: 'Alice',
    category: 'other',
    tags: [{}],
    owner_email: 'secret@example.com'
  }));
  const lintResult: LintResult = { errors: [], warnings: [] };
  const require = createRequire(import.meta.url);
  const lint = require(path.resolve('..', '..', 'tools', 'third-party-service-lint.cjs')) as LintModule;
  const schema = lint.loadManifestSchema(path.resolve('..'), lintResult);
  assert.ok(schema);

  lint.lintPlugin(root, lintResult, schema);

  assert.ok(lintResult.errors.some((error) => error.includes('capabilities.required[1] must be a string')));
  assert.ok(lintResult.errors.some((error) => error.includes('origins.connect[0] must be a string')));
  assert.ok(lintResult.errors.some((error) => error.includes('marketplace.tags[0] must be a string')));
  assert.ok(lintResult.errors.some((error) => error.includes('removed P0-A field: permissions')));
  assert.ok(lintResult.errors.some((error) => error.includes('removed P0-A field: bridge_origins')));
  assert.ok(lintResult.errors.some((error) => error.includes('bjtu-marketplace.json contains unknown field')));
  assert.ok(lintResult.errors.some((error) => error.includes('private, loopback, link-local')));
  assert.ok(lintResult.errors.some((error) => error.includes('must be an HTTPS origin')));
});

test('lint rejects unsupported and oversized plugin icons', async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'bjtu-plugin-icon-policy-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  await fs.mkdir(path.join(root, 'dist'));
  await fs.writeFile(path.join(root, 'dist', 'index.html'), '<html></html>');
  const manifest = {
    schema_version: 3,
    id: 'bjtu.demo',
    name: 'Demo',
    version: '1.0.0',
    entrypoint: 'index.html',
    icon: 'icon.txt',
    capabilities: {
      required: ['runtime.lifecycle@1']
    }
  };
  await fs.writeFile(path.join(root, 'dist', 'icon.txt'), 'not an image');
  await fs.writeFile(path.join(root, 'bjtu-plugin.json'), JSON.stringify(manifest));
  const require = createRequire(import.meta.url);
  const lint = require(path.resolve('..', '..', 'tools', 'third-party-service-lint.cjs')) as LintModule;
  const schemaResult: LintResult = { errors: [], warnings: [] };
  const schema = lint.loadManifestSchema(path.resolve('..'), schemaResult);
  assert.ok(schema);
  const unsupported: LintResult = { errors: [], warnings: [] };

  lint.lintPlugin(root, unsupported, schema);

  assert.ok(unsupported.errors.some((error) => error.includes('SVG, PNG, WebP, JPG, or JPEG')));

  manifest.icon = 'icon.png';
  await fs.writeFile(path.join(root, 'dist', 'icon.png'), Buffer.alloc((1024 * 1024) + 1));
  await fs.writeFile(path.join(root, 'bjtu-plugin.json'), JSON.stringify(manifest));
  const oversized: LintResult = { errors: [], warnings: [] };

  lint.lintPlugin(root, oversized, schema);

  assert.ok(oversized.errors.some((error) => error.includes('1 byte and 1 MiB')));
});

test('lint requires a restricted sandbox for declared remote iframes', async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'bjtu-plugin-frame-policy-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  await fs.mkdir(path.join(root, 'dist'));
  await fs.writeFile(path.join(root, 'dist', 'icon.svg'), '<svg></svg>');
  await fs.writeFile(
    path.join(root, 'dist', 'index.html'),
    '<iframe src="https://example.com/embed" sandbox="allow-scripts allow-forms allow-same-origin allow-popups"></iframe>'
  );
  await fs.writeFile(path.join(root, 'bjtu-plugin.json'), JSON.stringify({
    schema_version: 3,
    id: 'bjtu.frame.demo',
    name: 'Frame demo',
    version: '1.0.0',
    entrypoint: 'index.html',
    icon: 'icon.svg',
    capabilities: {
      required: ['runtime.lifecycle@1', 'remote.frame@1']
    },
    origins: {
      frame: ['https://example.com']
    }
  }));
  const require = createRequire(import.meta.url);
  const lint = require(path.resolve('..', '..', 'tools', 'third-party-service-lint.cjs')) as LintModule;
  const schemaResult: LintResult = { errors: [], warnings: [] };
  const schema = lint.loadManifestSchema(path.resolve('..'), schemaResult);
  assert.ok(schema);
  const result: LintResult = { errors: [], warnings: [] };

  lint.lintPlugin(root, result, schema);

  assert.ok(result.errors.some((error) => error.includes('allow-popups')));
});

test('lint binds navigation origins to navigation.external capability', async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'bjtu-plugin-navigation-policy-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  await fs.mkdir(path.join(root, 'dist'));
  await fs.writeFile(path.join(root, 'dist', 'icon.svg'), '<svg></svg>');
  await fs.writeFile(path.join(root, 'dist', 'index.html'), '<a href="https://example.com">open</a>');
  await fs.writeFile(path.join(root, 'bjtu-plugin.json'), JSON.stringify({
    schema_version: 3,
    id: 'bjtu.navigation.demo',
    name: 'Navigation demo',
    version: '1.0.0',
    entrypoint: 'index.html',
    icon: 'icon.svg',
    capabilities: {
      required: ['runtime.lifecycle@1']
    },
    origins: {
      navigation: ['https://example.com']
    }
  }));
  const require = createRequire(import.meta.url);
  const lint = require(path.resolve('..', '..', 'tools', 'third-party-service-lint.cjs')) as LintModule;
  const schemaResult: LintResult = { errors: [], warnings: [] };
  const schema = lint.loadManifestSchema(path.resolve('..'), schemaResult);
  assert.ok(schema);
  const result: LintResult = { errors: [], warnings: [] };

  lint.lintPlugin(root, result, schema);

  assert.ok(
    result.errors.some((error) =>
      error.includes('origins.navigation requires navigation.external@1')
    )
  );
});
