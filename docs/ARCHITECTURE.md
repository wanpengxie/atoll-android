# Atoll Android — architecture

A native client for an Atoll node, at feature parity with atoll-web
(`../atoll-web`), built for phones: fast cold start, smooth long timelines,
bounded memory. Kotlin + Jetpack Compose, OkHttp, Room, kotlinx.serialization.

The web app is the behavioural reference. When in doubt about *what* the app
does, read the web code it ports (paths given per layer below); *how* it does
it here follows this document.

## Principles

- One source of truth per fact. The ledger rows (from the node, cached in
  Room) are the truth; everything on screen is derived from them.
- One writer per state. Each layer owns its state and is mutated only on its
  own serial executor; others read immutable snapshots (StateFlow). No locks
  shared across layers.
- Pure core, thin edges. Protocol decoding and the timeline fold are pure
  Kotlin (JVM unit-testable, no Android types). Android types live only in
  `store/` and `ui/`.
- Main thread only renders. Decode, fold, Markdown parse, DB I/O run on
  background dispatchers.
- Simple over clever. No speculative abstraction; no edge case that the node
  cannot produce.

## Layers and packages (`dev.atoll.app`)

```
core/   shared contracts: Envelope, Row, frames (this package is the API between layers)
net/    IdentityClient (HTTP login/session/logout/register, cookie persisted),
        Wire (one WebSocket, attach, ref->receipt, reconnect, down-frame flow),
        FileTransfer (/files GET/PUT with tickets), Pty (/pty)
store/  Room: rows, coverage ranges, channel meta; DAO only
sync/   ChannelSync: keeps each channel's cached window contiguous with the
        node (initial tail, gap refill, older paging, live append)
model/  pure fold: rows -> turns/threads/standalone/narration (port of
        channel-replica.js), request lifecycle, waiting dock, presentation
        rules, unread, sub-tasks, roster, options
ui/     Compose: theme (from atoll-web src/styles/tokens.css), screens,
        components; ViewModels map model snapshots to UI state
app/    AppGraph: manual wiring of singletons (no DI framework)
```

Dependency direction: `ui -> model, sync -> store, net -> core`. `model`
depends only on `core`.

## Data flow

```
Wire (WebSocket) --DownFrame--> sync.ChannelSync --rows--> store (Room)
                                        |
                                        +--rows--> model.ChannelReplica (fold, per channel, serial)
                                                         |
                                                         +--> StateFlow<TimelineSnapshot> --> ui
UI actions --> net (submit/resolve/cancel/resource frames) --> node --> back as feed rows
```

- Cold start: `ChannelSync` loads the cached window from Room and folds it
  first (instant screen), then asks the node for rows after its newest seq.
- Coverage: per channel, the store records contiguous seq ranges it holds
  (`[low, high]`, as scanned by the node: `scan_low_seq`/`scan_high_seq`
  from `page_end`, `checkpoint` from live). A gap is filled down to the range
  below it; an interrupted refill resumes from the same owed floor. Never
  "newest held row" alone (that bug cost the web a stuck Waiting dock).
- Memory bound: the in-memory replica holds at most 5000 rows per channel
  (trim to 4500); older rows come back from Room when the reader pages up.

## Protocol (frame version 5)

- HTTP: `POST /api/identity/login {email,password}` sets the session cookie;
  `GET /api/identity/session`; `POST /api/identity/logout`;
  `POST /api/identity/register`. Bearer `Authorization` also works (tests).
- WebSocket `/ws`. Up: `{v:5, frame_type, ref, payload}`; down:
  `{v:5, frame_type, ref?, payload}`.
  - up: attach, submit, resolve, cancel, after, cancel_timer, resource,
    observe, unobserve, channel_meta, history_before, history_cancel
  - down: receipt (answers a ref), error (ref optional), feed
    `{channel_id, seq, envelope, source, generation}` (frames of another
    generation are dropped, as the web does), checkpoint `{channel_id, scan_low_seq,
    scanned_seq, generation}`, page_end `{head_seq, oldest_seq, newest_seq,
    scan_low_seq, scan_high_seq, next_before_seq, rows, bytes, has_older}`,
    observe_ended, memberships
  - attach `{focus, history_protocol:5, generation, label, since?}` ->
    receipt `{session, label, contract_version, boot, memberships
    [{channel_id, actor_id}], memberships_complete, history_meta
    [{channel_id, head_seq, has_rows, last_activity}]}`
  - Payload field lists and validation: atoll-web `src/protocol/frame.js`.
- Envelope: `id, ts, ts_received, channel_id, sender{kind,id}, kind
  (request|response|event), type, payload{_context?, body}, parent_id,
  correlation_id, visibility, audience, expires_at`. Business fields live in
  `payload.body` (`Envelope.body`). Terminal = response with status
  completed|failed. Vocabulary: atoll-web `src/protocol/vocab.js`.
- Files: `GET/PUT /files?channel_id=&t=<ticket>` (ticket from a `resource`
  frame); `HEAD`, `inline=1`, ETag/304 supported.

## Threading

- `net.Wire`: its own CoroutineScope; frames out through a single writer;
  down-frames published on a `SharedFlow`.
- `sync`/`model`: one serial executor per channel
  (`Dispatchers.Default.limitedParallelism(1)` per channel) — rows of a
  channel are applied in order with no locks; channels run in parallel.
- `store`: Room's own executors; batch inserts per page/burst.
- `ui`: collect StateFlows with lifecycle; never block.

## Testing

- `core`, `net` (frame codec), `model`, `sync` (with a fake Wire and an
  in-memory store): JVM unit tests (`./gradlew testDebugUnitTest`). Port the
  web's tests where they pin behaviour.
- Live smoke: tests that hit the real node are opt-in (env `ATOLL_URL`,
  `ATOLL_TOKEN`).
- UI: run on the emulator on mac-mbp; screenshots attached to the report.
