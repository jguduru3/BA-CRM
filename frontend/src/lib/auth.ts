import { create } from "zustand";
import type { Role, User } from "./types";

const TOKEN_KEY = "bettercrm.token";
const USER_KEY = "bettercrm.user";

/** Callbacks run on logout so every cache/store is wiped (registered from main.tsx / stores). */
const logoutHooks = new Set<() => void>();
export function onLogout(fn: () => void): () => void {
  logoutHooks.add(fn);
  return () => logoutHooks.delete(fn);
}

interface AuthState {
  token: string | null;
  user: User | null;
  isAuthenticated: boolean;
  setSession: (token: string, user: User) => void;
  updateUser: (user: User) => void;
  requirePasswordChange: () => void;
  logout: () => void;
}

function readUser(): User | null {
  try {
    const raw = localStorage.getItem(USER_KEY);
    return raw ? (JSON.parse(raw) as User) : null;
  } catch {
    return null;
  }
}

function safeGet(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

export const useAuthStore = create<AuthState>((set, get) => ({
  token: safeGet(TOKEN_KEY),
  user: readUser(),
  isAuthenticated: !!safeGet(TOKEN_KEY),
  setSession: (token, user) => {
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(USER_KEY, JSON.stringify(user));
    set({ token, user, isAuthenticated: true });
  },
  updateUser: (user) => {
    localStorage.setItem(USER_KEY, JSON.stringify(user));
    set({ user });
  },
  requirePasswordChange: () => {
    const user = get().user;
    if (user && !user.mustChangePassword) get().updateUser({ ...user, mustChangePassword: true });
  },
  logout: () => {
    try {
      localStorage.removeItem(TOKEN_KEY);
      localStorage.removeItem(USER_KEY);
    } catch {
      /* storage unavailable */
    }
    set({ token: null, user: null, isAuthenticated: false });
    logoutHooks.forEach((fn) => {
      try {
        fn();
      } catch {
        /* a failing hook must not block logout */
      }
    });
  },
}));

export function getToken(): string | null {
  return safeGet(TOKEN_KEY);
}

// Keep tabs in sync: signing out (or in) in one tab applies to all of them.
if (typeof window !== "undefined") {
  window.addEventListener("storage", (e) => {
    if (e.key !== TOKEN_KEY) return;
    if (!e.newValue) {
      if (useAuthStore.getState().isAuthenticated) useAuthStore.getState().logout();
    } else {
      useAuthStore.setState({ token: e.newValue, user: readUser(), isAuthenticated: true });
    }
  });
}

// Centralized role helpers — the backend is the real authority (every write
// is re-checked by PermissionService), these only drive what the UI offers.
export function isAdmin(role: Role | undefined): boolean {
  return role === "SUPER_ADMIN";
}
export function isManagerUp(role: Role | undefined): boolean {
  return role === "SUPER_ADMIN" || role === "VP_SALES" || role === "MANAGER";
}
export function isReadOnly(role: Role | undefined): boolean {
  return role === "SUPPORT";
}
