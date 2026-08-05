import { useEffect, useState } from "react";

/**
 * Dark or light, resolved after mount.
 *
 * The static HTML this site emits carries `data-theme="dark"`, and this hook corrects it once React
 * is running. That costs a light-preferring visitor one frame of dark — and never the reverse,
 * which is the direction that actually hurts.
 *
 * The alternative is the usual blocking `<script>` in `<head>`, and it was rejected on purpose:
 * that script needs a hash in the Content-Security-Policy, which means the deploy lane has to read
 * a build artefact to write its own configuration, and it means the site's CSP test can only ever
 * check "no third-party origin" instead of the far stronger property it checks now — that there is
 * **no inline script in any page at all**. One frame is a cheap price for an assertion that simple.
 */

export type ThemeChoice = "system" | "dark" | "light";
export type Resolved = "dark" | "light";

const KEY = "nodera-site-theme";

function systemPrefersLight(): boolean {
  return typeof window !== "undefined" && window.matchMedia("(prefers-color-scheme: light)").matches;
}

function stored(): ThemeChoice {
  if (typeof window === "undefined") return "system";
  const raw = window.localStorage.getItem(KEY);
  return raw === "dark" || raw === "light" || raw === "system" ? raw : "system";
}

export function useResolvedTheme(): { resolved: Resolved; choice: ThemeChoice; set: (c: ThemeChoice) => void } {
  const [choice, setChoice] = useState<ThemeChoice>("system");
  const [resolved, setResolved] = useState<Resolved>("dark");

  useEffect(() => {
    setChoice(stored());
  }, []);

  useEffect(() => {
    const apply = () => {
      const next: Resolved = choice === "system" ? (systemPrefersLight() ? "light" : "dark") : choice;
      setResolved(next);
      document.documentElement.dataset.theme = next;
    };
    apply();
    if (choice !== "system") return;
    const media = window.matchMedia("(prefers-color-scheme: light)");
    media.addEventListener("change", apply);
    return () => media.removeEventListener("change", apply);
  }, [choice]);

  const set = (next: ThemeChoice) => {
    setChoice(next);
    if (typeof window !== "undefined") window.localStorage.setItem(KEY, next);
  };

  return { resolved, choice, set };
}

/** True once the component has mounted in a browser. Everything hydration-unsafe waits on it. */
export function useMounted(): boolean {
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);
  return mounted;
}

/** Whether the visitor has asked for less motion. Read after mount; assumes "yes" before then. */
export function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState(true);
  useEffect(() => {
    const media = window.matchMedia("(prefers-reduced-motion: reduce)");
    const apply = () => setReduced(media.matches);
    apply();
    media.addEventListener("change", apply);
    return () => media.removeEventListener("change", apply);
  }, []);
  return reduced;
}

/** Holds the page still while a full-screen overlay is open. */
export function useScrollLock(locked: boolean): void {
  useEffect(() => {
    if (!locked) return;
    const previous = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = previous;
    };
  }, [locked]);
}
