import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { lintPlugin } from '../packages/plugin-cli/dist/index.js';

const official = createRequire(import.meta.url)('../../tools/third-party-service-lint.cjs');

test('shared and CLI lint allow stricter sandbox and reject unsafe frames', async (context) => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'bjtu-sandbox-test-'));
  context.after(() => rm(root, { recursive: true, force: true }));
  await mkdir(path.join(root, 'dist'));
  await writeFile(path.join(root, 'dist/icon.svg'), '<svg/>');
  await writeFile(path.join(root, 'bjtu-plugin.json'), JSON.stringify({
    schema_version: 3, id: 'io.example.frame', name: 'Frame', version: '1.0.0',
    entrypoint: 'index.html', icon: 'icon.svg',
    capabilities: { required: ['runtime.lifecycle@1', 'remote.frame@1'] },
    origins: { frame: ['https://frame.example.com'] }
  }));
  const forbidden = ['allow-downloads', 'allow-modals', 'allow-popups',
    'allow-popups-to-escape-sandbox', 'allow-presentation', 'allow-top-navigation',
    'allow-top-navigation-by-user-activation'];
  const cases = [
    ['', false], [' sandbox=""', true], [' sandbox="allow-scripts"', true],
    [' sandbox="allow-scripts allow-forms allow-same-origin"', true],
    ...forbidden.map((token) => [` sandbox="${token}"`, false])
  ];
  for (const [attribute, accepted] of cases) {
    await writeFile(path.join(root, 'dist/index.html'), `<iframe src="https://frame.example.com/embed"${attribute}></iframe>`);
    const result = { errors: [], warnings: [] };
    const schema = official.loadManifestSchema(fileURLToPath(new URL('../..', import.meta.url)), result);
    official.lintPlugin(root, result, schema);
    assert.equal(result.errors.length === 0, accepted, JSON.stringify({ attribute, errors: result.errors }));
    assert.equal((await lintPlugin(root)).ok, accepted, attribute);
  }
  await writeFile(path.join(root, 'dist/index.html'), '<iframe src="https://undeclared.example.com" sandbox=""></iframe>');
  const result = { errors: [], warnings: [] };
  const schema = official.loadManifestSchema(fileURLToPath(new URL('../..', import.meta.url)), result);
  official.lintPlugin(root, result, schema);
  assert.ok(result.errors.some((error) => error.includes('undeclared iframe origin')));
  assert.equal((await lintPlugin(root)).ok, false);
});
