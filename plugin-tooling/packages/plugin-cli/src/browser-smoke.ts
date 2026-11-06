import { spawn } from 'node:child_process';
import { createServer, type Server } from 'node:http';
import { stat, readFile, mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {
  CAPABILITY_MOCK_RESPONSES,
  CAPABILITY_REGISTRY,
  PROTOCOL_VERSION
} from '@bjtu-mis/plugin-sdk';

const SMOKE_TIMEOUT_MS = 20_000;
const MAX_BROWSER_OUTPUT_BYTES = 10 * 1024 * 1024;

export interface BrowserSmokeResult {
  browser: string;
  requestCount: number;
}

export async function runBrowserSmokeTest(
  pluginRoot: string,
  entrypoint: string,
  browserOverride?: string
): Promise<BrowserSmokeResult> {
  const dist = path.resolve(pluginRoot, 'dist');
  const browser = await findBrowser(browserOverride);
  if (!browser) {
    throw new Error(
      'No supported Chrome, Chromium, or Edge executable was found for the real browser smoke test.'
    );
  }
  const server = createSmokeServer(dist, entrypoint);
  await listen(server);
  const address = server.address();
  if (!address || typeof address === 'string') {
    server.close();
    throw new Error('Unable to bind the plugin browser smoke server.');
  }
  const target = `http://127.0.0.1:${address.port}/${entrypoint
    .split('/')
    .map(encodeURIComponent)
    .join('/')}`;
  const profile = await mkdtemp(path.join(os.tmpdir(), 'bjtu-smoke-profile-'));
  try {
    const result = await runBrowser(browser, target, profile);
    if (!result.dom.includes('data-bjtu-smoke-loaded="true"')) {
      throw new Error('Browser smoke test did not reach the window load event.');
    }
    if (!result.dom.includes('data-bjtu-smoke-errors="0"')) {
      throw new Error(
        `Browser smoke test observed an uncaught error or rejection.${result.stderr ? `\n${result.stderr}` : ''}`
      );
    }
    if (!result.dom.includes(`data-bjtu-smoke-protocol="${PROTOCOL_VERSION}"`)) {
      throw new Error('Browser smoke host did not complete the protocol v2 handshake probe.');
    }
    const requestCount = Number(
      /data-bjtu-smoke-requests="(\d+)"/.exec(result.dom)?.[1] ?? 0
    );
    return { browser, requestCount };
  } finally {
    await close(server);
    await rm(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
  }
}

function createSmokeServer(dist: string, entrypoint: string): Server {
  const normalizedEntrypoint = entrypoint.replaceAll('\\', '/').replace(/^\/+/, '');
  return createServer(async (request, response) => {
    try {
      const requestPath = decodeURIComponent(
        new URL(request.url ?? '/', 'http://127.0.0.1').pathname
      ).replace(/^\/+/, '');
      const relative = requestPath || normalizedEntrypoint;
      const target = path.resolve(dist, ...relative.split('/'));
      if (!isInside(dist, target)) {
        response.writeHead(403).end('Forbidden');
        return;
      }
      const info = await stat(target).catch(() => null);
      if (!info?.isFile()) {
        response.writeHead(404).end('Not Found');
        return;
      }
      let payload = await readFile(target);
      if (relative === normalizedEntrypoint) {
        payload = Buffer.from(injectMockHost(payload.toString('utf8')), 'utf8');
      }
      response.writeHead(200, {
        'Cache-Control': 'no-store',
        'Content-Length': String(payload.length),
        'Content-Type': contentType(target)
      });
      response.end(request.method === 'HEAD' ? undefined : payload);
    } catch (error) {
      response.writeHead(500).end(error instanceof Error ? error.message : String(error));
    }
  });
}

function injectMockHost(html: string): string {
  const responses = JSON.stringify(CAPABILITY_MOCK_RESPONSES).replaceAll('<', '\\u003c');
  const capabilities = JSON.stringify(
    CAPABILITY_REGISTRY.capabilities.map((capability) => capability.id)
  );
  const script = `<script>
(function () {
  var listeners = new Set();
  var errors = 0;
  var requests = 0;
  var responses = ${responses};
  var update = function () {
    document.documentElement.setAttribute('data-bjtu-smoke-errors', String(errors));
    document.documentElement.setAttribute('data-bjtu-smoke-requests', String(requests));
  };
  window.addEventListener('error', function () { errors += 1; update(); });
  window.addEventListener('unhandledrejection', function () { errors += 1; update(); });
  var bridge = Object.freeze({
    postMessage: function (message) {
      if (!message || message.kind === 'binaryChunk' || message.kind === 'cancel' || message.kind === 'eventAck') return;
      requests += 1;
      update();
      var route = String(message.capability || '') + '#' + String(message.method || '');
      var result = responses[route];
      if (route === 'runtime.lifecycle@1#handshake') {
        result = {
          protocolVersion: ${PROTOCOL_VERSION},
          contractProfile: ${JSON.stringify(CAPABILITY_REGISTRY.contractProfile)},
          runtimeFloor: ${CAPABILITY_REGISTRY.runtimeFloor},
          availableCapabilities: ${capabilities},
          binaryTransports: ['arraybuffer', 'base64url-chunks-v1'],
          preferredBinaryTransport: 'arraybuffer'
        };
      }
      var envelope = result === undefined
        ? {
            protocolVersion: ${PROTOCOL_VERSION},
            requestId: message.requestId,
            ok: false,
            error: {
              code: 'invalid_request',
              message: 'Unknown browser smoke route',
              retryable: false
            }
          }
        : {
            protocolVersion: ${PROTOCOL_VERSION},
            requestId: message.requestId,
            ok: true,
            result: result
          };
      queueMicrotask(function () {
        listeners.forEach(function (listener) { listener(envelope); });
      });
    },
    addEventListener: function (listener) {
      listeners.add(listener);
      return function () { listeners.delete(listener); };
    }
  });
  Object.defineProperty(window, '__BJTU_PLUGIN_BRIDGE_V2__', {
    value: bridge,
    configurable: false,
    enumerable: false,
    writable: false
  });
  document.documentElement.setAttribute('data-bjtu-smoke-errors', '0');
  document.documentElement.setAttribute('data-bjtu-smoke-requests', '0');
  bridge.addEventListener(function (message) {
    if (
      message &&
      message.requestId === '__bjtu_smoke_handshake__' &&
      message.ok === true &&
      message.result &&
      message.result.protocolVersion === ${PROTOCOL_VERSION}
    ) {
      document.documentElement.setAttribute(
        'data-bjtu-smoke-protocol',
        String(message.result.protocolVersion)
      );
    }
  });
  bridge.postMessage({
    protocolVersion: ${PROTOCOL_VERSION},
    requestId: '__bjtu_smoke_handshake__',
    capability: 'runtime.lifecycle@1',
    method: 'handshake',
    params: { sdkVersion: 'browser-smoke' }
  });
  window.addEventListener('load', function () {
    document.documentElement.setAttribute('data-bjtu-smoke-loaded', 'true');
    update();
  });
})();
</script>`;
  const head = /<head(?:\s[^>]*)?>/i.exec(html);
  if (head?.index !== undefined) {
    const end = head.index + head[0].length;
    return `${html.slice(0, end)}${script}${html.slice(end)}`;
  }
  return `${script}${html}`;
}

async function findBrowser(override?: string): Promise<string | null> {
  const explicit = override?.trim() || process.env.BJTU_PLUGIN_TEST_BROWSER?.trim();
  if (explicit) {
    if (!(await executableWorks(explicit))) {
      throw new Error(`Configured browser executable is unavailable: ${explicit}`);
    }
    return explicit;
  }
  const candidates =
    process.platform === 'win32'
      ? windowsBrowserCandidates()
      : process.platform === 'darwin'
        ? [
            '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
            '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
            '/Applications/Chromium.app/Contents/MacOS/Chromium'
          ]
        : ['google-chrome', 'google-chrome-stable', 'chromium', 'chromium-browser'];
  for (const candidate of candidates) {
    if (await executableWorks(candidate)) return candidate;
  }
  return null;
}

function windowsBrowserCandidates(): string[] {
  const roots = [
    process.env.ProgramFiles,
    process.env['ProgramFiles(x86)'],
    process.env.LOCALAPPDATA
  ].filter((value): value is string => Boolean(value));
  return [
    ...roots.flatMap((root) => [
      path.join(root, 'Google', 'Chrome', 'Application', 'chrome.exe'),
      path.join(root, 'Microsoft', 'Edge', 'Application', 'msedge.exe'),
      path.join(root, 'Chromium', 'Application', 'chrome.exe')
    ]),
    'chrome.exe',
    'msedge.exe'
  ];
}

async function executableWorks(executable: string): Promise<boolean> {
  return await new Promise<boolean>((resolve) => {
    const child = spawn(executable, ['--version'], {
      stdio: 'ignore',
      windowsHide: true
    });
    const timer = setTimeout(() => {
      child.kill();
      resolve(false);
    }, 3_000);
    child.once('error', () => {
      clearTimeout(timer);
      resolve(false);
    });
    child.once('exit', (code) => {
      clearTimeout(timer);
      resolve(code === 0);
    });
  });
}

async function runBrowser(
  browser: string,
  target: string,
  profile: string
): Promise<{ dom: string; stderr: string }> {
  const args = [
    '--headless=new',
    `--user-data-dir=${profile}`,
    '--disable-background-networking',
    '--disable-component-update',
    '--disable-default-apps',
    '--disable-extensions',
    '--disable-gpu',
    '--hide-scrollbars',
    '--mute-audio',
    '--no-first-run',
    '--no-default-browser-check',
    '--virtual-time-budget=3000',
    '--dump-dom',
    target
  ];
  if (typeof process.getuid === 'function' && process.getuid() === 0) {
    args.unshift('--no-sandbox');
  }
  return await new Promise((resolve, reject) => {
    const child = spawn(browser, args, {
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true
    });
    const stdout: Buffer[] = [];
    const stderr: Buffer[] = [];
    let outputBytes = 0;
    let settled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const finish = (error?: Error) => {
      if (settled) return;
      settled = true;
      if (timer !== undefined) clearTimeout(timer);
      if (error) reject(error);
      else {
        resolve({
          dom: Buffer.concat(stdout).toString('utf8'),
          stderr: Buffer.concat(stderr).toString('utf8').trim()
        });
      }
    };
    const collect = (targetChunks: Buffer[]) => (chunk: Buffer) => {
      outputBytes += chunk.length;
      if (outputBytes > MAX_BROWSER_OUTPUT_BYTES) {
        child.kill();
        finish(new Error('Browser smoke output exceeded 10 MiB.'));
        return;
      }
      targetChunks.push(chunk);
    };
    child.stdout.on('data', collect(stdout));
    child.stderr.on('data', collect(stderr));
    child.once('error', (error) => finish(error));
    child.once('close', (code) => {
      if (code !== 0) {
        finish(
          new Error(
            `Headless browser exited with code ${code}.${stderr.length ? `\n${Buffer.concat(stderr).toString('utf8').trim()}` : ''}`
          )
        );
      } else {
        finish();
      }
    });
    timer = setTimeout(() => {
      child.kill();
      finish(new Error(`Headless browser exceeded ${SMOKE_TIMEOUT_MS} ms. ${Buffer.concat(stderr).toString('utf8').slice(-8192)}`));
    }, SMOKE_TIMEOUT_MS);
  });
}

function listen(server: Server): Promise<void> {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => resolve());
  });
}

function close(server: Server): Promise<void> {
  return new Promise((resolve) => server.close(() => resolve()));
}

function isInside(root: string, target: string): boolean {
  const relative = path.relative(path.resolve(root), path.resolve(target));
  return (
    relative !== '..' &&
    !relative.startsWith(`..${path.sep}`) &&
    !path.isAbsolute(relative)
  );
}

function contentType(file: string): string {
  switch (path.extname(file).toLowerCase()) {
    case '.html':
    case '.htm':
      return 'text/html; charset=utf-8';
    case '.js':
    case '.mjs':
      return 'application/javascript; charset=utf-8';
    case '.css':
      return 'text/css; charset=utf-8';
    case '.json':
      return 'application/json; charset=utf-8';
    case '.svg':
      return 'image/svg+xml; charset=utf-8';
    case '.wasm':
      return 'application/wasm';
    default:
      return 'application/octet-stream';
  }
}
