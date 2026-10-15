import { api } from "@/lib/api";

/**
 * Web push via Firebase Cloud Messaging. Entirely optional: everything here is a
 * no-op unless the build has the VITE_FIREBASE_* variables below. The server
 * sends pushes with the FCM HTTP v1 API (FCM_SERVICE_ACCOUNT_JSON); the browser
 * only needs to obtain a registration token and hand it to
 * POST /api/notifications/device-tokens.
 *
 *   VITE_FIREBASE_API_KEY, VITE_FIREBASE_PROJECT_ID, VITE_FIREBASE_MESSAGING_SENDER_ID,
 *   VITE_FIREBASE_APP_ID, VITE_FIREBASE_VAPID_KEY  (optional: VITE_FIREBASE_AUTH_DOMAIN)
 */
export interface FirebaseWebConfig {
  apiKey: string;
  projectId: string;
  messagingSenderId: string;
  appId: string;
  authDomain?: string;
  vapidKey: string;
}

const TOKEN_KEY = "bettercrm.pushToken";

export function firebaseConfig(env: Record<string, string | undefined> = import.meta.env as unknown as Record<string, string | undefined>): FirebaseWebConfig | null {
  const cfg = {
    apiKey: env.VITE_FIREBASE_API_KEY,
    projectId: env.VITE_FIREBASE_PROJECT_ID,
    messagingSenderId: env.VITE_FIREBASE_MESSAGING_SENDER_ID,
    appId: env.VITE_FIREBASE_APP_ID,
    authDomain: env.VITE_FIREBASE_AUTH_DOMAIN,
    vapidKey: env.VITE_FIREBASE_VAPID_KEY,
  };
  if (!cfg.apiKey || !cfg.projectId || !cfg.messagingSenderId || !cfg.appId || !cfg.vapidKey) return null;
  return cfg as FirebaseWebConfig;
}

export function pushSupported(): boolean {
  return typeof window !== "undefined" && "serviceWorker" in navigator && "Notification" in window && firebaseConfig() !== null;
}

export function pushPermission(): NotificationPermission | "unsupported" {
  if (!pushSupported()) return "unsupported";
  return Notification.permission;
}

/**
 * Gets this browser's FCM token and registers it for the signed-in user.
 * Asks for notification permission only when {@code askPermission} is true
 * (call that from a click — browsers block prompts without a user gesture).
 * Returns the token, or null when push is unavailable / not permitted.
 */
export async function registerPush(askPermission = false): Promise<string | null> {
  const cfg = firebaseConfig();
  if (!cfg || !pushSupported()) return null;
  let permission = Notification.permission;
  if (permission === "default" && askPermission) permission = await Notification.requestPermission();
  if (permission !== "granted") return null;

  const params = new URLSearchParams({
    apiKey: cfg.apiKey,
    projectId: cfg.projectId,
    messagingSenderId: cfg.messagingSenderId,
    appId: cfg.appId,
  });
  const registration = await navigator.serviceWorker.register(`/firebase-messaging-sw.js?${params.toString()}`);
  const [{ initializeApp, getApps }, messaging] = await Promise.all([import("firebase/app"), import("firebase/messaging")]);
  if (!(await messaging.isSupported())) return null;
  const app =
    getApps()[0] ??
    initializeApp({ apiKey: cfg.apiKey, projectId: cfg.projectId, messagingSenderId: cfg.messagingSenderId, appId: cfg.appId, authDomain: cfg.authDomain });
  const token = await messaging.getToken(messaging.getMessaging(app), { vapidKey: cfg.vapidKey, serviceWorkerRegistration: registration });
  if (!token) return null;
  await api.post("/notifications/device-tokens", { token, platform: "web" });
  try {
    sessionStorage.setItem(TOKEN_KEY, token);
  } catch {
    /* storage unavailable */
  }
  return token;
}

/** Already registered in this browser session? (avoids re-posting the token on every mount) */
export function registeredThisSession(): boolean {
  try {
    return !!sessionStorage.getItem(TOKEN_KEY);
  } catch {
    return false;
  }
}
