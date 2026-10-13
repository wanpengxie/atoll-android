# Task queue

Base for wave 1: the `main` commit named in the dispatch message. Shared
files are frozen for workers: `core/**`, `app/build.gradle.kts`,
`settings.gradle.kts`, `docs/**`, `scripts/**` — ask the lead for a change.
Each task lists the paths it may change; check before reporting:
`scripts/check-scope.sh main '<pattern>' ...`.

Done means: the task's tests pass on mbp (`./gradlew testDebugUnitTest`
for JVM tests; `assembleDebug` builds), scope check passes, and the report
gives commands + results + branch.

## Wave 1 (independent, in parallel)

### W1-net — wire and identity (sol-1)
Port atoll-web `src/net/wire.js`, `src/net/identity.js`, `src/protocol/frame.js`
into `net/`:
- `FrameCodec`: encode up frames with the field rules of frame.js
  (PAYLOAD_FIELDS / REQUIRED / value checks), decode down frames into
  `core.DownFrame` (unknown types dropped, wrong version = incompatible).
- `IdentityClient` (OkHttp): login, session, logout, register; errors carry
  code/detail. Cookie jar persisted through a small `KeyValueStore`
  interface (Android impl over SharedPreferences, in-memory impl for tests).
- `Wire : core.WireClient` over one OkHttp WebSocket to `/ws`: attach on
  open (generation increments per connection, label from caller), ref
  counter, receipts with 30 s timeout, `history()` collecting rows +
  page_end by ref (those rows never go to `frames`), live feed / checkpoint /
  memberships / observe_ended to `frames`, reconnect with backoff
  (500 ms doubling, max 5 s), `reconnectNow()` for app foreground, stop on
  sign-out and on incompatibility. Session label: device model.
- Tests: codec unit tests; Wire against OkHttp MockWebServer (attach,
  receipt, error, history collection, reconnect, version mismatch); one
  opt-in live smoke test (env ATOLL_URL, ATOLL_TOKEN) that attaches and reads
  one history page.
Paths: `app/src/main/java/dev/atoll/app/net/**`,
`app/src/test/java/dev/atoll/app/net/**`.

### W1-model — the fold (sol-2) [heaviest]
Port atoll-web into pure Kotlin `model/` (no Android types):
`src/model/channel-replica.js` (rows -> timeline: turns with request,
provisional, terminal, thread children; standalone; narration; terminal
closures kept across trims; open-turn floor on trim; incremental apply),
`src/model/request-lifecycle.js` (lifecycle, member restarts, passedInQueue),
`src/model/conversation-visibility.js`,
`src/model/conversation-presentation.js` (visible entries, scopes all/mine
and the related-conversation relation, actor filter, weak-notice events,
continuation, day boundaries, sub-task attachment),
`src/model/background-tasks.js`.
API: `ChannelReplica(channelId, selfId)` with `apply(batch: RowBatch)` ->
what changed, `trim(maxRows)`, and immutable snapshots for the UI
(`TimelineSnapshot` with items and stable keys; `runningTasks`; waiting
turns). Keep the web's semantics; drop what is DOM-only.
Tests: port the web's tests that pin these behaviours (search
`src/**/*.test.js*` for the modules above).
Paths: `app/src/main/java/dev/atoll/app/model/**`,
`app/src/test/java/dev/atoll/app/model/**`.

### W1-sync — cache and sync (sol-3)
`store/`: Room database — rows (channel_id, seq, envelope json; PK
channel+seq), coverage (channel_id, low, high: contiguous scanned ranges),
channel meta (head_seq, last_activity, read position). Behind a `RowStore`
interface so `sync/` tests use an in-memory store.
`sync/ChannelSync`: per channel, serial: load the cached window (emit
`RowBatch(CACHE)`), then fill from the node: initial tail when nothing is
cached; otherwise refill the gap between the cached top and the node head
down to the owed floor (resumes after interruption; coverage from page_end
`scan_low_seq..scan_high_seq` and live `checkpoint`); older paging (Room
first, node when Room has a gap); live rows appended. Emits `RowBatch`es
on a flow. Uses `core.WireClient` (fake in tests).
Tests: in-memory store + fake wire: cold start, warm start with gap,
interrupted refill resumed, older paging across cache/node, live append,
duplicate rows ignored.
Paths: `app/src/main/java/dev/atoll/app/store/**`,
`app/src/main/java/dev/atoll/app/sync/**`, matching test dirs.

### W1-ui — design system and screens shell (sol-4)
`ui/theme`: colours, type, spacing, shapes from atoll-web
`src/styles/tokens.css` (light; dark if the web has it). `ui/components`:
message frame (avatar, name, AI label, time, continuation), Markdown
renderer on commonmark-java (headings, lists, emphasis, inline code, code
block with language label + copy, tables, blockquote, links incl. absolute
file paths, strikethrough) rendered in Compose with parse results cached
per message, weak notice line, status chips (processing/queued/failed),
day separator. `ui/screens`: Login, channel list, channel screen scaffold
(header with 动态/任务 tabs, 与我相关 + actor chips, timeline LazyColumn,
composer shell) driven by UI-owned view-state classes and fake data, plus a
screenshot of each on the emulator. Match atoll-web's mobile layout (run
atoll-web's mock or read its CSS for sizes).
Paths: `app/src/main/java/dev/atoll/app/ui/**`,
`app/src/main/java/dev/atoll/app/MainActivity.kt`, `app/src/main/res/**`,
`app/src/test/java/dev/atoll/app/ui/**`.

## Next (queued; refined after the parity inventory)
Integration (AppGraph: wire -> sync -> model -> ui), composer and agent
controls (ask/steer/insert/replace/interrupt/dismiss/edit), waiting dock,
files (browser, preview, download, upload), terminal (pty), members and
governance, tasks, automation and timers, activity, approvals, agent
options and context, running drawer, starred, recent, unread and
notifications, registration, settings.
