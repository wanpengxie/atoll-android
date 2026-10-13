import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import run
from ui import Adb, Failure, Selector as S, Tree


XML = '''<hierarchy><node package="dev.atoll.app" bounds="[0,0][1000,1000]">
<node package="dev.atoll.app" bounds="[0,0][1000,50]">
 <node package="dev.atoll.app" text="↳ request A" bounds="[0,10][500,30]"/>
 <node package="dev.atoll.app" text="取消" clickable="true" enabled="true" bounds="[800,0][1000,50]"/>
</node>
<node package="dev.atoll.app" bounds="[0,50][1000,100]">
 <node package="dev.atoll.app" text="↳ request B" bounds="[0,60][500,80]"/>
 <node package="dev.atoll.app" text="取消" clickable="true" enabled="true" bounds="[800,50][1000,100]"/>
</node>
<node package="dev.atoll.app" bounds="[0,100][1000,150]">
 <node package="dev.atoll.app" text="↳ request C" bounds="[0,110][500,130]"/>
</node>
<node package="another.app" text="取消" bounds="[0,150][1000,200]"/>
</node></hierarchy>'''


class DriverTest(unittest.TestCase):
    def test_only_exact_test_origin_is_allowed(self):
        self.assertEqual(run.URL, run.validate_url(run.URL + "/"))
        for url in ("https://tardis.tail6bc2a1.ts.net", "http://100.99.232.10:8832", "https://100.99.232.10:18080", "http://root@100.99.232.10:18080", run.URL + "/api", run.URL + "?x=1", "http://localhost:18080"):
            with self.subTest(url=url), self.assertRaises(Failure):
                run.validate_url(url)

    def test_dry_run_uses_no_adb_ssh_credentials_or_queue(self):
        with patch("run.ssh", side_effect=AssertionError("must not use SSH")), patch("run.subprocess.run", side_effect=AssertionError("must not run subprocess")):
            with contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(0, run.main(["--dry-run"]))
            self.assertIn('"login"', output.getvalue())
            self.assertIn('"logout"', output.getvalue())

    def test_rejection_happens_before_any_external_operation(self):
        with patch("run.load_node", side_effect=AssertionError("no metadata before guard")):
            with self.assertRaises(Failure):
                run.main(["--url", "https://tardis.tail6bc2a1.ts.net"])

    def test_controls_are_scoped_to_the_anchored_waiting_row(self):
        tree = Tree(XML)
        cancel = tree.find(S(text="取消"), S(pattern=r"request B$"))
        self.assertEqual("[800,50][1000,100]", cancel.attrib["bounds"])
        self.assertIsNone(tree.find(S(text="取消"), S(pattern=r"request C$")))
        self.assertIsNone(tree.find(S(text="取消"), S(text="unknown")))

    def test_text_description_and_disabled_parent_are_resolved_semantically(self):
        tree = Tree('''<hierarchy><node package="dev.atoll.app" clickable="true" enabled="false" bounds="[10,20][90,70]">
        <node package="dev.atoll.app" content-desc="发送" bounds="[30,30][70,60]"/></node></hierarchy>''')
        leaf = tree.find(S(description="发送"))
        self.assertEqual("false", tree.target(leaf).attrib["enabled"])

    def test_adb_lock_is_checked_before_every_command_and_private_error_does_not_leak(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp); (path / "owner").write_text("sol-1")
            adb = Adb("emulator-5554", path, "sol-2")
            with patch("ui.subprocess.run", side_effect=AssertionError("must not execute")), self.assertRaises(Failure):
                adb.shell("input", "text", "private-password", private=True)
            (path / "owner").write_text("sol-2")
            with patch("ui.subprocess.run") as execute:
                execute.return_value.returncode = 1
                with self.assertRaises(Failure) as error:
                    adb.shell("input", "text", "private-password", private=True)
                self.assertNotIn("private-password", str(error.exception))

    def test_step_failure_stops_and_records_remaining_work(self):
        with tempfile.TemporaryDirectory() as tmp:
            smoke = run.Smoke(None, {"channel_id": "test"}, None, tmp)
            smoke.ui.capture = lambda name: None
            with self.assertRaises(Failure):
                smoke.step("login", lambda: (_ for _ in ()).throw(Failure("login assertion failed")))
            import json
            result = json.loads((Path(tmp) / "summary.json").read_text())
            self.assertEqual("failed", result["steps"][0]["status"])
            self.assertIn("channel-list", result["pending"])

    def test_queue_mutation_keeps_existing_position_and_removes_only_own_entry(self):
        with tempfile.TemporaryDirectory() as tmp:
            lock = run.EmulatorLock("sol-2", 1)
            lock.base = Path(tmp); lock.queue = Path(tmp) / "queue"
            lock.queue.write_text("sol-1\nsol-2\nsol-3\n")
            lock._change_queue(True)
            self.assertEqual("sol-1\nsol-2\nsol-3\n", lock.queue.read_text())
            lock._change_queue(False)
            self.assertEqual("sol-1\nsol-3\n", lock.queue.read_text())


if __name__ == "__main__":
    unittest.main()
