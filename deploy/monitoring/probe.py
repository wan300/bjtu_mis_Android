#!/usr/bin/env python3
"""Bounded public health probes; response bodies and exception details stay private."""
import argparse
import json
import time
import urllib.error
import urllib.request

MAX_BODY = 1024 * 1024
TARGETS = (
    ('website', 'https://bjtu.cc/', 'website'),
    ('catalog', 'https://bjtu.cc/api/v3/plugins?limit=1', 'catalog'),
)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def valid_body(kind, body):
    if kind == 'website':
        return b'<title>BJTU MIS Android' in body
    try:
        data = json.loads(body)
    except (ValueError, UnicodeError):
        return False
    if not isinstance(data, dict):
        return False
    if kind == 'catalog':
        return data.get('apiVersion') == 3 and data.get('contractProfile') == 'contract_v1' and isinstance(data.get('items'), list)
    return kind == 'health' and data.get('ok') is True


def probe(name, url, kind, timeout=6):
    start = time.monotonic()
    result = {'name': name, 'ok': False, 'status': None}
    try:
        request = urllib.request.Request(url, headers={'User-Agent': 'BJTU-Availability-Monitor/1', 'Accept': 'application/json' if kind != 'website' else 'text/html'})
        # System CA validation stays enabled. Redirects cannot hide a login/error page.
        with urllib.request.build_opener(NoRedirect).open(request, timeout=timeout) as response:
            result['status'] = response.status
            body = response.read(MAX_BODY + 1)
            result['ok'] = response.status == 200 and len(body) <= MAX_BODY and valid_body(kind, body)
            if not result['ok']:
                result['reason'] = 'invalid_content_or_status'
    except urllib.error.HTTPError as exc:
        result.update(status=exc.code, reason='http_error')
    except Exception as exc:
        # URLs, response bodies, credentials, and server exception text are never logged.
        result['reason'] = type(exc).__name__
    result['elapsedMs'] = round((time.monotonic() - start) * 1000)
    return result


def check_targets(attempts=3, delay=5, targets=TARGETS):
    results = []
    for name, url, kind in targets:
        for attempt in range(1, attempts + 1):
            result = probe(name, url, kind)
            result['attempts'] = attempt
            if result['ok'] or attempt == attempts:
                break
            time.sleep(delay)
        results.append(result)
    return {'ok': all(r['ok'] for r in results), 'checks': results}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output')
    args = parser.parse_args()
    report = check_targets()
    encoded = json.dumps(report, separators=(',', ':'))
    if args.output:
        with open(args.output, 'w', encoding='utf-8') as f:
            f.write(encoded + '\n')
    print(encoded)
    return 0 if report['ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
