# Shared pieces for the WeChat-palette chat boards.
PALETTE = ['#5477AD', '#3F8C80', '#6E8243', '#A87548', '#B0605F', '#7F64A8', '#44835F', '#96628C']
INK, BODY, GRAY, HINT, LINE, BG, BAR, GREEN, LINK, RED = '#191919', '#333333', '#888888', '#B2B2B2', '#DCDCDC', '#EDEDED', '#F7F7F7', '#95EC69', '#576B95', '#FA5151'

def fnv(s):
    h = 0x811c9dc5
    for b in s.encode():
        h ^= b; h = (h * 0x01000193) & 0xffffffff
    return h

def color(name): return PALETTE[fnv(name) % len(PALETTE)]

def av(name, size=20, human=False):
    r = f'{size//2}px' if human else f'{max(3, size//6)}px'
    fs = max(10, int(size * .52))
    return (f'<span aria-hidden="true" style="width: {size}px; height: {size}px; flex: none; border-radius: {r}; background: {color(name)}; '
            f'color: #FFFFFF; font-size: {fs}px; font-weight: 600; display: flex; align-items: center; justify-content: center">{name[0].upper()}</span>')

SPIN = lambda c=GRAY: f'<span style="width: 10px; height: 10px; flex: none; border-radius: 5px; border: 1.5px solid {LINE}; border-top-color: {c}; box-sizing: border-box; animation: atollSpin 1.4s linear infinite"></span>'
P = f'<p style="margin: 0; font-size: 16px; line-height: 25px; color: {BODY}">'

def name_line(name, time='', human=False):
    t = f'<span>{time}</span>' if time else ''
    return (f'<span style="display: flex; align-items: center; gap: 8px; font-size: 13px; color: {GRAY}">{av(name, 20, human)}'
            f'<span style="font-weight: 600; color: {INK}">{name}</span>{t}</span>')

def agent(name, inner, time='', show_name=True, human=False, width=None):
    w = f' width: {width}px; box-sizing: border-box;' if width else ''
    head = name_line(name, time, human) + '\n      ' if show_name else ''
    return f'''    <section style="display: flex; flex-direction: column; gap: 6px; align-items: flex-start">
      {head}<div style="padding: 10px 14px; border-radius: 4px 12px 12px 12px; background: #FFFFFF; display: flex; flex-direction: column; gap: 8px;{w} max-width: 100%">
        {inner}
      </div>
    </section>
'''

def mine(text, below='', left='', faded=False):
    op = ' opacity: .5;' if faded else ''
    return f'''    <div style="display: flex; flex-direction: column; align-items: flex-end; gap: 4px; padding-left: 40px">
      <div style="display: flex; align-items: center; gap: 8px">{left}<div style="padding: 10px 14px; border-radius: 12px 4px 12px 12px; background: {GREEN}; font-size: 16px; line-height: 25px; color: {BODY};{op}">{text}</div></div>
      {below}
    </div>
'''

def quote(who, text, href='#'):
    return (f'<a href="{href}" style="max-width: 100%; box-sizing: border-box; padding: 6px 10px; border-radius: 4px; background: #E2E2E2; font-size: 13px; line-height: 19px; color: {GRAY}; '
            f'display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden">{who}：{text}</a>')

def meta(text, href=None, color_=GRAY, lead=''):
    tag = f'a href="{href}"' if href else 'span'
    end = 'a' if href else 'span'
    return f'<{tag} style="display: flex; align-items: center; gap: 6px; font-size: 13px; line-height: 18px; color: {color_}">{lead}{text}</{end}>'

def center(text):
    return f'    <div style="align-self: center; max-width: 300px; text-align: center; font-size: 12px; line-height: 17px; color: {GRAY}">{text}</div>\n'

AGENTS = ['builder', 'reviewer', 'tester', 'planner', 'deployer', 'test-writer']

def stack(current='builder', agents=AGENTS, show=4):
    order = [current] + [a for a in agents if a != current]
    out = ''.join(av(n, 22) for n in order[:show])
    more = len(order) - show
    if more > 0:
        out += f'<span style="font-size: 13px; color: {GRAY}">+{more}</span>'
    return f'<span style="flex: none; display: flex; align-items: center; gap: 4px">{out}</span>'

def header(title='atoll-web', current='builder', expanded=False, href='Filters.dc.html'):
    chev = 'M6 15l6-6 6 6' if expanded else 'M6 9l6 6 6-6'
    return f'''  <header style="position: relative; z-index: 2; height: 48px; flex: none; display: flex; align-items: center; padding: 0 4px; background: {BG}; border-bottom: 1px solid {LINE}">
    <a href="Channels.dc.html" aria-label="返回频道列表" style="width: 44px; height: 44px; display: flex; align-items: center; justify-content: center">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M15 6l-6 6 6 6"></path></svg>
    </a>
    <a href="{href}" aria-label="{title}，{len(AGENTS)} 个 Agent，正在和 {current} 对话，{'收起' if expanded else '展开'} Agent 成员" aria-expanded="{'true' if expanded else 'false'}" style="flex: 1; min-width: 0; display: flex; align-items: center; justify-content: center; gap: 4px; white-space: nowrap">
      <span style="font-size: 17px; font-weight: 500; overflow: hidden; text-overflow: ellipsis">{title}</span><span style="font-size: 17px; color: {GRAY}">·</span><span style="width: 6px; height: 6px; margin-left: 2px; flex: none; border-radius: 3px; background: #07C160"></span><span style="font-size: 17px; color: {GRAY}">{current}</span><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="{GRAY}" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="{chev}"></path></svg>
    </a>
    <a href="ChannelDetails.dc.html" aria-label="聊天信息，3 个待处理任务" style="position: relative; width: 44px; height: 44px; display: flex; align-items: center; justify-content: center">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><circle cx="5" cy="12" r="1.7"></circle><circle cx="12" cy="12" r="1.7"></circle><circle cx="19" cy="12" r="1.7"></circle></svg>
      <span style="position: absolute; top: 11px; right: 10px; width: 7px; height: 7px; border-radius: 4px; background: {RED}"></span>
    </a>
  </header>
'''

def dock(n=2, who='builder'):
    return f'''  <a href="Waiting.dc.html" aria-label="{n} 条等待消息，展开等待区" style="flex: none; height: 36px; display: flex; align-items: center; gap: 8px; padding: 0 12px 0 14px; background: {BAR}; border-top: 1px solid {LINE}; font-size: 13px; color: {GRAY}">
    {av(who, 16)}<span style="flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis"><span style="color: {INK}">{n} 条等待</span> · {who} 本轮结束后依次发出</span>›
  </a>
'''

def composer(to='builder'):
    return f'''  <div style="flex: none; display: flex; align-items: center; gap: 6px; padding: 8px 8px 10px; background: {BAR}; border-top: 1px solid {LINE}">
    <a href="AgentPicker.dc.html" aria-label="发给 {to}，更换" style="width: 40px; height: 40px; flex: none; display: flex; align-items: center; justify-content: center">{av(to, 28)}</a>
    <div role="textbox" aria-label="发送消息" style="flex: 1; height: 40px; box-sizing: border-box; padding: 8px 12px; border-radius: 4px; background: #FFFFFF; font-size: 16px; line-height: 24px; color: {HINT}">发给 {to}</div>
    <button aria-label="更多：附件、命令、频道文件" style="width: 40px; height: 40px; flex: none; display: flex; align-items: center; justify-content: center">
      <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="12" r="9.5"></circle><path d="M12 7.5v9M7.5 12h9"></path></svg>
    </button>
  </div>
'''

def ask_card(title, sub, body, buttons, href='HumanAsk.dc.html', width=248):
    cols = len(buttons)
    btns = ''.join(
        f'<a href="{href}" style="height: 44px; display: flex; align-items: center; justify-content: center; font-size: 15px; {"font-weight: 500; color: " + LINK if i == cols - 1 else "color: " + BODY}; {"border-right: 1px solid " + BG if i < cols - 1 else ""}">{b}</a>'
        for i, b in enumerate(buttons))
    return f'''    <section style="align-self: flex-start; width: {width}px; border-radius: 4px 12px 12px 12px; background: #FFFFFF; overflow: hidden">
      <div style="padding: 10px 14px; display: flex; flex-direction: column; gap: 4px">
        <span style="display: flex; align-items: center; gap: 6px; font-size: 13px; color: {GRAY}"><span style="width: 6px; height: 6px; border-radius: 3px; background: {RED}"></span><span style="font-weight: 600; color: {INK}">{title}</span>{sub}</span>
        <span style="font-size: 16px; line-height: 25px; color: {BODY}">{body}</span>
      </div>
      <div style="display: grid; grid-template-columns: repeat({cols}, minmax(0, 1fr)); border-top: 1px solid {BG}">{btns}</div>
    </section>
'''

HEAD = '''<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>{title}</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Geist:wght@400;500;600;700&amp;family=Geist+Mono:wght@400;500&amp;family=Noto+Sans+SC:wght@400;500;600;700&amp;display=swap">
<style>
body{{margin:0}}
button{{font:inherit;color:inherit;border:0;background:none;padding:0;cursor:pointer}}
a{{color:inherit;text-decoration:none}}
@keyframes atollSpin{{to{{transform:rotate(360deg)}}}}
</style>
</helmet>
'''
TAIL = '''</x-dc>
<script type="text/x-dc" data-dc-script data-props='{{"$preview":{{"width":{w},"height":{h}}}}}'>
class Component extends DCLogic {{
  renderVals() {{
    return {{}};
  }}
}}
</script>
</body>
</html>
'''
def page(title, body, w, h): return HEAD.format(title=title) + body + TAIL.format(w=w, h=h)

def root(w, h, inner, bg=BG):
    return (f'<div style="width: {w}px; height: {h}px; box-sizing: border-box; display: flex; flex-direction: column; overflow: hidden; position: relative; '
            f"background: {bg}; color: {INK}; font-family: 'Geist', 'Noto Sans SC', system-ui, sans-serif\">\n{inner}</div>\n")
