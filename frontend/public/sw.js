/*
 * Better CRM service worker — makes the app installable and the shell load fast.
 *
 * Scope is deliberately tiny:
 *  - only same-origin GET requests for static files are ever cached
 *    (the app shell, hashed /assets/*, icons, the manifest and brand images);
 *  - /api/* is NEVER intercepted or cached — CRM data always comes straight
 *    from the network with the user's auth header;
 *  - non-GET and cross-origin requests are ignored entirely.
 *
 * Bump CACHE_VERSION to drop every older cache on the next activation.
 */
const CACHE_VERSION = "v1";
const CACHE_NAME = `bettercrm-static-${CACHE_VERSION}`;
const SHELL_URL = "/index.html";
const PRECACHE = [
  SHELL_URL,
  "/site.webmanifest",
  "/favicon.ico",
  "/favicon-32.png",
  "/icon-192.png",
  "/icon-512.png",
  "/apple-touch-icon.png",
  "/brand/logo.png",
  "/brand/logo-white.png",
];

const STATIC_FILE = /\.(?:png|ico|svg|webmanifest|woff2?|webp|jpg|jpeg)$/i;

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(CACHE_NAME)
      // A missing optional icon must not break installation.
      .then((cache) => Promise.all(PRECACHE.map((url) => cache.add(url).catch(() => undefined))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(keys.filter((k) => k.startsWith("bettercrm-static-") && k !== CACHE_NAME).map((k) => caches.delete(k)))
      )
      .then(() => self.clients.claim())
  );
});

function isStaticAsset(url) {
  return url.pathname.startsWith("/assets/") || url.pathname.startsWith("/brand/") || STATIC_FILE.test(url.pathname);
}

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET") return;

  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;
  // Never touch the API (or the API docs): let them hit the network untouched.
  if (url.pathname === "/api" || url.pathname.startsWith("/api/")) return;
  if (url.pathname === "/sw.js") return;

  // Navigations: network first so new deploys show up immediately; the cached
  // shell is only the offline fallback.
  if (req.mode === "navigate") {
    event.respondWith(
      fetch(req)
        .then((res) => {
          if (res.ok) {
            const copy = res.clone();
            caches.open(CACHE_NAME).then((c) => c.put(SHELL_URL, copy));
          }
          return res;
        })
        .catch(() => caches.match(SHELL_URL).then((cached) => cached || Response.error()))
    );
    return;
  }

  if (!isStaticAsset(url)) return;

  // Static files: cache first (hashed assets are immutable), then network.
  event.respondWith(
    caches.match(req).then(
      (cached) =>
        cached ||
        fetch(req).then((res) => {
          if (res.ok && res.type === "basic") {
            const copy = res.clone();
            caches.open(CACHE_NAME).then((c) => c.put(req, copy));
          }
          return res;
        })
    )
  );
});

// ── Web push (Azure Notification Hubs delivers the payload sent by the API) ──
// Payload: {"title": "...", "body": "...", "data": {"relatedType": "Lead", "relatedId": "42", ...}}
self.addEventListener("push", (event) => {
  let payload = {};
  try {
    payload = event.data ? event.data.json() : {};
  } catch {
    payload = { body: event.data ? event.data.text() : "" };
  }
  const title = payload.title || "Better CRM";
  event.waitUntil(
    self.registration.showNotification(title, {
      body: payload.body || "",
      icon: "/icon-192.png",
      badge: "/favicon-32.png",
      data: payload.data || {},
      tag: payload.data && payload.data.notificationId ? `n-${payload.data.notificationId}` : undefined,
    })
  );
});

const RELATED_ROUTES = { Lead: "/leads", Opportunity: "/opportunities", Contact: "/contacts", Company: "/companies", Task: "/tasks" };

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const data = event.notification.data || {};
  const base = RELATED_ROUTES[data.relatedType];
  // Only ever navigate within this app — never to a URL taken from the payload.
  const target = base && /^\d+$/.test(String(data.relatedId || "")) ? `${base}?open=${data.relatedId}` : "/";
  event.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((wins) => {
      for (const w of wins) {
        if (new URL(w.url).origin === self.location.origin) {
          w.navigate(target);
          return w.focus();
        }
      }
      return self.clients.openWindow(target);
    })
  );
});
