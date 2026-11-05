import assert from 'node:assert/strict';
import { cp, mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { runCli } from '../../packages/plugin-cli/dist/index.js';

test('CLI test runs a real browser protocol v2 smoke check', { timeout: 60_000 }, async (context) => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'bjtu-browser-test-'));
  context.after(() => rm(root, { recursive: true, force: true }));
  assert.equal(await runCli(['create', root, '--id', 'io.example.smoke']), 0);
  await mkdir(path.join(root, 'dist'), { recursive: true });
  await writeFile(path.join(root, 'dist/index.html'), '<!doctype html><script type="module" src="./main.js"></script>');
  await writeFile(path.join(root, 'dist/main.js'), 'export {};\n');
  await cp(path.join(root, 'public/icon.svg'), path.join(root, 'dist/icon.svg'));
  assert.equal(await runCli(['test', root]), 0);
});
