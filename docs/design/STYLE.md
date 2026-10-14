# Atoll chat style v2 (WeChat palette) — the only rules for every conversation board

Use the helpers in `kit.py` (same folder): `page()`, `root()`, `header()`, `composer()`, `dock()`, `av()`, `agent()`, `mine()`, `quote()`, `meta()`, `center()`, `ask_card()`, `SPIN()`, and the colour constants. Import with
`import sys; sys.path.insert(0, 'tools'); from kit import *`.
Board size is 390x844 unless told otherwise. Write each board to
`/tmp/claude-0/-home-user-atoll-android/e478b11b-d59d-587c-9576-2194bac6fa07/scratchpad/canvas/project/<Name>.dc.html` with `page(title, root(390, 844, inner), 390, 844)`.

## Colour (WeChat values, nothing else)
- Page / header background `#EDEDED` (BG); bars, sheets' grey ground `#F7F7F7` (BAR); cards, rows, agent blocks `#FFFFFF`.
- Text: titles/names `#191919` (INK); body `#333333` (BODY); secondary/meta `#888888` (GRAY); placeholder `#B2B2B2` (HINT); hairlines `#DCDCDC` (LINE) or `#E5E5E5` inside white cards.
- My bubble `#95EC69` (GREEN). Link / primary text action `#576B95` (LINK). Red `#FA5151` (RED) only for unread dots, failure and destructive text.
- One filled primary button per screen at most: `#07C160` bg, white text, radius 8, height 44. Secondary button: `#F2F2F2` bg, `#191919` text.
- Scrim for sheets: `rgba(0,0,0,.5)`.
- NEVER: a coloured status (running/queued) — running is a grey `SPIN()` + grey text. Never small text in an accent colour. No tinted backgrounds (no blue/amber/green surfaces). No borders around cards on the grey ground (white on #EDEDED is the separation).

## Type
- Body 16px / 25px BODY. Header title 17px weight 500. Names 13px weight 600 INK. Meta 13px GRAY. Section labels on grey ground 13px GRAY, padding 16px.
- List row title 16px INK, subtitle 13px GRAY. Only weights 400/500/600.
- Font family inherited from root(). Mono (code/paths): `'Geist Mono', ui-monospace, monospace` 13px.

## Shapes & layout
- Agent block radius `4px 12px 12px 12px`; my bubble `12px 4px 12px 12px`; cards 12px; buttons 8px; avatars: agent rounded square, person circle (`av(name, size, human=True)`).
- Avatar colour is fixed per actor id: FNV-1a 32-bit over UTF-8 bytes, index = hash mod 8 into PALETTE (see kit.color). Always use `av()`; never pick avatar colours by hand.
- Screen gutters 12px for chat, 16px for lists. Touch targets 44px.
- WeChat list pattern: white rows (min-height 52-56px, padding 0 16px) grouped in a white block on BG, hairline `#E5E5E5` inset 16px between rows, trailing `›` in HINT.
- Bottom sheet: white, radius 12px 12px 0 0, grabber 36x4 `#DCDCDC`, title 17/500 centered or left, close/收起 as GRAY text button. Action sheet (WeChat): white rows centered 17px, separated by 1px, a 8px BAR gap, then 取消.
- Single header line only (one 48px header). No tab bars or filter chips under the header in chat screens.
- Icons: inline SVG, stroke currentColor, stroke-width 1.6–1.9, 22px.

## Semantics to keep from the old boards
Read the old board first (same file name) for what it shows and which actions exist; keep every function and link (href to other boards), drop every old colour and decoration. Old palette (#F6F6F2, #0F766E, #1D5FD1, #4F46E5, amber surfaces, etc.) must not appear anywhere in your output — grep your file for those before finishing.
