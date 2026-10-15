/* Web-push service worker for Firebase Cloud Messaging.
 * Registered by src/features/settings/push.ts only when the VITE_FIREBASE_* build
 * variables are present. It deliberately loads no Firebase script (the app's CSP
 * is script-src 'self'): FCM delivers a standard Web Push whose JSON payload is
 *   { "notification": { "title", "body" }, "data": { … } }
 * and this worker displays it and focuses the app on click.
 */
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));

self.addEventListener("push", (event) => {
  let payload = {};
  try {
    payload = event.data ? event.data.json() : {};
  } catch (e) {
    payload = { notification: { body: event.data ? event.data.text() : "" } };
  }
  const n = payload.notification || {};
  const data = payload.data || {};
  event.waitUntil(
    self.registration.showNotification(n.title || "Better CRM", {
      body: n.body || "",
      icon: "/icon-192.png",
      tag: data.notificationId ? "crm-" + data.notificationId : undefined,
      data,
    })
  );
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  event.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((wins) => {
      const existing = wins.find((w) => w.url.startsWith(self.location.origin));
      return existing ? existing.focus() : self.clients.openWindow("/");
    })
  );
});
