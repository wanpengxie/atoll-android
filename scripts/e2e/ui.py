"""Small semantic adb driver. Device positions come only from the current XML tree."""
from dataclasses import dataclass
from pathlib import Path
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "dev.atoll.app"


class Failure(RuntimeError):
    pass


@dataclass(frozen=True)
class Selector:
    text: str = None
    description: str = None
    pattern: str = None

    def matches(self, node):
        attrs = node.attrib
        return ((self.text is not None and attrs.get("text") == self.text)
                or (self.description is not None and attrs.get("content-desc") == self.description)
                or (self.pattern is not None and re.search(self.pattern, attrs.get("text", "")) is not None))

    def label(self):
        return self.text or self.description or self.pattern


def bounds(node):
    values = tuple(map(int, re.findall(r"-?\d+", node.attrib.get("bounds", ""))))
    if len(values) != 4 or values[2] <= values[0] or values[3] <= values[1]:
        raise Failure("matched control has no visible bounds")
    return values


class Tree:
    def __init__(self, xml):
        try:
            self.root = ET.fromstring(xml)
        except ET.ParseError:
            raise Failure("uiautomator returned invalid XML")
        self.parents = {child: parent for parent in self.root.iter() for child in parent}

    def find(self, selector, within=None):
        scopes = [self.root]
        if within is not None:
            scopes = []
            for anchor in self.root.iter("node"):
                if not within.matches(anchor):
                    continue
                try:
                    height = bounds(anchor)[3] - bounds(anchor)[1]
                except Failure:
                    continue
                parent = anchor
                while parent in self.parents:
                    parent = self.parents[parent]
                    try:
                        if bounds(parent)[3] - bounds(parent)[1] > height * 4:
                            break
                    except Failure:
                        break
                    if any(selector.matches(child) for child in parent.iter("node")):
                        scopes.append(parent)
                        break
        for scope in scopes:
            for node in scope.iter("node"):
                if node.attrib.get("package") != PACKAGE or not selector.matches(node):
                    continue
                try:
                    bounds(node)
                except Failure:
                    continue
                return node
        return None

    def target(self, node, editable=False):
        candidate = node
        while candidate in self.parents:
            if candidate.attrib.get("class") == "android.widget.EditText" or (not editable and candidate.attrib.get("clickable") == "true"):
                return candidate
            parent = self.parents[candidate]
            if editable:
                edits = [child for child in parent.iter("node") if child.attrib.get("class") == "android.widget.EditText"]
                if len(edits) == 1:
                    return edits[0]
            candidate = parent
        return node


class Adb:
    def __init__(self, serial, lock, owner):
        self.serial, self.lock, self.owner = serial, Path(lock), owner

    def check_owner(self):
        try:
            actual = (self.lock / "owner").read_text().strip()
        except OSError:
            actual = ""
        if actual != self.owner:
            raise Failure("emulator lock is not owned by this runner")

    def run(self, *args, input_data=None, timeout=30, private=False):
        self.check_owner()
        process = subprocess.run(["adb", "-s", self.serial, *args], input=input_data,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        if process.returncode:
            # Never include argv: password input may be one of its private arguments.
            raise Failure("adb action failed" if private else "adb action failed (exit %d)" % process.returncode)
        return process.stdout

    def shell(self, *args, private=False):
        return self.run("shell", shlex.join(args), private=private)


class UI:
    def __init__(self, adb, output):
        self.adb, self.output = adb, Path(output)
        self.last = None

    def dump(self):
        self.adb.shell("uiautomator", "dump", "/sdcard/atoll-e2e.xml")
        xml = self.adb.shell("cat", "/sdcard/atoll-e2e.xml")
        self.last = Tree(xml)
        return self.last

    def wait(self, selector, timeout=30, within=None, absent=False):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            tree = self.dump()
            node = tree.find(selector, within)
            if (node is None) == absent:
                return node
            time.sleep(.4)
        raise Failure("expected %s%s" % ("absence of " if absent else "", selector.label()))

    def wait_value(self, selector, text, timeout=20):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            node = self.dump().find(selector)
            if node is not None and node.attrib.get("text", "") == text:
                return
            time.sleep(.3)
        raise Failure("editor value was not confirmed")

    def click(self, selector, within=None, timeout=30, editable=False):
        node = self.wait(selector, timeout, within)
        target = self.last.target(node, editable)
        if target.attrib.get("enabled", "true") == "false":
            raise Failure("control is disabled: " + selector.label())
        left, top, right, bottom = bounds(target)
        self.adb.shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def scroll(self, direction="down"):
        tree = self.dump()
        containers = [n for n in tree.root.iter("node") if n.attrib.get("package") == PACKAGE and n.attrib.get("scrollable") == "true"]
        if not containers:
            raise Failure("no semantic scroll container")
        node = max(containers, key=lambda n: (bounds(n)[2] - bounds(n)[0]) * (bounds(n)[3] - bounds(n)[1]))
        left, top, right, bottom = bounds(node)
        x, high, low = (left + right) // 2, top + (bottom - top) // 5, top + 4 * (bottom - top) // 5
        self.adb.shell("input", "swipe", str(x), str(low if direction == "down" else high), str(x), str(high if direction == "down" else low), "300")

    def reveal(self, selector, attempts=6):
        for _ in range(attempts):
            if self.dump().find(selector) is not None:
                return
            self.scroll()
        raise Failure("cannot reveal: " + selector.label())

    def type(self, selector, text, private=False):
        self.click(selector, editable=True)
        self.adb.shell("input", "keycombination", "113", "29")  # Ctrl+A
        self.adb.shell("input", "keyevent", "67")
        self.input(text, private)

    def input(self, text, private=False):
        if not text.isascii() or "\n" in text or "\r" in text:
            raise Failure("adb input requires one ASCII line")
        self.adb.shell("input", "text", text.replace(" ", "%s"), private=private)

    def hide_keyboard(self):
        # BACK only when dumpsys says an IME is displayed; do not pop navigation.
        value = self.adb.shell("dumpsys", "input_method").decode(errors="replace")
        if "mInputShown=true" in value or "isInputViewShown=true" in value:
            self.adb.shell("input", "keyevent", "4")

    def capture(self, name):
        self.dump()
        for node in self.last.root.iter("node"):
            if node.attrib.get("password") == "true":
                node.attrib["text"] = "[password]"
        (self.output / (name + ".xml")).write_bytes(ET.tostring(self.last.root, encoding="utf-8"))
        (self.output / (name + ".png")).write_bytes(self.adb.run("exec-out", "screencap", "-p"))
