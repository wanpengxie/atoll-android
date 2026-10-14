import contextlib
import importlib.util
import io
import json
import os
import sys
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import instrumented
from support import EmulatorLock, Failure, URL, load_node, validate_url


class RunnerTest(unittest.TestCase):
    def test_custom_ui_class_dry_run_keeps_variant_and_origin_guards(self):
        name = 'dev.atoll.app.ui.MessageContentUiTest'
        with patch('instrumented.load_node', side_effect=AssertionError('no metadata')):
            with contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(0, instrumented.main(['--class', name, '--build-type', 'benchmark', '--dry-run']))
            record = json.loads(output.getvalue())
            self.assertEqual(name, record['class'])
            self.assertEqual('connectedBenchmarkAndroidTest', record['task'])
            self.assertEqual(URL, record['url'])
            self.assertEqual([], record['steps'])
            for args in [['--class', name, '--url', 'http://localhost:18080'],
                         ['--class', name, '--serial', 'e5ca3495'], ['--class', 'bad,name']]:
                with self.assertRaises(Failure): instrumented.main(args)

    def test_only_explicit_class_skips_smoke_summary_validation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            instrumented.validate_run_artifacts(root, 'dev.atoll.app.ui.MessageContentUiTest')
            with self.assertRaises(Failure): instrumented.validate_run_artifacts(root)

    def test_gradle_success_requires_all17_current_variant_steps(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with self.assertRaises(Failure): instrumented.validate_artifacts(root)
            report = {'server': URL, 'pending': [], 'steps': [{'step': step, 'status': 'passed'} for step in instrumented.STEPS]}
            path = root / 'summary.json'; path.write_text(json.dumps(report))
            instrumented.validate_artifacts(root)
            report['steps'][-1]['status'] = 'failed'; path.write_text(json.dumps(report))
            with self.assertRaises(Failure): instrumented.validate_artifacts(root)
            path.write_text(json.dumps({'server': URL, 'pending': [], 'steps': []}))
            with self.assertRaises(Failure): instrumented.validate_artifacts(root)

    def test_both_build_types_dry_run_without_external_operations(self):
        for build_type in ['debug', 'benchmark']:
            with self.subTest(build_type=build_type), patch('instrumented.ssh', side_effect=AssertionError('no secrets')), \
                    patch('instrumented.load_node', side_effect=AssertionError('no metadata')):
                with contextlib.redirect_stdout(io.StringIO()) as output:
                    self.assertEqual(0, instrumented.main(['--dry-run', '--build-type', build_type]))
                record = json.loads(output.getvalue())
                self.assertEqual('connected' + build_type.capitalize() + 'AndroidTest', record['task'])
                self.assertEqual(17, len(record['steps']))
                self.assertIn('atollPassword', record['parameters'])

    def test_wrong_origin_stops_before_reading_credentials_or_joining_queue(self):
        for url in ['https://tardis.tail6bc2a1.ts.net', URL + '/api', URL + '?x=1', 'http://localhost:18080']:
            with self.subTest(url=url), patch('instrumented.load_node', side_effect=AssertionError('no metadata')), self.assertRaises(Failure):
                instrumented.main(['--url', url])

    def test_exact_origin_and_emulator_only(self):
        self.assertEqual(URL, validate_url(URL + '/'))
        for url in ['http://100.99.232.10:8832', 'https://100.99.232.10:18080', 'http://root@100.99.232.10:18080']:
            with self.assertRaises(Failure): validate_url(url)
        with patch('instrumented.load_node', side_effect=AssertionError('no metadata')), self.assertRaises(Failure):
            instrumented.main(['--serial', 'physical-phone'])

    def test_metadata_cannot_replace_the_isolated_node_or_codex(self):
        for record in [{'url': 'https://tardis.tail6bc2a1.ts.net'}, {'url': URL, 'channel_name': 'c0.other', 'channel_id': 'c', 'codex_actor_id': 'agent:claude:1'}]:
            with patch('support.ssh', return_value=json.dumps(record).encode()), self.assertRaises(Failure): load_node()

    def test_queue_preserves_existing_order_and_releases_only_own_entry(self):
        with tempfile.TemporaryDirectory() as tmp:
            lock = EmulatorLock('sol-2', 1)
            lock.base = Path(tmp); lock.queue = Path(tmp) / 'queue'
            lock.queue.write_text('sol-1\nsol-2\nsol-3\n')
            lock._change_queue(True)
            self.assertEqual('sol-1\nsol-2\nsol-3\n', lock.queue.read_text())
            lock._change_queue(False)
            self.assertEqual('sol-1\nsol-3\n', lock.queue.read_text())

    def test_lock_is_verified_and_retained_if_execution_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            lock = EmulatorLock('sol-2', 1)
            lock.base = Path(tmp); lock.lock = Path(tmp) / 'lock'; lock.queue = Path(tmp) / 'queue'
            with self.assertRaisesRegex(Failure, 'test execution failed'):
                with lock:
                    lock.check_owner()
                    raise Failure('test execution failed')
            self.assertTrue(lock.lock.exists()); self.assertEqual('sol-2\n', lock.queue.read_text())
            lock.check_owner()

    def test_same_owner_cannot_join_or_remove_an_active_runners_queue(self):
        with tempfile.TemporaryDirectory() as tmp:
            def make_lock():
                lock = EmulatorLock('sol-2', 1)
                lock.base = Path(tmp); lock.lock = Path(tmp) / 'lock'; lock.queue = Path(tmp) / 'queue'
                return lock
            with make_lock() as first:
                with self.assertRaisesRegex(Failure, 'already active'): make_lock().__enter__()
                first.check_owner()
                self.assertEqual('sol-2\n', first.queue.read_text())

    def test_gradle_wait_finishes_before_lock_is_released(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); events = []
            lock = EmulatorLock('sol-2', 1, lambda event, **fields: events.append(event))
            lock.base = root; lock.lock = root / 'lock'; lock.queue = root / 'queue'
            with lock, contextlib.redirect_stdout(io.StringIO()):
                status = instrumented.run_gradle([sys.executable, '-c', 'pass'], dict(os.environ), io.StringIO(), ['secret'], lock)
                self.assertEqual(0, status)
                lock.check_owner()
            self.assertLess(events.index('lock-acquired'), events.index('gradle-start'))
            self.assertLess(events.index('gradle-finished'), events.index('lock-released'))

    def test_failed_gradle_captures_redacted_guest_logs_before_shutdown_and_release(self):
        with tempfile.TemporaryDirectory() as tmp, patch('support.Path.home', return_value=Path(tmp)):
            output = Path(tmp) / 'artifacts'
            password, token = 'p' * 32, 'fake-private-token'
            def adb(command, **kwargs):
                self.assertEqual(['adb', '-s', 'emulator-5554', 'logcat', '-d'], command[:5])
                self.assertEqual('emulator-5554', kwargs['env']['ANDROID_SERIAL'])
                self.assertTrue((Path(tmp) / 'atoll-build/emulator.lock').exists())
                text = 'FATAL EXCEPTION ' + password + '\n' if 'crash' in command else \
                    '\n'.join(['old'] + ['lmk ' + token] * 3000)
                return unittest.mock.Mock(stdout=text, stderr='', returncode=0)
            def stop(environment, lock):
                lock.check_owner()
                destination = next(output.iterdir())
                self.assertIn('[redacted]', (destination / 'logcat-crash.log').read_text())
                self.assertEqual(3000, len((destination / 'logcat-main-system.log').read_text().splitlines()))
            with patch('instrumented.load_node', return_value={'channel_id': 'c', 'codex_actor_id': 'agent:a'}), \
                 patch('instrumented.ssh', side_effect=[password.encode(), token.encode()]), \
                 patch('instrumented.urllib.request.build_opener'), patch('instrumented.ensure_emulator'), \
                 patch('instrumented.run_gradle', return_value=1), patch('instrumented.stop_emulator', side_effect=stop), \
                 patch('instrumented.subprocess.run', side_effect=adb) as capture, contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, instrumented.main(['--owner', 'sol-3', '--output', str(output)]))
            self.assertEqual(2, capture.call_count)
            self.assertEqual(['-b', 'crash'], capture.call_args_list[0].args[0][5:])
            self.assertEqual(['-b', 'main', '-b', 'system', '-t', '3000'], capture.call_args_list[1].args[0][5:])
            destination = next(output.iterdir())
            for name in ('logcat-crash.log', 'logcat-main-system.log'):
                text = (destination / name).read_text()
                self.assertNotIn(password, text); self.assertNotIn(token, text)
            events = [json.loads(line)['event'] for line in (destination / 'lock.jsonl').read_text().splitlines()]
            self.assertEqual(2, events.count('failure-logcat-collected'))
            self.assertLess(events.index('failure-logcat-collected'), events.index('lock-released'))
            self.assertEqual(1, json.loads((destination / 'run.json').read_text())['exit_code'])

    def test_failure_capture_requires_emulator_and_records_unavailable_logs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); lock = unittest.mock.Mock()
            with patch('instrumented.subprocess.run') as adb:
                with self.assertRaises(Failure): instrumented.capture_failure_logcat(root, {}, [], lock)
                adb.assert_not_called()
            with patch('instrumented.subprocess.run', side_effect=__import__('subprocess').TimeoutExpired('adb', 15)):
                instrumented.capture_failure_logcat(root, {'ANDROID_SERIAL': 'emulator-5554'}, [], lock)
            self.assertIn('TimeoutExpired', (root / 'logcat-crash.log').read_text())
            self.assertIn('TimeoutExpired', (root / 'logcat-main-system.log').read_text())

    def test_cancel_retains_lock_and_queue_for_manual_inspection(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            lock = EmulatorLock('sol-2', 1)
            lock.base = root; lock.lock = root / 'lock'; lock.queue = root / 'queue'
            parent = "import time; print('ready',flush=True); time.sleep(30)"
            log = io.StringIO()
            with patch.object(log, 'write', side_effect=KeyboardInterrupt), self.assertRaises(KeyboardInterrupt), contextlib.redirect_stderr(io.StringIO()) as output:
                with lock:
                    instrumented.run_gradle([sys.executable, '-c', parent], dict(os.environ), log, ['secret'], lock)
            self.assertTrue(lock.lock.exists())
            self.assertEqual('sol-2\n', lock.queue.read_text())
            self.assertIn('--release-lock', output.getvalue())

    def test_other_device_present_requires_explicit_emulator_serial(self):
        lock = unittest.mock.Mock()
        with patch('instrumented.subprocess.run') as adb:
            with self.assertRaises(Failure): instrumented.verify_emulator({}, lock)
            adb.assert_not_called()
            adb.return_value.stdout = 'List of devices attached\nemulator-5554\tdevice\ne5ca3495\tdevice\n'
            environment = {'ANDROID_SERIAL': 'emulator-5554'}
            instrumented.verify_emulator(environment, lock)
            self.assertEqual(environment, adb.call_args.kwargs['env'])
            self.assertEqual(['adb', '-s', 'emulator-5554', 'devices'], adb.call_args.args[0])
            adb.return_value.stdout = 'List of devices attached\ne5ca3495\tdevice\n'
            with self.assertRaises(Failure): instrumented.verify_emulator(environment, lock)

    def test_manual_release_refuses_active_instrumentation_or_other_owner(self):
        with tempfile.TemporaryDirectory() as tmp, patch('support.Path.home', return_value=Path(tmp)):
            lock = EmulatorLock('sol-2', 1)
            with lock: lock.retain()
            def adb_result(command, **kwargs):
                self.assertEqual('emulator-5554', kwargs['env']['ANDROID_SERIAL'])
                self.assertEqual(['adb', '-s', 'emulator-5554'], command[:3])
                result = unittest.mock.Mock()
                result.stdout = 'List of devices attached\nemulator-5554\tdevice\n' if command[-1] == 'devices' else 'app_process com.android.commands.am.Am instrument -w runner'
                return result
            with patch('instrumented.subprocess.run', side_effect=adb_result):
                with self.assertRaisesRegex(Failure, 'still active'): instrumented.release_emulator('sol-2')
                self.assertTrue(lock.lock.exists())
                with self.assertRaises(Failure): instrumented.release_emulator('sol-3')
            def idle(command, **kwargs):
                result = unittest.mock.Mock()
                result.stdout = 'List of devices attached\nemulator-5554\tdevice\n' if command[-1] == 'devices' else 'init\n/system/bin/logd\n'
                return result
            with patch('instrumented.subprocess.run', side_effect=idle), contextlib.redirect_stdout(io.StringIO()):
                instrumented.release_emulator('sol-2')
            self.assertFalse(lock.lock.exists()); self.assertEqual('', lock.queue.read_text())

    def test_emulator_starts_under_lock_and_waits_for_boot_with_explicit_serial(self):
        lock = unittest.mock.Mock()
        with tempfile.TemporaryDirectory() as tmp:
            lock.base = Path(tmp)
            def response(command, **kwargs):
                self.assertEqual('emulator-5554', kwargs['env']['ANDROID_SERIAL'])
                self.assertEqual(['adb', '-s', 'emulator-5554'], command[:3])
                result = unittest.mock.Mock(returncode=0)
                result.stdout = 'List of devices attached\n' if command[-1] == 'devices' and response.calls == 0 else \
                    ('List of devices attached\nemulator-5554\tdevice\n' if command[-1] == 'devices' else ('stopped\n' if command[-1] == 'init.svc.bootanim' else '1\n'))
                response.calls += 1
                return result
            response.calls = 0
            with patch('instrumented.subprocess.run', side_effect=response), patch('instrumented.subprocess.Popen') as start:
                instrumented.ensure_emulator({'ANDROID_SERIAL': 'emulator-5554'}, lock)
                self.assertTrue((lock.base / 'emulator.started-by-e2e').exists())
                self.assertIn('-port', start.call_args.args[0])
                self.assertIn('5554', start.call_args.args[0])
                self.assertEqual('emulator-5554', start.call_args.kwargs['env']['ANDROID_SERIAL'])

    def test_existing_emulator_is_never_stopped_without_our_marker(self):
        lock = unittest.mock.Mock()
        with tempfile.TemporaryDirectory() as tmp:
            lock.base = Path(tmp)
            def response(command, **kwargs):
                result = unittest.mock.Mock(returncode=0)
                result.stdout = 'List of devices attached\nemulator-5554\tdevice\n' if command[-1] == 'devices' else ('stopped\n' if command[-1] == 'init.svc.bootanim' else '1\n')
                return result
            with patch('instrumented.subprocess.run', side_effect=response), patch('instrumented.subprocess.Popen') as start:
                instrumented.ensure_emulator({'ANDROID_SERIAL': 'emulator-5554'}, lock)
                start.assert_not_called()
                self.assertFalse((lock.base / 'emulator.started-by-e2e').exists())
            with patch('instrumented.subprocess.run') as adb:
                instrumented.stop_emulator({'ANDROID_SERIAL': 'emulator-5554'}, lock)
                adb.assert_not_called()

    def test_owned_emulator_disappears_before_marker_and_lock_are_released(self):
        lock = unittest.mock.Mock()
        with tempfile.TemporaryDirectory() as tmp:
            lock.base = Path(tmp); marker = lock.base / 'emulator.started-by-e2e'; marker.write_text('emulator-5554\n')
            replies = iter(['List of devices attached\nemulator-5554\tdevice\ne5ca3495\tdevice\n', '',
                            'List of devices attached\nemulator-5554\toffline\ne5ca3495\tdevice\n',
                            'List of devices attached\ne5ca3495\tdevice\n'])
            commands = []
            def response(command, **kwargs):
                self.assertEqual('emulator-5554', kwargs['env']['ANDROID_SERIAL'])
                self.assertTrue(marker.exists())
                commands.append(command)
                result = unittest.mock.Mock(stdout=next(replies)); return result
            with patch('instrumented.subprocess.run', side_effect=response), patch('instrumented.time.sleep'):
                instrumented.stop_emulator({'ANDROID_SERIAL': 'emulator-5554'}, lock)
            self.assertFalse(marker.exists())
            self.assertEqual(['adb', '-s', 'emulator-5554', 'emu', 'kill'], commands[1])
            self.assertEqual(4, len(commands))

    def test_shutdown_only_when_queue_empty_and_while_lock_still_owned(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            def new_lock():
                lock = EmulatorLock('sol-2', 1)
                lock.base = root; lock.lock = root / 'lock'; lock.queue = root / 'queue'
                return lock
            with new_lock() as lock:
                def stop():
                    lock.check_owner()
                    self.assertEqual('', lock.queue.read_text())
                callback = unittest.mock.Mock(side_effect=stop)
                lock.on_empty = callback
            callback.assert_called_once()
            with new_lock() as lock:
                lock.queue.write_text('sol-2\nsol-3\n')
                lock.on_empty = callback
            callback.assert_called_once()
            self.assertEqual('sol-3\n', lock.queue.read_text())

    def test_network_probe_waits_until_boot_animation_stops(self):
        lock = unittest.mock.Mock()
        animation_calls = 0
        commands = []
        def response(command, **kwargs):
            nonlocal animation_calls
            commands.append(command)
            result = unittest.mock.Mock(returncode=0, stdout='')
            if command[-1] == 'devices': result.stdout = 'List of devices attached\nemulator-5554\tdevice\n'
            elif command[-1] == 'sys.boot_completed': result.stdout = '1'
            elif command[-1] == 'init.svc.bootanim':
                animation_calls += 1
                result.stdout = 'running' if animation_calls == 1 else 'stopped'
            elif 'nc' in command: self.assertEqual(2, animation_calls)
            return result
        with patch('instrumented.subprocess.run', side_effect=response), patch('instrumented.time.sleep'):
            instrumented.ensure_emulator({'ANDROID_SERIAL': 'emulator-5554'}, lock)
        self.assertEqual(2, animation_calls)
        self.assertEqual(1, sum('nc' in command for command in commands))

    def test_guest_network_failure_is_retried_before_emulator_ready(self):
        lock = unittest.mock.Mock()
        responses = [unittest.mock.Mock(returncode=1), unittest.mock.Mock(returncode=0)]
        environment = {'ANDROID_SERIAL': 'emulator-5554'}
        with patch('instrumented.subprocess.run', side_effect=responses) as adb, patch('instrumented.time.sleep') as sleep:
            instrumented.wait_emulator_network(environment, lock)
        self.assertEqual(2, adb.call_count)
        self.assertEqual(['adb', '-s', 'emulator-5554', 'shell', 'toybox', 'nc', '-z', '-w', '3', '100.99.232.10', '18080'], adb.call_args.args[0])
        self.assertEqual(environment, adb.call_args.kwargs['env'])
        sleep.assert_called_once_with(2)
        lock.event.assert_any_call('emulator-network-ready', server=URL, attempts=2)

    def test_guest_network_timeout_is_an_environment_failure_without_ready_event(self):
        lock = unittest.mock.Mock()
        with patch('instrumented.time.monotonic', side_effect=[0, 0, 121]), \
             patch('instrumented.subprocess.run', side_effect=__import__('subprocess').TimeoutExpired('adb', 8)), \
             patch('instrumented.time.sleep'), self.assertRaisesRegex(Failure, 'environment problem.*120s.*no Gradle'):
            instrumented.wait_emulator_network({'ANDROID_SERIAL': 'emulator-5554'}, lock)
        self.assertFalse(any(call.args[0] == 'emulator-network-ready' for call in lock.event.call_args_list))

    def test_guest_network_probe_refuses_a_physical_device(self):
        with patch('instrumented.subprocess.run') as adb, self.assertRaises(Failure):
            instrumented.wait_emulator_network({'ANDROID_SERIAL': 'e5ca3495'}, unittest.mock.Mock())
        adb.assert_not_called()

    def test_short_network_failure_does_not_toggle_wifi(self):
        clock = [0.0]
        lock = unittest.mock.Mock()
        def sleep(seconds): clock[0] += seconds
        def probe(command, **kwargs): return unittest.mock.Mock(returncode=0 if clock[0] >= 18 else 1)
        with patch('instrumented.time.monotonic', side_effect=lambda: clock[0]), \
             patch('instrumented.time.sleep', side_effect=sleep), \
             patch('instrumented.subprocess.run', side_effect=probe) as adb:
            instrumented.wait_emulator_network({'ANDROID_SERIAL': 'emulator-5554'}, lock)
        self.assertEqual(18, clock[0])
        self.assertTrue(all('nc' in call.args[0] for call in adb.call_args_list))
        self.assertFalse(any(call.args[0] == 'emulator-network-heal' for call in lock.event.call_args_list))

    def test_wifi_heal_runs_once_under_lease_without_resetting_total_deadline(self):
        clock = [0.0]
        commands = []
        sleeps = []
        lock = unittest.mock.Mock()
        environment = {'ANDROID_SERIAL': 'emulator-5554'}
        def sleep(seconds): sleeps.append(seconds); clock[0] += seconds
        def execute(command, **kwargs):
            self.assertEqual(environment, kwargs['env'])
            self.assertEqual(['adb', '-s', 'emulator-5554'], command[:3])
            self.assertEqual(len(commands) + 1, lock.check_owner.call_count)
            commands.append((clock[0], command))
            return unittest.mock.Mock(returncode=1 if 'nc' in command else 0)
        with patch('instrumented.time.monotonic', side_effect=lambda: clock[0]), \
             patch('instrumented.time.sleep', side_effect=sleep), \
             patch('instrumented.subprocess.run', side_effect=execute), \
             self.assertRaisesRegex(Failure, 'environment problem.*120s'):
            instrumented.wait_emulator_network(environment, lock)
        toggles = [(instant, command[-1]) for instant, command in commands if 'wifi' in command]
        self.assertEqual([(20, 'disable'), (23, 'enable')], toggles)
        self.assertEqual(1, sleeps.count(3))
        self.assertEqual(120, clock[0])
        self.assertEqual(1, sum(call.args[0] == 'emulator-network-heal' for call in lock.event.call_args_list))
        lock.event.assert_any_call('emulator-network-heal', serial='emulator-5554', method='wifi-toggle')
        self.assertFalse(any(call.args[0] == 'emulator-network-ready' for call in lock.event.call_args_list))

    def test_network_recovered_by_wifi_heal_proceeds_to_ready(self):
        clock = [0.0]
        enabled = [False]
        lock = unittest.mock.Mock()
        def sleep(seconds): clock[0] += seconds
        def execute(command, **kwargs):
            if command[-1] == 'enable': enabled[0] = True
            return unittest.mock.Mock(returncode=0 if 'wifi' in command or enabled[0] else 1)
        with patch('instrumented.time.monotonic', side_effect=lambda: clock[0]), \
             patch('instrumented.time.sleep', side_effect=sleep), \
             patch('instrumented.subprocess.run', side_effect=execute):
            instrumented.wait_emulator_network({'ANDROID_SERIAL': 'emulator-5554'}, lock)
        self.assertEqual(25, clock[0])
        lock.event.assert_any_call('emulator-network-ready', server=URL, attempts=12)

    def test_late_probe_leaves_no_budget_for_wifi_toggle(self):
        clock = [0.0]
        lock = unittest.mock.Mock()
        def sleep(seconds): clock[0] += seconds
        def execute(command, **kwargs):
            clock[0] = 115 if clock[0] == 0 else 120
            return unittest.mock.Mock(returncode=1)
        with patch('instrumented.time.monotonic', side_effect=lambda: clock[0]), \
             patch('instrumented.time.sleep', side_effect=sleep), \
             patch('instrumented.subprocess.run', side_effect=execute) as adb, \
             self.assertRaisesRegex(Failure, 'environment problem.*120s'):
            instrumented.wait_emulator_network({'ANDROID_SERIAL': 'emulator-5554'}, lock)
        self.assertEqual(120, clock[0])
        self.assertTrue(all('nc' in call.args[0] for call in adb.call_args_list))
        self.assertEqual(3, adb.call_args.kwargs['timeout'])
        self.assertFalse(any(call.args[0] == 'emulator-network-heal' for call in lock.event.call_args_list))

    def test_isolated_password_helper_remains_outside_the_ui_runner(self):
        spec = importlib.util.spec_from_file_location('test_password', Path(__file__).parent.parent / 'test-node-password.py')
        helper = importlib.util.module_from_spec(spec); spec.loader.exec_module(helper)
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp).resolve(); root = home / 'atoll-test'; root.mkdir()
            path = root / 'root-password'; path.write_text('legacy-symbol-password_\n')
            with patch.object(helper.Path, 'home', return_value=home), patch.object(helper.subprocess, 'run') as execute:
                execute.return_value.returncode = 0
                helper.ensure_password(); first = path.read_text().strip()
                self.assertRegex(first, r'^[A-Za-z0-9]{32}$'); self.assertEqual(0o600, path.stat().st_mode & 0o777)
                helper.ensure_password(); self.assertEqual(first, path.read_text().strip())
                self.assertEqual((first + '\n').encode(), execute.call_args.kwargs['input'])


if __name__ == '__main__':
    unittest.main()
