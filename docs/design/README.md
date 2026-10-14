# Android UI redesign — design source

The design canvas for the redesign, copied from the claude.ai Design artifact
(https://claude.ai/artifact/QepnvZYAAnYqGewm7wKF5Q, private to its owner).
Each `artboards/*.dc.html` file is one screen or board. They are plain HTML
with inline styles: read them for exact layout, sizes, colours and copy.
They load a canvas runtime (`./support.js`) that is not in this repo, so
opening them in a browser shows unstyled `{{…}}` holes in a few places;
the markup itself is complete. `canvas.json` lists every board with its
title and position.

Phone boards are 390×844 px (1 px = 1 dp). The UI text is Chinese.

**To look at the designs**, open `preview/index.html` in any browser: the
same boards as standalone static pages, no login or runtime needed, with
the links between screens working. Regenerate them from `artboards/`
whenever the artboards change.

## Where to start

**The conversation was redesigned again (v2, WeChat palette and layout).
Build it from `CHAT_V2.md`; visual rules are in `STYLE.md`.** Where an older
board or the sections below disagree with `CHAT_V2.md`, `CHAT_V2.md` wins.

| Board | What it defines |
|---|---|
| `Main.dc.html` | Conversation page (v2) |
| `ChatStates.dc.html` | Every complex timeline case on one long board, numbered ①–⑫ |
| `Filters.dc.html` | Agent chips opened from the header title |
| `ChannelDetails.dc.html` | ··· page: tasks, files, terminal, scope / 频道活动 / history, settings |
| `Waiting.dc.html` | Waiting dock expanded |
| `AvatarColors.dc.html` | Fixed avatar colour algorithm |
| `ConversationModel.dc.html` | Inventory of every timeline row type and the filter rules (data model still valid; its visuals are superseded) |
| `Navigation.dc.html`, `Foundations.dc.html`, `Components.dc.html` | Old palette; still valid for screens outside the conversation |

All other boards are one screen each (titles in `canvas.json`). Boards
redrawn in v2: Main, ChatStates, Filters, ChannelDetails, Waiting,
MessageActions, Mention, Commands, AgentPicker, HumanAsk, Process,
CollabSheet, AvatarColors. The rest still show the previous palette.

`tools/kit.py` holds the shared markup the v2 boards are generated from, and
`tools/previews.py` regenerates `preview/` (`python3 tools/previews.py`).

## Code mapping

| Design | Code |
|---|---|
| Colour and type tokens | `ui/theme/AtollTheme.kt` (`AtollColors`, `AtollShapes`, `AtollAvatarTints`) |
| Component kit | `ui/components/AtollKit.kt` |
| Turn card, work strip | `ui/components/TimelineComponents.kt` (`MessageFrame`, `MessagePart`), `ContentTrails.kt`, `RelatedCalls.kt` |
| Channel header, tabs, filter bar | `ui/screens/ChannelScreen.kt` |
| Home, bottom navigation | `ui/screens/ChannelListScreen.kt` (`HomeBar`), `AppGraph.home()` |
| Message actions | `compose/TimelineMessageActions.kt` |
| Composer, agent sheet | `compose/ComposerUi.kt` |
| 我 page | `app/WorkspaceSettings.kt` |

## Not yet implemented in code (pre-v2 list)

For the conversation, `CHAT_V2.md` supersedes items 1–6 and 10 below. The rest still apply:

1. Work strip count chips (过程 / 协作 / 子 Agent / 调用) expanding in place (`TurnExpanded`).
2. Process panel tabs 过程 · 协作 · 子任务 · 调用 · 账本 (`Process`).
3. Collaboration detail with path navigation for deep threads (`CollabSheet`).
4. Consecutive narration grouped as “频道活动 N 条”, and the 频道活动 switch in 全部 scope (`Main`, `Filters`).
5. Merged / preempted turns collapsed to one line, “重新发送” on failed or lost turns, “已编辑” tag (`TurnStates`).
6. History window banner “正在看较早的记录 · 回到最新” when `newerUnloaded` (`Filters`).
7. Timed action presets instead of a millisecond field (`Schedule`).
8. Approve / reject directly on activity inbox cards (`Activity`).
9. Reconnect banner under the channel header (`Connection`).
10. Long-press actions as a bottom sheet including 查看过程 (`MessageActions`).
11. Inner layouts of new channel, member config, starred + recent, upgrade (`NewChannel`, `MemberConfig`, `Starred`, `Upgrade`); only their containers are restyled.

Not planned until the node offers an API: quick-answer chips on asks,
per-channel notification settings, activity badge count.

The app uses the system sans (Noto Sans CJK) instead of Geist, which would
need bundled font files.
