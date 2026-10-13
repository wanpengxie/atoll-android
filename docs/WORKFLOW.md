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
- Node for live tests: `https://tardis.tail6bc2a1.ts.net` (from mbp and the
  emulator). A local automation token is on tardis at
  `~/.atoll/server/atoll-token` (send as `Authorization: Bearer`). Everything
  you send through `workspace.bash` — the command and its output — is written
  to the channel ledger, so never put the token on a command line or print
  it: read it on mbp at use time, e.g.
  `ATOLL_TOKEN=$(ssh xiewanpeng@100.99.232.10 cat .atoll/server/atoll-token) ./gradlew ...`.
  Never commit it.
- Report back: what you built, what you tested (commands + results), what you
  did not, the branch name. Keep to the task's scope; raise design questions
  instead of guessing.
