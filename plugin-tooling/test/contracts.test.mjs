import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { validateRegistrySchema, matchesGeneratedContent } from '../scripts/registry-validation.mjs';

const toolingRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repositoryRoot = path.resolve(toolingRoot, '..');

test('generation tolerates CRLF checkouts without hiding actual drift', () => {
  assert.equal(matchesGeneratedContent('one\r\ntwo\r\n', 'one\ntwo\n'), true);
  for (const actual of [null, 'one\ntwo', 'one \ntwo\n', 'one\rthree\n', 'changed\ntwo\n']) {
    assert.equal(matchesGeneratedContent(actual, 'one\ntwo\n'), false);
  }
});

test('Registry schema accepts stabilization and rejects invalid metadata', async () => {
  const registry = JSON.parse(await readFile(path.join(toolingRoot, 'contracts/capability-contracts.json'), 'utf8'));
  const stabilized = structuredClone(registry);
  stabilized.capabilities.find((item) => item.stability === 'beta').stability = 'stable';
  assert.doesNotThrow(() => validateRegistrySchema(stabilized));
  for (const [key, value] of [
    ['stability', 'invalid'], ['runtimeFloor', 1], ['confirmation', 'silent'],
    ['idempotency', 'optional'], ['timeoutMs', -1], ['optionalOnly', 'true']
  ]) {
    const invalid = structuredClone(registry);
    invalid.capabilities[0][key] = value;
    assert.throws(() => validateRegistrySchema(invalid), /Invalid capability registry/, key);
  }
  const missing = structuredClone(registry);
  delete missing.capabilities[0].support;
  assert.throws(() => validateRegistrySchema(missing), /support/);
});

test('contract registry has unique capability routes and command invariants', async () => {
  const registry = JSON.parse(
    await readFile(path.join(toolingRoot, 'contracts', 'capability-contracts.json'), 'utf8')
  );
  const capabilityIds = registry.capabilities.map((item) => item.id);
  assert.equal(new Set(capabilityIds).size, capabilityIds.length);
  assert.ok(capabilityIds.includes('runtime.lifecycle@1'));
  assert.ok(capabilityIds.includes('network.request@1'));
  assert.ok(capabilityIds.includes('storage.kv@2'));
  assert.ok(capabilityIds.includes('storage.blob@1'));
  assert.ok(capabilityIds.includes('cache.resource@1'));
  const androidCapabilityIds = [
    'android.accessibility.events@1',
    'android.accessibility.nodes@1',
    'android.accessibility.actions@1',
    'android.packages.read@1',
    'android.settings.open@1',
    'android.device.info@1',
    'android.network.status@1',
    'android.battery.status@1',
    'android.haptics.perform@1',
    'android.files.pick@1',
    'android.files.save@1',
    'android.media.pick@1',
    'android.share.open@1',
    'android.notifications.post@1',
    'android.location.read@1',
    'android.calendar.read@1',
    'android.calendar.write@1',
    'android.camera.capture@1',
    'android.audio.record@1',
    'android.sensors.read@1',
    'android.biometric.verify@1'
  ];
  for (const id of androidCapabilityIds) {
    const capability = registry.capabilities.find((item) => item.id === id);
    assert.ok(['beta', 'stable'].includes(capability.stability), id);
    assert.equal(capability.runtimeFloor, 2, id);
  }
  const androidActions = registry.capabilities.find(
    (capability) => capability.id === 'android.accessibility.actions@1'
  );
  assert.equal(androidActions.confirmation, 'none');
  assert.equal(androidActions.idempotency, 'required');
  assert.deepEqual(
    registry.capabilities.find(
      (capability) => capability.id === 'android.accessibility.events@1'
    ).events.map((event) => event.name),
    ['received']
  );
  assert.equal(
    registry.capabilities.find((capability) => capability.id === 'android.calendar.write@1')
      .idempotency,
    'required'
  );
  assert.equal(
    registry.capabilities.find((capability) => capability.id === 'android.files.pick@1')
      .confirmation,
    'none'
  );
  assert.equal(registry.schemas.accessibilityNode.properties.sensitive.type, 'boolean');
  assert.deepEqual(registry.schemas.accessibilityGestureStroke.required, ['points', 'durationMs']);
  assert.deepEqual(registry.packageLimits, {
    archiveBytes: 25 * 1024 * 1024,
    extractedBytes: 50 * 1024 * 1024,
    files: 1000,
    iconBytes: 1024 * 1024
  });
  for (const capability of registry.capabilities) {
    const eventNames = (capability.events ?? []).map((event) => event.name);
    assert.equal(new Set(eventNames).size, eventNames.length, capability.id);
    if (capability.confirmation === 'eachCall') {
      assert.equal(capability.idempotency, 'required', capability.id);
    }
  }
  const lifecycle = registry.capabilities.find(
    (capability) => capability.id === 'runtime.lifecycle@1'
  );
  assert.equal(
    lifecycle.events.find((event) => event.name === 'back').requiresAcknowledgement,
    true
  );
  assert.equal(registry.schemas.resourceHandle.properties.size.minimum, 0);
});

test('platform package limits are generated from the capability registry', async () => {
  const generated = await readFile(
    path.join(
      repositoryRoot,
      'web',
      'platform',
      'src',
      'generated',
      'plugin-contract.ts'
    ),
    'utf8'
  );
  const registry = JSON.parse(await readFile(path.join(toolingRoot, 'contracts/capability-contracts.json'), 'utf8'));
  const { PACKAGE_LIMITS } = await import('../packages/plugin-sdk/dist/index.js');
  assert.deepEqual(PACKAGE_LIMITS, registry.packageLimits);
  for (const [key, value] of Object.entries(registry.packageLimits)) {
    assert.ok(generated.includes(`${JSON.stringify(key)}: ${value}`), key);
  }
});

test('generated manifest schema is compact and mirrors the public copy', async () => {
  const docsSchema = await readFile(
    path.join(repositoryRoot, 'docs', 'third-party-service-manifest.schema.json'),
    'utf8'
  );
  const webSchema = await readFile(
    path.join(repositoryRoot, 'web', 'assets', 'schemas', 'third-party-service-manifest.schema.json'),
    'utf8'
  );
  assert.equal(docsSchema, webSchema);
  const schema = JSON.parse(docsSchema);
  assert.equal(schema.properties.bridge_origins, undefined);
  assert.equal(schema.properties.permissions, undefined);
  assert.equal(schema.properties.runtime_version, undefined);
  assert.deepEqual(schema.required, [
    'schema_version',
    'id',
    'name',
    'version',
    'entrypoint',
    'icon',
    'capabilities'
  ]);
});

test('generated marketplace schemas are byte-identical', async () => {
  const docsSchema = await readFile(
    path.join(repositoryRoot, 'docs', 'bjtu-marketplace.schema.json'),
    'utf8'
  );
  const webSchema = await readFile(
    path.join(repositoryRoot, 'web', 'assets', 'schemas', 'bjtu-marketplace.schema.json'),
    'utf8'
  );
  assert.equal(docsSchema, webSchema);
});

test('app-open autostart is a declaration-only optional capability', async () => {
  const registry = JSON.parse(await readFile(path.join(toolingRoot, 'contracts', 'capability-contracts.json'), 'utf8'));
  const capability = registry.capabilities.find((item) => item.id === 'android.app.autoStart@1');
  assert.equal(capability.optionalOnly, true);
  assert.equal(capability.runtimeFloor, 4);
  assert.equal(capability.permission.id, 'android.app.autoStart');
  assert.equal(capability.confirmation, 'none');
  assert.equal(capability.idempotency, 'none');
  assert.deepEqual(capability.methods, []);
  assert.equal(capability.quota.maxBackgroundRuntimes, 4);
  const schema = JSON.parse(await readFile(path.join(repositoryRoot, 'docs', 'third-party-service-manifest.schema.json'), 'utf8'));
  assert.ok(!schema.properties.capabilities.properties.required.items.enum.includes(capability.id));
  assert.ok(schema.properties.capabilities.properties.optional.items.enum.includes(capability.id));
});


test('keep-alive contract has a distinct runtime floor and closed response schemas', async () => {
  const registry = JSON.parse(await readFile(path.join(toolingRoot, 'contracts', 'capability-contracts.json'), 'utf8'));
  const capability = registry.capabilities.find((c) => c.id === 'android.session.keepAlive@1');
  assert.equal(capability.runtimeFloor, 3);
  assert.equal(capability.confirmation, 'none');
  assert.equal(capability.idempotency, 'required');
  assert.deepEqual(capability.methods.map((m) => m.name), ['acquire', 'renew', 'release', 'getStatus']);
  for (const method of capability.methods) {
    assert.equal(method.response.additionalProperties, false);
    if (method.name !== 'getStatus') assert.ok(method.request.required.includes('idempotencyKey'));
  }
  assert.equal(capability.methods[0].request.properties.requestedDurationMs.maximum, 3600000);
});
