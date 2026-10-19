#!/usr/bin/env python3
"""Sequential live limits, guarded by non-disruptive health checks and rollback."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time

from collect import cgroup, pairs
from probe import TARGETS, probe

MIB = 1024 * 1024
BUDGET = {'worker': (256, 384, 35000), 'api': (192, 320, 50000), 'postgres': (256, 384, 40000)}
FIELDS = ('Memory', 'MemorySwap', 'MemoryReservation', 'NanoCpus', 'CpuQuota', 'CpuPeriod', 'PidsLimit')


def run(args):
    p = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
    if p.returncode:
        raise RuntimeError('{} failed: {}'.format(args[0], p.stderr[:300]))
    return p.stdout.strip()


def inspect(name):
    # Docker inspect is reduced in-process; environment/labels never leave the host.
    raw = json.loads(run(['docker', 'inspect', name]))[0]
    state = raw['State']
    return {'id': raw['Id'], 'name': raw['Name'].lstrip('/'), 'image': raw['Image'], 'startedAt': state['StartedAt'], 'running': state['Running'], 'pid': state['Pid'], 'restarts': raw['RestartCount'], 'oomKilled': state['OOMKilled'], 'health': state.get('Health', {}).get('Status'), 'limits': {key: raw['HostConfig'].get(key) for key in FIELDS}}


def health(baseline):
    checks = [probe(name, url, kind, timeout=5) for name, url, kind in TARGETS + (('local_api', 'http://127.0.0.1:15020/health', 'health'),)]
    if not all(row['ok'] for row in checks):
        raise RuntimeError('Endpoint health check failed')
    run(['docker', 'exec', 'bjtu-plugin-platform-postgres-1', 'pg_isready', '-U', 'bjtu_plugins', '-d', 'bjtu_plugins'])
    for before in baseline:
        now = inspect(before['name'])
        if any(now[key] != before[key] for key in ('id', 'startedAt', 'restarts')) or not now['running'] or now['oomKilled'] or now['health'] == 'unhealthy':
            raise RuntimeError('Container state changed: ' + before['name'])
    return checks


def rollback_one(before):
    now = inspect(before['name'])
    if now['id'] != before['id']:
        raise RuntimeError('Refusing to roll back a replacement container')
    old = before['limits']
    if old.get('NanoCpus'):
        raise RuntimeError('NanoCPU rollback requires a reviewed migration')
    # Docker ignores a zero memory update, and this installed version leaves the
    # previous cgroup swap max when passed -1. A ceiling above RAM+swap and a
    # positive combined total restore effectively unlimited host allocation.
    memory = old['Memory'] or 8 * 1024**3
    swap = old['MemorySwap'] if old['MemorySwap'] and old['MemorySwap'] > 0 else memory + 2 * 1024**3
    args = ['docker', 'update', '--memory', str(memory), '--memory-swap', str(swap), '--cpu-period', str(old['CpuPeriod'] or 100000), '--cpu-quota', str(old['CpuQuota'] or -1), '--pids-limit', str(old.get('PidsLimit') or -1), before['id']]
    run(args)
    enforced = cgroup(now['pid'])
    if enforced['memoryLimitBytes'] != memory or enforced['swapLimitBytes'] != swap - memory or enforced['cpuMax'].split()[0] != (str(old['CpuQuota']) if old['CpuQuota'] else 'max'):
        raise RuntimeError('Rollback cgroup verification failed')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['apply', 'rollback'])
    parser.add_argument('--backup-dir', type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o077)
    backup = args.backup_dir.resolve()
    if os.geteuid() != 0 or not backup.is_relative_to(Path('/var/backups/bjtu-ops')):
        raise SystemExit('Run as root with /var/backups/bjtu-ops backup')
    path = backup / 'resources.json'
    if args.action == 'rollback':
        saved = json.loads(path.read_text())
        for before in reversed(saved['changed']):
            rollback_one(before)
        health(saved['baseline'])
        print('Resource budgets rolled back without replacing containers')
        return
    if path.exists():
        raise SystemExit('Resource backup exists; use a new backup directory')
    baseline = [inspect(cid) for cid in run(['docker', 'ps', '-q']).split()]
    health(baseline)
    mem = pairs(Path('/proc/meminfo').read_text())
    if mem['MemAvailable'] < 512 * 1024:
        raise RuntimeError('Insufficient host headroom before resource changes')
    saved = {'baseline': baseline, 'changed': []}
    backup.mkdir(mode=0o700, parents=True, exist_ok=True)
    path.write_text(json.dumps(saved, indent=2))
    try:
        for service, (memory, swap, quota) in BUDGET.items():
            name = 'bjtu-plugin-platform-' + service + '-1'
            before = next(row for row in baseline if row['name'] == name)
            if before['limits']['NanoCpus'] or before['limits']['MemoryReservation']:
                raise RuntimeError('Preexisting CPU/reservation policy requires review')
            counters = cgroup(before['pid'])
            if counters['memoryBytes'] > memory * MIB * .75 or counters['pids'] > 64:
                raise RuntimeError('Workload too close to planned limit: ' + name)
            saved['changed'].append(before)
            path.write_text(json.dumps(saved, indent=2))
            run(['docker', 'update', '--memory', str(memory * MIB), '--memory-swap', str(swap * MIB), '--cpu-period', '100000', '--cpu-quota', str(quota), '--pids-limit', '128', before['id']])
            time.sleep(3)
            checks = health(baseline)
            now = inspect(name)
            effective = cgroup(now['pid'])
            expected = {'Memory': memory * MIB, 'MemorySwap': swap * MIB, 'CpuPeriod': 100000, 'CpuQuota': quota, 'PidsLimit': 128}
            if any(now['limits'][key] != value for key, value in expected.items()) or effective['memoryLimitBytes'] != memory * MIB or effective['swapLimitBytes'] != (swap - memory) * MIB or effective['cpuMax'] != str(quota) + ' 100000' or effective['pidsLimit'] != 128:
                raise RuntimeError('Resource limit verification failed')
            print(json.dumps({'updated': name, 'memoryMiB': memory, 'memorySwapTotalMiB': swap, 'cpuCores': quota / 100000, 'checks': checks}), flush=True)
    except Exception:
        for before in reversed(saved['changed']):
            rollback_one(before)
        health(baseline)
        print('Failure: changes reverted to previous effective resource allocation', flush=True)
        raise


if __name__ == '__main__':
    main()
