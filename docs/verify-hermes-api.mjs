// Read-only smoke test of the Hermes gateway contract described in hermes-api.md.
// Node >= 22 (global fetch + WebSocket). Never prints secrets: cookie values, tokens and tickets are masked.
//   node verify-hermes-api.mjs <baseUrl> <login-file> [--native]
//   login-file: "username: ...\npassword: ..." (e.g. %LOCALAPPDATA%\hermes\webapp-login.txt)
// Does: public probes, password sign-in (cookie flow; --native also runs the RFC 8252 bearer flow),
// /api/auth/me, profiles, sessions, one transcript page, socket ticket, /api/ws canonical dial,
// gateway.ready, client.capabilities, gateway.ping, a few read-only RPCs, then listens for events.
// It sends no chat, runs nothing, changes no setting, creates/deletes nothing.
import { readFileSync } from 'node:fs'
import { createHash, randomBytes } from 'node:crypto'

const [base, loginFile, ...flags] = process.argv.slice(2)
const native = flags.includes('--native')
const listenMs = Number(process.env.LISTEN_MS || 40000)
const creds = Object.fromEntries(readFileSync(loginFile, 'utf8').replace(/^﻿/, '').split(/\r?\n/)
  .filter(Boolean).map(l => [l.slice(0, l.indexOf(':')).trim(), l.slice(l.indexOf(':') + 1).trim()]))
const jar = new Map()
const mask = s => (s ? `${String(s).slice(0, 4)}…(${String(s).length})` : String(s))
const log = (...a) => console.log(...a)
const keys = o => (o && typeof o === 'object' ? Object.keys(o).sort().join(',') : typeof o)
const cookieHeader = () => [...jar].map(([k, v]) => `${k}=${v}`).join('; ')
let bearer = ''

async function call(path, { method = 'GET', body, auth = true, redirect = 'manual', headers = {} } = {}) {
  const h = { ...headers }
  if (body !== undefined) h['Content-Type'] = 'application/json'
  if (auth && bearer) h.Authorization = `Bearer ${bearer}`
  else if (auth && jar.size) h.Cookie = cookieHeader()
  const t0 = Date.now()
  const res = await fetch(new URL(path, base), { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body), redirect })
  for (const c of res.headers.getSetCookie?.() || []) {
    const [pair, ...attrs] = c.split(';')
    const name = pair.slice(0, pair.indexOf('=')), value = pair.slice(pair.indexOf('=') + 1)
    if (/max-age=0/i.test(c) || value === '' || value === '""') jar.delete(name); else jar.set(name, value)
    log(`  set-cookie ${name}=${mask(value)};${attrs.join(';')}`)
  }
  const text = await res.text()
  let json; try { json = JSON.parse(text) } catch { json = undefined }
  log(`${method} ${path} -> ${res.status} (${Date.now() - t0} ms)`)
  return { res, json, text }
}

log(`base ${new URL(base).origin}`)
// 1. public
let r = await call('/api/health', { auth: false }); log('  health keys:', keys(r.json))
r = await call('/api/status', { auth: false }); log('  status keys:', keys(r.json))
r = await call('/api/auth/providers', { auth: false }); log('  providers:', JSON.stringify(r.json))
const providers = r.json?.providers || []
const pw = providers.find(p => p.supports_password || p.kind === 'password' || p.type === 'password') || providers[0]
const provider = pw?.name || pw?.id || 'basic'
r = await call('/api/profiles', { auth: false }); log('  unauthenticated /api/profiles body:', r.text.slice(0, 200))

// 2. sign in (cookie flow)
r = await call('/auth/password-login', { method: 'POST', auth: false, body: { provider, username: creds.username, password: creds.password, next: '/' } })
log('  login body:', JSON.stringify(r.json))
if (!r.res.ok) process.exit(1)
log('  cookie names:', [...jar.keys()].join(', '))

// 2b. native RFC 8252 flow (bearer). The loopback redirect is never followed: `next` carries the code.
if (native) {
  const verifier = randomBytes(32).toString('base64url')
  const challenge = createHash('sha256').update(verifier).digest('base64url')
  const saved = new Map(jar); jar.clear()
  const q = new URLSearchParams({ provider, code_challenge: challenge, code_challenge_method: 'S256', redirect_uri: 'http://127.0.0.1:53682/callback', state: 'verify' })
  r = await call(`/auth/native/authorize?${q}`, { auth: true }); log('  authorize location:', r.res.headers.get('location'))
  r = await call('/auth/password-login', { method: 'POST', auth: true, body: { provider, username: creds.username, password: creds.password, next: '' } })
  const next = r.json?.next ? new URL(r.json.next) : null
  log('  native next:', next ? `${next.origin}${next.pathname}?code=${mask(next.searchParams.get('code'))}&state=${next.searchParams.get('state')}` : r.text.slice(0, 200))
  r = await call('/auth/native/token', { method: 'POST', auth: false, body: { code: next?.searchParams.get('code'), code_verifier: verifier } })
  log('  token response keys:', keys(r.json), 'token_type', r.json?.token_type, 'expires_in_s', r.json?.expires_at ? Math.round(r.json.expires_at - Date.now() / 1000) : null)
  bearer = r.json?.access_token || ''
  r = await call('/api/auth/me'); log('  me (bearer):', keys(r.json), 'provider', r.json?.provider)
  bearer = ''; jar.clear(); for (const [k, v] of saved) jar.set(k, v)
}

// 3. authenticated REST reads
r = await call('/api/auth/me'); log('  me keys:', keys(r.json), 'provider', r.json?.provider, 'expires_in_s', r.json?.expires_at ? Math.round(r.json.expires_at - Date.now() / 1000) : null)
r = await call('/api/profiles'); const profiles = r.json?.profiles || []
log(`  profiles: ${profiles.length}; row keys: ${keys(profiles[0])}`)
r = await call('/api/sessions?limit=3&order=recent'); const sessions = r.json?.sessions || []
log(`  /api/sessions keys: ${keys(r.json)}; rows: ${sessions.length}; row keys: ${keys(sessions[0])}`)
const sid = sessions[0]?.id || sessions[0]?.session_id
if (sid) {
  r = await call(`/api/sessions/${encodeURIComponent(sid)}/messages?limit=5&order=latest`)
  const msgs = r.json?.messages || []
  log(`  messages keys: ${keys(r.json)}; rows: ${msgs.length}; row keys: ${keys(msgs[0])}`)
}

// 4. socket ticket + canonical /api/ws dial
r = await call('/api/webapp/socket-ticket?socket=gateway', { method: 'POST' })
log('  ticket:', mask(r.json?.ticket), 'ttl', r.json?.ttl_seconds)
const wsUrl = new URL('/api/ws', base); wsUrl.protocol = wsUrl.protocol === 'https:' ? 'wss:' : 'ws:'; wsUrl.searchParams.set('native_dial', 'browser')
const ws = new WebSocket(wsUrl, ['hermes-gateway-v1', `hermes-gateway-ticket.${r.json?.ticket}`])
const pending = new Map(); let nextId = 0; const counts = {}
const rpc = (method, params = {}) => new Promise((resolve, reject) => {
  const id = `v${++nextId}`; const t0 = Date.now()
  pending.set(id, { resolve: v => resolve({ ...v, ms: Date.now() - t0 }), reject })
  ws.send(JSON.stringify({ jsonrpc: '2.0', id, method, params }))
  setTimeout(() => pending.has(id) && (pending.delete(id), reject(new Error(`timeout ${method}`))), 20000)
})
const ready = new Promise(res => {
  ws.addEventListener('message', ev => {
    const f = JSON.parse(String(ev.data))
    if (f.method === 'event') {
      const t = f.params?.type; counts[t] = (counts[t] || 0) + 1
      if (t === 'gateway.ready') { log(`  gateway.ready payload keys: ${keys(f.params.payload)}; heartbeat=${f.params.payload.heartbeat} change_events=${f.params.payload.change_events} session_authority=${f.params.payload.session_authority}`); res() }
      else if (counts[t] === 1) log(`  event ${t}: params keys ${keys(f.params)}; payload keys ${keys(f.params?.payload)}`)
    } else if (typeof f.id === 'string' && f.method) {
      log(`  server request ${f.method} (answering -32601)`); ws.send(JSON.stringify({ jsonrpc: '2.0', id: f.id, error: { code: -32601, message: 'verify script' } }))
    } else if (pending.has(f.id)) { const p = pending.get(f.id); pending.delete(f.id); p.resolve(f) }
  })
})
await new Promise((res, rej) => { ws.addEventListener('open', res, { once: true }); ws.addEventListener('error', () => rej(new Error('ws error')), { once: true }) })
log(`WS open protocol="${ws.protocol}"`)
ws.addEventListener('close', e => log(`WS closed code=${e.code} reason=${e.reason}`))
await ready
const show = (name, f, pick) => log(`  ${name} (${f.ms} ms):`, f.error ? `ERROR ${JSON.stringify(f.error)}` : pick(f.result))
show('client.capabilities', await rpc('client.capabilities', { server_requests: true }), v => JSON.stringify(v))
show('gateway.ping', await rpc('gateway.ping'), v => JSON.stringify(v))
show('runtime.describe', await rpc('runtime.describe'), v => `keys ${keys(v)}; capabilities ${JSON.stringify(v.capabilities)}`)
show('session.list stored', await rpc('session.list', { scope: 'stored', limit: 3 }), v => `scope ${v.scope}; rows ${v.sessions?.length}; row keys ${keys(v.sessions?.[0])}`)
show('session.list live', await rpc('session.list', { scope: 'live', limit: 5 }), v => `rows ${v.sessions?.length}; row keys ${keys(v.sessions?.[0])}`)
show('session.list Bot Chat', await rpc('session.list', { title: 'Bot Chat', include_hidden: true, limit: 1 }), v => `rows ${v.sessions?.length}`)
show('profiles.list', await rpc('profiles.list', { include_sessions: true }), v => `profiles ${v.profiles?.length}; row keys ${keys(v.profiles?.[0])}; bot_mode_protocol ${v.bot_mode_protocol}`)
show('session.active_list', await rpc('session.active_list'), v => `rows ${v.sessions?.length}`)
show('commands.catalog', await rpc('commands.catalog'), v => `keys ${keys(v)}; commands ${Object.keys(v.commands || {}).length}; skills ${v.skill_count}`)
show('model.options', await rpc('model.options', {}), v => `providers ${v.providers?.length}; provider keys ${keys(v.providers?.[0])}; current ${v.provider}/${v.model}`)
show('config.get full (canonical)', await rpc('config.get', { key: 'full' }), v => `config keys ${keys(v.config)}`)
if (sid) show('session.events.since', await rpc('session.events.since', { session_id: sid, last_seen: 0 }), v => `keys ${keys(v)}; events ${v.events?.length}; truncated ${v.truncated}; snapshot_required ${v.snapshot_required}`)
show('projects.list (legacy fallback)', await rpc('projects.list', {}), v => `keys ${keys(v)}`)
show('mcp.servers.list (legacy fallback)', await rpc('mcp.servers.list', {}), v => `keys ${keys(v)}`)
show('pet.info (legacy fallback)', await rpc('pet.info', {}), v => `keys ${keys(v)}`)
show('no.such.method', await rpc('no.such.method'), v => JSON.stringify(v))

// 5. live stream: client pings every 15 s like the shipped client; log every event type seen
log(`listening ${listenMs / 1000}s ...`)
const pinger = setInterval(async () => { const f = await rpc('gateway.ping').catch(e => ({ error: e.message, ms: -1 })); log(`  ping -> ${JSON.stringify(f.result ?? f.error)} ${f.ms} ms`) }, 15000)
await new Promise(res => setTimeout(res, listenMs))
clearInterval(pinger)
log('event counts:', JSON.stringify(counts))
ws.close(1000)
await new Promise(res => setTimeout(res, 500))
