# Isolated-node instrumented smoke test

Run on mbp with Android Studio's JBR as `JAVA_HOME` (see WORKFLOW.md):

```sh
python3 -m unittest discover -s scripts/e2e -p 'test_*.py'
python3 scripts/e2e/instrumented.py --dry-run
python3 scripts/e2e/instrumented.py --owner sol-2
python3 scripts/e2e/instrumented.py --owner sol-2 --build-type benchmark
```

Debug uses `connectedDebugAndroidTest`. Benchmark selects
`-PatollTestBuildType=benchmark connectedBenchmarkAndroidTest`: the same 17
steps run against the optimized, non-debuggable application and its R8 rules.
Benchmark keeps its existing build configuration; the runner does not turn
off shrinking or add a production test hook. The test APK is separate from
the app delivered to users.

The runner accepts only `http://100.99.232.10:18080` and emulator serials.
It rejects another origin before reading credentials, and refuses redirects.
`~/atoll-test/node.json`, `root-password` and `server/atoll-token` are read
over SSH from tardis. The password/token become instrumentation arguments
inside this process; they are not printed in tool commands or reports.
The test itself validates the exact URL before launching the Activity.

`support.py` owns the shared `~/atoll-build/emulator.queue` and lock. Only
the head acquires it, and the runner verifies ownership before invoking
Gradle. Its lock and queue entry are released on success or failure.
`--queue-timeout`, `--serial` and `--output` override those runner settings.
Artifacts stay outside the repository, grouped by time and build type:
Gradle log, per-step PNGs, redacted UI text, terminal output, observer
retirement evidence and `summary.json` with timings and pending steps.

All input/clicks use Compose semantics and `performTextReplacement`, not
adb input or uiautomator. The suite covers real login, channel list,
`c0.android-test`, codex reply, FIFO waiting, cancellation, editing and
insertion, files/preview, terminal `ls`, tasks, members, channel details,
child creation/retirement and logout. The terminal command uses the public
PTY client after opening its page. A slow turn forms a real wait queue;
new `agent.ask` admissions clear a hold, so pre-holding would be incorrect.
Logout invalidates only this run's fresh UI session and checks that the
shared test token still works.

The isolated node's password setup source is `scripts/test-node-password.py`;
its installed copy is `~/atoll-test/bin/test-node-password.py` on tardis.
The node's control script calls it after start/reset. The retired adb UI
driver and XML-selector code have been removed.
