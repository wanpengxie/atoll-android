#!/usr/bin/env python3
"""Run only on the isolated node, using the public benchmark app and semantic UI."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import uuid

from ui import Adb, Failure, PACKAGE, Selector as S, UI

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
    if value.get("channel_name") != "c0.android-test" or not value.get("channel_id") or not value.get("codex_actor_id", "").startswith("agent:"):
        raise Failure("test node metadata is incomplete")
    return value


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise Failure("test node redirects are refused")


class EmulatorLock:
    def __init__(self, owner, timeout):
        if not re.fullmatch(r"sol-[1-4]", owner):
            raise Failure("owner must be an existing worker name")
        self.owner, self.timeout = owner, timeout
        self.base = Path.home() / "atoll-build"
        self.lock, self.queue = self.base / "emulator.lock", self.base / "emulator.queue"
        self.held = False

    def __enter__(self):
        # A short mutation lock serializes queue appends/removals across runners.
        self._change_queue(add=True)
        deadline = time.monotonic() + self.timeout
        try:
            while time.monotonic() < deadline:
                rows = self.queue.read_text().splitlines()
                if rows and rows[0] == self.owner:
                    try:
                        self.lock.mkdir()
                        (self.lock / "owner").write_text(self.owner + "\n")
                        self.held = True
                        return self
                    except FileExistsError:
                        pass
                time.sleep(15)
            raise Failure("timed out waiting for emulator queue")
        except BaseException:
            self._change_queue(add=False)
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

    def __exit__(self, *args):
        if self.held and (self.lock / "owner").read_text().strip() == self.owner:
            (self.lock / "owner").unlink()
            self.lock.rmdir()
        self._change_queue(add=False)


class Smoke:
    def __init__(self, options, node, adb, output):
        self.options, self.node, self.adb = options, node, adb
        self.output = Path(output)
        self.ui = UI(adb, output)
        self.rows = []
        self.name = uuid.uuid4().hex[:10]
        self.prefix = "E2E_" + self.name
        self.file = "android-e2e-" + self.name + ".txt"
        self.child = "e2e-" + self.name

    def step(self, name, action):
        started = time.monotonic()
        row = {"step": name, "status": "running"}
        self.rows.append(row)
        try:
            action()
            self.ui.capture(name)
            row["status"] = "passed"
            print("PASS " + name, flush=True)
        except BaseException as error:
            row["status"] = "failed"
            # Failure messages refer to selectors/state only, never subprocess argv or credentials.
            row["detail"] = str(error) if isinstance(error, Failure) else type(error).__name__
            try:
                self.ui.capture(name + "-failed")
            except BaseException:
                pass
            raise
        finally:
            row["seconds"] = round(time.monotonic() - started, 3)
            self.summary()

    def summary(self):
        result = {"server": URL, "run": self.name, "channel_id": self.node["channel_id"], "steps": self.rows,
                  "pending": [name for name in STEPS if name not in {row["step"] for row in self.rows}]}
        (self.output / "summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")

    def menu(self, label):
        self.ui.click(S(description="频道菜单"))
        self.ui.click(S(text=label))

    def send(self, text):
        self.ui.type(S(description="消息输入框"), text)
        self.ui.click(S(description="发送"))
        self.ui.wait_value(S(description="消息输入框"), "")
        if not text.startswith("/"):
            self.ui.wait(S(pattern=re.escape(text)), timeout=20)
        self.ui.hide_keyboard()

    def waiting(self, marker):
        return S(pattern=r"^↳ .*" + re.escape(marker))

    def seed_file(self):
        # The remote script accepts no destination; only this node's one test-channel workspace.
        program = """import json,pathlib,re,sys
root=pathlib.Path.home()/'atoll-test'
node=json.loads((root/'node.json').read_text())
data=json.loads(sys.stdin.read())
assert node['url']=='http://100.99.232.10:18080' and node['channel_name']=='c0.android-test'
assert re.fullmatch(r'android-e2e-[a-f0-9]{10}\\.txt',data['name'])
p=root/'device/daemons/local-device/channels/c0.android-test'/data['name']
assert p.parent.is_dir() and not p.is_symlink()
p.write_text(data['text']+'\\n')
"""
        import shlex
        ssh("python3 -c " + shlex.quote(program), json.dumps({"name": self.file, "text": self.prefix + "_PREVIEW"}).encode())

    def login(self):
        self.adb.run("install", "-r", str(self.options.apk), timeout=90)
        self.adb.shell("am", "force-stop", PACKAGE)
        # Only this emulator test package; no cached user's server/session can be restored.
        self.adb.shell("pm", "clear", PACKAGE)
        self.adb.shell("am", "start", "-n", PACKAGE + "/.MainActivity")
        self.ui.wait(S(text="进入 Atoll"))
        self.ui.type(S(text="服务器地址"), URL)
        self.ui.type(S(text="账号"), "root")
        password = ssh("cat atoll-test/root-password").decode().strip()
        if not re.fullmatch(r"[A-Za-z0-9]{32}", password):
            raise Failure("isolated test password unavailable")
        # The script's command text contains only a file read. Test-only transient adb argv is accepted.
        self.ui.type(S(text="密码"), password, private=True)
        password = ""
        self.ui.hide_keyboard()
        # Verify the actual editable node after all fields settle, immediately before submit.
        # A cleared app can restore a default server asynchronously while we fill the form.
        tree = self.ui.dump()
        server_label = tree.find(S(text="服务器地址"))
        if server_label is None or tree.target(server_label, editable=True).attrib.get("text") != URL:
            raise Failure("login server field is not the isolated test node")
        account_label = tree.find(S(text="账号"))
        account_ok = account_label is not None and tree.target(account_label, editable=True).attrib.get("text") == "root"
        password_label = tree.find(S(text="密码"))
        password_length_ok = password_label is not None and len(tree.target(password_label, editable=True).attrib.get("text", "")) == 32
        print("login readback: server=test-node, account=root: %s, password length=32: %s" % (account_ok, password_length_ok), flush=True)
        if not account_ok or not password_length_ok:
            raise Failure("login field readback failed")
        self.ui.click(S(text="进入 Atoll"))
        self.ui.wait(S(text="OPEN"), timeout=40)

    def channel_list(self):
        self.ui.wait(S(text="OPEN"), timeout=40)
        self.ui.wait(S(pattern=r"^(?:c0\.)?android-test$"))

    def open_channel(self):
        self.ui.click(S(pattern=r"^(?:c0\.)?android-test$"))
        self.ui.wait(S(description="消息输入框"), timeout=40)
        # The fixture has one agent; its current choice must load before slash controls are sent.
        self.ui.wait(S(pattern=r"test-codex|codex"), timeout=30)

    def reply(self):
        self.send("Reply only " + self.prefix + "_REPLY. This is an isolated Android UI smoke test; do not change files.")
        self.ui.wait(S(text=self.prefix + "_REPLY"), timeout=self.options.reply_timeout)

    def queue(self):
        self.send('/hold {"duration_ms":180000}')
        self.send("Run the shell command sleep 30, then reply only " + self.prefix + "_SLOW. Do not change files.")
        self.send("Ignore this extra isolated test instruction; keep your current reply. " + self.prefix + "_Q2")
        self.send("Discard this isolated UI test request. " + self.prefix + "_Q3")
        self.ui.wait(S(pattern=r"3 条等待消息"))

    def cancel(self):
        self.ui.click(S(text="取消"), within=self.waiting(self.prefix + "_Q3"))
        self.ui.wait(self.waiting(self.prefix + "_Q3"), absent=True)

    def edit(self):
        self.ui.click(S(text="编辑"), within=self.waiting(self.prefix + "_Q2"))
        self.ui.wait(S(text="提交编辑"), timeout=30)
        value = "Ignore this extra isolated test instruction; keep your current reply. " + self.prefix + "_EDITED"
        self.ui.type(S(description="消息输入框"), value)
        self.ui.click(S(text="提交编辑"))
        self.ui.wait(S(text="编辑已提交"), timeout=30)
        self.ui.wait(self.waiting(self.prefix + "_EDITED"))

    def insert(self):
        self.ui.click(S(text="插入"), within=self.waiting(self.prefix + "_EDITED"))
        self.ui.wait(self.waiting(self.prefix + "_EDITED"), absent=True)
        self.ui.wait(S(text=self.prefix + "_SLOW"), timeout=self.options.reply_timeout)

    def files(self):
        self.menu("打开文件")
        self.ui.wait(S(description="关闭文件"))
        self.ui.reveal(S(text=self.file))

    def preview(self):
        self.ui.click(S(text=self.file))
        self.ui.wait(S(pattern=self.prefix + "_PREVIEW"))
        self.ui.click(S(text="‹ 返回"))
        self.ui.click(S(description="关闭文件"))

    def terminal(self):
        self.menu("打开终端")
        self.ui.wait(S(text="已连接"), timeout=30)
        self.ui.click(S(text="⌨"))
        self.ui.input("ls; echo " + self.prefix + "_TERMINAL")
        self.adb.shell("input", "keyevent", "66")
        time.sleep(1)
        # Existing public shortcut opens selectable transcript; no test-only app hook.
        self.adb.shell("input", "keycombination", "113", "59", "31")  # Ctrl+Shift+C
        self.ui.wait(S(text="选择终端文本"))
        self.ui.wait(S(pattern=re.escape(self.file)))
        self.ui.wait(S(pattern=r"(?m)^" + self.prefix + r"_TERMINAL\r?$"))
        self.ui.capture("terminal-transcript")
        self.ui.click(S(text="关闭"))
        self.ui.hide_keyboard()
        self.menu("关闭终端")

    def tasks(self):
        self.ui.click(S(text="任务"))
        self.ui.wait(S(pattern=r"^工作项 \d+"))
        self.ui.click(S(text="动态"))

    def members(self):
        self.ui.click(S(text="成员"))
        self.ui.wait(S(text="刷新名册"))
        self.ui.wait(S(pattern=r"test-codex"))
        self.ui.click(S(text="关闭"))

    def details(self):
        self.menu("频道详情")
        self.ui.wait(S(text="危险操作"))
        self.ui.click(S(text="关闭"))

    def create(self):
        self.menu("新建子频道")
        self.ui.type(S(text="频道名称"), self.child)
        self.ui.hide_keyboard()
        self.ui.reveal(S(text="创建频道"))
        self.ui.click(S(text="创建频道"))
        self.ui.wait(S(text="进入新频道"), timeout=45)
        self.ui.click(S(text="进入新频道"))
        self.ui.wait(S(text=self.child), timeout=30)

    def retire(self):
        self.menu("频道详情")
        self.ui.click(S(text="危险操作"))
        # The detail port may show the qualified name or leaf name. Read its actual confirmation label.
        node = self.ui.wait(S(pattern=r"^输入 .*" + re.escape(self.child) + r" 确认$"))
        label = node.attrib["text"]
        self.ui.type(S(text=label), label[3:-3])
        self.ui.hide_keyboard()
        self.ui.click(S(text="退役当前频道"))
        self.ui.click(S(text="确认"))
        self.ui.wait(S(pattern=r"频道退役终态已确认|频道成员关系已结束|暂不可用|频道列表"), timeout=35)
        self.adb.shell("am", "start", "-n", PACKAGE + "/.MainActivity", "-d", "atoll://channels/" + self.node["channel_id"])
        if self.ui.dump().find(S(text="关闭")) is not None:
            self.ui.click(S(text="关闭"))
        self.ui.wait(S(description="频道菜单"))

    def logout(self):
        self.ui.click(S(text="‹"))
        self.ui.click(S(text="退出"))
        self.ui.wait(S(text="进入 Atoll"), timeout=30)

    def run(self):
        self.seed_file()
        methods = [self.login, self.channel_list, self.open_channel, self.reply, self.queue, self.cancel,
                   self.edit, self.insert, self.files, self.preview, self.terminal, self.tasks, self.members,
                   self.details, self.create, self.retire, self.logout]
        for name, method in zip(STEPS, methods):
            self.step(name, method)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default=URL)
    parser.add_argument("--apk", type=Path, default=Path("app/build/outputs/apk/benchmark/app-benchmark.apk"))
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--owner", default="sol-2")
    parser.add_argument("--output", type=Path, default=Path.home() / "atoll-build/e2e-artifacts")
    parser.add_argument("--queue-timeout", type=int, default=900)
    parser.add_argument("--reply-timeout", type=int, default=150)
    parser.add_argument("--dry-run", action="store_true")
    options = parser.parse_args(argv)
    validate_url(options.url)
    if not re.fullmatch(r"emulator-[0-9]+", options.serial):
        raise Failure("only an Android emulator serial is allowed")
    if options.dry_run:
        print(json.dumps({"dry_run": True, "url": URL, "steps": STEPS, "login": "own password file -> UI login", "writes": "isolated node only"}, indent=2))
        return 0
    if not options.apk.is_file():
        raise Failure("benchmark APK not found")
    node = load_node()
    opener = urllib.request.build_opener(NoRedirect)
    with opener.open(URL + "/healthz", timeout=10) as response:
        if response.status != 200:
            raise Failure("isolated node is not healthy")
    output = options.output / time.strftime("%Y%m%d-%H%M%S")
    output.mkdir(parents=True)
    with EmulatorLock(options.owner, options.queue_timeout) as lock:
        adb = Adb(options.serial, lock.lock, options.owner)
        smoke = Smoke(options, node, adb, output)
        try:
            smoke.run()
        finally:
            # No credentials in diagnostics. Stop only our package and release only our lock.
            adb.shell("am", "force-stop", PACKAGE)
            smoke.summary()
    print("summary: " + str(output / "summary.json"))
    return 0


if __name__ == "__main__":
    try:
        status = main()
    except Exception as failure:
        print("FAIL " + (str(failure) if isinstance(failure, Failure) else type(failure).__name__), file=sys.stderr)
        status = 1
    sys.exit(status)
