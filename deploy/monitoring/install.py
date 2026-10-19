#!/usr/bin/env python3
"""Install/restore only this task's monitoring config; preserve all collected data."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

UNITS = ('bjtu-monitor.timer', 'bjtu-monitor.service', 'sysstat-collect.timer', 'sysstat-summary.timer', 'sysstat.service')
FILES = (
    '/etc/default/sysstat', '/etc/sysstat/sysstat',
    '/etc/systemd/system/sysstat-collect.timer.d/bjtu-minute.conf',
    '/etc/systemd/system/sysstat-collect.service.d/bjtu-priority.conf',
    '/etc/systemd/system/sysstat-summary.service.d/bjtu-priority.conf',
    '/etc/systemd/system/bjtu-monitor.service', '/etc/systemd/system/bjtu-monitor.timer',
)
LINKS = ('/opt/bjtu-monitor/current', '/opt/bjtu-ops/current', '/usr/local/sbin/bjtu-platform')


def run(args, check=True):
    p = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=65)
    if check and p.returncode:
        raise RuntimeError('{} failed: {}'.format(args[0], p.stderr[:300]))
    return p.stdout.strip()


def write(path, text, mode=0o644):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name('.' + path.name + '.bjtu-new')
    temporary.write_text(text, encoding='utf-8')
    temporary.chmod(mode)
    temporary.replace(path)


def link(path, target):
    path = Path(path)
    if path.exists() and not path.is_symlink():
        raise RuntimeError('Refusing to replace non-symlink ' + str(path))
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.bjtu-new')
    if temporary.is_symlink():
        temporary.unlink()
    temporary.symlink_to(target)
    temporary.replace(path)


def snapshot(backup):
    if (backup / 'monitoring.json').exists():
        raise RuntimeError('Backup already exists; use a new deployment ID')
    backup.mkdir(parents=True, mode=0o700, exist_ok=True)
    backup.chmod(0o700)
    manifest = {'files': {}, 'links': {}, 'units': {}}
    for name in FILES:
        path = Path(name)
        if path.is_symlink():
            raise RuntimeError('Unexpected config symlink ' + name)
        manifest['files'][name] = path.exists()
        if path.exists():
            copy = backup / 'files' / name.lstrip('/')
            copy.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, copy)
    for name in LINKS:
        path = Path(name)
        if path.exists() and not path.is_symlink():
            raise RuntimeError('Unexpected non-symlink ' + name)
        manifest['links'][name] = os.readlink(path) if path.is_symlink() else None
    for unit in UNITS:
        output = run(['systemctl', 'show', unit, '-p', 'ActiveState', '-p', 'UnitFileState'], check=False)
        manifest['units'][unit] = dict(line.split('=', 1) for line in output.splitlines() if '=' in line)
    write(backup / 'monitoring.json', json.dumps(manifest, indent=2), mode=0o600)


def restore(backup):
    manifest = json.loads((backup / 'monitoring.json').read_text())
    for unit in UNITS:
        run(['systemctl', 'stop', unit], check=False)
    for unit in UNITS:
        run(['systemctl', 'disable', unit], check=False)
    for name in FILES:
        path = Path(name)
        if manifest['files'][name]:
            shutil.copy2(backup / 'files' / name.lstrip('/'), path)
        elif path.is_file():
            path.unlink()
    for name in LINKS:
        target = manifest['links'][name]
        if target is not None:
            link(name, target)
        elif Path(name).is_symlink():
            Path(name).unlink()
    run(['systemctl', 'daemon-reload'])
    for unit, state in manifest['units'].items():
        if state.get('UnitFileState') == 'enabled':
            run(['systemctl', 'enable', unit])
    # Also= can enable timer siblings; explicitly restore disabled states last.
    for unit, state in manifest['units'].items():
        if state.get('UnitFileState') == 'disabled':
            run(['systemctl', 'disable', unit])
    for unit, state in manifest['units'].items():
        if state.get('ActiveState') == 'active':
            run(['systemctl', 'start', unit])
    print('Monitoring configuration restored; history preserved')


def install(source, backup):
    snapshot(backup)
    try:
        default = Path('/etc/default/sysstat').read_text()
        if not re.search(r'^ENABLED=', default, re.M):
            raise RuntimeError('Unrecognized sysstat defaults')
        write('/etc/default/sysstat', re.sub(r'^ENABLED=.*$', 'ENABLED="true"', default, flags=re.M))
        conf = Path('/etc/sysstat/sysstat').read_text()
        for key, value in [('HISTORY', '14'), ('COMPRESSAFTER', '3'), ('UMASK', '0077')]:
            if not re.search('^' + key + '=', conf, re.M):
                raise RuntimeError('Missing sysstat option ' + key)
            conf = re.sub('^' + key + '=.*$', key + '=' + value, conf, flags=re.M)
        write('/etc/sysstat/sysstat', conf)
        copies = {
            'sysstat-minute.conf': FILES[2],
            'sysstat-low-priority.conf': FILES[3],
            'bjtu-monitor.service': FILES[5], 'bjtu-monitor.timer': FILES[6],
        }
        for filename, destination in copies.items():
            write(destination, (source / filename).read_text())
        write(FILES[4], (source / 'sysstat-low-priority.conf').read_text())
        link(LINKS[0], source)
        link(LINKS[1], source.parents[1])
        link(LINKS[2], '/opt/bjtu-ops/current/deploy/platform-production.sh')
        run(['systemd-analyze', 'verify', FILES[5], FILES[6]])
        run(['systemctl', 'daemon-reload'])
        run(['systemctl', 'enable', '--now', 'sysstat.service', 'sysstat-collect.timer', 'sysstat-summary.timer', 'bjtu-monitor.timer'])
        run(['systemctl', 'start', 'sysstat-collect.service'])
        run(['systemctl', 'start', 'bjtu-monitor.service'])
        print(json.dumps({'installed': True, 'backup': str(backup), 'historyDays': 14, 'intervalSeconds': 60}))
    except Exception:
        restore(backup)
        raise


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['install', 'rollback'])
    parser.add_argument('--backup-dir', type=Path, required=True)
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise SystemExit('Run as root')
    backup = args.backup_dir.resolve()
    if not backup.is_relative_to(Path('/var/backups/bjtu-ops')):
        raise SystemExit('Backup must be under /var/backups/bjtu-ops')
    os.umask(0o077)
    if args.action == 'rollback':
        restore(backup)
    else:
        source = Path(__file__).resolve().parent
        if not source.is_relative_to(Path('/opt/bjtu-ops/releases')):
            raise SystemExit('Install from an immutable /opt/bjtu-ops/releases directory')
        install(source, backup)


if __name__ == '__main__':
    main()
