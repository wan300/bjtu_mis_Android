import base64
import binascii
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

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


if __name__ == '__main__':
    unittest.main()
