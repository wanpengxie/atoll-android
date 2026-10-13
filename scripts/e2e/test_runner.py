import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import instrumented
from support import EmulatorLock, Failure, URL, load_node, validate_url


class RunnerTest(unittest.TestCase):
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

    def test_lock_is_verified_and_released_even_if_execution_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            lock = EmulatorLock('sol-2', 1)
            lock.base = Path(tmp); lock.lock = Path(tmp) / 'lock'; lock.queue = Path(tmp) / 'queue'
            with self.assertRaisesRegex(Failure, 'test execution failed'):
                with lock:
                    lock.check_owner()
                    raise Failure('test execution failed')
            self.assertFalse(lock.lock.exists()); self.assertEqual('', lock.queue.read_text())
            with self.assertRaises(Failure): lock.check_owner()

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
