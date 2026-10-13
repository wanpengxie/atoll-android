#!/usr/bin/env python3
"""One entry point for Compose's real-app smoke test; never drive input with adb."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
import urllib.request

from support import EmulatorLock, Failure, NoRedirect, STEPS, URL, load_node, ssh, validate_url


def validate_artifacts(root):
    summaries = list(root.rglob('summary.json'))
    if len(summaries) != 1:
        raise Failure('expected exactly one instrumented smoke summary for this build type')
    record = json.loads(summaries[0].read_text())
    steps = record.get('steps', [])
    if record.get('server') != URL or record.get('pending') != [] or \
            [row.get('step') for row in steps] != STEPS or any(row.get('status') != 'passed' for row in steps):
        raise Failure('instrumented run did not pass all 17 isolated-node steps')


def device_states(environment, lock):
    lock.check_owner()
    if environment.get('ANDROID_SERIAL') != 'emulator-5554':
        raise Failure('ANDROID_SERIAL must explicitly select emulator-5554')
    result = subprocess.run(['adb', '-s', 'emulator-5554', 'devices'], env=environment,
                            capture_output=True, text=True, check=True, timeout=15)
    return dict(line.split()[:2] for line in result.stdout.splitlines()[1:] if len(line.split()) >= 2)


def verify_emulator(environment, lock):
    if device_states(environment, lock).get('emulator-5554') != 'device':
        raise Failure('emulator-5554 is not available; no Gradle device task was started')
    lock.event('emulator-verified', serial='emulator-5554')


def ensure_emulator(environment, lock):
    if 'emulator-5554' not in device_states(environment, lock):
        emulator = Path.home() / 'Library/Android/sdk/emulator/emulator'
        with (lock.base / 'emulator.log').open('a') as log:
            subprocess.Popen([str(emulator), '-avd', 'ash-browser', '-port', '5554', '-no-window',
                              '-no-snapshot-load', '-no-snapshot-save', '-gpu', 'host',
                              '-dns-server', '100.100.100.100,223.5.5.5'], env=environment,
                             stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        (lock.base / 'emulator.started-by-e2e').write_text('emulator-5554\n')
        lock.event('emulator-started', serial='emulator-5554')
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        lock.check_owner()
        boot = subprocess.run(['adb', '-s', 'emulator-5554', 'shell', 'getprop', 'sys.boot_completed'],
                              env=environment, capture_output=True, text=True, timeout=15)
        if boot.returncode == 0 and boot.stdout.strip() == '1':
            verify_emulator(environment, lock)
            return
        time.sleep(2)
    raise Failure('emulator-5554 did not finish booting; lock retained')


def stop_emulator(environment, lock):
    lock.check_owner()
    marker = lock.base / 'emulator.started-by-e2e'
    if not marker.exists():
        return  # An already running user/Ash emulator belongs to its starter.
    if 'emulator-5554' in device_states(environment, lock):
        subprocess.run(['adb', '-s', 'emulator-5554', 'emu', 'kill'], env=environment,
                       capture_output=True, text=True, check=True, timeout=15)
        deadline = time.monotonic() + 60
        while 'emulator-5554' in device_states(environment, lock):
            if time.monotonic() >= deadline:
                raise Failure('emulator-5554 is still shutting down; lock retained')
            time.sleep(2)
        lock.event('emulator-stopped', serial='emulator-5554')
    marker.unlink()


def release_emulator(owner):
    import fcntl
    lock = EmulatorLock(owner, 1)
    if not lock.lock.is_dir():
        raise Failure('there is no emulator lock to release')
    lock.identity = lock.lock.stat().st_ino
    lock.held = True
    lock.owner_guard = (lock.base / (owner + '.emulator-runner.guard')).open('a')
    try:
        fcntl.flock(lock.owner_guard, fcntl.LOCK_EX | fcntl.LOCK_NB)
        environment = dict(os.environ, ANDROID_SERIAL='emulator-5554')
        states = device_states(environment, lock)
        if 'emulator-5554' in states:
            if states['emulator-5554'] != 'device':
                raise Failure('emulator is offline; cannot verify instrumentation, lock retained')
            result = subprocess.run(['adb', '-s', 'emulator-5554', 'shell', 'ps', '-A', '-o', 'ARGS'],
                                    env=environment, capture_output=True, text=True, check=True, timeout=15)
            if any('instrument' in line and any(marker in line for marker in
                   ['com.android.commands.am.Am', 'am instrument', 'cmd activity instrument'])
                   for line in result.stdout.splitlines()):
                raise Failure('am instrument is still active; emulator lock retained')
        lock.on_empty = lambda: stop_emulator(environment, lock)
        lock.__exit__()
        print('Emulator lock released for ' + owner)
    except BlockingIOError:
        raise Failure('emulator runner is still active; lock retained')
    finally:
        lock.owner_guard.close()


def run_gradle(command, environment, log, secrets, lock):
    lock.check_owner()
    process = subprocess.Popen(command, env=environment, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True)
    lock.event('gradle-start', process_id=process.pid)
    try:
        for line in process.stdout:
            for secret in secrets: line = line.replace(secret, '[redacted]')
            log.write(line); log.flush(); print(line, end='', flush=True)
        status = process.wait()
        lock.check_owner()
        lock.event('gradle-finished', process_id=process.pid, exit_code=status)
        return status
    except BaseException:
        # A cancelled client cannot prove its daemon/UTP has finished. Never
        # release automatically; the operator must check and explicitly release.
        lock.retain()
        process.terminate()
        try: process.wait(timeout=10)
        except subprocess.TimeoutExpired: pass
        raise
    finally:
        process.stdout.close()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default=URL)
    parser.add_argument('--owner', default='sol-2')
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--build-type', choices=['debug', 'benchmark'], default='debug')
    parser.add_argument('--queue-timeout', type=int, default=900)
    parser.add_argument('--output', type=Path, default=Path.home() / 'atoll-build/e2e-artifacts/instrumented')
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--release-lock', action='store_true')
    args = parser.parse_args(argv)
    validate_url(args.url)
    if args.serial != 'emulator-5554':
        raise Failure('only emulator-5554 is allowed')
    if args.release_lock:
        release_emulator(args.owner)
        return 0
    task = 'connected' + args.build_type.capitalize() + 'AndroidTest'
    if args.dry_run:
        print(json.dumps({'task': task, 'build_type': args.build_type, 'url': URL, 'steps': STEPS,
                          'parameters': ['atollUrl', 'atollPassword', 'atollToken', 'atollChannelId', 'atollCodexId']}, indent=2))
        return 0
    node = load_node()
    urllib.request.build_opener(NoRedirect).open(URL + '/healthz', timeout=10).close()
    password = ssh('cat atoll-test/root-password').decode().strip()
    token = ssh('cat atoll-test/server/atoll-token').decode().strip()
    if not re.fullmatch(r'[A-Za-z0-9]{32}', password) or not token:
        raise Failure('isolated test credentials are unavailable')
    parameters = {'class': 'dev.atoll.app.e2e.IsolatedNodeSmokeTest', 'atollUrl': URL, 'atollPassword': password,
                  'atollToken': token, 'atollChannelId': node['channel_id'], 'atollCodexId': node['codex_actor_id'],
                  'additionalTestOutputDir': '/sdcard/Android/media/dev.atoll.app/additional_test_output'}
    command = ['./gradlew', '--no-daemon', '--console=plain', '-PatollTestBuildType=' + args.build_type, task] + [
        '-Pandroid.testInstrumentationRunnerArguments.' + key + '=' + value for key, value in parameters.items()]
    destination = args.output.resolve() / (time.strftime('%Y%m%d-%H%M%S') + '-' + args.build_type)
    destination.mkdir(parents=True)
    additional = Path('app/build/outputs/connected_android_test_additional_output') / (args.build_type + 'AndroidTest')
    def audit(event, **fields):
        record = {'time': datetime.now(timezone.utc).isoformat(), 'event': event,
                  'owner': args.owner, 'runner_pid': os.getpid(), **fields}
        with (destination / 'lock.jsonl').open('a') as output:
            output.write(json.dumps(record) + '\n')
    # Workspace timeout must retain the lock, like Ctrl-C.
    def interrupted(signum, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    with EmulatorLock(args.owner, args.queue_timeout, audit) as lock:
        lock.check_owner()
        # Another variant's or a previous run's screenshots cannot establish this run's success.
        if additional.exists():
            shutil.rmtree(additional)
        environment = dict(os.environ, ANDROID_SERIAL='emulator-5554')
        lock.on_empty = lambda: stop_emulator(environment, lock)
        ensure_emulator(environment, lock)
        # Capture/sanitize output here, so AGP/adb failure diagnostics cannot expose arguments.
        with (destination / 'gradle.log').open('w') as log:
            status = run_gradle(command, environment, log, [password, token], lock)
        # AGP collects additional outputs before it uninstalls the test packages.
        if additional.is_dir():
            shutil.copytree(additional, destination / 'app-artifacts')
    if status == 0:
        try:
            validate_artifacts(destination / 'app-artifacts')
        except Failure as error:
            print('FAIL ' + str(error), file=sys.stderr)
            status = 1
    (destination / 'run.json').write_text(json.dumps({'server': URL, 'task': task, 'build_type': args.build_type, 'exit_code': status}, indent=2))
    print('Instrumented artifacts: ' + str(destination))
    return status


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Failure as error:
        print('FAIL ' + str(error), file=sys.stderr)
        sys.exit(1)
