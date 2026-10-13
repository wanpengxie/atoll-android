#!/usr/bin/env python3
"""One entry point for Compose's real-app smoke test; never drive input with adb."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.request

from support import EmulatorLock, Failure, NoRedirect, STEPS, URL, load_node, ssh, validate_url


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
    command = ['./gradlew', '--console=plain', '-PatollTestBuildType=' + args.build_type, task] + [
        '-Pandroid.testInstrumentationRunnerArguments.' + key + '=' + value for key, value in parameters.items()]
    destination = args.output.resolve() / (time.strftime('%Y%m%d-%H%M%S') + '-' + args.build_type)
    destination.mkdir(parents=True)
    with EmulatorLock(args.owner, args.queue_timeout) as lock:
        lock.check_owner()
        environment = dict(os.environ, ANDROID_SERIAL=args.serial)
        # Capture/sanitize output here, so AGP/adb failure diagnostics cannot expose arguments.
        with (destination / 'gradle.log').open('w') as log:
            process = subprocess.Popen(command, env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            try:
                for line in process.stdout:
                    safe = line.replace(password, '[redacted]').replace(token, '[redacted]')
                    log.write(safe); log.flush(); print(safe, end='', flush=True)
                status = process.wait()
            except BaseException:
                process.terminate(); process.wait(timeout=20)
                raise
        # AGP collects additional outputs before it uninstalls the test packages.
        additional = Path('app/build/outputs/connected_android_test_additional_output')
        if additional.is_dir():
            shutil.copytree(additional, destination / 'app-artifacts')
    (destination / 'run.json').write_text(json.dumps({'server': URL, 'task': task, 'build_type': args.build_type, 'exit_code': status}, indent=2))
    print('Instrumented artifacts: ' + str(destination))
    return status


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Failure as error:
        print('FAIL ' + str(error), file=sys.stderr)
        sys.exit(1)
