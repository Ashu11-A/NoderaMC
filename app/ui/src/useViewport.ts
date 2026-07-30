// Which layout to render — and the honest distinction between two different questions.
//
// `useIsCompact` asks about the *window*: it is a media query, so a desktop window dragged narrow
// gets the mobile layout, which is what the user is asking for by making it narrow.
//
// `useIsMobileBuild` asks about the *binary*: the Android build has no Java worker to supervise and
// therefore no LAN lane and no mod installer, and hiding those is not a layout decision. Keeping
// the two apart is what stops a narrow desktop window from losing features it genuinely has.
import { useEffect, useState } from "react";
import { fetchIsMobileBuild } from "./ipc";

/** The M3 compact window-size class: under 600dp wide. */
const COMPACT = "(max-width: 599px)";

export function useIsCompact(): boolean {
  const [compact, setCompact] = useState(
    () => typeof window !== "undefined" && window.matchMedia?.(COMPACT).matches === true,
  );
  useEffect(() => {
    const query = window.matchMedia?.(COMPACT);
    if (!query) return;
    const onChange = (e: MediaQueryListEvent) => setCompact(e.matches);
    query.addEventListener("change", onChange);
    setCompact(query.matches);
    return () => query.removeEventListener("change", onChange);
  }, []);
  return compact;
}

/**
 * Whether this is the peer-only build.
 *
 * Starts as `null` — "not asked yet" — so the shell can wait one tick rather than flashing the
 * desktop chrome on a phone or hiding desktop features on a desktop.
 */
export function useIsMobileBuild(): boolean | null {
  const [mobile, setMobile] = useState<boolean | null>(null);
  useEffect(() => {
    let alive = true;
    fetchIsMobileBuild()
      .then((v) => alive && setMobile(v))
      .catch(() => alive && setMobile(false));
    return () => {
      alive = false;
    };
  }, []);
  return mobile;
}
