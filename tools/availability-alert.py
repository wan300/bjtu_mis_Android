#!/usr/bin/env python3
"""Deduplicated GitHub incidents. Only public probe summaries enter issue bodies."""
import argparse
import datetime as dt
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request

TITLE = '[Availability] bjtu.cc / plugin platform'


class GitHub:
    def __init__(self, repository, token):
        if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
            raise ValueError('Invalid repository')
        self.repository = repository
        self.token = token

    def request(self, method, path, body=None):
        data = None if body is None else json.dumps(body).encode('utf-8')
        request = urllib.request.Request('https://api.github.com/repos/' + self.repository + path, data=data, method=method, headers={
            'Authorization': 'Bearer ' + self.token,
            'Accept': 'application/vnd.github+json', 'Content-Type': 'application/json',
            'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'BJTU-Availability-Monitor/1',
        })
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                return json.load(response)
        except urllib.error.HTTPError as exc:
            # The response/request must not be printed, as it can include credentials.
            raise RuntimeError('GitHub API HTTP {}'.format(exc.code)) from None

    def open_incidents(self, marker):
        for page in range(1, 11):
            issues = self.request('GET', '/issues?state=open&per_page=100&page=' + str(page))
            for issue in issues:
                if 'pull_request' not in issue and marker in (issue.get('body') or '') and issue.get('user', {}).get('login') == 'github-actions[bot]':
                    yield issue
            if len(issues) < 100:
                return
        raise RuntimeError('Issue pagination safety limit reached')


def marker_for(scope):
    if scope != 'production' and not re.fullmatch(r'drill-\d+', scope):
        raise ValueError('Invalid incident scope')
    return '<!-- bjtu-availability-monitor:v1:' + scope + ' -->'


def summary(report):
    rows = []
    for check in report.get('checks', []):
        name = str(check.get('name', 'unknown'))
        reason = str(check.get('reason', 'ok'))
        if not re.fullmatch(r'[a-z_]+', name) or not re.fullmatch(r'[A-Za-z_]+', reason):
            raise ValueError('Unsafe report values')
        status = check.get('status')
        elapsed = check.get('elapsedMs')
        if status is not None and not isinstance(status, int):
            raise ValueError('Invalid status')
        if elapsed is not None and not isinstance(elapsed, (int, float)):
            raise ValueError('Invalid duration')
        rows.append('- {}: {}, HTTP {}, {} ms, {}'.format(name, 'OK' if check.get('ok') else 'FAILED', status, elapsed, reason))
    return '\n'.join(rows)


def reconcile(api, report, scope, run_url, assignee):
    marker = marker_for(scope)
    existing = list(api.open_incidents(marker))
    if len(existing) > 1:
        raise RuntimeError('Multiple active incidents require review')
    now = dt.datetime.now(dt.timezone.utc).isoformat()
    if report['ok']:
        if not existing:
            return {'action': 'healthy', 'scope': scope}
        issue = existing[0]
        api.request('POST', '/issues/{}/comments'.format(issue['number']), {'body': 'Recovered at {}.\n\n{}\n\n[Verification run]({})'.format(now, summary(report), run_url)})
        api.request('PATCH', '/issues/' + str(issue['number']), {'state': 'closed', 'state_reason': 'completed'})
        return {'action': 'recovered', 'number': issue['number'], 'url': issue['html_url'], 'scope': scope}
    if existing:
        return {'action': 'already_open', 'number': existing[0]['number'], 'url': existing[0]['html_url'], 'scope': scope}
    drill = scope != 'production'
    body = '{}\n\n{}\n\nDetected at {} after bounded retries.\n\n{}\n\n[Probe run]({})\n\nThis issue is updated on recovery; repeated failures do not create duplicate notifications.'.format(
        marker, '**Notification drill: production services were not interrupted.**' if drill else 'Public availability check failed.', now, summary(report), run_url)
    issue = api.request('POST', '/issues', {'title': ('[TEST] ' if drill else '') + TITLE, 'body': body, 'assignees': [assignee]})
    if assignee not in [user['login'] for user in issue.get('assignees', [])]:
        raise RuntimeError('Issue created but required assignee was not confirmed')
    return {'action': 'opened', 'number': issue['number'], 'url': issue['html_url'], 'scope': scope}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--report', required=True)
    parser.add_argument('--drill', action='store_true')
    args = parser.parse_args()
    repository = os.environ['GITHUB_REPOSITORY']
    run_id = os.environ['GITHUB_RUN_ID']
    if not run_id.isdigit():
        raise ValueError('Invalid run ID')
    run_url = 'https://github.com/' + repository + '/actions/runs/' + run_id
    api = GitHub(repository, os.environ['GITHUB_TOKEN'])
    assignee = os.environ.get('BJTU_ALERT_ASSIGNEE', repository.split('/')[0])
    try:
        with open(args.report, encoding='utf-8') as f:
            report = json.load(f)
        if not isinstance(report.get('ok'), bool) or not isinstance(report.get('checks'), list):
            raise ValueError('Invalid report')
        if report['ok'] and ({check.get('name') for check in report['checks']} != {'website', 'catalog'} or not all(check.get('ok') for check in report['checks'])):
            raise ValueError('Incomplete successful report')
    except (OSError, ValueError):
        report = {'ok': False, 'checks': [{'name': 'monitor', 'ok': False, 'reason': 'missing_or_invalid_report'}]}
    # Always reconcile real availability, including on a requested drill run.
    print(json.dumps(reconcile(api, report, 'production', run_url, assignee)))
    if args.drill and report['ok']:
        scope = 'drill-' + run_id
        failed = {'ok': False, 'checks': [{'name': 'notification_drill', 'ok': False, 'reason': 'controlled_simulation', 'status': 503, 'elapsedMs': 0}]}
        opened = reconcile(api, failed, scope, run_url, assignee)
        repeated = reconcile(api, failed, scope, run_url, assignee)
        if repeated['action'] != 'already_open' or repeated['number'] != opened['number']:
            raise RuntimeError('Deduplication drill failed')
        print(json.dumps(opened))
        print(json.dumps(repeated))
        print(json.dumps(reconcile(api, report, scope, run_url, assignee)))
    return 0 if report['ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
