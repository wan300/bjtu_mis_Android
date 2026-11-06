import assert from 'node:assert/strict';
import test from 'node:test';
import { CAPABILITY_REGISTRY, createBjtuPluginSdk } from '../packages/plugin-sdk/dist/index.js';
import { createMockTransport } from '../packages/plugin-sdk/dist/mock.js';

test('new public routes include form encoding without granting v1 Cookie access', () => {
  const network = CAPABILITY_REGISTRY.capabilities.find((c) => c.id === 'network.request@1');
  assert.ok(network.methods[0].request.properties.bodyType.enum.includes('formUrlEncoded'));
  assert.equal(network.methods[0].request.properties.credentials, undefined);
  const cookies = CAPABILITY_REGISTRY.capabilities.find((c) => c.id === 'network.request@2');
  assert.deepEqual(cookies.methods[0].request.properties.credentials.enum, ['omit', 'include']);
  assert.equal(cookies.methods[0].request.properties.credentials.default, 'omit');
  const imports = CAPABILITY_REGISTRY.capabilities.find((c) => c.id === 'android.credentials.import@1');
  assert.equal(imports.confirmation, 'userGesture');
  assert.equal(imports.idempotency, 'none');
  assert.equal(imports.methods[0].response.additionalProperties, false);
});

test('background tasks are bounded, idempotent, cancellable from background and never restarted by replay', async () => {
  const transport = createMockTransport();
  const sdk = createBjtuPluginSdk(transport);
  const request = { idempotencyKey: 'schedule-1', callbackId: 'sync', intervalMs: 1000, maxDurationMs: 60000 };
  const receipt = await sdk.android.background.task.schedule(request);
  assert.deepEqual(await sdk.android.background.task.schedule(request), receipt);
  assert.equal((await sdk.android.background.task.list()).tasks.length, 1);
  await assert.rejects(sdk.android.background.task.schedule({ ...request, intervalMs: 2000 }), { code: 'idempotency_conflict' });
  await assert.rejects(sdk.android.background.task.schedule({ ...request, idempotencyKey: 'too-long', maxDurationMs: 3600001 }), { code: 'invalid_request' });
  transport.setScenario({ lifecycle: 'background' });
  await assert.rejects(sdk.android.background.task.schedule({ ...request, idempotencyKey: 'background' }), { code: 'foreground_required' });
  await sdk.android.background.task.cancel({ idempotencyKey: 'cancel', taskId: receipt.result.taskId });
  assert.equal((await sdk.android.background.task.list()).tasks.length, 0);
  assert.deepEqual(await sdk.android.background.task.schedule(request), receipt);
  assert.equal((await sdk.android.background.task.list()).tasks.length, 0);
  let tick;
  const off = sdk.android.background.task.onTick((data) => { tick = data; });
  await transport.emit('android.background.task@1', 'tick', { taskId: 'test', callbackId: 'sync', scheduledAtMs: 0, expiresAtMs: 60000 });
  assert.equal(tick.callbackId, 'sync');
  off();
});

test('structured import, address query and Cookie request expose namespaced APIs with foreground gates', async () => {
  const transport = createMockTransport();
  const sdk = createBjtuPluginSdk(transport);
  const credentials = { identity: 'fixture-user', umid: 'fixture-umid', refreshToken: 'fixture-token' };
  assert.deepEqual(await sdk.android.credentials.import({ packageName: 'com.example.app', credentials }), credentials);
  await sdk.android.network.getAddresses({ publicIpUrl: 'https://api.example.com/ip' });
  await sdk.network.request({ url: 'https://api.example.com/form', method: 'POST', bodyType: 'formUrlEncoded', body: { a: '空格 +&' } });
  await sdk.network.requestWithCookies({ url: 'https://api.example.com/session', credentials: 'include' });
  assert.equal(transport.requests.at(-1).request.capability, 'network.request@2');
  transport.setScenario({ lifecycle: 'background' });
  await assert.rejects(sdk.android.credentials.import({ packageName: 'com.example.app', credentials }), { code: 'foreground_required' });
  await assert.rejects(sdk.android.network.getAddresses(), { code: 'foreground_required' });
});
