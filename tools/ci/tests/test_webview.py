import base64
import binascii
import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('prepare_webview', Path(__file__).resolve().parents[1] / 'prepare_webview.py')
webview = importlib.util.module_from_spec(spec)
spec.loader.exec_module(webview)


class WebViewProvisioningTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.cache = Path(temporary.name)
        self.resources = {}
        for name in webview.RESOURCES:
            content = f'synthetic fixture {name}'.encode()
            (self.cache / name).write_bytes(content)
            self.resources[name] = ('https://example.invalid/' + name, hashlib.sha256(content).hexdigest(), 1024)
        patched = patch.object(webview, 'RESOURCES', self.resources)
        patched.start()
        self.addCleanup(patched.stop)

    def fake_run(self, properties=None, provider=True, certificate=True, selection='Success', active=True, devices=None):
        calls = []
        state = {'selected': False}
        values = {'ro.kernel.qemu': '1', 'ro.build.version.sdk': '26', 'ro.build.type': 'userdebug', **(properties or {})}

        def run(args):
            calls.append(args)
            if args == ['adb', 'devices']:
                return devices if devices is not None else 'List of devices attached\nemulator-5554\tdevice'
            if 'getprop' in args:
                return values[args[-1]]
            if 'dumpsys' in args:
                if state['selected']:
                    return f'Current WebView package (name, version): ({webview.PACKAGE}, {webview.VERSION if active else "wrong"})'
                return 'Valid package ' + (webview.PACKAGE if provider else 'com.google.android.webview')
            if 'verify' in args:
                return 'Signer #1 certificate SHA-256 digest: ' + (webview.TEST_CERTIFICATE if certificate else 'invalid')
            if 'set-webview-implementation' in args:
                state['selected'] = True
                return selection
            return 'Success'
        return run, calls

    def install(self, **options):
        runner, calls = self.fake_run(**options)
        with patch.object(webview, 'run', side_effect=runner), patch.object(webview, 'apksigner', return_value='/sdk/apksigner'):
            webview.install(self.cache)
        return calls

    def test_success_signs_verifies_selects_and_confirms_actual_provider(self):
        calls = self.install()
        self.assertTrue(any('sign' in args for args in calls))
        self.assertTrue(any('verify' in args for args in calls))
        self.assertTrue(any('install' in args for args in calls))
        self.assertTrue(all(args[:3] == ['adb', '-s', 'emulator-5554'] for args in calls if args[0] == 'adb' and args != ['adb', 'devices']))

    def test_non_emulator_wrong_api_and_non_debuggable_devices_are_rejected_before_install(self):
        for properties in [{'ro.kernel.qemu': '0'}, {'ro.build.version.sdk': '35'}, {'ro.build.type': 'user'}]:
            runner, calls = self.fake_run(properties=properties)
            with patch.object(webview, 'run', side_effect=runner), self.assertRaises(ValueError):
                webview.install(self.cache)
            self.assertFalse(any('install' in args for args in calls))
        for devices in ['List of devices attached\nphysical\tdevice', 'emulator-5554\tdevice\nemulator-5556\tdevice']:
            with self.assertRaises(ValueError):
                self.install(devices=devices)

    def test_wrong_framework_signer_selection_or_active_version_fails_closed(self):
        for options in [{'provider': False}, {'certificate': False}, {'selection': 'Failed'}, {'active': False}]:
            with self.assertRaises(ValueError):
                self.install(**options)

    def test_corrupt_cached_input_is_rejected_before_any_device_command(self):
        for name in self.resources:
            original = (self.cache / name).read_bytes()
            (self.cache / name).write_bytes(b'corrupt')
            with patch.object(webview, 'run') as runner, self.assertRaises(ValueError):
                webview.install(self.cache)
            runner.assert_not_called()
            (self.cache / name).write_bytes(original)

    def test_download_decode_requires_valid_base64_and_pinned_digest(self):
        output = self.cache / 'decoded.apk'
        content = (self.cache / 'webview.apk').read_bytes()
        webview.decode_resource(base64.b64encode(content), output, 'webview.apk')
        for encoded in [b'!invalid!', base64.b64encode(b'wrong artifact')]:
            with self.assertRaises((ValueError, binascii.Error)):
                webview.decode_resource(encoded, output, 'webview.apk')

    def test_cache_hits_still_validate_every_input_without_network(self):
        with patch.object(webview.urllib.request, 'build_opener') as opener:
            webview.fetch(self.cache)
            opener.return_value.open.assert_not_called()
            (self.cache / 'testkey.pk8').write_bytes(b'corrupt')
            with self.assertRaises(ValueError):
                webview.fetch(self.cache)

    def test_redirects_are_rejected(self):
        with self.assertRaises(ValueError):
            webview.NoRedirect().redirect_request(None, None, 302, '', {}, 'http://example.invalid')

    def test_http_retries_only_transient_errors(self):
        opener = Mock()
        opener.open.side_effect = [webview.urllib.error.HTTPError('https://example.invalid', 503, '', {}, None), io.BytesIO(b'YWJj')]
        with patch.object(webview.time, 'sleep') as sleep:
            self.assertEqual(webview.read_encoded(opener, 'https://example.invalid', 100), b'YWJj')
            sleep.assert_called_once()
        for error in [webview.urllib.error.HTTPError('https://example.invalid', 403, '', {}, None),
                      webview.urllib.error.URLError(webview.ssl.SSLCertVerificationError('bad certificate')),
                      ValueError('redirect forbidden')]:
            opener = Mock()
            opener.open.side_effect = error
            with patch.object(webview.time, 'sleep') as sleep, self.assertRaises(type(error)):
                webview.read_encoded(opener, 'https://example.invalid', 100)
            sleep.assert_not_called()
            self.assertEqual(opener.open.call_count, 1)

    def test_http_retry_budget_and_size_limit_are_enforced(self):
        opener = Mock()
        opener.open.side_effect = webview.urllib.error.HTTPError('https://example.invalid', 503, '', {}, None)
        with patch.object(webview.time, 'sleep'), self.assertRaises(webview.urllib.error.HTTPError):
            webview.read_encoded(opener, 'https://example.invalid', 100)
        self.assertEqual(opener.open.call_count, 3)
        opener.open.side_effect = None
        opener.open.return_value = io.BytesIO(b'oversized')
        with self.assertRaises(ValueError):
            webview.read_encoded(opener, 'https://example.invalid', 2)

    def git_runner(self, commit=None, content=None):
        calls = []
        def run(args, **kwargs):
            calls.append(args)
            if 'clone' in args:
                provider = Path(args[-1]) / 'prebuilt/x86_64/webview.apk'
                provider.parent.mkdir(parents=True)
                provider.write_bytes(content if content is not None else (self.cache / 'webview.apk').read_bytes())
            return (commit or webview.WEBVIEW_COMMIT) if 'rev-parse' in args else ''
        return run, calls

    def test_git_checkout_requires_pinned_commit_and_digest(self):
        output = self.cache / 'fetched.apk'
        runner, calls = self.git_runner()
        with patch.object(webview, 'run', side_effect=runner):
            webview.fetch_apk_git(output)
        self.assertEqual(output.read_bytes(), (self.cache / 'webview.apk').read_bytes())
        self.assertTrue(all('http.followRedirects=false' in args for args in calls if 'rev-parse' not in args))
        for options in [{'commit': 'wrong'}, {'content': b'wrong artifact'}]:
            runner, _ = self.git_runner(**options)
            with patch.object(webview, 'run', side_effect=runner), self.assertRaises(ValueError):
                webview.fetch_apk_git(self.cache / 'rejected.apk')
            self.assertFalse((self.cache / 'rejected.apk').exists())

    def test_git_transport_has_a_bounded_retry_budget(self):
        with patch.object(webview, 'run', side_effect=webview.subprocess.CalledProcessError(1, ['git'])) as runner, \
                patch.object(webview.time, 'sleep'), self.assertRaises(webview.subprocess.CalledProcessError):
            webview.fetch_apk_git(self.cache / 'rejected.apk')
        self.assertEqual(runner.call_count, 2)


if __name__ == '__main__':
    unittest.main()
