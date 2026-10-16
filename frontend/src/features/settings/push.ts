import { api } from "@/lib/api";

/**
 * Web push via Azure Notification Hubs — standard browser Push API, no Firebase.
 *
 * The server says whether push is configured and hands out the hub's VAPID public
 * key at runtime (GET /api/notifications/push-status), so one SPA build works in
 * every environment. The browser subscribes through our own service worker
 * (/sw.js, which shows the notification on "push") and the PushSubscription is
 * registered for the signed-in user via POST /api/notifications/device-tokens.
 */
interface PushStatus {
  enabled: boolean;
  vapidPublicKey: string | null;
}

const TOKEN_KEY = "bettercrm.pushToken";
let statusCache: Promise<PushStatus | null> | null = null;

export function pushSupported(): boolean {
  return (
    typeof window !== "undefined" &&
    "serviceWorker" in navigator &&
    "PushManager" in window &&
    "Notification" in window
  );
}

export function pushPermission(): NotificationPermission | "unsupported" {
  if (!pushSupported()) return "unsupported";
  return Notification.permission;
}

async function pushStatus(): Promise<PushStatus | null> {
  if (!statusCache) {
    statusCache = api
      .get<PushStatus>("/notifications/push-status")
      .then((r) => r.data)
      .catch(() => {
        statusCache = null;
        return null;
      });
  }
  return statusCache;
}

/** True when the server has push configured (hub + VAPID key). */
export async function pushAvailable(): Promise<boolean> {
  if (!pushSupported()) return false;
  const s = await pushStatus();
  return !!(s?.enabled && s.vapidPublicKey);
}

/** VAPID keys are URL-safe base64; PushManager wants the raw bytes. */
export function urlBase64ToUint8Array(base64: string): Uint8Array<ArrayBuffer> {
  const padding = "=".repeat((4 - (base64.length % 4)) % 4);
  const b64 = (base64 + padding).replace(/-/g, "+").replace(/_/g, "/");
  const raw = atob(b64);
  const out = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}

/**
 * Subscribes this browser and registers it for the signed-in user.
 * Asks for notification permission only when {@code askPermission} is true
 * (call that from a click — browsers block prompts without a user gesture).
 * Returns the installation id, or null when push is unavailable / not permitted.
 */
export async function registerPush(askPermission = false): Promise<string | null> {
  if (!pushSupported()) return null;
  const status = await pushStatus();
  if (!status?.enabled || !status.vapidPublicKey) return null;

  let permission = Notification.permission;
  if (permission === "default" && askPermission) permission = await Notification.requestPermission();
  if (permission !== "granted") return null;

  const registration =
    (await navigator.serviceWorker.getRegistration("/")) ?? (await navigator.serviceWorker.register("/sw.js", { scope: "/" }));
  await navigator.serviceWorker.ready;

  const appKey = urlBase64ToUint8Array(status.vapidPublicKey);
  let subscription = await registration.pushManager.getSubscription();
  if (subscription) {
    // Re-subscribe if the hub's VAPID key changed since this browser subscribed.
    const current = subscription.options.applicationServerKey;
    const same =
      current && new Uint8Array(current).length === appKey.length && new Uint8Array(current).every((b, i) => b === appKey[i]);
    if (!same) {
      await subscription.unsubscribe();
      subscription = null;
    }
  }
  if (!subscription) {
    subscription = await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: appKey });
  }

  const res = await api.post<{ token: string }>("/notifications/device-tokens", {
    platform: "web",
    subscription: subscription.toJSON(),
  });
  try {
    sessionStorage.setItem(TOKEN_KEY, res.data.token);
  } catch {
    /* storage unavailable */
  }
  return res.data.token;
}

/** Already registered in this browser session? (avoids re-posting on every mount) */
export function registeredThisSession(): boolean {
  try {
    return !!sessionStorage.getItem(TOKEN_KEY);
  } catch {
    return false;
  }
}
