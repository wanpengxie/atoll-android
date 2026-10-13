#!/usr/bin/env python3
"""Configure only ~/atoll-test, without a password in argv or output."""
import os
from pathlib import Path
import secrets
import re
import subprocess


def ensure_password():
    root = Path.home() / "atoll-test"
    if root.is_symlink() or root.resolve() != root:
        raise RuntimeError("refuse a redirected test node home")
    path = root / "root-password"
    if path.is_symlink():
        raise RuntimeError("refuse a redirected test password file")
    current = path.read_text().strip() if path.exists() else ""
    if not re.fullmatch(r"[A-Za-z0-9]{32}", current):
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as output:
            output.write(secrets.token_hex(16) + "\n")
    os.chmod(path, 0o600)
    password = path.read_bytes()
    if not password.strip():
        raise RuntimeError("test password file is empty")
    result = subprocess.run([str(root / "bin/atoll"), "passwd", "--stdin", "--dir", str(root)],
                            input=password, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        # No raw CLI output: the only useful public fact is that setup failed.
        raise RuntimeError("test node passwd failed (exit %d)" % result.returncode)


if __name__ == "__main__":
    ensure_password()
