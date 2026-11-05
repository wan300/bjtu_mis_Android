"""Provision a verified WebView on the disposable AOSP API 26 CI emulator only."""
import argparse
import base64
import hashlib
import http.client
import os
from pathlib import Path
import re
import ssl
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

PACKAGE = 'com.android.webview'
VERSION = '124.0.6367.219'
TEST_CERTIFICATE = 'a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc'
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
    return subprocess.run(arguments, check=True, capture_output=True, text=True, timeout=timeout).stdout.strip()


def apksigner():
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        raise ValueError('ANDROID_HOME or ANDROID_SDK_ROOT is required')
    executable = 'apksigner.bat' if os.name == 'nt' else 'apksigner'
    candidates = [path for path in Path(sdk).glob(f'build-tools/*/{executable}')
                  if re.fullmatch(r'\d+(?:\.\d+)*', path.parent.name)]
    if not candidates:
        raise ValueError('Android build-tools apksigner was not found')
    return str(max(candidates, key=lambda path: tuple(map(int, path.parent.name.split('.')))))


def install(cache):
    for name in RESOURCES:
        verify(cache / name, name)
    emulators = [line.split()[0] for line in run(['adb', 'devices']).splitlines()
                 if re.fullmatch(r'emulator-\d+\s+device', line.strip())]
    if len(emulators) != 1:
        raise ValueError('Exactly one connected disposable emulator is required')
    adb = ['adb', '-s', emulators[0]]
    properties = {name: run([*adb, 'shell', 'getprop', name]) for name in
                  ('ro.kernel.qemu', 'ro.build.version.sdk', 'ro.build.type')}
    if (properties['ro.kernel.qemu'] != '1' or properties['ro.build.version.sdk'] != '26' or
            properties['ro.build.type'] not in ('userdebug', 'eng')):
        raise ValueError('WebView provisioning is restricted to debuggable API 26 emulators')
    if PACKAGE not in run([*adb, 'shell', 'dumpsys', 'webviewupdate']):
        raise ValueError('The emulator framework must register the AOSP WebView provider')
    signer = apksigner()
    with tempfile.TemporaryDirectory(prefix='webview-signed-', dir=cache) as temp:
        signed = str(Path(temp) / 'webview.apk')
        run([signer, 'sign', '--key', str(cache / 'testkey.pk8'),
             '--cert', str(cache / 'testkey.x509.pem'), '--out', signed, str(cache / 'webview.apk')])
        signature = run([signer, 'verify', '--print-certs', signed])
        if f'Signer #1 certificate SHA-256 digest: {TEST_CERTIFICATE}' not in signature:
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
    args = parser.parse_args()
    (fetch if args.action == 'fetch' else install)(args.cache)
