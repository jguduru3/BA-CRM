import { create } from "zustand";

export type ThemeMode = "light" | "dark" | "system";
const KEY = "bettercrm.theme";

interface ThemeState {
  mode: ThemeMode;
  setMode: (m: ThemeMode) => void;
}

function apply(mode: ThemeMode) {
  const root = document.documentElement;
  if (mode === "system") {
    root.removeAttribute("data-theme");
  } else {
    root.setAttribute("data-theme", mode);
  }
}

const initial = (localStorage.getItem(KEY) as ThemeMode) || "system";
apply(initial);

export const useThemeStore = create<ThemeState>((set) => ({
  mode: initial,
  setMode: (mode) => {
    localStorage.setItem(KEY, mode);
    apply(mode);
    set({ mode });
  },
}));
