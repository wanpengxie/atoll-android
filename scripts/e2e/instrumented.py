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


def group_running(group):
    # ps prints only group IDs and status; never process arguments or credentials.
    result = subprocess.run(['ps', '-axo', 'pgid=,stat='], capture_output=True, text=True, check=True)
    return any(parts[0] == str(group) and not parts[1].startswith('Z')
               for line in result.stdout.splitlines() if len(parts := line.split()) == 2)


def drain_group(process, cancel=False):
    if cancel:
        try: os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError: pass
    deadline = time.monotonic() + 20
    while group_running(process.pid) and time.monotonic() < deadline:
        process.poll()
        time.sleep(0.1)
    if group_running(process.pid):
        try: os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError: pass
        deadline = time.monotonic() + 10
        while group_running(process.pid) and time.monotonic() < deadline:
            process.poll()
            time.sleep(0.1)
    process.wait(timeout=10)
    if group_running(process.pid):
        raise Failure('Gradle processes still running; emulator lock retained for inspection')


def run_gradle(command, environment, log, secrets, lock):
    lock.check_owner()
    # A private session and a single-use daemon keep cancellation from orphaning
    # Gradle/UTP work or killing another worker's shared Gradle daemon.
    process = subprocess.Popen(command, env=environment, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, start_new_session=True)
    lock.event('gradle-start', process_id=process.pid)
    try:
        for line in process.stdout:
            for secret in secrets: line = line.replace(secret, '[redacted]')
            log.write(line); log.flush(); print(line, end='', flush=True)
        status = process.wait()
        lock.event('gradle-client-exit', process_id=process.pid, exit_code=status)
    except BaseException:
        try: drain_group(process, cancel=True)
        except BaseException:
            lock.retain()
            raise
        lock.event('gradle-group-finished', process_id=process.pid, cancelled=True)
        raise
    finally:
        process.stdout.close()
    try: drain_group(process)
    except BaseException:
        lock.retain()
        raise
    lock.check_owner()
    lock.event('gradle-group-finished', process_id=process.pid, cancelled=False)
    return status


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default=URL)
    parser.add_argument('--owner', default='sol-2')
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--build-type', choices=['debug', 'benchmark'], default='debug')
    parser.add_argument('--queue-timeout', type=int, default=900)
    parser.add_argument('--output', type=Path, default=Path.home() / 'atoll-build/e2e-artifacts/instrumented')
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args(argv)
    validate_url(args.url)
    if not re.fullmatch(r'emulator-\d+', args.serial):
        raise Failure('only an emulator serial is allowed')
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
    # Workspace timeout sends SIGTERM; treat it like Ctrl-C and drain Gradle
    # before unwinding the lock, rather than letting a daemon keep using adb.
    def interrupted(signum, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    with EmulatorLock(args.owner, args.queue_timeout, audit) as lock:
        lock.check_owner()
        # Another variant's or a previous run's screenshots cannot establish this run's success.
        if additional.exists():
            shutil.rmtree(additional)
        environment = dict(os.environ, ANDROID_SERIAL=args.serial)
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
