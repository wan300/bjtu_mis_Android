import copy
import contextlib
import datetime as dt
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'deploy' / 'monitoring'))
import collect
import install
import probe

spec = importlib.util.spec_from_file_location('availability_alert', ROOT / 'tools' / 'availability-alert.py')
alert = importlib.util.module_from_spec(spec)
spec.loader.exec_module(alert)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path == '/redirect':
            self.send_response(302)
            self.send_header('Location', '/website')
            self.end_headers()
            return
        data = {
            '/website': b'<title>BJTU MIS Android - test</title>',
            '/catalog': b'{"apiVersion":3,"contractProfile":"contract_v1","items":[]}',
            '/wrong': b'{"apiVersion":2,"items":[]}',
            '/large': b'x' * (probe.MAX_BODY + 1),
        }.get(self.path, b'error')
        self.send_response(500 if self.path == '/fail' else 200)
        self.end_headers()
        self.wfile.write(data)


class ProbeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = 'http://127.0.0.1:' + str(cls.server.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def test_status_content_redirect_and_size_are_checked(self):
        self.assertTrue(probe.probe('web', self.base + '/website', 'website')['ok'])
        self.assertTrue(probe.probe('api', self.base + '/catalog', 'catalog')['ok'])
        for path in ['/wrong', '/fail', '/redirect', '/large']:
            self.assertFalse(probe.probe('api', self.base + path, 'catalog')['ok'], path)

    def test_transient_failure_retries_without_leaking_body(self):
        with patch.object(probe, 'probe', side_effect=[{'ok': False, 'reason': 'http_error'}, {'ok': True}]):
            result = probe.check_targets(attempts=3, delay=0, targets=[('web', self.base, 'website')])
        self.assertTrue(result['ok'])
        self.assertEqual(result['checks'][0]['attempts'], 2)

    def test_invalid_json_shapes_are_rejected(self):
        for body in [b'[]', b'null', b'bad', b'{"apiVersion":3,"contractProfile":"contract_v1"}']:
            self.assertFalse(probe.valid_body('catalog', body))


class HistoryTests(unittest.TestCase):
    def test_retention_boundary_and_daily_cap(self):
        now = dt.datetime(2026, 10, 20, tzinfo=dt.timezone.utc)
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            old = directory / 'metrics-2026-10-06.jsonl'
            keep = directory / 'metrics-2026-10-07.jsonl'
            old.write_text('old')
            keep.write_text('keep')
            collect.append_history(directory, {'ok': True}, now)
            self.assertFalse(old.exists())
            self.assertTrue(keep.exists())
            with patch.object(collect, 'MAX_DAILY_BYTES', 1):
                with self.assertRaisesRegex(RuntimeError, 'size_cap'):
                    collect.append_history(directory, {'ok': True}, now)

    def test_restart_oom_and_missing_container_are_observable(self):
        sample = {'host': {'memoryAvailableKiB': 500000, 'diskUsedPercent': 20, 'load': [0, 0, 0], 'psi': {'memory': {}, 'io': {}}}, 'containers': [], 'checks': []}
        self.assertTrue(any('missing' in value for value in collect.warnings(sample, {})))
        item = {'name': collect.EXPECTED[0], 'startedAt': 'a', 'restarts': 0, 'memoryEvents': {'oom_kill': 0}}
        previous = {'containers': [copy.deepcopy(item)]}
        item['memoryEvents']['oom_kill'] = 1
        sample['containers'] = [item]
        self.assertTrue(any('new_restart_or_oom' in value for value in collect.warnings(sample, previous)))
        item['startedAt'] = 'b'
        self.assertTrue(any('restarted_or_replaced' in value for value in collect.warnings(sample, previous)))

    def test_cpu_uses_counter_deltas(self):
        self.assertIsNone(collect.delta_percent([10] * 8, None))
        self.assertEqual(collect.delta_percent([10, 0, 0, 90, 0, 0, 0, 0], [0] * 8), 10)


class RollbackTests(unittest.TestCase):
    @unittest.skipIf(sys.platform == 'win32', 'Linux rollback uses POSIX symlinks')
    def test_config_enablement_links_and_history_are_restored_separately(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            original = root / 'original.conf'
            added = root / 'added.conf'
            original.write_text('original config')
            old_link = root / 'existing-link'
            new_link = root / 'new-link'
            old_link.symlink_to(original)
            history = root / 'history.jsonl'
            history.write_text('retained metrics')
            calls = []

            def systemctl(args, check=True):
                calls.append(args)
                if 'show' in args:
                    return 'ActiveState=active\nUnitFileState=enabled' if args[2] == 'old.timer' else 'ActiveState=inactive\nUnitFileState=disabled'
                return ''

            with patch.object(install, 'FILES', (str(original), str(added))), patch.object(install, 'LINKS', (str(old_link), str(new_link))), patch.object(install, 'UNITS', ('old.timer', 'new.timer')), patch.object(install, 'run', side_effect=systemctl):
                backup = root / 'backup'
                install.snapshot(backup)
                original.write_text('changed config')
                added.write_text('new config')
                old_link.unlink()
                old_link.symlink_to(added)
                new_link.symlink_to(added)
                install.restore(backup)
            self.assertEqual(original.read_text(), 'original config')
            self.assertFalse(added.exists())
            self.assertEqual(old_link.resolve(), original)
            self.assertFalse(new_link.is_symlink())
            self.assertEqual(history.read_text(), 'retained metrics')
            self.assertIn(['systemctl', 'enable', 'old.timer'], calls)
            self.assertIn(['systemctl', 'start', 'old.timer'], calls)
            self.assertIn(['systemctl', 'disable', 'new.timer'], calls)


class FakeGitHub:
    def __init__(self):
        self.issues = []
        self.comments = []

    def open_incidents(self, marker):
        return [issue for issue in self.issues if issue['state'] == 'open' and marker in issue['body']]

    def request(self, method, path, body=None):
        if method == 'POST' and path == '/issues':
            issue = dict(body, number=len(self.issues) + 1, html_url='https://github.com/example/repo/issues/1', state='open', assignees=[{'login': user} for user in body['assignees']])
            self.issues.append(issue)
            return issue
        if method == 'POST' and path.endswith('/comments'):
            self.comments.append(body)
            return body
        if method == 'PATCH':
            self.issues[int(path.rsplit('/', 1)[1]) - 1].update(body)
            return body
        raise AssertionError((method, path))


class AlertTests(unittest.TestCase):
    def test_issue_list_lag_cannot_duplicate_or_resurrect_own_write(self):
        api = alert.GitHub('owner/repo', 'unused')
        issue = {'number': 41, 'state': 'open', 'html_url': 'https://github.com/owner/repo/issues/41', 'body': alert.marker_for('production'), 'user': {'login': 'github-actions[bot]'}, 'assignees': [{'login': 'owner'}]}
        creations = []

        def response(request, **kwargs):
            if request.method == 'POST' and request.full_url.endswith('/issues'):
                creations.append(request)
                value = issue
            elif request.method == 'PATCH':
                issue.update(json.loads(request.data))
                value = issue
            elif request.method == 'GET':
                # Always return stale listing: empty before creation, then an
                # open version even after the authoritative PATCH closes it.
                value = [] if issue['state'] == 'open' else [dict(issue, state='open')]
            else:
                value = {}
            return contextlib.closing(io.BytesIO(json.dumps(value).encode()))

        with patch.object(alert.urllib.request, 'urlopen', side_effect=response):
            fail = {'ok': False, 'checks': []}
            healthy = {'ok': True, 'checks': []}
            self.assertEqual(alert.reconcile(api, fail, 'production', 'https://github.com/owner/repo/actions/runs/1', 'owner')['action'], 'opened')
            self.assertEqual(alert.reconcile(api, fail, 'production', 'https://github.com/owner/repo/actions/runs/1', 'owner')['action'], 'already_open')
            self.assertEqual(alert.reconcile(api, healthy, 'production', 'https://github.com/owner/repo/actions/runs/1', 'owner')['action'], 'recovered')
            self.assertEqual(alert.reconcile(api, healthy, 'production', 'https://github.com/owner/repo/actions/runs/1', 'owner')['action'], 'healthy')
        self.assertEqual(len(creations), 1)

    def test_deduplicated_failure_and_recovery(self):
        api = FakeGitHub()
        failure = {'ok': False, 'checks': [{'name': 'catalog', 'ok': False, 'status': 503, 'reason': 'http_error'}]}
        healthy = {'ok': True, 'checks': [{'name': 'catalog', 'ok': True, 'status': 200}]}
        self.assertEqual(alert.reconcile(api, failure, 'production', 'https://github.com/example/repo/actions/runs/1', 'owner')['action'], 'opened')
        self.assertEqual(alert.reconcile(api, failure, 'production', 'https://github.com/example/repo/actions/runs/2', 'owner')['action'], 'already_open')
        self.assertEqual(len(api.issues), 1)
        self.assertEqual(alert.reconcile(api, healthy, 'production', 'https://github.com/example/repo/actions/runs/3', 'owner')['action'], 'recovered')
        self.assertEqual(len(api.comments), 1)
        self.assertEqual(api.issues[0]['state'], 'closed')

    def test_drill_cannot_close_real_incident(self):
        api = FakeGitHub()
        failure = {'ok': False, 'checks': []}
        alert.reconcile(api, failure, 'production', 'https://github.com/example/repo/actions/runs/1', 'owner')
        alert.reconcile(api, failure, 'drill-2', 'https://github.com/example/repo/actions/runs/2', 'owner')
        alert.reconcile(api, {'ok': True, 'checks': []}, 'drill-2', 'https://github.com/example/repo/actions/runs/2', 'owner')
        self.assertEqual(api.issues[0]['state'], 'open')
        self.assertEqual(api.issues[1]['state'], 'closed')

    def test_unsafe_issue_values_and_scope_are_rejected(self):
        with self.assertRaises(ValueError):
            alert.summary({'checks': [{'name': '@everyone\nsecret', 'reason': 'bad'}]})
        with self.assertRaises(ValueError):
            alert.marker_for('arbitrary-marker')

    def test_foreign_issue_marker_is_ignored(self):
        api = alert.GitHub('owner/repo', 'unused')
        with patch.object(api, 'request', return_value=[{'number': 1, 'body': alert.marker_for('production'), 'user': {'login': 'other-user'}}]):
            self.assertEqual(list(api.open_incidents(alert.marker_for('production'))), [])


if __name__ == '__main__':
    unittest.main()
