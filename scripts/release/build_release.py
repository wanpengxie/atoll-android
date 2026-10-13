#!/usr/bin/env python3
"""Run on mbp: freeze a main commit, build release, verify and publish its APK."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile

HOST = 'xiewanpeng@100.99.232.10'
REPO = '/home/xiewanpeng/.atoll/device/daemons/local-device/channels/c0.dev/atoll-android'
DELIVERY = '/home/xiewanpeng/.atoll/device/daemons/local-device/channels/c0.dev.web/android-release'


def ssh(arguments, **kwargs):
    return subprocess.run(['ssh', '-o', 'BatchMode=yes', HOST, shlex.join(arguments)], check=True, **kwargs)


def manifest_metadata(badging):
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    minimum = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", badging, re.M)
    if not package or not minimum or package[1] != 'dev.atoll.app':
        raise ValueError('Unexpected APK manifest')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._+-]*', package[3]):
        raise ValueError('Version name cannot be used as a filename')
    if re.search(r'^application-debuggable', badging, re.M):
        raise ValueError('Release APK must not be debuggable')
    return {'application_id': package[1], 'version_code': int(package[2]),
            'version_name': package[3], 'min_sdk': int(minimum[1])}


def certificate_digest(report):
    values = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$', report, re.M)
    if len(values) != 1:
        raise ValueError('Expected one verified APK signer')
    return values[0].lower()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('commit', help='Full hash of a commit on main')
    parser.add_argument('--dry-run', action='store_true', help='Really build, but publish under dry-run/ as non-final')
    args = parser.parse_args()
    if not re.fullmatch(r'[a-f0-9]{40}', args.commit):
        parser.error('Pass the actual full hash printed by git rev-parse main')
    # Reachability, not HEAD equality: main can advance during the frozen build.
    ssh(['git', '-C', REPO, 'merge-base', '--is-ancestor', args.commit, 'main'])
    build_root = Path.home() / 'atoll-build/release'
    build_root.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix=args.commit[:12] + '-', dir=build_root))
    archive = work / 'source.tar'
    with archive.open('wb') as output:
        ssh(['git', '-C', REPO, 'archive', '--format=tar', args.commit], stdout=output)
    source = work / 'source'
    source.mkdir()
    subprocess.run(['tar', '-xf', str(archive), '-C', str(source)], check=True)
    sdk = Path.home() / 'Library/Android/sdk'
    (source / 'local.properties').write_text('sdk.dir=' + str(sdk) + '\n')
    environment = dict(os.environ, JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home')
    started = datetime.now(timezone.utc).isoformat()
    command = ['./gradlew', '--no-daemon', '--console=plain', 'assembleRelease']
    print('Building frozen main ' + args.commit + (' [NON-FINAL]' if args.dry_run else ''), flush=True)
    with (work / 'build.log').open('w') as log:
        result = subprocess.run(command, cwd=source, env=environment, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError('Release build failed; log: ' + str(work / 'build.log'))
    apk = source / 'app/build/outputs/apk/release/app-release.apk'
    build_tools = sdk / 'build-tools/35.0.1'
    badging = subprocess.run([str(build_tools / 'aapt2'), 'dump', 'badging', str(apk)],
                             env=environment, capture_output=True, text=True, check=True).stdout
    metadata = manifest_metadata(badging)
    signature = subprocess.run([str(build_tools / 'apksigner'), 'verify', '--print-certs', str(apk)],
                               env=environment, capture_output=True, text=True, check=True).stdout
    fingerprint = certificate_digest(signature)
    debug_certificate = subprocess.run([str(Path(environment['JAVA_HOME']) / 'bin/keytool'), '-exportcert',
        '-keystore', str(Path.home() / '.android/debug.keystore'), '-alias', 'androiddebugkey',
        '-storepass', 'android'], capture_output=True, check=True).stdout
    if fingerprint != hashlib.sha256(debug_certificate).hexdigest():
        raise ValueError('Release signer does not match this mbp debug certificate')
    apk_hash = hashlib.sha256(apk.read_bytes()).hexdigest()
    filename = 'atoll-' + metadata['version_name'] + '.apk'
    destination = DELIVERY + ('/dry-run' if args.dry_run else '') + '/' + args.commit
    record = {'source_commit': args.commit, 'source_branch': 'main', 'variant': 'release',
        'delivery_status': 'non-final' if args.dry_run else 'final', 'filename': filename,
        'sha256': apk_hash, 'size_bytes': apk.stat().st_size,
        'signing_certificate_sha256': fingerprint, 'debug_certificate': True,
        'source_archive_sha256': hashlib.sha256(archive.read_bytes()).hexdigest(),
        'build_started_at': started, 'build_finished_at': datetime.now(timezone.utc).isoformat(),
        'build_command': command, **metadata}
    metadata_file = work / 'build.json'
    metadata_file.write_text(json.dumps(record, indent=2) + '\n')
    ssh(['mkdir', '-p', destination])
    subprocess.run(['scp', '-q', str(apk), HOST + ':' + destination + '/' + filename], check=True)
    # Check the transferred APK before publishing its completion/metadata record.
    remote_check = 'import hashlib,pathlib,sys; print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())'
    received = ssh(['python3', '-c', remote_check, destination + '/' + filename],
                   capture_output=True, text=True).stdout.strip()
    if received != apk_hash:
        raise ValueError('Delivered APK checksum differs from the built APK')
    subprocess.run(['scp', '-q', str(metadata_file), HOST + ':' + destination + '/build.json'], check=True)
    print(json.dumps(record, indent=2))
    print('Delivery: ' + destination)
    print('Build log: ' + str(work / 'build.log'))


if __name__ == '__main__':
    main()
