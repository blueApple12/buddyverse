// Buddyverse PWA service worker. `__BUILD__` is replaced per build (webPlugin task: version + content hash), so every
// deploy is a byte-different sw.js: the browser installs it, it takes over at once and drops the older caches.
// Pages (index.html) and sw.js: network first, cache only when offline. Everything else in this folder is cache first
// inside this build's cache (index.html asks for buddyverse.js?v=<build>; the wasm names are content hashes).
// API calls and sockets are outside this folder and never touched.
const BUILD = '__BUILD__';
const PREFIX = 'buddyverse-';
const CACHE = PREFIX + BUILD;
const SHELL = ['./index.html', './buddyverse.js?v=' + BUILD, './pwa.json', './icon-192.png', './icon-512.png', './apple-touch-icon.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL.map(u => new Request(u, { cache: 'reload' })))).catch(() => {})
    .then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => e.waitUntil((async () => {
  // 'buddyverse-shell' = the cache of the first, unversioned service worker.
  const old = (await caches.keys()).filter(k => k.startsWith(PREFIX) && k !== CACHE);
  // Compose's own resource cache (fonts, strings) belongs to the old build too.
  if (old.length) old.push('compose_web_resources_cache');
  await Promise.all(old.map(k => caches.delete(k)));
  await self.clients.claim();
  // A real update (an older build's cache was here): tell open windows to reload into this build (index.html listens).
  // Not WindowClient.navigate(): it wedged a page whose wasm app was running. First install: nothing to reload.
  if (old.length) for (const c of await self.clients.matchAll({ type: 'window' })) c.postMessage({ type: 'buddyverse-reload', build: BUILD });
})()));

self.addEventListener('fetch', e => {
  const req = e.request;
  const url = new URL(req.url);
  const scope = new URL(self.registration.scope);
  if (req.method !== 'GET' || url.origin !== scope.origin || !url.pathname.startsWith(scope.pathname)) return;
  const page = req.mode === 'navigate' || url.pathname.endsWith('.html') || url.pathname.endsWith('/sw.js') || url.pathname === scope.pathname;
  e.respondWith(caches.open(CACHE).then(async c => {
    if (page) {
      try {
        const r = await fetch(req, { cache: 'no-store' });
        if (r.ok) c.put(req, r.clone());
        return r;
      } catch (err) {
        const hit = await c.match(req, { ignoreSearch: true });
        if (hit) return hit;
        throw err;
      }
    }
    const hit = await c.match(req);
    if (hit) return hit;
    const r = await fetch(req);
    if (r.ok) c.put(req, r.clone());
    return r;
  }));
});
