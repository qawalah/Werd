// Keeps the web app working offline (only where the browser allows it: https or localhost).
const CACHE = 'werd-web-v1';
const SHELL = ['./', 'index.html', 'app.css', 'app.js', 'parser.js', 'manifest.webmanifest', 'icon-192.png',
  'fonts/amiri_regular.ttf', 'fonts/amiri_bold.ttf', 'fonts/tajawal_regular.ttf', 'fonts/tajawal_medium.ttf', 'fonts/tajawal_bold.ttf'];
self.addEventListener('install', (e) => { e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL))); self.skipWaiting(); });
self.addEventListener('activate', (e) => e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim())));
self.addEventListener('fetch', (e) => {
  const u = new URL(e.request.url);
  if (e.request.method !== 'GET' || u.pathname.startsWith('/v1/')) return;   // the API always goes to the server
  // app files: the network first (so updates arrive), the saved copy when offline
  e.respondWith(fetch(e.request).then((r) => { const c = r.clone(); caches.open(CACHE).then((x) => x.put(e.request, c)); return r; })
    .catch(() => caches.match(e.request)));
});
