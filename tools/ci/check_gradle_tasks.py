"""Verify compilation stays independent while APKs require real frontend assets."""
import argparse
from pathlib import Path
import re

FRONTEND = (':app:npmCiOpenWebUi', ':app:buildOpenWebUi', ':app:syncOpenWebUiAssets')


def check_graph(mode, output):
    tasks = re.findall(r'^(?:> Task )?(:app:\w+)\s+SKIPPED\s*$', output, re.MULTILINE)
    if not tasks:
        raise ValueError('No app tasks found in Gradle dry-run output')
    if mode == 'compile':
        expected = (':app:testDebugUnitTest', ':app:compileDebugAndroidTestKotlin', ':app:compileReleaseKotlin')
        if any(task in tasks for task in FRONTEND):
            raise ValueError('Kotlin/JVM verification unexpectedly requires the frontend')
    else:
        expected = (*FRONTEND, ':app:mergeDebugAssets', ':app:packageDebug', ':app:assembleDebug')
    if any(task not in tasks for task in expected):
        raise ValueError(f'Missing required {mode} tasks: {sorted(set(expected) - set(tasks))}')
    if mode == 'package':
        positions = [tasks.index(task) for task in expected]
        if positions != sorted(positions):
            raise ValueError('Frontend build and sync must precede APK asset merging and packaging')
    print(f'Gradle {mode} task dependencies verified.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=('compile', 'package'))
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    check_graph(args.mode, args.output.read_text(encoding='utf-8'))
