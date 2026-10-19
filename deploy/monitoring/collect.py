#!/usr/bin/env python3
"""Private per-minute host/cgroup history, with bounded retention and work."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

from probe import TARGETS, probe

EXPECTED = ('bjtu-plugin-platform-api-1', 'bjtu-plugin-platform-worker-1', 'bjtu-plugin-platform-postgres-1')
MAX_DAILY_BYTES = 16 * 1024 * 1024
RETENTION_DAYS = 14


def pairs(text):
    result = {}
    for line in text.splitlines():
        parts = line.replace(':', '').split()
        if len(parts) >= 2:
            try:
                result[parts[0]] = int(parts[1])
            except ValueError:
                pass
    return result


def pressure(text):
    return {p[0]: {key: float(value) for key, value in (v.split('=') for v in p[1:])} for p in (line.split() for line in text.splitlines()) if p}


def read(path):
    return Path(path).read_text(encoding='utf-8')


def run(args):
    p = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=5, check=True)
    return p.stdout


def delta_percent(current, previous):
    if not previous or len(current) != len(previous):
        return None
    delta = [max(0, a - b) for a, b in zip(current, previous)]
    total = sum(delta)
    return round(100 * (total - delta[3] - delta[4]) / total, 2) if total else 0


def host(previous):
    mem = pairs(read('/proc/meminfo'))
    vm = pairs(read('/proc/vmstat'))
    cpu = [int(v) for v in read('/proc/stat').splitlines()[0].split()[1:9]]
    disk = shutil.disk_usage('/')
    stats = {
        'memoryTotalKiB': mem['MemTotal'], 'memoryAvailableKiB': mem['MemAvailable'],
        'swapUsedKiB': mem['SwapTotal'] - mem['SwapFree'], 'load': list(os.getloadavg()),
        'diskUsedPercent': round(100 * disk.used / disk.total, 2),
        'cpuBusyPercent': delta_percent(cpu, previous.get('cpu')),
        'cpuCounters': cpu,
        'vmCounters': {k: vm[k] for k in ('pswpin', 'pswpout', 'pgmajfault', 'oom_kill') if k in vm},
        'psi': {name: pressure(read('/proc/pressure/' + name)) for name in ('cpu', 'memory', 'io')},
    }
    return stats


def cgroup(pid):
    lines = read('/proc/{}/cgroup'.format(pid)).splitlines()
    relative = next((line[3:] for line in lines if line.startswith('0::')), None)
    if relative is None:
        raise RuntimeError('cgroup_v2_required')
    base = Path('/sys/fs/cgroup').resolve()
    root = (base / relative.lstrip('/')).resolve()
    if not root.is_relative_to(base):
        raise RuntimeError('invalid_cgroup_path')
    memstat = pairs(read(root / 'memory.stat'))
    current = int(read(root / 'memory.current'))
    limit = read(root / 'memory.max').strip()
    swap_limit = read(root / 'memory.swap.max').strip()
    pid_limit = read(root / 'pids.max').strip()
    return {
        'memoryBytes': current,
        'workingSetBytes': max(0, current - memstat.get('inactive_file', 0)),
        'memoryLimitBytes': None if limit == 'max' else int(limit),
        'swapLimitBytes': None if swap_limit == 'max' else int(swap_limit),
        'memoryEvents': pairs(read(root / 'memory.events')),
        'cpu': pairs(read(root / 'cpu.stat')),
        'cpuMax': read(root / 'cpu.max').strip(),
        'pids': int(read(root / 'pids.current')),
        'pidsLimit': None if pid_limit == 'max' else int(pid_limit),
    }


def containers():
    ids = run(['docker', 'ps', '-q']).split()
    if any(not re.fullmatch('[a-f0-9]{12,64}', value) for value in ids) or len(ids) > 32:
        raise RuntimeError('unexpected_container_inventory')
    if not ids:
        return []
    template = '{"name":{{json .Name}},"pid":{{.State.Pid}},"running":{{.State.Running}},"startedAt":{{json .State.StartedAt}},"restarts":{{.RestartCount}},"oomKilled":{{.State.OOMKilled}},"health":{{if .State.Health}}{{json .State.Health.Status}}{{else}}null{{end}}}'
    result = []
    for line in run(['docker', 'inspect', '--format', template] + ids).splitlines():
        item = json.loads(line)
        item['name'] = item['name'].lstrip('/')
        try:
            item.update(cgroup(item.pop('pid')))
        except (OSError, ValueError, RuntimeError) as exc:
            item['counterError'] = type(exc).__name__
        result.append(item)
    return result


def warnings(sample, previous):
    result = []
    h = sample['host']
    if h['memoryAvailableKiB'] < 160 * 1024:
        result.append('host_memory_available_below_160MiB')
    if h['diskUsedPercent'] >= 85:
        result.append('root_disk_above_85percent')
    if h['psi']['memory'].get('full', {}).get('avg60', 0) >= 5:
        result.append('memory_full_stall_above_5percent')
    if h['psi']['io'].get('full', {}).get('avg60', 0) >= 10:
        result.append('io_full_stall_above_10percent')
    if h['load'][1] > os.cpu_count() * 2:
        result.append('sustained_high_load')
    old = {c['name']: c for c in previous.get('containers', [])}
    names = {c['name'] for c in sample['containers']}
    for name in EXPECTED:
        if name not in names:
            result.append(name + ':missing')
    for item in sample['containers']:
        name = item['name']
        if item.get('oomKilled') or item.get('health') == 'unhealthy' or item.get('counterError'):
            result.append(name + ':unhealthy_or_missing_counters')
        limit = item.get('memoryLimitBytes')
        if limit and item.get('workingSetBytes', 0) > limit * .85:
            result.append(name + ':memory_above_85percent')
        before = old.get(name, {})
        if before.get('startedAt') == item['startedAt']:
            if item['restarts'] > before.get('restarts', 0) or item.get('memoryEvents', {}).get('oom_kill', 0) > before.get('memoryEvents', {}).get('oom_kill', 0):
                result.append(name + ':new_restart_or_oom')
        elif before:
            result.append(name + ':container_restarted_or_replaced')
    for check in sample['checks']:
        if not check['ok']:
            result.append(check['name'] + ':probe_failed')
    return result


def append_history(directory, sample, now):
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    cutoff = now.date() - dt.timedelta(days=RETENTION_DAYS - 1)
    for path in directory.glob('metrics-????-??-??.jsonl'):
        try:
            day = dt.date.fromisoformat(path.name[8:18])
        except ValueError:
            continue
        if day < cutoff and path.is_file() and not path.is_symlink():
            path.unlink()
    path = directory / ('metrics-' + now.date().isoformat() + '.jsonl')
    data = (json.dumps(sample, separators=(',', ':')) + '\n').encode('utf-8')
    if path.exists() and path.stat().st_size + len(data) > MAX_DAILY_BYTES:
        raise RuntimeError('daily_history_size_cap_reached')
    with path.open('ab') as f:
        f.write(data)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--state-dir', type=Path, default=Path('/var/lib/bjtu-monitor'))
    parser.add_argument('--log-dir', type=Path, default=Path('/var/log/bjtu-monitor'))
    args = parser.parse_args()
    os.umask(0o077)
    args.state_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    state_path = args.state_dir / 'previous.json'
    try:
        previous = json.loads(read(state_path))
    except (OSError, ValueError):
        previous = {}
    now = dt.datetime.now(dt.timezone.utc)
    boot_id = read('/proc/sys/kernel/random/boot_id').strip()
    if previous.get('bootId') != boot_id:
        previous = {}
    sample = {'time': now.isoformat(), 'bootId': boot_id, 'host': host(previous)}
    try:
        sample['containers'] = containers()
    except Exception as exc:
        sample['containers'] = []
        sample['containerCollectionError'] = type(exc).__name__
    targets = TARGETS + (('local_api', 'http://127.0.0.1:15020/health', 'health'),)
    sample['checks'] = [probe(name, url, kind, timeout=5) for name, url, kind in targets]
    sample['warnings'] = warnings(sample, previous)
    append_history(args.log_dir, sample, now)
    state = {'bootId': boot_id, 'cpu': sample['host']['cpuCounters'], 'containers': sample['containers']}
    temporary = state_path.with_suffix('.tmp')
    temporary.write_text(json.dumps(state, separators=(',', ':')), encoding='utf-8')
    temporary.replace(state_path)
    print(json.dumps({'time': sample['time'], 'ok': not sample['warnings'], 'warnings': sample['warnings']}, separators=(',', ':')))
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(json.dumps({'collectorFailed': type(exc).__name__}), file=sys.stderr)
        raise SystemExit(1)
