"""Regenerate preview/*.html (static pages, no canvas runtime) from artboards/*.dc.html.
Run from docs/design: python3 tools/previews.py"""
import json, re, pathlib
root = pathlib.Path(__file__).resolve().parent.parent
canvas = json.loads((root / 'canvas.json').read_text())
out = root / 'preview'
for f in (root / 'artboards').glob('*.dc.html'):
    s = f.read_text()
    s = s.replace('<script src="./support.js"></script>', '')
    s = re.sub(r'<script type="text/x-dc".*?</script>', '', s, flags=re.S)
    s = s.replace('<body>', '<body style="margin:0;background:#D8D8D8;display:flex;justify-content:center;padding:24px 0">', 1)
    s = s.replace('<x-dc>', '<div class="dc-root">').replace('</x-dc>', '</div>').replace('<helmet>', '').replace('</helmet>', '')
    s = re.sub(r'href="([A-Za-z]+)\.dc\.html"', r'href="\1.html"', s)
    (out / f.name.replace('.dc.html', '.html')).write_text(s)
items = ''.join(f'<li><a href="{k[:-8]}.html">{b["title"]}</a> <span>{k[:-8]}.html · {b["w"]}×{b["h"]}</span></li>'
                for k in canvas['order'] for b in [canvas['boards'][k]])
(out / 'index.html').write_text(f'''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Atoll 设计稿</title>
<style>body{{margin:0;padding:24px 16px;font-family:system-ui,'Noto Sans SC',sans-serif;background:#EDEDED;color:#191919}}main{{max-width:720px;margin:0 auto}}
h1{{font-size:24px;font-weight:600}}ul{{list-style:none;padding:0;background:#FFFFFF;border-radius:12px}}li{{padding:14px 16px;border-top:1px solid #E5E5E5;display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap}}li:first-child{{border-top:0}}
a{{color:#576B95;font-weight:500;text-decoration:none}}span{{color:#888888;font-size:13px}}</style></head>
<body><main><h1>Atoll Android 重设计 · 设计稿</h1><p style="color:#888888">{len(canvas['order'])} 张画板。手机画板为 390×844（1px = 1dp）。画板之间的链接可以点。对话部分按 STYLE.md / CHAT_V2.md。</p><ul>{items}</ul></main></body></html>
''')
print('previews:', len(canvas['order']))
