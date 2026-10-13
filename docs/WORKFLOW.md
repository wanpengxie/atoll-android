# How work is done here

- Repo: tardis `c0.dev/atoll-android` (git, branch `main`; worktrees under `../atoll-android-wt/<worker>`). The lead (claude)
  owns `main`, design, review and merges.
- Each worker works on its own branch in its own git worktree:
  `git -C <repo> worktree add ../atoll-android-wt/<worker> -b <worker>/<task>`.
  Commit small, with a clear message. Do not push to `main`; report the
  branch when done.
- Build and test on mac-mbp (it has the Android SDK and the emulator), via the
  channel member `device-mbp` (`workspace.bash`). Each worker has its own
  build directory there, synced from its worktree:

  ```
  W=sol-1   # your name
  SRC=xiewanpeng@100.99.232.10:/home/xiewanpeng/.atoll/device/daemons/local-device/channels/c0.dev/atoll-android-wt/$W/
  mkdir -p ~/atoll-build/$W && rsync -a --delete --exclude build --exclude .gradle --exclude local.properties "$SRC" ~/atoll-build/$W/
  cd ~/atoll-build/$W && echo 'sdk.dir=/Users/xiewanpeng/Library/Android/sdk' > local.properties
  export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
  ./gradlew testDebugUnitTest assembleDebug
  ```

  Long builds: `workspace.bash` answers after `yield_ms`; read on with
  `workspace.poll`.
- Emulator (`emulator-5554`) is shared: take the lock first
  (`mkdir ~/atoll-build/emulator.lock` succeeds = yours; `rmdir` when done).
  It must resolve tailnet names, so it runs with MagicDNS first; if it is
  down, the lead restarts it with:
  `nohup ~/Library/Android/sdk/emulator/emulator -avd ash-browser -no-window -no-snapshot-load -no-snapshot-save -gpu host -dns-server 100.100.100.100,223.5.5.5 > ~/atoll-build/emulator.log 2>&1 &`.
  Never work around DNS in app code.
  Wait for the lock by retrying, not by messaging its holder:
  in turn, first come first served (`~/atoll-build/emulator.queue`, one
  name per line):
  `grep -qx $W ~/atoll-build/emulator.queue || echo $W >> ~/atoll-build/emulator.queue;`
  `until [ "$(head -1 ~/atoll-build/emulator.queue)" = $W ] && mkdir ~/atoll-build/emulator.lock 2>/dev/null; do sleep 15; done; echo $W > ~/atoll-build/emulator.lock/owner`.
  Release: `rm -rf ~/atoll-build/emulator.lock; sed -i '' "/^$W\$/d" ~/atoll-build/emulator.queue`.
  A `workspace.bash` answer of "still running" means you do not have it yet.
  Start every emulator command with
  `[ "$(cat ~/atoll-build/emulator.lock/owner)" = $W ] || exit 1`.
  The emulator also hosts other projects' apps: launch ours explicitly
  (`am start -n dev.atoll.app/...`) and never uninstall or stop other packages.
  Hold it only for install + run + screenshots (about 10 minutes at most);
  develop and run JVM tests without it.
- Workers do not message each other; anything shared goes through the lead.
- Node for live tests: OUR OWN test node `http://100.99.232.10:18080`
  (tardis; home `~/atoll-test`). Never test against the user's node
  (`tardis.tail6bc2a1.ts.net`, `~/.atoll`). Control:
  `~/atoll-test/bin/test-node.sh start|stop|status|reset` (reset wipes it and
  recreates channel `c0.android-test` with a codex member; actor ids change,
  read them from `~/atoll-test/node.json`). Its token is
  `~/atoll-test/server/atoll-token` on tardis: read it at use time over ssh,
  never print it or put it on a command line (everything sent through
  `workspace.bash`, command and output, goes into the channel ledger), e.g.
  `ATOLL_TOKEN=$(ssh xiewanpeng@100.99.232.10 cat atoll-test/server/atoll-token) ./gradlew ...`.
  Never commit it. Anything may be tested there,
  writes and logout included; if you break it, `reset`. Debug and benchmark
  builds allow cleartext to 100.99.232.10 only.
  The login screen pre-fills the user's node when nothing is saved: any UI
  login in a test must type the test node URL into the server field and
  read it back before submitting.
- End-to-end UI suite (17 steps on the test node, Compose instrumented):
  `python3 scripts/e2e/instrumented.py --owner <worker>` (takes the emulator
  queue, injects the test node URL/credentials as instrumentation arguments,
  collects screenshots and summary.json). Run it before reporting a change
  to app/** or anything that touches login, timeline, composer, waiting,
  files, terminal, tasks, roster or governance.
- Report a commit only after `git rev-parse HEAD` printed it; never a guessed or placeholder hash.
- Report back: what you built, what you tested (commands + results), what you
  did not, the branch name. Keep to the task's scope; raise design questions
  instead of guessing.
