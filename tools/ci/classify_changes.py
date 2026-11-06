"""Conservative CI selection: unknown paths and shared security inputs run everything."""
import argparse
import json
import os
import re
import subprocess

TARGETS = ('tooling', 'platform', 'frontend', 'android')
SHARED_PREFIXES = ('.github/', 'tools/', 'plugin-tooling/', 'third-party-plugins/', '.specify/')
SHARED_FILES = frozenset(('AGENTS.md', 'PLANS.md', '.gitattributes', 'docs/third-party-services.md'))


def classify(paths):
    selected = set()
    for path in paths:
        if (path.startswith(SHARED_PREFIXES) or path in SHARED_FILES or
                '/generated/' in path or path.endswith('.schema.json')):
            selected.update(TARGETS)
        elif path.startswith('android/open-webui/'):
            selected.update(('frontend', 'android'))
        elif path.startswith('android/'):
            selected.add('android')
        elif path.startswith(('web/', 'deploy/')):
            selected.add('platform')
        elif path in ('README.md', 'LICENSE') or (
                path.startswith('docs/') and path.endswith(('.md', '.png', '.jpg', '.svg', '.webp'))):
            continue
        else:
            selected.update(TARGETS)
    return {target: target in selected for target in TARGETS}


def verify_jobs(selection, jobs):
    expected = {'changes': True, 'tooling-build': selection['tooling'],
                'browser-smoke': selection['tooling'], 'platform': selection['platform'],
                'frontend': selection['frontend'], 'android-jvm': selection['android'],
                'android-instrumentation': selection['android']}
    for job, required in expected.items():
        result = jobs.get(job, {}).get('result')
        allowed = ('success',) if required else ('success', 'skipped')
        if result not in allowed:
            raise ValueError(f'{job}: expected {allowed}, received {result}')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--verify-jobs', action='store_true')
    args = parser.parse_args()
    if args.verify_jobs:
        jobs = json.loads(os.environ['CI_JOB_RESULTS'])
        selection = {name: jobs['changes']['outputs'].get(name) == 'true' for name in TARGETS}
        if any(jobs['changes']['outputs'].get(name) not in ('true', 'false') for name in TARGETS):
            raise ValueError('Missing or invalid change classification')
        verify_jobs(selection, jobs)
        print('Every selected CI job passed.')
        return
    # Pushes and manual runs are full baselines; only PRs use path selection.
    selection = dict.fromkeys(TARGETS, True)
    if os.environ.get('GITHUB_EVENT_NAME') == 'pull_request':
        base = os.environ.get('PR_BASE_SHA', '')
        if not re.fullmatch(r'[a-fA-F0-9]{40}', base):
            raise ValueError('PR_BASE_SHA must be a full commit SHA')
        output = subprocess.check_output(['git', 'diff', '--no-renames', '--name-only', '-z', f'{base}...HEAD'])
        paths = [item.decode('utf-8', errors='strict') for item in output.split(b'\0') if item]
        # An empty diff is not evidence that it is safe to skip validation.
        if paths:
            selection = classify(paths)
    print(json.dumps(selection))
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as stream:
        for name, enabled in selection.items():
            stream.write(f'{name}={str(enabled).lower()}\n')


if __name__ == '__main__':
    main()
