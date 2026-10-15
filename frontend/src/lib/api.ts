import axios, { AxiosError } from "axios";
import { getToken, useAuthStore } from "./auth";

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || "/api",
  timeout: 60_000,
});

/** Error thrown by every API call — carries the HTTP status and the server's error code. */
export class ApiError extends Error {
  status?: number;
  code?: string;
  requestId?: string;
  constructor(message: string, status?: number, code?: string, requestId?: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.requestId = requestId;
  }
}

api.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

api.interceptors.response.use(
  (res) => res,
  (error: AxiosError<{ error?: string; code?: string; requestId?: string }>) => {
    const status = error.response?.status;
    const data = error.response?.data;
    const code = data?.code;
    const isLogin = error.config?.url?.includes("/auth/login");

    if (status === 401 && !isLogin && getToken()) {
      // Token expired or revoked (password change, "sign out everywhere", deactivation).
      useAuthStore.getState().logout();
      if (!window.location.pathname.startsWith("/login")) {
        const back = window.location.pathname + window.location.search;
        window.location.assign(`/login?next=${encodeURIComponent(back)}&reason=expired`);
      }
    }
    if (status === 403 && code === "PASSWORD_CHANGE_REQUIRED") {
      useAuthStore.getState().requirePasswordChange();
      if (!window.location.pathname.startsWith("/change-password")) {
        window.location.assign("/change-password");
      }
    }

    let message =
      data?.error ||
      (status === 403
        ? "You don't have permission to do that."
        : status === 429
        ? "Too many requests — please wait a moment and try again."
        : error.code === "ECONNABORTED"
        ? "The server took too long to respond."
        : !error.response
        ? "Can't reach the server. Check your connection."
        : error.message) ||
      "Something went wrong";
    if (status && status >= 500 && data?.requestId && !message.includes(data.requestId)) {
      message = `${message} (ref ${data.requestId})`;
    }
    return Promise.reject(new ApiError(message, status, code, data?.requestId));
  }
);

/** GET that returns the raw payload — used for {count,data} list endpoints. */
export async function getList<T>(
  url: string,
  params?: Record<string, unknown>
): Promise<{ count: number; data: T[] }> {
  const res = await api.get(url, { params });
  // A handful of endpoints (users, teams list, reports) return a bare array.
  if (Array.isArray(res.data)) return { count: res.data.length, data: res.data };
  return res.data;
}

/** Downloads an authenticated file (CSV export, attachment) via a blob URL. */
export async function downloadFile(url: string, fallbackName: string): Promise<void> {
  const res = await api.get(url, { responseType: "blob" });
  const disposition: string = res.headers["content-disposition"] ?? "";
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition);
  const name = match ? decodeURIComponent(match[1]) : fallbackName;
  const href = URL.createObjectURL(res.data as Blob);
  const a = document.createElement("a");
  a.href = href;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(href), 1000);
}
