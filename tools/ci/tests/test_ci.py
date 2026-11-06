import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'tools/ci' / f'{name}.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


changes = load('classify_changes')
instrumentation = load('check_instrumentation')
gradle = load('check_gradle_tasks')


class GradleGraphTests(unittest.TestCase):
    def graph(self, tasks):
        return '\n'.join(f'{task} SKIPPED' for task in tasks)

    def test_compile_graph_excludes_frontend_but_retains_all_native_checks(self):
        tasks = [':app:testDebugUnitTest', ':app:compileDebugAndroidTestKotlin', ':app:compileReleaseKotlin']
        gradle.check_graph('compile', self.graph(tasks))
        for invalid in [tasks[:-1], [*tasks, ':app:buildOpenWebUi'], []]:
            with self.assertRaises(ValueError):
                gradle.check_graph('compile', self.graph(invalid))

    def test_packaging_graph_requires_real_assets_in_order(self):
        tasks = [*gradle.FRONTEND, ':app:mergeDebugAssets', ':app:packageDebug', ':app:assembleDebug']
        gradle.check_graph('package', self.graph(tasks))
        for invalid in [tasks[1:], tasks[::-1], []]:
            with self.assertRaises(ValueError):
                gradle.check_graph('package', self.graph(invalid))


class ClassificationTests(unittest.TestCase):
    def invoke(self, event, **extra):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / 'outputs'
            env = {**os.environ, 'GITHUB_EVENT_NAME': event, 'GITHUB_OUTPUT': str(output), **extra}
            result = subprocess.run([sys.executable, '-B', str(ROOT / 'tools/ci/classify_changes.py')],
                                    env=env, capture_output=True, text=True)
            return result, output.read_text(encoding='utf-8') if output.exists() else ''

    def test_manual_and_push_entrypoints_emit_full_baseline(self):
        for event in ['workflow_dispatch', 'push']:
            result, output = self.invoke(event)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(set(output.splitlines()), {f'{name}=true' for name in changes.TARGETS})

    def test_invalid_pr_base_fails_before_any_skip_outputs(self):
        result, output = self.invoke('pull_request', PR_BASE_SHA='--invalid-ref')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(output, '')

    def test_shared_and_unknown_paths_run_every_consumer(self):
        for path in ['plugin-tooling/contracts/capability-contracts.json', 'tools/ci/classify_changes.py',
                     '.github/workflows/plugin-platform-v3.yml', 'docs/third-party-services.md',
                     'web/assets/schemas/third-party-service-manifest.schema.json',
                     'android/app/src/main/java/generated/Contracts.kt', '.specify/memory/constitution.md',
                     '.gitattributes', 'unknown/new-file', 'third-party-plugins/templates/basic/index.html']:
            self.assertTrue(all(changes.classify([path]).values()), path)

    def test_module_selection_and_union(self):
        self.assertEqual(changes.classify(['web/platform/src/server.ts']),
                         dict(tooling=False, platform=True, frontend=False, android=False))
        self.assertEqual(changes.classify(['android/open-webui/src/lib/local-first/agent.ts']),
                         dict(tooling=False, platform=False, frontend=True, android=True))
        self.assertTrue(changes.classify(['web/index.html', 'android/app/build.gradle.kts'])['android'])
        self.assertFalse(any(changes.classify(['README.md', 'docs/ci-validation-execplan.md']).values()))

    def test_gate_rejects_failed_missing_cancelled_or_unexpectedly_skipped_jobs(self):
        selection = dict.fromkeys(changes.TARGETS, True)
        jobs = {name: {'result': 'success'} for name in
                ('changes', 'tooling-build', 'browser-smoke', 'platform', 'frontend', 'android-jvm', 'android-instrumentation')}
        changes.verify_jobs(selection, jobs)
        for job in jobs:
            for result in ['failure', 'cancelled', 'skipped', None]:
                invalid = json.loads(json.dumps(jobs))
                invalid[job]['result'] = result
                with self.assertRaises(ValueError, msg=f'{job}: {result}'):
                    changes.verify_jobs(selection, invalid)
        jobs['android-instrumentation']['result'] = 'skipped'
        with self.assertRaises(ValueError):
            changes.verify_jobs(selection, jobs)

    def test_documentation_only_run_can_skip_module_jobs(self):
        jobs = {name: {'result': 'skipped'} for name in
                ('tooling-build', 'browser-smoke', 'platform', 'frontend', 'android-jvm', 'android-instrumentation')}
        jobs['changes'] = {'result': 'success'}
        changes.verify_jobs(dict.fromkeys(changes.TARGETS, False), jobs)

    def test_renamed_code_is_not_hidden_by_documentation_destination(self):
        selected = changes.classify(['android/open-webui/src/old.ts', 'docs/old.md'])
        self.assertTrue(selected['android'])
        self.assertTrue(selected['frontend'])


class InstrumentationTests(unittest.TestCase):
    def check(self, xml, required=None):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            if xml is not None:
                (path / 'TEST-security.xml').write_text(xml, encoding='utf-8')
            instrumentation.check_reports(path, required or {('security.Test', 'bridge')})

    def test_pass_and_optional_arraybuffer_skip(self):
        self.check('<testsuite><testcase classname="security.Test" name="bridge"/></testsuite>')
        self.check('<testsuite><testcase classname="security.Test" name="realJavaScriptUploadsThroughArrayBufferTransport"><skipped/></testcase></testsuite>',
                   {('security.Test', instrumentation.OPTIONAL_TEST)})

    def test_missing_skipped_failed_and_malformed_reports_fail(self):
        for xml in [None, '<testsuite/>', '<invalid',
                    '<testsuite><testcase classname="security.Test" name="bridge"><skipped/></testcase></testsuite>',
                    '<testsuite><testcase classname="security.Test" name="bridge"><failure/></testcase></testsuite>',
                    '<testsuite errors="1"><testcase classname="security.Test" name="bridge"/></testsuite>']:
            with self.assertRaises((ValueError, instrumentation.ET.ParseError)):
                self.check(xml)

    def test_required_tests_follow_current_security_sources(self):
        tests = instrumentation.required_tests(ROOT)
        self.assertGreaterEqual(len(tests), 16)
        self.assertTrue(any(name == 'documentStartRunsBeforePageCodeAndRemoteFramesReceiveNoBridge' for _, name in tests))
        self.assertTrue(any(name == 'realJavaScriptUploadsThroughBase64UrlCompatibilityTransport' for _, name in tests))


if __name__ == '__main__':
    unittest.main()
