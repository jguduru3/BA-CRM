import { useEffect, useState } from "react";
import { useThemeStore } from "./theme";

// Mirrors the CSS custom properties in index.css — recharts needs literal
// hex (SVG fill/stroke), so this is the single source of truth kept in sync
// with the validated dataviz palette for both chart surfaces.
const LIGHT = {
  series: ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#4a3aa7", "#e34948"],
  grid: "#e1e0d9",
  axis: "#898781",
  ink: "#52514e",
  surface: "#ffffff",
  good: "#0ca30c",
  critical: "#d03b3b",
};
const DARK = {
  series: ["#3987e5", "#d95926", "#199e70", "#c98500", "#d55181", "#008300", "#9085e9", "#e66767"],
  grid: "#232a3a",
  axis: "#727e92",
  ink: "#aab3c5",
  surface: "#10182a",
  good: "#0ca30c",
  critical: "#e66767",
};

function resolveIsDark(mode: string): boolean {
  if (mode === "dark") return true;
  if (mode === "light") return false;
  return window.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false;
}

export function useChartPalette() {
  const mode = useThemeStore((s) => s.mode);
  const [isDark, setIsDark] = useState(() => resolveIsDark(mode));

  useEffect(() => {
    setIsDark(resolveIsDark(mode));
    if (mode !== "system") return;
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const handler = () => setIsDark(mq.matches);
    mq.addEventListener("change", handler);
    return () => mq.removeEventListener("change", handler);
  }, [mode]);

  return isDark ? DARK : LIGHT;
}
