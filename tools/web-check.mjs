// Drives the web app (PWA) in headless Chrome over CDP for quick checks. Never prints secrets.
//   node tools/web-check.mjs <url> <desktop|mobile> <port>      # starts Chrome + a command server on 127.0.0.1:<port>
//   curl -s localhost:<port> -d '{"op":"shot","path":"x.png"}'   # one command per request (JSON), result as JSON
// Ops: signin (injects the PC app's stored access token into localStorage; refresh token NOT copied, so the PC app's
// sign-in is never rotated), reload, wait{ms}, shot{path}, eval{js}, click{x,y}, tap{x,y}, mouseDrag{pts,hold},
// touchDrag{pts,hold}, swipe{x,y,dx,dy}, key{key,code,keyCode}, quit.
import { spawn } from 'node:child_process'
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { createServer } from 'node:http'
import { homedir, tmpdir } from 'node:os'
import { join, dirname } from 'node:path'

const [url, mode = 'desktop', port = '9333', tag = ''] = process.argv.slice(2)
const mobile = mode === 'mobile'
const W = mobile ? 390 : 1280, H = mobile ? 844 : 800
const chromePath = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const dbg = Number(port) + 1
const profile = join(process.env.TMPDIR || tmpdir(), `web-check-${mode}${tag}`) // tag: a fresh browser profile
const chrome = spawn(chromePath, [`--remote-debugging-port=${dbg}`, `--user-data-dir=${profile}`, '--headless=new',
  '--no-first-run', '--no-default-browser-check', '--enable-unsafe-swiftshader', '--use-angle=swiftshader',
  `--window-size=${W},${H}`, 'about:blank'], { stdio: 'ignore' })
process.on('exit', () => chrome.kill())

const sleep = ms => new Promise(r => setTimeout(r, ms))
let target
for (let i = 0; i < 50 && !target; i++) {
  await sleep(300)
  try { target = (await (await fetch(`http://127.0.0.1:${dbg}/json`)).json()).find(t => t.type === 'page') } catch {}
}
const ws = new WebSocket(target.webSocketDebuggerUrl)
await new Promise(r => ws.onopen = r)
let n = 0; const pending = new Map(); const logs = []
ws.onmessage = m => {
  const f = JSON.parse(m.data)
  if (f.id && pending.has(f.id)) { pending.get(f.id)(f); pending.delete(f.id) }
  else if (f.method === 'Runtime.consoleAPICalled') logs.push(f.params.args.map(a => a.value ?? a.description).join(' ').slice(0, 300))
  else if (f.method === 'Runtime.exceptionThrown') logs.push('EXC ' + (f.params.exceptionDetails.exception?.description || f.params.exceptionDetails.text).slice(0, 300))
}
const cdp = (method, params = {}) => new Promise((ok, no) => { const id = ++n; pending.set(id, f => f.error ? no(new Error(f.error.message)) : ok(f.result)); ws.send(JSON.stringify({ id, method, params })) })
await cdp('Runtime.enable'); await cdp('Page.enable')
await cdp('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: mobile ? 3 : 1, mobile })
if (mobile) {
  await cdp('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 })
  await cdp('Emulation.setUserAgentOverride', { userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3 Mobile/15E148 Safari/604.1' })
}
await cdp('Page.navigate', { url })

function storedTokens() {
  const props = readFileSync(join(homedir(), '.buddyverse', 'settings.properties'), 'utf8')
  const line = props.split(/\r?\n/).find(l => l.startsWith('tokens='))
  const t = JSON.parse(line.slice(7).replace(/\\(.)/g, '$1'))
  return JSON.stringify({ access_token: t.access_token, refresh_token: 'not-copied', expires_at: t.expires_at })
}
const mouse = (type, x, y, buttons = 1) => cdp('Input.dispatchMouseEvent', { type, x, y, button: 'left', buttons, clickCount: 1 })
const touch = (type, x, y) => cdp('Input.dispatchTouchEvent', { type, touchPoints: type === 'touchEnd' ? [] : [{ x, y, id: 1 }] })
async function drag(kind, pts, hold, shotDuring) {
  const [x0, y0] = pts[0]
  if (kind === 'mouse') { await mouse('mouseMoved', x0, y0, 0); await mouse('mousePressed', x0, y0) } else await touch('touchStart', x0, y0)
  for (let i = 1; i < pts.length; i++) {
    const [ax, ay] = pts[i - 1], [bx, by] = pts[i]
    for (let s = 1; s <= 8; s++) {
      const x = ax + (bx - ax) * s / 8, y = ay + (by - ay) * s / 8
      if (kind === 'mouse') await mouse('mouseMoved', x, y); else await touch('touchMove', x, y)
      await sleep(16)
    }
  }
  if (hold) await sleep(hold)
  if (shotDuring) await shot(shotDuring)
  const [xe, ye] = pts[pts.length - 1]
  if (kind === 'mouse') await cdp('Input.dispatchMouseEvent', { type: 'mouseReleased', x: xe, y: ye, button: 'left', buttons: 0, clickCount: 1 }); else await touch('touchEnd')
}
async function shot(path) {
  mkdirSync(dirname(path), { recursive: true })
  const r = await cdp('Page.captureScreenshot', { format: 'png' })
  writeFileSync(path, Buffer.from(r.data, 'base64')); return path
}

createServer(async (req, res) => {
  let body = ''; for await (const c of req) body += c
  let out
  try {
    const c = JSON.parse(body || '{}')
    switch (c.op) {
      case 'signin': await cdp('Runtime.evaluate', { expression: `localStorage.setItem('buddyverse.tokens', ${JSON.stringify(storedTokens())}); 'ok'` }); out = 'tokens set'; break
      case 'reload': await cdp('Page.reload', { ignoreCache: !!c.hard }); out = 'reloaded'; break
      case 'goto': await cdp('Page.navigate', { url: c.url }); out = 'navigated'; break
      case 'wait': await sleep(c.ms || 1000); out = 'waited'; break
      case 'shot': out = await shot(c.path); break
      case 'eval': { const r = await cdp('Runtime.evaluate', { expression: c.js, awaitPromise: true, returnByValue: true }); out = r.exceptionDetails ? 'EXC ' + r.exceptionDetails.text : r.result.value; break }
      case 'click': await mouse('mouseMoved', c.x, c.y, 0); await mouse('mousePressed', c.x, c.y); await sleep(60); await cdp('Input.dispatchMouseEvent', { type: 'mouseReleased', x: c.x, y: c.y, button: 'left', buttons: 0, clickCount: 1 }); out = 'clicked'; break
      case 'tap': await touch('touchStart', c.x, c.y); await sleep(60); await touch('touchEnd'); out = 'tapped'; break
      case 'mouseDrag': await drag('mouse', c.pts, c.hold, c.during); out = 'dragged'; break
      case 'touchDrag': await drag('touch', c.pts, c.hold, c.during); out = 'dragged'; break
      case 'swipe': { const k = mobile ? 'touch' : 'mouse'; await drag(k, [[c.x, c.y], [c.x + c.dx, c.y + c.dy]], 0); out = 'swiped'; break }
      case 'wheel': await cdp('Input.dispatchMouseEvent', { type: 'mouseWheel', x: c.x, y: c.y, deltaX: 0, deltaY: c.dy }); out = 'wheeled'; break
      case 'key': for (const type of ['rawKeyDown', 'keyUp']) await cdp('Input.dispatchKeyEvent', { type, key: c.key, code: c.code || c.key, windowsVirtualKeyCode: c.keyCode || 0, modifiers: c.modifiers || 0, commands: type === 'rawKeyDown' ? c.commands || [] : [] }); out = 'key'; break
      case 'type': await cdp('Input.insertText', { text: c.text }); out = 'typed'; break
      case 'logs': out = logs.splice(0); break
      case 'quit': res.end('"bye"'); process.exit(0)
      default: out = 'unknown op'
    }
  } catch (e) { out = 'ERROR ' + e.message }
  res.end(JSON.stringify(out))
}).listen(Number(port), '127.0.0.1')
console.log(`ready ${mode} ${W}x${H} on ${port}`)
