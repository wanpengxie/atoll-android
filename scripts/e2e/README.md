# Isolated-node Android smoke test

Preferred runner: Compose instrumented UI in the app's `androidTest` source set.
It uses `createAndroidComposeRule<MainActivity>`, `performTextReplacement`,
semantic actions and `waitUntil`; no adb keyboard or coordinate input.

```sh
python3 scripts/e2e/instrumented.py --dry-run
python3 scripts/e2e/instrumented.py --owner sol-2
```

The second command runs `./gradlew connectedDebugAndroidTest` with the five
instrumentation parameters listed by dry-run. The wrapper obtains the test
credentials through SSH, captures/redacts Gradle output, owns the emulator
queue/lock and pulls the per-step screenshots and JSON summary. No plaintext
credentials belong in a manually pasted command. JAVA_HOME should point to
Android Studio's JBR, as in WORKFLOW.md.

Inside the test, the exact isolated URL is required **before Activity startup**;
only the isolated account's saved cookie/bootstrap is cleared. Login/logout
use a new UI session. The terminal page is opened through UI, then its public
PTY interface executes `ls` and checks the fixture file plus a standalone
completion marker. This verifies the real PTY without simulating native keys.

The older adb runner below is retained until the instrumented suite passes.

Run on mbp. Python 3 standard library, adb, and uiautomator are enough. The
runner accepts **only** `http://100.99.232.10:18080`; it rejects other hosts,
ports, schemes, query strings and redirects before reading credentials or
calling adb. It does not read the user's `~/.atoll`.

```sh
python3 scripts/e2e/run.py --dry-run
python3 -m unittest discover -s scripts/e2e -p 'test_*.py'
./gradlew assembleBenchmark
python3 scripts/e2e/run.py --owner sol-2
```

Use `--apk PATH` for another benchmark APK, `--output PATH` for artifacts,
and `--reply-timeout 180` for a slow provider. The output must remain outside
this repository. The runner joins `~/atoll-build/emulator.queue`, takes the
lock only at its turn, checks ownership before **every** adb command, and
releases its own lock and queue entry even after a failure. An existing
entry keeps its place. Do not run two runners with the same worker name.

Login uses the real login page. The test-only root password lives in
`tardis:~/atoll-test/root-password` (0600); it is read through SSH at use
time, never printed, logged or stored in reports. Its transient adb argv is
acceptable for this isolated test password. The user node's credentials
are never read. Before login the runner clears **only** the emulator's
`dev.atoll.app` data, so an old user's session cannot be restored. At the
submit boundary it reads the actual server, account and password fields:
server must equal the isolated URL, account must equal root, and password
length must equal 32. A mismatch stops before submission. Only these checks
are reported; password contents are never reported. Login passes only when
the authenticated `OPEN` connection indicator appears. At the
end logout invalidates only the newly created UI session, leaving the
shared test-node token and other workers' sessions usable.

`test-node-password.py` configures the test password through
`~/atoll-test/bin/atoll passwd --stdin --dir ~/atoll-test`. A copy is installed
as `~/atoll-test/bin/test-node-password.py`; the isolated node's control
script calls it after `start` and `reset`. The password persists across
reset; its 32 alphanumeric characters avoid adb shell/input escaping.
No secrets are in this repository or the command text sent to workspace tools.

Steps cover login, channel list, opening `c0.android-test`, a real codex
reply, queued messages, cancel/edit/insert, file browsing and preview,
terminal `ls`, tasks, members, channel details, creation and retirement of
one uniquely named child, and logout. `/hold` makes the queue deterministic;
edit's exact unhold resumes it. Terminal output is checked through its
existing Ctrl+Shift+C selectable-transcript dialog. A unique fixture file
is written only into the test channel's own workspace for preview/`ls`.

All controls are found by text or contentDescription. A waiting action is
scoped to the row with this run's marker, never the first global “取消”.
Tap bounds and scroll containers come from the current XML tree; there are
no fixed screen pixels, production app hooks, or test instrumentation APKs.

Each successful step saves PNG and XML; failure saves its last screen and
stops immediately. `summary.json` records passed/failed steps, elapsed time
and remaining steps. No raw subprocess command/credential output is saved.
A failed run leaves its uniquely named test file/child and any unfinished
agent requests available for diagnosis; use the test node's reset only
when the lead confirms no other worker is using it.
