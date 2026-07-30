import { useEffect, useState } from "react";
import type { Theme } from "./ipc";

/**
 * Resolve the "system" theme choice into the concrete scheme to render, and keep following the OS
 * while it stays on "system".
 *
 * The listener is attached unconditionally rather than only in system mode: React would otherwise
 * have to tear down and rebuild the subscription every time the user toggles the preference, and a
 * missed transition leaves the window in the wrong scheme until something else re-renders.
 */
export function useResolvedTheme(preference: Theme): "dark" | "light" {
  const [system, setSystem] = useState<"dark" | "light">(() =>
    typeof window !== "undefined" && window.matchMedia?.("(prefers-color-scheme: light)").matches
      ? "light"
      : "dark",
  );

  useEffect(() => {
    const query = window.matchMedia?.("(prefers-color-scheme: light)");
    if (!query) return;
    const onChange = (e: MediaQueryListEvent) => setSystem(e.matches ? "light" : "dark");
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, []);

  const resolved = preference === "system" ? system : preference;

  useEffect(() => {
    // The stylesheet keys off this attribute, so one write drives the whole tree — no per-component
    // theme prop threading, and no flash of the wrong scheme on a nested mount.
    document.documentElement.dataset.theme = resolved;
    document.documentElement.style.colorScheme = resolved;
  }, [resolved]);

  return resolved;
}
