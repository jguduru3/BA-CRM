import { create } from "zustand";

export type ThemeMode = "light" | "dark" | "system";
const KEY = "bettercrm.theme";

interface ThemeState {
  mode: ThemeMode;
  setMode: (m: ThemeMode) => void;
}

function darkQuery(): MediaQueryList | null {
  return typeof window !== "undefined" && typeof window.matchMedia === "function"
    ? window.matchMedia("(prefers-color-scheme: dark)")
    : null;
}

/**
 * The design tokens switch on [data-theme], so "system" is resolved to the
 * OS preference here (and kept in sync below) rather than left to CSS.
 */
function apply(mode: ThemeMode) {
  const root = document.documentElement;
  const resolved = mode === "system" ? (darkQuery()?.matches ? "dark" : "light") : mode;
  root.setAttribute("data-theme", resolved);
}

function readMode(): ThemeMode {
  try {
    const v = localStorage.getItem(KEY);
    return v === "light" || v === "dark" || v === "system" ? v : "system";
  } catch {
    return "system";
  }
}

const initial = readMode();
apply(initial);

export const useThemeStore = create<ThemeState>((set) => ({
  mode: initial,
  setMode: (mode) => {
    try {
      localStorage.setItem(KEY, mode);
    } catch {
      /* storage unavailable */
    }
    apply(mode);
    set({ mode });
  },
}));

// Follow OS changes while in "system" mode.
darkQuery()?.addEventListener?.("change", () => {
  if (useThemeStore.getState().mode === "system") apply("system");
});
