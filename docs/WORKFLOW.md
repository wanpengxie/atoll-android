# How work is done here

- Repo: tardis `c0.dev/atoll-android` (git, branch `main`). The lead (claude)
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
- Node for live tests: `https://tardis.tail6bc2a1.ts.net` (from mbp and the
  emulator). A local automation token is on tardis at
  `~/.atoll/server/atoll-token` (send as `Authorization: Bearer`); never
  print it or commit it.
- Report back: what you built, what you tested (commands + results), what you
  did not, the branch name. Keep to the task's scope; raise design questions
  instead of guessing.
