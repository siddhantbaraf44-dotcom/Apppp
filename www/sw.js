/* Offline shell cache for the *web* (GitHub Pages) version of Shelf.
 * The Android build deliberately serves a 404 for /sw.js (see
 * AssetWebViewClient in MainActivity.java): a service worker inside a WebView
 * would route requests through ServiceWorkerClient and bypass the asset loader.
 * Everything is already bundled in the APK, so no service worker is needed there. */
const CACHE = 'shelf-v1';
const SHELL = ['./', './index.html', './manifest.json', './icon-192.png', './icon-512.png'];

self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', event => {
  event.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', event => {
  if (event.request.method !== 'GET') return;
  if (new URL(event.request.url).origin !== self.location.origin) return;
  event.respondWith(
    fetch(event.request)
      .then(response => {
        const copy = response.clone();
        caches.open(CACHE).then(c => c.put(event.request, copy));
        return response;
      })
      .catch(() => caches.match(event.request))
  );
});
