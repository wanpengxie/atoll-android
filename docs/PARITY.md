# Parity checklist (atoll-web -> Android)

Tick an item only when it works on the emulator against the real node.
Paths are relative to atoll-web.


Source root: `/home/xiewanpeng/.atoll/device/daemons/local-device/channels/c0.dev/atoll-web/`. All paths below are relative to it. The UI text is Chinese, so Chinese labels are quoted as they appear.

Things that may not match your checklist:
- The header has only two tabs, "动态" (conversation) and "任务" (tasks).
- "与我相关" / "全部" is a scope toggle, and the per-agent chips are actor filters. Both render into the header on desktop and float over the timeline on phones (`src/ui/conversation/ConversationSurface.jsx`).
- The "重启频道" (restart channel) button is always disabled. Clicking it only shows a notice.
- Timer listing, ui.state / ui.navigate / ui.open, observe/unobserve and text steer from the composer exist in the protocol layer but nothing in the UI uses them.

---

## 0. Wire protocol and HTTP surface

### WebSocket `/ws` (`src/protocol/frame.js`, `src/net/wire.js`)
- Envelope is `{v: 5, frame_type, ref?, payload}`. Maximum frame size is 512 KB. Unacked requests time out after 30 s.
- Reconnect backoff starts at 500 ms and doubles each time. The cap is 30 s on desktop and 5 s on mobile.
- On mobile only, the client re-attaches immediately when the page becomes visible again or the network comes back `online` (`src/net/wake.js`).
- **Up frames**:
  - `attach {since, focus, history_protocol:5, generation, label}`. `label` is the client name, e.g. "Android Chrome" (`src/model/client-label.js`).
  - `submit {channel_id, id, msg_type, kind:'request', payload, audience, visibility:'public', parent_id?, expires_at_ms?}`. Human-authored payloads get `origin: {session, label}` added.
  - `resolve {channel_id, req_id, text? | decision approve/reject + note?}` for human.ask / human.approve. The variant `{result | error}` answers ui.* requests.
  - `cancel {channel_id, req_id}`
  - `after {channel_id, duration_ms, msg_type, payload}` and `cancel_timer {channel_id, timer_id}`
  - `resource {channel_id, op: create|read|write|delete|stat|list, resource_id?, address?, args?, query{prefix, limit, cursor}?, with_content?, node_type?}`
  - `channel_meta {channel_id, generation}`
  - `history_before {channel_id, before_seq, limit ≤200, byte_limit ≤4 MiB, generation, purpose: initial-tail|user-demand|hydrate, priority: foreground|background}`
  - `history_cancel {channel_id, target_ref, generation}`
  - `observe` / `unobserve` are defined but unused.
- **Down frames**: `feed`, `checkpoint`, `receipt`, `error`, `observe_ended`, `page_end`, `memberships` (pushed full membership list).
- **Attach receipt fields used**: `contract_version`, `session`, `label`, `boot` (server world id; a change wipes local caches), `generation`, `memberships[{channel_id, actor_id}]`, `memberships_complete`, `history_meta`.
- **Error codes**: `bad_payload`, `not_in_audience`, `unauthorized_sender`, `already_closed`, `request_not_found`, `invalid_decision`, `unavailable`, `routing_unavailable`, `idempotency_conflict`, `now_member`, `channel_not_found`, `channel_unavailable`, `capability_unavailable`, `forbidden`, `closed`, `bad_cursor`. A version mismatch yields `version_incompatible`.
- **Envelope** (`src/protocol/envelope.js`):
  - Fields: `id, ts, channel_id, sender{id, kind}, kind (event|request|response), type, payload{body}, parent_id, correlation_id, visibility (public|system), audience, expires_at`.
  - Final statuses: completed, failed. Provisional statuses: received, queued, processing, deferred, unavailable.

### Message types (`src/protocol/vocab.js`)
- **agent.\***: ask, steer, queue, interrupt, dismiss, hold, unhold, replace, hold_expired, fork, compact, new, select, options, context, provider.run, task, timer.wake.
- **human.\***: message, ask, approve.
- **ui.\***: state, navigate, open, form. Only `ui.form` is answered. `ui.session.list` is answered by the server.
- **system.\* sent to the `system` actor**:
  - member.create, admit, list, get, delete, restart, set, config.get, config.set, config.write, restart_all
  - log.recent, log.query
  - channel.create, get, list, set, delete; channel.device.list; channel.description.get, write
  - actor.description.create, get, list, retire
  - class.list
  - principal.\*, credential.set
  - device.create, attach, detach, list, delete
- **Other**: `actor.describe`, `storage.get_url`, `task.create`.
- **Narration events** (visibility=system): system.member.created, deleted, updated; system.channel.inbound; system.build.started, finished.
- **Reserved channels**: `c0` (root, always sorted first). `c0.lobby`, `c0.home` and actor-type channels are hidden from the rail. A member's default device is `local-device`.

### HTTP
- **Identity** (`src/net/identity.js`, cookie auth):
  - `GET /api/identity/session`
  - `POST /api/identity/login {email, password}`
  - `POST /api/identity/register {email, password, display_name?, id?}`
  - `POST /api/identity/logout`
- **Observation** (`src/net/obs.js`, read-only; a 401 forces logout):
  - `GET /obs/space/channels?parent_id=` (walked as a tree)
  - `GET /obs/space/principals`, `/obs/space/daemons`, `/obs/space/actor-descriptions`
  - `GET /obs/channel/{id}/actors` (roster), `/obs/channel/{id}/devices`, `/obs/channel/{id}/profile`
- **Files**:
  - Upload: a `resource create` frame returns a ticket, then `PUT /files?channel_id=&t=<ticket>`.
  - Download / preview: a `resource read with_content` frame returns a ticket, then `GET /files?channel_id=&t=`.
  - `oss://` storage files skip `/files`: `storage.get_url` (sent to the storage-seat member) returns a signed URL that is fetched directly.
- **Node update** (root principal only; `src/app/hooks/useWireSession.js`):
  - `GET /api/update?check=1` on start, every 6 h, and after each reconnect.
  - `POST /api/update` starts an upgrade. While upgrading, status is polled every 1 s.
- **Terminal WebSocket `/pty`** (`src/net/pty.js`):
  - One socket carries every terminal.
  - JSON control frames: `open {id, channel_id, device, session, cols, rows}`, `resize`, `detach`, `close`. Responses: `ready {session}`, `exit {reason}`, `error {code, detail}`.
  - Binary frames carry a 4-byte big-endian stream id followed by the bytes.
- The dev proxy also forwards `/compute` and `/healthz`, but `src/` never calls them.

### Local persistence (needed for parity)
| Data | Where it is stored |
|---|---|
| Channel feed cache | IndexedDB `atoll-channel-replica-v1` (`src/model/channel-replica.js`) |
| Durable outbox and drafts (recipients, attachments, reply target; body text is not persisted) | IndexedDB `atoll-outbox-v2` (`src/model/outbox-store.js`) |
| Server world id | localStorage `atoll.server.boot.v2` |
| Cursors | localStorage `atoll.cursor.v3.*` |
| Read positions | localStorage `atoll.read-position.v1.*` |
| Per-channel scope and actor filter | localStorage `atoll.view-session.v3.<principal>` |
| Stars | localStorage `atoll.stars.v1.<principal>` |
| File reading history (24) and preview stack (20) | localStorage `atoll.web.file-reading-history.v1.*` |
| Last channel | localStorage `atoll.web.last-channel` |
| Pane widths, rail collapsed | localStorage `atoll.web.pane.*`, `atoll.rail.collapsed` |
| Terminal theme | localStorage `atoll.terminal.theme` |
| Terminal session ids | sessionStorage `atoll.terminal.session.<ch>.<device>` |
| Perf profile override | localStorage `atoll.perf.profile.v1` (set by `?perf=mobile`) |
| Cached principal and memberships for warm boot | `src/model/workspace-bootstrap-cache.js` |

A server world change (`boot` differs) clears all of the above that is world-scoped.

---

## 1. Auth and boot
- [ ] **Boot check**: calls `/api/identity/session` and shows "正在启动工作区…". A 401 shows the auth page. A cached principal is display-only until the server confirms it. Files: `src/app/WorkspaceApp.jsx` (IdentityBoundary), `src/app/hooks/useWireSession.js` (useIdentitySession).
- [ ] **Login / register tabs** (`src/ui/Auth.jsx`):
  - Login takes an account (plain text, so "root" works) and a password.
  - Register takes email, optional display name, and password (minimum 8).
  - Errors show inline. The submit button reads "请稍候…" while busy.
  - Endpoints: login and register above.
- [ ] **Logout**: the "退出" button in the rail account card. Calls `POST /api/identity/logout`, then tears down all state (`src/app/WorkspaceLayout.jsx`).
- [ ] **Session expiry**: any 401 from `/obs` returns to the auth page.
- [ ] **Version incompatible screen**: blocks the app and offers "刷新页面". Triggered when attach reports `version_incompatible` (`src/ui/VersionIncompatible.jsx`).

## 2. App shell and navigation (`src/app/WorkspaceLayout.jsx`, `src/app/SurfaceShell.jsx`, `src/app/hooks/useWireSession.js`)
- [ ] **Layout breakpoints**: mobile ≤640 px, compact ≤900 px, otherwise desktop. Separately, the "mobile performance profile" means coarse pointer plus ≤900 px (`src/model/device-profile.js`).
- [ ] **Rail header**: ATOLL brand and a connection state badge (OPEN / CONNECTING / RECONNECTING / CLOSED).
- [ ] **Rail collapse/expand** (desktop and compact only), remembered across sessions.
- [ ] **Resizable panes**: rail 200–520 px, context panel ≥300 px, preview ≥420 px. Drag, arrow keys, or double-click to reset. Widths persist.
- [ ] **Rail global action**: "活动" opens the activity center (§11).
- [ ] **Rail sections**: "我的频道 N" (member channels) and "空间 N" (other channels in the space), each with an empty state.
- [ ] **"＋ 新建频道"**: opens the new-channel modal (§12.4).
- [ ] **Channel row** shows:
  - `#` and the channel name.
  - An access label: 离线缓存 (offline cache), 暂不可用 (unavailable), 只读旁观 (read-only observer), 旁观中断 (observer interrupted), 可发现 (discoverable), 无权访问 (no access), 确认中 (confirming).
  - Agent activity timers: up to 2 entries as "agent name + mm:ss" ticking every second, then "+N".
  - Unread badge: a count capped at "99+", "…" while unread state is recovering, "?" when unknown.
- [ ] **Node upgrade block** (root only):
  - Button label follows the state: "升级到 vX" (upgrade to vX), 准备/下载/校验/安装/重启 (preparing / downloading / verifying / installing / restarting), "正在重连…" (reconnecting), "升级失败，重试" (failed, retry), or an unsupported message.
  - Clicking asks for confirmation first.
  - Also shows "当前版本：x（只读）" (current version, read-only).
- [ ] **Account card**: avatar initial, display name and id, "空间管理" (space admin, §13), "退出" (logout).
- [ ] **Channel header**:
  - Mobile "‹" button opens the channel drawer.
  - Channel name.
  - View tabs "动态" / "任务" (arrow-key navigation). Returning to 动态 restores Files if Files was open.
  - Header slot for the scope and actor filter (not used on mobile).
  - Quick actions: "文件" toggle, "终端" toggle, "重启频道" (always disabled with a notice).
  - "SEQ n" label (hidden on mobile).
  - "成员" button (members panel).
  - "•••" channel menu.
- [ ] **"•••" channel menu items**:
  - 频道详情 (channel details)
  - 定时动作 (timed actions)
  - 高级资源工具 (advanced resource tools)
  - 新建子频道 (new subchannel)
  - 导出性能记录 (export perf record)
  - 重启频道 (disabled)
  - Mobile-only additions: 打开/关闭文件, 最近阅读 (recent reads), 星标消息 (starred), 后台任务(N) (background tasks), 打开/关闭终端.
- [ ] **Status stack**: a dismissable top error bar and a dismissable channel notice bar (access notices, transient notices).
- [ ] **Edge tabs on the conversation column** (desktop and compact only; on mobile these live in the ••• menu):
  - "最近" (recent reads)
  - "★ 星标" (starred)
  - "运行中 N" (running background tasks)
- [ ] **Routing and deep links**:
  - Hash route `#/channels/{id}/{conversation|files|tasks}?focus={turn|artifact|work_item|participant|channel}:{key}`.
  - Back/forward is supported. Opening a focused panel pushes a history entry.
  - Without a route, the last channel opens. Fallback order: requested channel, then the first `member_active` channel, then the first channel.
- [ ] **Per-channel state on switch**: Files open/closed and terminal open/closed are remembered per channel. The 任务 view persists across channel switches.
- [ ] **Keyboard shortcuts**:
  - Ctrl+1…9 jumps to a member channel; Ctrl+N / Ctrl+P go to next / previous.
  - Ctrl+F12 toggles the terminal.
  - Esc closes the open panel or menu.
- [ ] **Access states**: member_active, member_stale, member_unavailable, observer_active, observer_stale, discoverable, access_denied, retired, loading. Non-content states show a placeholder and hide cached content. A retired channel triggers an automatic switch with a notice.
- [ ] **Mobile shell**:
  - Single column; the channel rail becomes a modal drawer with focus trap and "×".
  - A 44 px header with the view tabs in the middle.
  - Files or terminal take over the whole content area. The composer stays usable over Files.
  - The layout follows `visualViewport` so the keyboard does not cover the composer.
  - Touch targets are at least 44 px.

## 3. Conversation timeline (`src/ui/conversation/ConversationSurface.jsx`, `src/ui/timeline/*`, `src/model/conversation-presentation.js`, `src/model/conversation-visibility.js`)
- [ ] **Scope toggle**: "与我相关" (mine, the default) vs "全部" (all), saved per channel. Hidden until the user's own actor id is known.
- [ ] **Actor filter chips** (only in the 与我相关 scope):
  - One chip per agent in the roster. A green dot means that agent is running now.
  - Multiple chips can be selected.
  - A filter for an agent no longer in the channel shows as "已失效 · id" (stale) and can be removed.
  - When exactly one agent is filtered, the composer's default target follows it.
- [ ] **Empty states**:
  - Empty channel.
  - No conversations involving you.
  - No conversations match the actor filter.
  - "loading earlier…" partial state.
  - "已到频道最早一条动态" (reached the start of the channel).
- [ ] **Virtualized list** (Virtuoso in the web app):
  - Two modes: follow the bottom, or browse.
  - Older history prefetches as you scroll up (`history_before`). Shows "正在读取更早动态…" while waiting, and an error with "重试".
  - Availability errors also offer "重试".
- [ ] **"↓ N 条新动态" pill**: counts unread conversation roots that arrived while browsing.
- [ ] **"↓" jump button**: appears when the reader is several screens away from the bottom.
- [ ] **Position memory**: each view remembers its browse position in page memory only. A reload always opens at the latest message.
- [ ] **Read marking**: `markSeen` advances the read cursor while scrolling. While following live, new arrivals count as read, but only while the page is visible.
- [ ] **Day separators**: a date line between days (`toLocaleDateString zh-CN`).
- [ ] **Continuation rows**: consecutive messages from the same author within 5 minutes drop the avatar and header and show only the time.
- [ ] **Turn card**, i.e. a request plus its response (`src/ui/timeline/TimelineRowRenderer.jsx` TurnCard):
  - **Request bubble**: avatar, sender name, "AI" tag for agents, time, "发送给 X、Y" (sent to), and the local send state when not yet sent.
  - **Agent answer bubble**: name, "AI", time, and a status: "已完成" (done), "处理失败" (failed), "未完成 · Agent 已重启" (agent restarted) or "未完成 · 已过期" (expired).
  - Streaming text from `process.kind=stage, stage=text` renders above the final answer.
  - An interrupted turn shows "✗ 已停止 · 发消息即继续" (stopped; send a message to continue).
  - A "terminal result unavailable" line can appear.
  - Turns that were superseded (`merged_into` / `preempted_by`) have their bubble hidden.
  - Pending controls: "编辑" (agent.ask only), "停止" (agent.interrupt, shown when the provisional frame lists it in `controls`), and "取消" for local unsent messages (wire `cancel`).
  - The request shows "正在输入框中编辑" while it is being edited.
- [ ] **Progress / tool trail** (ProgressTrail):
  - While running: "处理中: <title>" with run duration, start clock, and the last 2 rows. Expand to see all rows with timestamps.
  - When finished: "N 条过程记录", collapsible.
  - Rows: stage rows ("思考中…" for thinking) and tool rows ("tool: X …" then "完成" or "失败").
  - Clicking a row opens the process panel (§7.1).
  - Mobile shows tool output as text, capped at 4 KB.
- [ ] **Agent collaboration tree** under an answer:
  - Agent→agent and agent→human calls render as compact collapsible messages that quote the request and show the result and progress, with tree rails.
  - Sub-tasks (`agent.task` events) render as leaves: state (进行中 / 已完成 / 失败), kind ("子 Agent" for sub-agent, "后台命令" for background command), title, elapsed time, owner, latest line. Expand to see messages and the result.
  - More than 3 tasks collapse into a group: "子 Agent N 个" with counts.
- [ ] **"N 次关联调用"** (related calls): collapsible list of tool/system calls with nested results and progress.
- [ ] **Standalone messages** (human.message, events): avatar or continuation time, body, attachments.
  - `agent.provider.run` with `task_summary` adds a leaf: "后台任务已返回" (background task returned).
  - An orphan `agent.task` (its request is not on screen) renders as a task tree.
- [ ] **Weak notice events**: one-line "name text · time". Bodiless events get labels such as `actor.initializing` → 正在启动, `actor.ready` → 已就绪.
- [ ] **Narration** (system events):
  - "X 已加入频道" (joined) / "X 已离开频道（原因）" (left, with reason). Generated members are hidden.
  - Build started/finished lines with tone.
  - "收到来自 X 的频道请求：type" (inbound request from another channel).
  - Known system operations show labels, e.g. 添加参与者.
- [ ] **Hidden rows**:
  - Control words: hold, unhold, hold_expired, interrupt, dismiss, context, options, fork, actor.describe, storage.get_url.
  - Steers that carry no text.
  - agent.select and agent.new until they complete.
  - ui.\* types.
  - Queued agent.ask / queue / replace (these sit in the waiting dock until processing starts).
- [ ] **Message actions** (desktop on hover/focus):
  - "复制" (copy, with feedback)
  - "☆ 星标 / ★ 已星标" (star)
  - "↩ 回复" (reply; only to agents or humans other than you)
  - "创建任务" (create task; needs a channel member that declares `task.create`)
  - "查看过程" (view process; only when the turn has process data)
- [ ] **Touch gestures** (mobile): action buttons are hidden. Tapping the message body starts a reply. Long-pressing 500 ms copies. Moving more than 8 px cancels.
- [ ] **Folding**: bodies longer than about 18 visual lines or about 1200 display units collapse to 14 lines. Toggle "展开全文 · N 行" (expand) / "收起" (collapse). The latest row is exempt. The choice lasts for the page only.
- [ ] **Attachments in messages**: from `body.attachments` / `files` and `resource_link` content blocks. Each shows a type label (图片 image / PDF / 音频 audio / 视频 video / 文本 text / 文件 file). Tap previews; "↓" downloads.
- [ ] **Structured results**:
  - JSON text renders as a collapsible tree (20-item cap, depth ≥2 collapsed).
  - Failures show the error title from the code table, the code, the detail, and the error data.
  - "返回了空文本" (empty text) and "✓ 已完成" (bare ack) states.
  - actor.describe results are titled "X 的能力" (X's capabilities).
- [ ] **Approval cards** for human.ask / human.approve addressed to you:
  - Title, detail, and "影响" (impact).
  - A textarea: the answer for ask, an optional note for approve.
  - Deadline or "已过期" (expired).
  - Buttons: "提交回答" (submit answer), or "批准" (approve) / "拒绝" (reject).
  - Shows "已回执" (receipt confirmed), the resolver and decision, and the response body. Wire errors render inline.
  - Uses the `resolve` frame.
- [ ] **Row error fallback**: "这条消息暂时无法显示…" (`src/ui/timeline/TimelineList.jsx`).
- [ ] **Jump to message**: from Starred or Running, scrolls the message to the middle. Only works if the message is already loaded.

### 3.1 Markdown and content (`src/ui/MarkdownContent.jsx`, `src/ui/CodeBlock.jsx`, `src/ui/MermaidBlock.jsx`, `src/model/math-markdown.js`)
- [ ] GFM: tables scroll horizontally, task-list checkboxes are disabled.
- [ ] Line breaks render as written (remark-breaks).
- [ ] Math via KaTeX, with normalization (`src/model/math-markdown.js`).
- [ ] Raw HTML is never rendered.
- [ ] Code blocks: language label, "复制" (copy), Prism highlighting, line numbers when there are 5 or more lines.
- [ ] Mermaid diagrams: toggle "查看源码" (source) / "查看图表" (diagram); "图表语法有误" on syntax error.
- [ ] Images: lazy-loaded in a fixed frame with "正在加载图片…" and "图片无法加载" states.
- [ ] Links:
  - External links open in a new tab.
  - `/abs/path[:line]` links open the file preview at that line.
  - `oss://storage/host/path` links, including bare text addresses (auto-linked), open the preview via storage.get_url.
  - A file that belongs to another channel shows an error naming that channel.

## 4. Composer (`src/ui/composer/*`)
- [ ] **Editor**: rich text (TipTap) with placeholder "输入消息；@ 选择成员，/ 使用命令". Enter sends, Shift+Enter adds a newline. Input-method composition is handled. Repeated Enter cannot double-send.
- [ ] **Recipient pill above the input**:
  - Shows "@name +N", "⚠ 无收件人" (no recipient), or "⚠ X 已不在频道" (recipient left the channel).
  - Mention pills are removable. Backspace at the start of the input removes the last mention.
  - Delivery priority: reply target > @mention > actor-filter agent > manual pick > most recent agent you talked to > the channel's only agent.
- [ ] **@ menu**: lists channel members with kind and body; arrow keys and Enter to pick; Esc dismisses. Sends one request per recipient: `agent.ask` to agents, `human.message` to humans.
- [ ] **Slash commands** (`/` at line start; `//text` sends a literal "/text"):

  | Command | Sends |
  |---|---|
  | /compact | agent.compact |
  | /new | agent.new |
  | /restart | system.member.restart `{member: target}` to system |
  | /model [model] [effort] | agent.select |
  | /fork | agent.fork |
  | /context | agent.context |
  | /status | actor.describe |
  | /introduce `<class or actor:id[@v]>` [name] | system.member.create |
  | /admit `<principal>` | system.member.admit |
  | /members | system.member.list |
  | /channels [parent] | system.channel.list |

  - Dynamic commands: every `agent.<word>` the target declares in actor.describe with an input schema, used as `/word {JSON}`.
  - Usage and availability errors show inline.
  - Slash commands cannot be combined with a reply or with attachments.
- [ ] **Reply mode**: a quote bar "回复 @X" with an excerpt. "×" or Esc cancels. Sends with `parent_id`. Replying to a human shows "@name" instead of the model selector.
- [ ] **Attachments**:
  - Upload from the device (multiple files), by drag-and-drop ("松开以上传到当前频道"), or by pasting files.
  - Files upload into the channel's files directory on the default device. Name collisions get "-2" etc.
  - "从频道文件选择" (pick from channel files) opens a picker modal.
  - Draft chips show name and size; tap previews, "×" removes. The payload carries `{text, attachments}`.
  - Attachments are disabled while editing a message.
- [ ] **Model/agent selector** (bottom right; `src/ui/composer/agent-parameters.js`, `src/app/hooks/useAgentProbes.js`):
  - "选择 Agent" menu when there are 2 or more agents and no target.
  - "N 个目标" when there are multiple targets.
  - Agent name plus refresh: loads `agent.options` and `agent.context`.
  - Shows current model and effort, plus a compact context-usage percentage.
  - Popover sections "模型" (model) and "推理强度" (effort) send `agent.select`; shows "切换中" while switching.
  - Read-only client version / update status, and a context usage bar "上下文 x / y · 已使用 n%".
  - Fallback: options come from actor.describe `agent.select` oneOf branches.
  - Capabilities (`actor.describe`) are probed automatically for every agent when the channel is writable.
- [ ] **Send states and offline queue** (`src/ui/composer/useComposerSubmissionRuntime.js`, `src/model/outbox-store.js`):
  - States: queued, transmitting, accepted, delayed, uncertain, rejected.
  - Submissions are journaled to IndexedDB. Uncertain ones retry automatically after reconnect. Expired ones (`expires_at_ms`) are dropped.
  - Status rail messages:
    - "离线编辑；发送会先保存到本机，连接可用后自动发送。" (offline: saved locally, sent when connected)
    - The disabled reason plus "草稿仍保留在当前设备" (draft kept on this device)
    - A failure with "使用原编号重试" (retry with the same message id)
    - "发送结果待确认，正在通过重连账本核对。" (send result being confirmed after reconnect)
  - Local echoes appear in the timeline with their state and a "取消" button.
- [ ] **Drafts**: text is kept per channel while the app is running. Recipients, attachments, and reply target survive a reload.
- [ ] **Message edit**, from 编辑 on a pending turn or on a waiting-dock item:
  - Warns first if the agent has background tasks running.
  - Sends `agent.hold {target}`, receives a hold id, then `agent.context` as a probe.
  - The composer switches to edit mode with "取消编辑" (cancel edit) and "提交编辑" (submit edit).
  - Submitting sends `agent.replace {target, old_text, new_text, expected_hold_id}`. Cancelling sends `agent.unhold`.
  - Notices: "已退出编辑" (edit exited), "已停止这个 Agent…" (agent stopped), "当前 Agent 不支持安全编辑" (unsupported).
  - Edit is offered only when the agent declares replace/unhold with a lease (`src/ui/timeline/useWaitingEditingController.jsx`).
- [ ] **Mobile composer**: when empty and unfocused it rests as one line and hides the toolbar. Editor font is 16 px to avoid iOS zoom. Buttons are 44 px.

## 5. Waiting dock (`src/ui/timeline/useWaitingEditingController.jsx`, WaitingLayer)
- [ ] Floats above the composer. Lists queued `agent.ask` / `agent.queue` items grouped by agent. Collapse shows "N 条等待消息" with "展开" (expand).
- [ ] **Header actions**:
  - "全部插入" or "插入 X 全部" (insert all): `agent.steer {all: true}`.
  - "全部取消" or "取消 X 全部" (cancel all): `agent.hold`, then cancel or dismiss each item, then `agent.unhold`.
  - "收起" (collapse).
- [ ] **Per-item actions**:
  - "插入": `agent.steer {target}`.
  - "编辑".
  - "取消": wire `cancel` for your own item, `agent.dismiss` for someone else's.
  - Extra actions are the controls the agent declared on that item.
- [ ] **Item labels**: "等待连接后发出" (waiting for connection), "确认中" (confirming), "已暂停" (paused), "正在并入…" (merging), "收件人已离席，等待账本关闭" (recipient left), "Agent 不支持安全编辑", "正在编辑".

## 6. Right-side drawers / context panels (`src/ui/features/WorkspaceFeatures.jsx`)
On desktop these are a resizable side pane closed by Esc. On mobile they are full screen.
- [ ] **Running ("运行中 · N")**: sub-agent and background command tasks still running from live members. Each shows kind, owner, "已运行 N 分钟" (running N min), title, and last text. Tap to jump to the originating message; on mobile the drawer then closes. Source: `src/model/background-tasks.js`.
- [ ] **Starred ("星标")**: stars for this channel, newest first, stored on the device per principal. Each shows sender, time, and a snippet. Tap to jump ("这条消息还没加载到页面上…" if not loaded). "×" removes the star.
- [ ] **Recent reads ("最近阅读")**: files opened from messages or the file browser (24 max). Shows name, time, and line. Tap to preview.
- [ ] **Activity center** (§11), **process panel** (§7.1), **actor detail** (§10), **task detail** (§9.2), **channel details** (§12), **space admin** (§13), **timed actions** (§9.3), **advanced resources** (§8.3).

## 7. Process and preview panels
### 7.1 Process panel ("过程") — `TurnDetailPanel` in `src/ui/features/WorkspaceFeatures.jsx`
- [ ] Opened from "查看过程" or by clicking a progress row (deep-link `focus=turn:id`).
- [ ] Lists process records. Opening one shows tool input/output as a tree, mobile output text, and "执行说明" (execution notes).
- [ ] "账本信息" (ledger info) section: request id, type, status, ledger seq, terminal id, process ids, detail.

### 7.2 File preview (`src/ui/features/files/ArtifactPreviewPanel.jsx`, `src/app/hooks/useAttachmentTransactions.js`)
- [ ] Preview stays open per channel and keeps its layer (up to 6 channels cached).
- [ ] Back button walks the preview stack (up to 20).
- [ ] Rich files (Markdown, HTML) have a "预览" (rendered) / "源码" (source) toggle. "复制" copies the full text.
- [ ] Supported types:
  - Image (≤20 MB), video and audio (≤50 MB), PDF (≤25 MB, embedded).
  - Markdown rendered; links inside open further previews.
  - HTML in a sandboxed iframe.
  - Text and source with Prism highlighting and line numbers, jumping to the target line. Shows "目标第 N 行超出文件范围" if the line is out of range.
  - Text limit 512 KB. Unknown types are sniffed for text.
  - Otherwise "此文件暂不支持站内预览" (unsupported) or "预览暂不可用" (unavailable), with "下载原文件" (download original).
- [ ] Data paths: `resource read with_content` → ticket → `GET /files`; or `storage.get_url` → signed URL.

## 8. Files
### 8.1 Files browser (`src/ui/features/files/FilesFeature.jsx`)
- [ ] Opened from the "文件" toggle or the menu. Route view is `files`.
- [ ] Toolbar:
  - Back to the parent directory and breadcrumbs (root is the channel name).
  - Device selector when there is more than one device (devices from `/obs/channel/{id}/devices`).
  - "新建文件夹" (new folder): inline form, `resource create {address, node_type: 'directory'}`.
  - Refresh.
  - "上传" (upload, single file) into the current directory: create ticket, then PUT.
  - Close.
- [ ] Recent strip "最近查看" (up to 8).
- [ ] Table with sortable columns: name, modified date, size, kind. Folders sort first.
- [ ] Row tap opens a folder or previews a file.
- [ ] Row actions:
  - Download (anchor via `/files` ticket, or a signed URL).
  - Attach to the composer draft.
  - Delete, after confirmation; a folder must be empty. Uses `resource delete`.
- [ ] Listing: `resource list {query: {prefix: 'daemon://<device>/<channel>/<dir>/', limit: 100, cursor}}`. "载入更多" (load more) fetches the next page. Scroll position is remembered.
- [ ] Empty states: no device mounted, empty directory.

### 8.2 Channel file picker modal
- [ ] "从频道文件选择" from the composer: navigate, switch device, "载入更多", "选择" (choose) a file to attach as a draft attachment.

### 8.3 Advanced resource tools ("资源与文件")
- [ ] Two tabs. "文件" embeds the Files browser.
- [ ] "KV" tab: resource id and Args JSON, with buttons for create, read, write, stat, list, delete. Shows the raw JSON result. Uses the `resource` frame.

## 9. Tasks
### 9.1 Tasks view (`src/ui/features/tasks/TasksFeature.jsx`, `src/model/feature-tasks.js`)
- [ ] **Header buttons**: "安排自动动作" (schedule automation; opens §9.3) and "新建任务" (new task; only when some member declares `task.create`).
- [ ] **Tabs**: "工作项 N" (work items) and "等待区 N" (waiting).
- [ ] **Filters**:
  - Scope: 与我相关 / 全部.
  - Status: 待处理 (pending), 已完成 (done), 失败 (failed).
  - Type: 任务 (task), 审批 (approval), Agent 回合 (agent turn), 恢复事项 (recovery), 自动动作 (automation).
- [ ] **Groups**: 需要你处理 (needs you), 进行中 (active), 恢复事项, 自动动作 · 本设备 (this device), 历史 (history). Paged with "查看更早/更新任务" (older / newer).
- [ ] **Rows**: kind, title, assignees, state, "本设备" tag.
- [ ] **Waiting tab rows** have actions: 插入指令 (`agent.steer`), 停止 (`agent.interrupt`), 撤回请求 (wire `cancel`).
- [ ] **Item sources**:
  - task.create turns that returned a task id.
  - human.ask / human.approve.
  - Agent run turns.
  - Rejected or uncertain submissions (recovery).
  - Local timer records.

### 9.2 Task detail panel (`src/ui/features/tasks/TaskDetailPanel.jsx`)
- [ ] Shows state, "需要你处理" (needs you), type, assignee, creator, due/trigger time, provenance, description, current wait, impact, run state with "打开完整回合与控制" (open full turn).
- [ ] Recovery explanation; automation message type and payload; task id.
- [ ] Actions: 批准 / 拒绝 (resolve), 使用原编号重试 (retry with original id), 取消请求 (cancel request), 取消本设备自动动作 (cancel this device's automation).
- [ ] "返回来源" (go to source) and "诊断信息" (diagnostic JSON).

### 9.3 Create task dialog
- [ ] From the header, or "创建任务" on a message (records the source seq).
- [ ] Fields: content, optional note, executor (members declaring task.create), optional due time (datetime-local).
- [ ] Sends `submit msg_type task.create` to the chosen provider.

### 9.4 Timed actions ("定时动作") — `ChannelAutomationPanel` in `src/ui/features/governance/GovernanceFeature.jsx`
- [ ] Create: delay in ms, message type (default `agent.ask`), payload JSON. Sends `after`.
- [ ] Cancel by `timer_id` (`cancel_timer`).
- [ ] Lists only this browser session's timers: 已安排 (scheduled), 已取消 (cancelled), 已触发 (fired, detected from timer events in the feed). The server has no timer list.

## 10. Members / roster
- [ ] **Roster panel "成员 N"** (`src/ui/features/roster/RosterFeature.jsx`):
  - Source: `GET /obs/channel/{id}/actors` (fields kind, name, config_id, entry_id, body, principal, bound, device_online). Refreshed on member events.
  - Rows show kind icon, name, a "我" (me) tag, kind · body, and presence: 在线 (online), 已绑定 (bound), 未绑定 (unbound), 未知 (unknown).
  - Generated members are hidden. Shows "正在确认你在本频道中的 Actor 身份" while your own identity is pending. Manual refresh.
- [ ] **Actor detail panel**:
  - Shows id, type, source, bound state, description.
  - "刷新能力" (refresh capabilities) sends `actor.describe`. Capabilities are listed with descriptions and possible error codes.
  - "调用能力" (invoke a capability): pick a word, enter JSON, submit as a control to that actor.
- [ ] **Member config section** (agents and tools only; `src/ui/features/roster/MemberConfig.jsx`, `src/model/member-config.js`):
  - "读取配置" (read config) sends `system.member.get`. Shows class, source, run device, note, last build (`src/ui/features/governance/BuildLine.jsx`).
  - Missing `$required` placeholders, each with a fill input. Filling sends `member.config.set`.
  - "编辑条目" (edit entry):
    - Build from Class or from an Actor description (latest version or pinned @version).
    - params JSON (merged as a patch) and requires list.
    - Change preview, then `system.member.set`.
  - "编辑配置" (edit config):
    - Run device (desired_host).
    - For agent-class members, a picker for which agent class to run (`system.class.list`).
    - values JSON patch, with an insert-reference picker for `$global.<key>` and a missing-key warning.
    - Sends `system.member.config.set`.
  - "合成后的值" (merged values) table showing each key, its value, and which layer it came from.

## 11. Activity center ("活动中心")
- [ ] Tab "活动": across all visible channels, approvals, agent turns, tasks, recovery and automation, with kind, title, channel, state. "返回来源 ›" goes to the channel, view and focus.
- [ ] Tab "操作": channel-creation operations and live agent runs ("X 正在运行"). Shows "当前没有可用的进行中操作快照" when disconnected.
- [ ] Built from feed facts (`src/app/WorkspaceApp.jsx` activityPort).

## 12. Channel details / governance (`src/ui/features/governance/GovernanceFeature.jsx`)
### 12.1 Tab "成员" (members)
- [ ] List with runtime state (在线 / 已绑定 / 未绑定). Per-member actions:
  - "查看" (view; opens actor detail).
  - "重启" (restart) after confirmation: `system.member.restart`.
  - "移除" (remove) after confirmation: `system.member.delete`. Disabled for yourself and the owner.
  - "绑定" (bind) is always disabled.
- [ ] "添加参与者" (add participant), with candidates:
  - Users from `/obs/space/principals`, excluding guest and existing members: `system.member.admit`.
  - Actor descriptions @version: `system.member.create {body: {actor}}`.
  - Direct Class: `system.member.create {body: {class}}`.
  - Optional display name.
  - Shows "成员已就绪" (member ready) once the roster contains the new member.

### 12.2 Tab "设置" (settings)
- [ ] Channel facts: id, parent, owner, serving state. "刷新目录事实" (refresh directory facts).
- [ ] "读取" (read) sends `system.channel.get`: description revision, description, serving flag. Read-only (kernel) and unreadable descriptions show the raw text.
- [ ] Edit description and "对外服务" (serving) checkbox, then save: `system.channel.set`.
- [ ] Devices: local-device is the default. "挂到本频道" (attach) / "卸载" (detach) for each space device: `system.device.attach` / `detach`.
- [ ] Subchannel list.

### 12.3 Tab "危险操作" (danger zone)
- [ ] Retire the channel by typing its name to confirm: `system.channel.delete`.

### 12.4 New channel modal
- [ ] Name validated as 1–63 chars of `[a-z0-9-]`.
- [ ] Starting point: 空白 (blank) with optional description; 复制一个频道 (copy a channel, `copy_from`, member channels only, not kernel channels); 从本频道挑成员 (pick member entries from this channel, read via `system.channel.description.get`).
- [ ] People to bring along (checkboxes; you are always included).
- [ ] Sends `system.channel.create {name, parent, humans, copy_from? | description{description, members}}`.
- [ ] Progress steps: 账本确认 (ledger confirmed), 频道可观察 (observable via OBS), 成员关系 (membership), 服务就绪 (serving). Then "进入新频道" (enter channel) or "重新创建" (retry).

## 13. Space admin ("空间管理")
- [ ] **Tab "Actor 描述"**:
  - Grouped by id with all versions: class, status, configurable flag, owner, params.
  - "退役" (retire) after confirmation: `system.actor.description.retire {id, version}`.
  - Create a new description, or a new version of an existing one: name, class, description, params JSON, "可被配置" (configurable). Sends `system.actor.description.create`.
- [ ] **Tab "设备"** (devices):
  - Create a device by name: `system.device.create`.
  - List devices with online status (`/obs/space/daemons`).
  - "退役" (retire): `system.device.delete`. Not allowed for local-device.
- [ ] **Tab "全局 key"** (`src/ui/features/governance/GlobalKeysPanel.jsx`, `src/model/global-keys.js`):
  - List names only, via `resource list` with prefix `global/` through the current channel's resource frame.
  - Add (name `^[a-z0-9_-]{1,64}$` plus value): stat, then create or write.
  - "修改值" (overwrite value), "删除" (delete) after confirmation.
  - Requires member access to the current channel.

## 14. Agent-initiated UI forms (`ui.form`)
- [ ] Watches for open `ui.form` requests addressed to this client's session id and queues them as a modal (`src/app/hooks/useUiWords.js`, `src/ui/UiFormModal.jsx`, `src/ui/primitives/JsonSchemaForm.jsx`, `src/model/ui-form.js`).
- [ ] Shows "X 请你在这块屏上填写 · channel" (X asks you to fill this in).
- [ ] Fields are generated from the JSON Schema: text, number/integer, enum select, boolean.
- [ ] Secret fields:
  - Rendered as password inputs.
  - The client writes the value to `global/<name>` via `resource`. The reply carries only a mask.
  - Shows the key's existing value and an overwrite warning.
- [ ] "提交" (submit) and "取消" (cancel) both answer via `resolve {result | error}`.
- [ ] If the request was already closed, shows a notice saying which keys were already written.

## 15. Notifications and unread logic (`src/model/notification-policy.js`, `src/model/channel-feed-runtime.js`)
- [ ] **What counts as unread**: request or final-answer rows that are in your conversation (the "与我相关" set), sent by someone else, and above your read position. Counted once per conversation root.
- [ ] **Never unread**: progress, events, system rows, timer rows, and ui/hidden controls. A `provider.run` event counts as an answer.
- [ ] **States**: pending (…) while history is incomplete, unknown (?) when the context is unresolved.
- [ ] **Agent activity**: built from live processing frames for each request. Drives the rail timers and the green dots on filter chips.
- [ ] **No OS-level notifications**: no Notification API, title badge, sound, or push.

## 16. Misc and diagnostics
- [ ] **"导出性能记录"** (export perf record): writes `atoll-perf-<ts>.json` into the channel's files via upload (`src/model/perf-trace.js`).
- [ ] **Diagnostics ring buffer**: last 500 events; warnings are also written to sessionStorage. Exposed as `window.__ATOLL_DIAGNOSTICS__` with snapshot / exportText / clear (`src/model/diagnostics.js`).
- [ ] **Mobile performance profile**:
  - Lower in-memory row caps per channel (`trimReplica`).
  - 5 s reconnect cap and immediate re-attach on foreground.
  - Tool output capped as text.
  - Starred / Running drawers close after a jump.
- [ ] **Warm boot**: shows cached memberships and the last channel before the attach receipt arrives.
- [ ] **Other storage files**: storage-seat discovery for `oss://` is in `src/model/storage-seat.js` (§0 Files); the `oss://` address format is in `src/model/storage-address.js`.