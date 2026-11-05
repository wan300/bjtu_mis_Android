"""Fail CI if security instrumentation is missing, failed, or silently skipped."""
import argparse
from pathlib import Path
import re
import xml.etree.ElementTree as ET

TEST_ROOT = Path('android/app/src/androidTest/kotlin/cn/edu/bjtu/mis')
SECURITY_SOURCES = (
    'ui/ThirdPartyWebViewV3InstrumentationTest.kt',
    'data/db/AppDatabaseMigrationInstrumentationTest.kt',
    'data/sync/SessionKeepAliveInstrumentationTest.kt',
    'data/agent/PluginAutoStartDeviceTest.kt',
)
# ArrayBuffer is optional; secure transport and the Base64URL fallback are required.
OPTIONAL_TEST = 'realJavaScriptUploadsThroughArrayBufferTransport'


def required_tests(repository):
    required = set()
    for relative in SECURITY_SOURCES:
        source = (repository / TEST_ROOT / relative).read_text(encoding='utf-8')
        package = re.search(r'^package\s+(\S+)', source, re.MULTILINE).group(1)
        classname = Path(relative).stem
        methods = re.findall(r'@Test\b.*?\bfun\s+(\w+)\s*\(', source, re.DOTALL)
        if not methods:
            raise ValueError(f'No security tests discovered in {relative}')
        required.update((f'{package}.{classname}', method) for method in methods)
    return required


def check_reports(directory, required):
    reports = sorted(directory.rglob('*.xml'))
    if not reports:
        raise ValueError('No instrumentation XML reports found')
    observed = set()
    for report in reports:
        root = ET.parse(report).getroot()
        for suite in root.iter('testsuite'):
            if int(suite.get('failures', '0')) or int(suite.get('errors', '0')):
                raise ValueError(f'Instrumentation suite failed: {report}')
        for case in root.iter('testcase'):
            key = (case.get('classname'), case.get('name'))
            if case.find('failure') is not None or case.find('error') is not None:
                raise ValueError(f'Instrumentation test failed: {key}')
            if key in required:
                if case.find('skipped') is not None and key[1] != OPTIONAL_TEST:
                    raise ValueError(f'Required security test was skipped: {key}')
                observed.add(key)
    missing = required - observed
    if missing:
        raise ValueError(f'Missing security tests: {sorted(missing)}')
    print(f'Instrumentation coverage verified: {len(observed)} security tests reported.')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('reports', type=Path)
    args = parser.parse_args()
    check_reports(args.reports, required_tests(Path(__file__).resolve().parents[2]))


if __name__ == '__main__':
    main()
