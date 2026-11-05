"""Provision a verified WebView on the disposable AOSP API 26 CI emulator only."""
import argparse
import base64
import hashlib
import http.client
import os
from pathlib import Path
import re
import ssl
import struct
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import zipfile

PACKAGE = 'com.google.android.apps.chrome'
VERSION = '124.0.6367.219'
TEST_CERTIFICATE = 'a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc'
# Official API 26 default x86_64 r01 framework; its XML registers the Chromium debug package.
FRAMEWORK_SHA256 = '63dbee0f89fd1ed6a0ff123a495d53c5c9ce47cec8f47ca082065d567ac6f0e9'
AOSP = 'https://android.googlesource.com/platform/'
TAG = '/+/refs/tags/android-15.0.0_r1/'
WEBVIEW_COMMIT = '479929d2f7c4e3484903132afa864b1f68dd2705'
# Public AOSP development fixtures, never production signing material.
RESOURCES = {
    'webview.apk': (
        AOSP + 'external/chromium-webview' + TAG + 'prebuilt/x86_64/webview.apk?format=TEXT',
        'b76f0b24f2f3f90dbbc7c23007721428b74eb0b2b969dc8315b5a9dc98469c8f',
        400 * 1024 * 1024),
    'testkey.pk8': (
        AOSP + 'build' + TAG + 'target/product/security/testkey.pk8?format=TEXT',
        '495675d32e89a149d5abe191f4e9c0e218b9068714e9b53a7c91e164a0741a23', 16 * 1024),
    'testkey.x509.pem': (
        AOSP + 'build' + TAG + 'target/product/security/testkey.x509.pem?format=TEXT',
        'a4384ba815b9499a5ce349b4e33c1755278873fe2eac150a068823f526e6dbde', 16 * 1024),
}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('CI WebView downloads must not redirect')


def verify(path, name):
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if digest != RESOURCES[name][1]:
        raise ValueError(f'Invalid SHA-256 for CI fixture {name}')


def decode_resource(encoded, output, name):
    output.write_bytes(base64.b64decode(encoded, validate=True))
    verify(output, name)


def read_encoded(opener, url, limit):
    for attempt in range(3):
        try:
            encoded = bytearray()
            with opener.open(url, timeout=90) as response:
                while True:
                    try:
                        chunk = response.read(1024 * 1024)
                    except http.client.IncompleteRead as error:
                        encoded.extend(error.partial)
                        break  # Full pinned digest validation remains mandatory.
                    if not chunk:
                        break
                    encoded.extend(chunk)
                    if len(encoded) > limit:
                        raise ValueError('Oversized CI fixture')
            if len(encoded) > limit:
                raise ValueError('Oversized CI fixture')
            return encoded
        except urllib.error.HTTPError as error:
            if error.code not in (408, 429, 500, 502, 503, 504) or attempt == 2:
                raise
        except urllib.error.URLError as error:
            if isinstance(error.reason, ssl.SSLError) or attempt == 2:
                raise
        except TimeoutError:
            if attempt == 2:
                raise
        time.sleep(2 ** attempt)
    raise RuntimeError('CI fixture download attempts exhausted')


def fetch_apk_git(output):
    # Gitiles' large Base64 endpoint can return 503. Fetch only the pinned blob via Git.
    for attempt in range(2):
        with tempfile.TemporaryDirectory(prefix='webview-git-', dir=output.parent) as temp:
            repository = Path(temp) / 'source'
            try:
                run(['git', '-c', 'http.followRedirects=false', 'clone', '--filter=blob:none',
                     '--depth=1', '--sparse', '--single-branch', '--branch', 'android-15.0.0_r1',
                     AOSP + 'external/chromium-webview', str(repository)], timeout=300)
                if run(['git', '-C', str(repository), 'rev-parse', 'HEAD']) != WEBVIEW_COMMIT:
                    raise ValueError('Unexpected AOSP WebView source commit')
                run(['git', '-C', str(repository), '-c', 'http.followRedirects=false',
                     'sparse-checkout', 'set', 'prebuilt/x86_64'], timeout=300)
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                if attempt == 1:
                    raise
                time.sleep(3)
                continue
            source = repository / 'prebuilt/x86_64/webview.apk'
            verify(source, 'webview.apk')
            source.replace(output)
            return


def fetch(cache):
    cache.mkdir(parents=True, exist_ok=True)
    opener = urllib.request.build_opener(NoRedirect)
    for name, (url, _, limit) in RESOURCES.items():
        destination = cache / name
        if destination.exists():
            verify(destination, name)
            continue
        # Interrupted downloads and invalid bytes can never become cache hits.
        with tempfile.TemporaryDirectory(prefix='webview-download-', dir=cache) as temp:
            output = Path(temp) / name
            if name == 'webview.apk':
                fetch_apk_git(output)
            else:
                decode_resource(read_encoded(opener, url, limit), output, name)
            output.replace(destination)
    print('Pinned AOSP WebView and public development certificate verified.')


def run(arguments, *, timeout=120):
    try:
        return subprocess.run(arguments, check=True, capture_output=True, text=True, timeout=timeout).stdout.strip()
    except subprocess.CalledProcessError as error:
        print(error.stdout or '')
        print(error.stderr or '')
        raise


def build_tool(executable):
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        raise ValueError('ANDROID_HOME or ANDROID_SDK_ROOT is required')
    if os.name == 'nt':
        executable += '.bat' if executable == 'apksigner' else '.exe'
    candidates = [path for path in Path(sdk).glob(f'build-tools/*/{executable}')
                  if re.fullmatch(r'\d+(?:\.\d+)*', path.parent.name)]
    if not candidates:
        raise ValueError(f'Android build-tool {executable} was not found')
    return str(max(candidates, key=lambda path: tuple(map(int, path.parent.name.split('.')))))


def apksigner():
    return build_tool('apksigner')


def rename_manifest(data):
    # The pinned AAPT manifest has one unstyled UTF-16 pool. Fail closed on other formats.
    if len(data) < 36 or struct.unpack_from('<HHI', data) != (3, 8, len(data)):
        raise ValueError('Unexpected WebView manifest header')
    kind, header, size, count, styles, flags, start, style_start = struct.unpack_from('<HH6I', data, 8)
    if (kind, header, styles, flags, style_start) != (1, 28, 0, 0, 0) or start != 28 + count * 4 or 8 + size > len(data):
        raise ValueError('Unexpected WebView manifest string pool')
    offsets = struct.unpack_from(f'<{count}I', data, 36)
    strings = []
    names = ['com.android.webview' + suffix for suffix in (
        '', '.LicenseContentProvider', '.DeveloperModeContentProvider',
        '.SafeModeVariationsSeedContentProvider', '.SafeModeContentProvider')]
    matches = {name: 0 for name in names}
    for offset in offsets:
        position = 8 + start + offset
        length = struct.unpack_from('<H', data, position)[0]
        end = position + 2 + length * 2
        if length >= 0x8000 or end + 2 > 8 + size or data[end:end + 2] != b'\0\0':
            raise ValueError('Invalid WebView manifest string')
        encoded = data[position + 2:end]
        name = encoded.decode('utf-16-le')
        if name in matches:
            encoded = (PACKAGE + name[len('com.android.webview'):]).encode('utf-16-le')
            matches[name] += 1
        strings.append(struct.pack('<H', len(encoded) // 2) + encoded + b'\0\0')
    if set(matches.values()) != {1}:
        raise ValueError('Expected unique WebView package and provider authority strings')
    pool = bytearray()
    new_offsets = []
    for value in strings:
        new_offsets.append(len(pool))
        pool.extend(value)
    pool.extend(b'\0' * (-len(pool) % 4))
    new_size = start + len(pool)
    result = (struct.pack('<HHI', 3, 8, len(data) + new_size - size) +
              struct.pack('<HH6I', 1, 28, new_size, count, 0, 0, start, 0) +
              struct.pack(f'<{count}I', *new_offsets) + pool + data[8 + size:])
    return result


def repackage(source, output):
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(output, 'w') as rewritten:
        if len(original.namelist()) != len(set(original.namelist())):
            raise ValueError('Duplicate WebView APK entries')
        for entry in original.infolist():
            if entry.filename.startswith('META-INF/'):
                continue  # The verified fixture is signed again after aligning.
            content = original.read(entry)
            if entry.filename == 'AndroidManifest.xml':
                content = rename_manifest(content)
            elif entry.filename == 'resources.arsc':
                content = rename_resource_package(content)
            rewritten.writestr(entry, content)


def rename_resource_package(data):
    if len(data) < 12 or struct.unpack_from('<HHII', data) != (2, 12, len(data), 1):
        raise ValueError('Unexpected WebView resource table')
    position = 12
    names = []
    while position + 8 <= len(data):
        kind, header, size = struct.unpack_from('<HHI', data, position)
        if size < header or header < 8 or position + size > len(data):
            raise ValueError('Invalid WebView resource chunk')
        if kind == 0x200:
            if header != 288:
                raise ValueError('Unexpected WebView resource package header')
            names.append(position + 12)
        position += size
    original = 'com.android.webview'.encode('utf-16-le').ljust(256, b'\0')
    if position != len(data) or len(names) != 1 or data[names[0]:names[0] + 256] != original:
        raise ValueError('Expected exactly one AOSP WebView resource package')
    position = names[0]
    return data[:position] + PACKAGE.encode('utf-16-le').ljust(256, b'\0') + data[position + 256:]


def install(cache, serial=None):
    for name in RESOURCES:
        verify(cache / name, name)
    emulators = [line.split()[0] for line in run(['adb', 'devices']).splitlines()
                 if re.fullmatch(r'emulator-\d+\s+device', line.strip())]
    if serial is not None:
        if serial not in emulators:
            raise ValueError('The selected serial must be a connected emulator')
        emulators = [serial]
    if len(emulators) != 1:
        raise ValueError('Exactly one connected disposable emulator is required')
    adb = ['adb', '-s', emulators[0]]
    properties = {name: run([*adb, 'shell', 'getprop', name]) for name in
                  ('ro.kernel.qemu', 'ro.build.version.sdk', 'ro.build.type')}
    if (properties['ro.kernel.qemu'] != '1' or properties['ro.build.version.sdk'] != '26' or
            properties['ro.build.type'] not in ('userdebug', 'eng')):
        raise ValueError('WebView provisioning is restricted to debuggable API 26 emulators')
    initial = run([*adb, 'shell', 'dumpsys', 'webviewupdate'])
    print(initial)
    framework = run([*adb, 'shell', 'sha256sum', '/system/framework/framework-res.apk'])
    if not framework.split() or framework.split()[0] != FRAMEWORK_SHA256:
        raise ValueError('Unknown API 26 framework provider configuration')
    signer = apksigner()
    with tempfile.TemporaryDirectory(prefix='webview-signed-', dir=cache) as temp:
        signed = str(Path(temp) / 'webview.apk')
        unsigned = Path(temp) / 'unsigned.apk'
        aligned = str(Path(temp) / 'aligned.apk')
        repackage(cache / 'webview.apk', unsigned)
        run([build_tool('zipalign'), '-f', '-p', '4', str(unsigned), aligned])
        run([signer, 'sign', '--key', str(cache / 'testkey.pk8'),
             '--cert', str(cache / 'testkey.x509.pem'), '--out', signed, aligned])
        signature = run([signer, 'verify', '--print-certs', signed])
        certificates = re.findall(r'certificate SHA-256 digest: ([0-9a-f]{64})\b', signature)
        if not certificates or set(certificates) != {TEST_CERTIFICATE}:
            raise ValueError('Unexpected CI WebView signing certificate')
        run([*adb, 'install', '-r', '--no-streaming', signed])
    selected = run([*adb, 'shell', 'cmd', 'webviewupdate', 'set-webview-implementation', PACKAGE])
    if selected != 'Success':
        raise ValueError(f'Unable to select the verified AOSP WebView: {selected}')
    state = run([*adb, 'shell', 'dumpsys', 'webviewupdate'])
    if f'Current WebView package (name, version): ({PACKAGE}, {VERSION})' not in state:
        raise ValueError('The verified WebView is not the active provider')
    print(f'Active CI WebView: {PACKAGE} {VERSION} on API 26.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=('fetch', 'install'))
    parser.add_argument('cache', type=Path)
    parser.add_argument('--serial', help='Select a connected API 26 emulator for local verification')
    args = parser.parse_args()
    if args.action == 'fetch':
        fetch(args.cache)
    else:
        install(args.cache, args.serial)
