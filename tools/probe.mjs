// Dev probe against a live Hermes gateway (read-only unless PROBE_SCRIPT says otherwise).
// Never prints secrets.  node tools/probe.mjs <base> <login-file> <script.json>
// script.json: [{"profile":"<socket profile or empty>","method":"…","params":{…},"keys":true}]
import { readFileSync } from 'node:fs'
import { createHash, randomBytes } from 'node:crypto'

const [base, loginFile, scriptFile] = process.argv.slice(2)
const creds = Object.fromEntries(readFileSync(loginFile, 'utf8').replace(/^﻿/, '').split(/\r?\n/)
  .filter(Boolean).map(l => [l.slice(0, l.indexOf(':')).trim(), l.slice(l.indexOf(':') + 1).trim()]))
const script = JSON.parse(readFileSync(scriptFile, 'utf8'))
const jar = new Map()
async function call(path, { method = 'GET', body, bearer } = {}) {
  const h = {}
  if (body !== undefined) h['Content-Type'] = 'application/json'
  if (bearer) h.Authorization = `Bearer ${bearer}`
  else if (jar.size) h.Cookie = [...jar].map(([k, v]) => `${k}=${v}`).join('; ')
  const res = await fetch(new URL(path, base), { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body), redirect: 'manual' })
  for (const c of res.headers.getSetCookie?.() || []) { const p = c.split(';')[0]; jar.set(p.slice(0, p.indexOf('=')), p.slice(p.indexOf('=') + 1)) }
  const text = await res.text(); let json; try { json = JSON.parse(text) } catch {}
  return { status: res.status, json, text }
}
const verifier = randomBytes(32).toString('base64url')
const challenge = createHash('sha256').update(verifier).digest('base64url')
const q = new URLSearchParams({ provider: 'basic', code_challenge: challenge, code_challenge_method: 'S256', redirect_uri: 'http://127.0.0.1:53682/callback', state: 's1' })
await call(`/auth/native/authorize?${q}`)
let r = await call('/auth/password-login', { method: 'POST', body: { provider: 'basic', username: creds.username, password: creds.password, next: '' } })
const code = new URL(r.json.next).searchParams.get('code'); jar.clear()
r = await call('/auth/native/token', { method: 'POST', body: { code, code_verifier: verifier } })
const bearer = r.json.access_token
console.log('signed in:', !!bearer)

const sockets = new Map()
async function socket(profile) {
  if (sockets.has(profile)) return sockets.get(profile)
  const t = await call(`/api/webapp/socket-ticket?socket=gateway${profile ? `&profile=${profile}` : ''}`, { method: 'POST', bearer })
  const url = new URL(`/api/ws?native_dial=browser${profile ? `&profile=${profile}` : ''}`, base); url.protocol = url.protocol.replace('http', 'ws')
  const ws = new WebSocket(url, ['hermes-gateway-v1', `hermes-gateway-ticket.${t.json.ticket}`])
  const pending = new Map(); let n = 0; const events = []
  ws.onmessage = m => { const f = JSON.parse(m.data); if (f.method === 'event') { events.push(f.params); if (process.env.SHOW_EVENTS) console.log('  event', f.params.type, JSON.stringify(f.params.payload).slice(0, 300)) } else if (f.id && pending.has(f.id)) { pending.get(f.id)(f); pending.delete(f.id) } else if (f.method) console.log('  server request', f.method, JSON.stringify(f.params).slice(0, 300)) }
  await new Promise((ok, no) => { ws.onopen = ok; ws.onerror = no })
  const s = { ws, events, rpc: (method, params) => new Promise(ok => { const id = `p${++n}`; pending.set(id, ok); ws.send(JSON.stringify({ jsonrpc: '2.0', id, method, params })) }) }
  await new Promise(ok => setTimeout(ok, 300))
  await s.rpc('client.capabilities', { server_requests: true })
  sockets.set(profile, s); return s
}
const shape = (v, d = 0) => v === null ? 'null' : Array.isArray(v) ? `[${v.length}]${v.length && d < 2 ? shape(v[0], d + 1) : ''}` : typeof v === 'object' ? `{${Object.keys(v).slice(0, 40).map(k => d < 1 ? `${k}:${shape(v[k], d + 1)}` : k).join(',')}}` : typeof v
for (const step of script) {
  if (step.rest) { const x = await call(step.rest, { method: step.httpMethod || 'GET', body: step.body, bearer }); console.log(`REST ${step.httpMethod || 'GET'} ${step.rest} -> ${x.status}`, step.full ? JSON.stringify(x.json ?? x.text).slice(0, step.full) : shape(x.json)); continue }
  if (step.wait) { await new Promise(ok => setTimeout(ok, step.wait)); continue }
  const s = await socket(step.profile || '')
  const f = await s.rpc(step.method, step.params || {})
  console.log(`${step.profile || '-'} ${step.method}`, f.error ? `ERROR ${JSON.stringify(f.error).slice(0, 400)}` : (step.full ? JSON.stringify(f.result).slice(0, step.full) : shape(f.result)))
}
for (const s of sockets.values()) s.ws.close()
