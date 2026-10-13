"""Isolated-node guards and shared emulator queue for the instrumented runner."""
import json
from pathlib import Path
import re
import subprocess
import time
import urllib.parse
import urllib.request


class Failure(RuntimeError):
    pass


URL = "http://100.99.232.10:18080"
SSH = "xiewanpeng@100.99.232.10"
STEPS = ["login", "channel-list", "open-channel", "codex-reply", "waiting-queue", "waiting-cancel",
         "waiting-edit", "waiting-insert", "files", "preview", "terminal-ls", "tasks", "members",
         "channel-details", "create-child", "retire-child", "logout"]


def validate_url(url):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "http" or parsed.netloc != "100.99.232.10:18080" or parsed.path not in ("", "/") or parsed.query or parsed.fragment:
        raise Failure("only http://100.99.232.10:18080 is allowed")
    return URL


def ssh(command, input_data=None):
    result = subprocess.run(["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", SSH, command],
                            input=input_data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=45)
    if result.returncode:
        raise Failure("isolated node SSH operation failed")
    return result.stdout


def load_node():
    value = json.loads(ssh("cat atoll-test/node.json"))
    validate_url(value.get("url", ""))
    if value.get("channel_name") != "c0.android-test" or not value.get("channel_id") or not value.get("codex_actor_id", "").startswith("agent:test-codex:"):
        raise Failure("test node metadata is incomplete")
    return value


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise Failure("test node redirects are refused")


class EmulatorLock:
    def __init__(self, owner, timeout, event=None):
        if not re.fullmatch(r"sol-[1-4]", owner):
            raise Failure("owner must be an existing worker name")
        self.owner, self.timeout = owner, timeout
        self.base = Path.home() / "atoll-build"
        self.lock, self.queue = self.base / "emulator.lock", self.base / "emulator.queue"
        self.held = False
        self.retained = False
        self.event = event or (lambda *args, **kwargs: None)
        self.owner_guard = None
        self.identity = None

    def __enter__(self):
        # A short mutation lock serializes queue appends/removals across runners.
        import fcntl
        self.base.mkdir(parents=True, exist_ok=True)
        self.owner_guard = (self.base / (self.owner + '.emulator-runner.guard')).open('a')
        try:
            fcntl.flock(self.owner_guard, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            self.owner_guard.close()
            raise Failure('another emulator runner for this owner is already active')
        self._change_queue(add=True)
        self.event('queued')
        deadline = time.monotonic() + self.timeout
        try:
            while time.monotonic() < deadline:
                rows = self.queue.read_text().splitlines()
                if rows and rows[0] == self.owner:
                    try:
                        self.lock.mkdir()
                        (self.lock / "owner").write_text(self.owner + "\n")
                        self.identity = self.lock.stat().st_ino
                        self.held = True
                        self.event('lock-acquired', lock_inode=self.identity)
                        return self
                    except FileExistsError:
                        pass
                time.sleep(15)
            raise Failure("timed out waiting for emulator queue")
        except BaseException:
            self._change_queue(add=False)
            self.owner_guard.close()
            raise

    def _change_queue(self, add):
        import fcntl
        self.base.mkdir(parents=True, exist_ok=True)
        with (self.base / "emulator.queue.guard").open("a") as guard:
            fcntl.flock(guard, fcntl.LOCK_EX)
            rows = self.queue.read_text().splitlines() if self.queue.exists() else []
            if add:
                if self.owner not in rows:
                    rows.append(self.owner)
            else:
                rows = [row for row in rows if row != self.owner]
            self.queue.write_text("".join(row + "\n" for row in rows))

    def check_owner(self):
        try:
            actual = (self.lock / "owner").read_text().strip()
        except OSError:
            actual = ""
        if actual != self.owner or not self.lock.exists() or self.lock.stat().st_ino != self.identity:
            raise Failure("emulator lock is not owned by this runner")

    def retain(self):
        self.retained = True
        self.event('lock-retained')

    def __exit__(self, *args):
        try:
            if self.retained:
                return
            if self.held:
                self.check_owner()
                (self.lock / "owner").unlink()
                self.lock.rmdir()
                self.event('lock-released', lock_inode=self.identity)
            self._change_queue(add=False)
        finally:
            if self.owner_guard is not None:
                self.owner_guard.close()
