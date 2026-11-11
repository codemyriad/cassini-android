#!/usr/bin/env python3
"""D97 synthetic local-transport checks on a newly created, muted arm64 emulator."""

import argparse
import hashlib
import json
import os
import re
import signal
import socket
import subprocess
import threading
from concurrent.futures import Future
from pathlib import Path

PACKAGE = 'io.codemyriad.cassini.talk.debug'
FIXTURE = 'io.codemyriad.cassini.backup.BackupTransportFixtureTest'
TRANSPORT = 'com.android.localtransport/.LocalTransport'


def backup_result(output):
    """Require the per-package result, not merely a successful bmgr process exit."""
    match = re.search(r'Package ' + re.escape(PACKAGE) + r' with result: ([^\r\n]+)', output)
    if match is None:
        raise ValueError('Missing per-package backup result')
    result = match.group(1).strip()
    finished = re.search(r'Backup finished with result: ([^\r\n]+)', output)
    if finished is None or (result == 'Success' and finished.group(1).strip() != 'Success'):
        raise ValueError('Backup did not report a matching completed result')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--app', type=Path, required=True, help='Locally signed debug APK; no keystore on this host')
    parser.add_argument('--test-apk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if os.environ.get('CASSINI_HEAVY_LOCK_HELD') != '1':
        raise ValueError('Run through the shared heavy-run wrapper')
    args.output.mkdir(parents=True, exist_ok=True)
    avd_home = args.output.parent / 'avd'
    owner = avd_home / 'cassini-d97-owner'
    marker = 'Cassini D97 synthetic backup fixture\n'
    if avd_home.exists() and (not owner.is_file() or owner.read_text() != marker):
        raise ValueError('Refusing an unowned AVD directory')
    avd_home.mkdir(parents=True, exist_ok=True)
    owner.write_text(marker)
    environment = os.environ | {'ANDROID_AVD_HOME': str(avd_home)}
    report = {'package': PACKAGE, 'cases': [], 'restores': [],
              'app_sha256': hashlib.sha256(args.app.read_bytes()).hexdigest(),
              'test_apk_sha256': hashlib.sha256(args.test_apk.read_bytes()).hexdigest()}
    serial = None
    server_port = None

    def run(*command, input_text=None, timeout=180):
        result = subprocess.run(command, input=input_text, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, env=environment, timeout=timeout, check=False)
        with (args.output / 'commands.log').open('a') as log:
            log.write(json.dumps(command) + '\n' + result.stdout + '\n')
        if result.returncode:
            raise RuntimeError(f'Command failed ({result.returncode}): {command}: {result.stdout}')
        return result.stdout

    def adb(*command):
        return run(str(args.sdk / 'platform-tools/adb'), '-P', str(server_port), '-s', serial, *command)

    def fixture(stage, **choices):
        command = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', FIXTURE,
                   '-e', 'ownedBackupEmulator', 'true', '-e', 'backupStage', stage]
        for key, value in choices.items():
            command += ['-e', key, str(value).lower()]
        output = adb(*command, PACKAGE + '.test/io.codemyriad.cassini.CassiniTestRunner')
        if 'OK (1 test)' not in output or 'INSTRUMENTATION_CODE: -1' not in output:
            raise ValueError(f'Fixture stage {stage} did not pass: {output}')

    def backup(name, allowed, quota=False):
        adb('logcat', '-c')
        output = adb('shell', 'bmgr', 'backupnow', PACKAGE)
        result = backup_result(output)
        logcat = adb('logcat', '-d', '-v', 'brief')
        (args.output / (name + '-logcat.txt')).write_text(logcat)
        if allowed:
            if result != 'Success':
                raise ValueError(f'{name} did not back up: {result}')
        elif quota:
            if result != 'Size quota exceeded':
                raise ValueError(f'{name} did not demonstrate the quota refusal: {result}')
            fixture('verifyQuota')
        elif result == 'Success' or 'This backup transport is disabled' not in logcat:
            raise ValueError(f'{name} did not demonstrate the runtime consent refusal: {result}')
        report['cases'].append({'name': name, 'allowed': allowed, 'quota': quota, 'result': result})

    def restore(name):
        sets = adb('shell', 'bmgr', 'list', 'sets')
        match = re.search(r'^\s*([0-9a-fA-F]+)\s*:', sets, re.MULTILINE)
        if match is None:
            raise ValueError(f'No local restore set: {sets}')
        if adb('shell', 'pm', 'clear', PACKAGE).strip() != 'Success':
            raise ValueError('Could not clear the owned synthetic app')
        output = adb('shell', 'bmgr', 'restore', match.group(1), PACKAGE)
        if re.search(r'restoreFinished:\s*0\b', output) is None:
            raise ValueError(f'Restore did not complete: {output}')
        fixture('verify')
        report['restores'].append({'name': name, 'verified': True})

    name = 'cassini-d97'
    if not (avd_home / (name + '.avd')).exists():
        run(str(args.sdk / 'cmdline-tools/latest/bin/avdmanager'), 'create', 'avd', '--name', name,
            '--package', 'system-images;android-35;google_apis;arm64-v8a', '--device', 'pixel_8',
            '--path', str(avd_home / (name + '.avd')), input_text='no\n')
    for port in range(5600, 5620, 2):
        try:
            with socket.socket() as console, socket.socket() as transport, socket.socket() as server:
                console.bind(('127.0.0.1', port))
                transport.bind(('127.0.0.1', port + 1))
                server.bind(('127.0.0.1', port + 100))
            break
        except OSError:
            continue
    else:
        raise ValueError('No free dedicated emulator port')
    serial = f'emulator-{port}'
    report['serial'] = serial
    server_port = port + 100
    report['adb_server_port'] = server_port
    environment.update({
        'ANDROID_ADB_SERVER_PORT': str(server_port),
        'ADB_SERVER_SOCKET': f'tcp:127.0.0.1:{server_port}',
        'ADB_LOCAL_TRANSPORT_MAX_PORT': '0',  # No scanning of other emulators.
        'ADB_MDNS_AUTO_CONNECT': '',
    })
    # This deliberately unmatched USB serial prevents this task's server from claiming physical devices.
    started = run(str(args.sdk / 'platform-tools/adb'), '-P', str(server_port),
                  '--one-device', 'cassini-d97-no-usb', 'start-server')
    if 'daemon started successfully' not in started:
        raise ValueError('Cannot establish ownership of the dedicated ADB server')
    emulator = subprocess.Popen(
        [str(args.sdk / 'emulator/emulator'), '-avd', name, '-port', str(port), '-no-audio', '-no-window',
         '-no-snapshot', '-wipe-data', '-no-boot-anim', '-gpu', 'swiftshader_indirect',
         '-memory', '2048', '-cores', '2', '-camera-back', 'none', '-camera-front', 'none'],
        env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors='replace',
        start_new_session=True,
    )
    booted = Future()

    def emulator_events():
        with (args.output / 'emulator.log').open('w') as log:
            for line in emulator.stdout:
                log.write(line)
                log.flush()
                if 'Boot completed' in line and not booted.done():
                    booted.set_result(True)
        if not booted.done():
            booted.set_exception(RuntimeError('Emulator exited before its boot-completed event'))

    reader = threading.Thread(target=emulator_events, daemon=True)
    reader.start()
    try:
        booted.result(timeout=240)  # Boot-completed or process-exit event; timeout is only a hang guard.
        adb('wait-for-device')
        if adb('emu', 'avd', 'name').splitlines()[0].strip() != name:
            raise ValueError('ADB serial does not belong to this task')
        if adb('shell', 'getprop', 'sys.boot_completed').strip() != '1':
            raise ValueError('Guest boot property disagrees with emulator event')
        report['api'] = int(adb('shell', 'getprop', 'ro.build.version.sdk').strip())
        report['abi'] = adb('shell', 'getprop', 'ro.product.cpu.abi').strip()
        if report['api'] != 35 or report['abi'] != 'arm64-v8a':
            raise ValueError('Expected the owned API 35 arm64 image')
        adb('shell', 'cmd', 'activity', 'wait-for-broadcast-idle')
        adb('install', '-t', str(args.app))
        adb('install', '-t', str(args.test_apk))
        adb('shell', 'settings', 'put', 'secure', 'user_setup_complete', '1')
        adb('shell', 'settings', 'put', 'global', 'device_provisioned', '1')
        adb('shell', 'bmgr', 'enable', 'true')
        selected = adb('shell', 'bmgr', 'transport', TRANSPORT)
        if 'Selected transport' not in selected:
            raise ValueError(f'Local transport was not selected: {selected}')
        adb('shell', 'bmgr', 'init', TRANSPORT)
        fixture('seed')
        adb('shell', 'settings', 'put', 'secure', 'backup_local_transport_parameters',
            'is_encrypted=true,is_device_transfer=false')
        backup('cloud-default-off', False)
        fixture('choices', cloud=True, transfer=False)
        backup('cloud-old-opt-in-refused-by-build-flag', False)
        adb('shell', 'settings', 'put', 'secure', 'backup_local_transport_parameters',
            'is_encrypted=true,is_device_transfer=true')
        fixture('choices', cloud=True, transfer=False)
        backup('transfer-off-cloud-on', False)
        fixture('choices', cloud=False, transfer=True)
        backup('transfer-on-cloud-off', True)
        restore('transfer')
        fixture('oversized')
        backup('transfer-over-quota', False, quota=True)
        fixture('shrink')
        backup('transfer-after-quota-retry', True)
        fixture('verifyQuotaCleared')
        restore('transfer-after-quota-retry')
        fixture('plainWithoutCatalogue')
        backup('transfer-without-catalogue', True)
        restore('transfer-without-catalogue')
        report['success'] = True
    finally:
        (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        if emulator.poll() is None:
            os.killpg(emulator.pid, signal.SIGTERM)
            try:
                emulator.wait(timeout=30)
            except subprocess.TimeoutExpired:
                os.killpg(emulator.pid, signal.SIGKILL)
                emulator.wait(timeout=30)
        reader.join(timeout=30)
        run(str(args.sdk / 'platform-tools/adb'), '-P', str(server_port), 'kill-server')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
