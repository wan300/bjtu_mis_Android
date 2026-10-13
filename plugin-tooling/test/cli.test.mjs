import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import {
  cp,
  mkdir,
  mkdtemp,
  readFile,
  rm,
  writeFile
} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  androidDevelopmentInvocations,
  lintPlugin,
  migrateP0aManifest,
  runCli,
  writeDeterministicZip
} from '../packages/plugin-cli/dist/index.js';

test('CLI create/lint/test/pack/doctor/inspect/dev commands work', async (context) => {
  const temporary = await mkdtemp(path.join(os.tmpdir(), 'bjtu-cli-'));
  context.after(() => rm(temporary, { recursive: true, force: true }));
  const plugin = path.join(temporary, 'demo');
  assert.equal(await runCli(['create', plugin, '--id', 'io.example.test']), 0);
  assert.equal(await runCli(['lint', plugin, '--source']), 0);
  assert.equal(await runCli(['doctor', plugin]), 0);
  assert.equal(await runCli(['inspect', plugin]), 0);

  await mkdir(path.join(plugin, 'dist'), { recursive: true });
  await writeFile(
    path.join(plugin, 'dist', 'index.html'),
    '<!doctype html><script type="module" src="./main.js"></script>',
    'utf8'
  );
  await writeFile(path.join(plugin, 'dist', 'main.js'), 'export {};\n', 'utf8');
  await cp(path.join(plugin, 'public', 'icon.svg'), path.join(plugin, 'dist', 'icon.svg'));
  assert.equal((await lintPlugin(plugin)).ok, true);
  assert.equal(await runCli(['test', plugin]), 0);

  const first = path.join(temporary, 'first.zip');
  const second = path.join(temporary, 'second.zip');
  assert.equal(await runCli(['pack', plugin, '--out', first]), 0);
  assert.equal(await runCli(['pack', plugin, '--out', second]), 0);
  assert.equal(hash(await readFile(first)), hash(await readFile(second)));
  assert.equal((await readFile(first)).includes(Buffer.from('bjtu-plugin.dev.json')), false);

  await writeFile(
    path.join(plugin, 'package.json'),
    JSON.stringify({
      name: 'test-plugin',
      private: true,
      scripts: {
        dev: 'node -e "process.exit(0)"'
      }
    }),
    'utf8'
  );
  assert.equal(await runCli(['dev', plugin]), 0);
});

test('CLI migrates the P0-A fixture into deterministic contract files', async () => {
  const old = JSON.parse(
    await readFile(new URL('./fixtures/p0a/bjtu-service.json', import.meta.url), 'utf8')
  );
  const expectedManifest = JSON.parse(
    await readFile(
      new URL('./fixtures/p0a/expected-bjtu-plugin.json', import.meta.url),
      'utf8'
    )
  );
  const expectedMarketplace = JSON.parse(
    await readFile(
      new URL('./fixtures/p0a/expected-bjtu-marketplace.json', import.meta.url),
      'utf8'
    )
  );
  const result = migrateP0aManifest(old);
  assert.deepEqual(result.manifest, expectedManifest);
  assert.deepEqual(result.marketplace, expectedMarketplace);
});

test('CLI rejects old bridge and permission fields', async (context) => {
  const temporary = await mkdtemp(path.join(os.tmpdir(), 'bjtu-lint-'));
  context.after(() => rm(temporary, { recursive: true, force: true }));
  await writeFile(
    path.join(temporary, 'bjtu-plugin.json'),
    JSON.stringify({
      schema_version: 3,
      id: 'io.example.bad',
      name: 'Bad',
      version: '1.0.0',
      entrypoint: 'index.html',
      icon: 'icon.svg',
      capabilities: {
        required: ['runtime.lifecycle@1']
      },
      bridge_origins: ['self'],
      permissions: {
        required: [],
        optional: []
      }
    }),
    'utf8'
  );
  const result = await lintPlugin(temporary, { requireDist: false });
  assert.equal(result.ok, false);
  assert.ok(result.errors.filter((item) => item.code === 'legacy_field').length >= 2);
});

test('CLI accepts optional autostart and rejects required autostart', async (context) => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'bjtu-autostart-lint-'));
  context.after(() => rm(root, { recursive: true, force: true }));
  const manifest = {
    schema_version: 3,
    id: 'io.example.autostart',
    name: 'Autostart',
    version: '1.0.0',
    entrypoint: 'index.html',
    icon: 'icon.svg',
    capabilities: { required: ['runtime.lifecycle@1'], optional: ['android.app.autoStart@1'] }
  };
  const file = path.join(root, 'bjtu-plugin.json');
  await writeFile(file, JSON.stringify(manifest));
  assert.equal((await lintPlugin(root, { requireDist: false })).ok, true);
  manifest.capabilities.required.push('android.app.autoStart@1');
  manifest.capabilities.optional = [];
  delete manifest.capabilities.optional;
  await writeFile(file, JSON.stringify(manifest));
  const result = await lintPlugin(root, { requireDist: false });
  assert.equal(result.ok, false);
  assert.ok(result.errors.some((item) => item.code === 'capability_optional_only'));
});

test('CLI requires the navigation capability for declared navigation origins', async (context) => {
  const temporary = await mkdtemp(path.join(os.tmpdir(), 'bjtu-navigation-lint-'));
  context.after(() => rm(temporary, { recursive: true, force: true }));
  await writeFile(
    path.join(temporary, 'bjtu-plugin.json'),
    JSON.stringify({
      schema_version: 3,
      id: 'io.example.navigation',
      name: 'Navigation',
      version: '1.0.0',
      entrypoint: 'index.html',
      icon: 'icon.svg',
      capabilities: {
        required: ['runtime.lifecycle@1']
      },
      origins: {
        connect: ['https://100.64.0.1'],
        navigation: ['https://navigate.example.com']
      }
    }),
    'utf8'
  );

  const result = await lintPlugin(temporary, { requireDist: false });

  assert.equal(result.ok, false);
  assert.ok(result.errors.some((item) => item.code === 'navigation_capability'));
  assert.ok(result.errors.some((item) => item.code === 'private_origin'));
});

test('CLI validates marketplace screenshot and configuration element schemas', async (context) => {
  const temporary = await mkdtemp(path.join(os.tmpdir(), 'bjtu-deep-lint-'));
  context.after(() => rm(temporary, { recursive: true, force: true }));
  await writeFile(
    path.join(temporary, 'bjtu-plugin.json'),
    JSON.stringify({
      schema_version: 3,
      id: 'io.example.deep',
      name: 'Deep lint',
      version: '1.0.0',
      entrypoint: 'index.html',
      icon: 'icon.svg',
      capabilities: {
        required: ['runtime.lifecycle@1', 'configuration.read@1', 'network.request@1']
      },
      origins: {
        connect: ['https://api.example.com:443']
      },
      configuration: [
        {
          key: 'TOKEN',
          label: 'Token',
          description: '',
          type: 'secret',
          required: true,
          default: 'must-not-ship'
        }
      ]
    }),
    'utf8'
  );
  await writeFile(
    path.join(temporary, 'bjtu-marketplace.json'),
    JSON.stringify({
      description: 'Demo',
      author: 'Alice',
      category: 'other',
      tags: [],
      screenshots: [{ src: '../outside.png' }]
    }),
    'utf8'
  );

  const result = await lintPlugin(temporary, { requireDist: false });

  assert.equal(result.ok, false);
  assert.ok(result.errors.some((item) => item.code === 'configuration_secret_default'));
  assert.ok(result.errors.some((item) => item.code === 'marketplace_screenshot_field'));
  assert.ok(result.errors.some((item) => item.code === 'asset_path'));
  assert.equal(result.errors.some((item) => item.code === 'origin_invalid'), false);

  const require = createRequire(import.meta.url);
  const official = require('../../tools/third-party-service-lint.cjs');
  const officialResult = { errors: [], warnings: [] };
  const schema = official.loadManifestSchema(
    fileURLToPath(new URL('../..', import.meta.url)),
    officialResult
  );
  official.lintPlugin(temporary, officialResult, schema, { requireDist: false });
  assert.ok(officialResult.errors.some((message) => message.includes('secret values')));
  assert.ok(officialResult.errors.some((message) => message.includes('missing required field: alt')));
});

test('deterministic ZIP writer enforces platform-equivalent limits before writing', async (context) => {
  const temporary = await mkdtemp(path.join(os.tmpdir(), 'bjtu-zip-limit-'));
  context.after(() => rm(temporary, { recursive: true, force: true }));
  const source = path.join(temporary, 'payload.bin');
  const output = path.join(temporary, 'plugin.zip');
  await writeFile(source, Buffer.alloc(128));

  await assert.rejects(
    writeDeterministicZip(
      output,
      [{ source, name: 'dist/payload.bin' }],
      { archiveBytes: 64, extractedBytes: 1024, files: 10 }
    ),
    /archive limit/
  );
});

test('CLI builds scoped Android debug enable and cleanup commands', () => {
  const enabled = androidDevelopmentInvocations(
    'adb',
    'io.example.test',
    5173,
    6173,
    true
  );
  assert.deepEqual(enabled[0], {
    command: 'adb',
    args: ['reverse', 'tcp:6173', 'tcp:5173']
  });
  assert.equal(enabled[1].args.includes('cn.edu.bjtu.mis.debug.PLUGIN_DEVELOPMENT'), true);
  assert.equal(enabled[1].args.includes('io.example.test'), true);
  assert.equal(enabled[1].args.at(-1), 'true');

  const disabled = androidDevelopmentInvocations(
    'adb',
    'io.example.test',
    5173,
    6173,
    false
  );
  assert.equal(disabled[0].args.at(-1), 'false');
  assert.deepEqual(disabled[1].args, ['reverse', '--remove', 'tcp:6173']);
});

function hash(value) {
  return createHash('sha256').update(value).digest('hex');
}
