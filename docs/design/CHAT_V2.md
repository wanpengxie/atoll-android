# Conversation v2 — implementation spec

This spec replaces the earlier conversation design (turn cards, three header
layers, warm grey + lagoon palette). It covers the channel conversation and
every surface opened from it. The boards are the source of truth for layout and
copy; this file says what to build and where it lands in code. Visual rules
are in `STYLE.md`.

Boards: `Main`, `ChatStates` (every complex case on one long board),
`Filters` (agent chips), `ChannelDetails` (···), `Waiting`, `MessageActions`,
`Mention`, `Commands`, `AgentPicker`, `HumanAsk`, `Process`, `CollabSheet`,
`AvatarColors`. Open `preview/index.html` to click through them.

Boards outside the conversation (channel list, tasks, files, terminal,
members, login, settings…) still use the old palette and have not been
redrawn yet. Apply the new tokens to them only when their boards are updated.

## 1. Tokens (`ui/theme/AtollTheme.kt`)

WeChat values. Change `AtollColors` to these and remove the colour roles
that no longer exist:

| Role | Value | Replaces |
|---|---|---|
| Conversation ground, header | `#EDEDED` | `Ground` `#F6F6F2` |
| Bars (composer, dock), sheet ground | `#F7F7F7` | `Raised` / `Subtle` |
| Blocks, cards, rows | `#FFFFFF` | `Surface` |
| Titles, names | `#191919` | `Ink` |
| Message body | `#333333` | `Body` |
| Secondary, meta, running status | `#888888` | `Muted`, **and all of `Running*`** |
| Placeholder, chevrons | `#B2B2B2` | `Faint` |
| Hairline on grey | `#DCDCDC`; inside white `#E5E5E5` | `Line`, `LineSoft` |
| My bubble | `#95EC69` | (new) |
| Link / text primary action | `#576B95` | `Lagoon`, `Agent` |
| Unread dot, failure, destructive | `#FA5151` | `Danger` |
| Filled primary button, switch on, running dot | `#07C160` | (new) |
| Secondary button | `#F2F2F2` bg, `#191919` text | — |
| Scrim | `rgba(0,0,0,.5)` | `Scrim` |

Rules that matter more than the values:

- Running and queued are **not coloured**. Running = small grey spinner + grey
  text. Remove `Tone.Running` / `Tone.Waiting` colouring from chat surfaces
  (`StatusPill`, `StatusChip`, `PulseDot`, `CallStatus`, the waiting dock).
- Never small text in an accent colour. Colour appears only on: my bubble,
  red dots / failure lines, `#576B95` text actions, one `#07C160` button per
  screen, and the 6dp green "running" dot next to agent names.
- No tinted surfaces, no borders around white blocks on the grey ground.
- Body 16sp / 25sp. Names 13sp semibold. Meta 13sp. Header title 17sp medium.

### Avatar colour (`AtollAvatarTints`)

Fixed per actor id, no registry. FNV-1a 32-bit over the UTF-8 bytes of the
actor id (display name only when there is no id), unsigned mod 8:

```kotlin
private val AvatarPalette = listOf(
    Color(0xFF5477AD), Color(0xFF3F8C80), Color(0xFF6E8243), Color(0xFFA87548),
    Color(0xFFB0605F), Color(0xFF7F64A8), Color(0xFF44835F), Color(0xFF96628C),
)
fun avatarColor(actorId: String): Color {
    var h = 0x811c9dc5.toInt()
    for (b in actorId.encodeToByteArray()) {
        h = h xor (b.toInt() and 0xff)
        h *= 0x01000193
    }
    return AvatarPalette[Integer.remainderUnsigned(h, AvatarPalette.size)]
}
```

White initial on the colour. Agents: rounded square (radius ≈ size/6).
People: circle. My own messages show no avatar. `String.hashCode()` must not
be used (the web client has to produce the same colour).

## 2. Channel header (`ui/screens/ChannelScreen.kt`)

One 48dp line, `#EDEDED`, hairline below. **Delete `ChannelTabs` and
`FilterBar`.**

```
‹   atoll-web · ●builder ⌄                    ···•
```

- Title = channel name (17sp medium). After it, in grey: `·`, a 6dp green dot
  when that agent is running, and the **current agent** = the composer's send
  target (the same value the agent picker edits). Chevron.
- Tap title → **agent chips** dropdown from under the header (`Filters`
  board): every agent of the channel as a pill (32dp, `#F2F2F2`, 14sp name),
  green dot before the name when running, the current target outlined in ink
  and semibold, then `+ 添加` → members. Hint line: “绿点 = 运行中 · 点选切换对话
  · 长按只看 TA 的消息”.
  - Tap a chip → set the composer target (same effect as choosing in the
    agent picker), close the dropdown.
  - Long-press a chip → toggle that actor in the existing actor filter
    (`onActor` / `actorFilters`). While a filter is active the header shows
    the filtered names instead of the current agent, e.g. `atoll-web · 只看 tester`.
- `···` → `ChannelDetails`. Red dot when there are pending tasks.

Everything that used to be in the tabs / filter bar moves into
`ChannelDetails` (WeChat 聊天信息 page): member grid, 任务 (pending count +
red dot), 文件, 终端, 星标消息, 定时动作, 消息免打扰, **消息显示** (与我相关 /
全部, 频道活动 on/off, 历史窗口, 通知 — one row opening a sub-page), 设备与高级,
清空本机缓存, 退出频道.

## 3. Timeline rows (`ui/components/TimelineComponents.kt`)

Stop drawing request + answer as one card. `MessagePart.Request/Answer`
become ordinary rows; keep the part only to decide adjacency.

| Row | Layout |
|---|---|
| Others (agent / person) | Name line only when the speaker changes or > 5 min passed: 20dp avatar, name 13sp semibold `#191919`, time 13sp `#888`. Then a white block, radius 4/12/12/12, padding 10×14, full width. |
| Mine | Right-aligned `#95EC69` bubble, radius 12/4/12/12, padding 10×14, 40dp left inset. No name line. |
| Time / system notice | Centred 12sp `#888`, no bubble. |
| Approval / question (`agent.ask` to me) | Narrow card (248dp) left-aligned, title line with red dot + “· 剩 N 分钟”, body, bottom button row (last button `#576B95` medium). Opens `HumanAsk`. |

Inside an agent block, below the text, at most one status line (13sp):

- Running: grey spinner + `处理中 02:14 · <current step> ›` → `Process`.
- Work: `调用了 N 个工具 ›` (replaces the work strip chips) → `Process`.
- Failed: red `失败：<reason>` + `重试` (link) + `过程 ›` (grey).
- Interrupted: grey `已中断 · 你停止了这一轮`. Lost: grey `没有收到结果 · 连接断开时丢失` + `重新发送`.
- Completed: nothing.

Relations (`ChatStates` board, numbered ①–⑫):

- **Reply not adjacent to its request** (④): grey quote box (`#E2E2E2`,
  13sp, 2 lines) under the bubble / inside the block; tap scrolls to the
  original. Adjacent answers get no quote (⑤).
- **Agent ↔ agent collaboration** (⑥): a “聊天记录” card inside the block
  (`#F7F7F7`, title “A 与 B 的协作”, last two lines, `协作记录 · N 条 ›`) →
  `CollabSheet`. Replaces `RelatedCalls` conversation lists in the timeline.
- **Sub-tasks** (⑦): list inside the block under a hairline: grey ✓ / spinner
  / ○ / red ✕, title, 16dp assignee avatar, grey trailing text.
- **Filtered out by 与我相关** (③): one centred link line “N 条与你无关的消息 · 显示”.
- **Superseded mine** (⑩): bubble at 50% opacity + grey caption
  (已被下一条替换 / 已合并到上一条一起处理 / 抢占了 X 正在进行的一轮).
- **Local send state** (⑪): left of my bubble — grey spinner while sending,
  red round `!` when failed (tap to resend).
- **Long code** (⑧): `#F7F7F7` mono block capped at 6 lines with fade + `展开全部 · N 行 ›`.

## 4. Waiting dock (`waiting/WaitingDock.kt`)

- Collapsed: a 36dp strip above the composer, `#F7F7F7`: 16dp avatar,
  “**2 条等待** · builder 本轮结束后依次发出 ›”. No amber.
- Expanded (`Waiting` board): bottom sheet; one white group per agent
  (32dp avatar, “builder · 2 条”, grey spinner + “本轮结束后依次发出” or
  “已暂停 · 恢复后发出”, `全部插入` link); rows numbered, text 16sp, labels in
  grey, actions `插入` (link) `编辑` (ink) `取消` (red); `全部取消` red at the end.

## 5. Other surfaces

- **Composer** (`compose/ComposerUi.kt`): WeChat bar `#F7F7F7`: 28dp avatar
  of the current target (→ agent picker), white field radius 4dp, placeholder
  “发给 builder”, ⊕ for attachments / commands / files.
- **Long-press** (`compose/TimelineMessageActions.kt`): WeChat dark popover
  `#4C4C4C` above the message, white icon + 12sp label, 5 per row, arrow to
  the message; message gets a light highlight. Actions per `MessageActions`.
- `Mention`, `Commands`, `AgentPicker`, `HumanAsk`, `Process`, `CollabSheet`:
  follow their boards; all are WeChat lists / sheets in the tokens above.

## 6. Checks before calling it done

- No `#F6F6F2`, `#0F766E`, `#1D5FD1`, amber surfaces or `Running*` colours
  reachable from the conversation screen.
- Header is a single 48dp row on every conversation surface.
- Same actor id → same avatar colour on Android and web.
- 44dp touch targets on chips, dock, status lines and card buttons.
