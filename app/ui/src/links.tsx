// The one way this interface opens a web link.
//
// ## Why an anchor is not enough
//
// In a Tauri v2 webview `<a target="_blank">` opens **nothing**: there is no browser chrome and no
// tab for it to open in, so the click is swallowed. Every outbound link in this app was therefore
// decorative — `About.tsx` had already noticed and turned its repository link into a
// copy-to-clipboard button, which is a workaround that admits the link did not work.
//
// An anchor *without* `target="_blank"` is worse, not better: this window would navigate away, and
// the user would be left looking at somebody's web page where the application used to be, with no
// back button because the app has no browser chrome either.
//
// So links are buttons that ask the host to open them, and the host decides where — the desktop's
// default browser, or on Android a Custom Tab, then the browser, then an in-app WebView.
//
// ## Failing honestly
//
// A device can have no browser at all. When every rung of that ladder fails the link cannot be
// opened, and the useful thing to do is put the address on the clipboard and say so, rather than
// showing a dead control that appears to do nothing when pressed.
import { useCallback, useEffect, useRef, useState } from "react";
import { openExternal } from "./ipc";

/** What the last attempt did, for the caller to render beside the control. */
export type LinkState =
  | { status: "idle" }
  | { status: "opening" }
  /** Opened. `how` is the platform's answer: `browser`, `custom-tab` or `webview`. */
  | { status: "opened"; how: string }
  /** Could not open, so the address was put on the clipboard instead. */
  | { status: "copied" }
  /** Could not open and could not copy. `reason` is the host's own words. */
  | { status: "failed"; reason: string };

/** How long a transient "opened"/"copied" note stays on screen. */
const NOTE_MS = 2000;

/**
 * Open links, with the clipboard as the fallback nobody has to think about.
 *
 * ```tsx
 * const link = useExternalLink();
 * <button onClick={() => link.open(store.homepage)}>Homepage</button>
 * {link.state.status === "copied" && <span>no browser — address copied</span>}
 * ```
 */
export function useExternalLink() {
  const [state, setState] = useState<LinkState>({ status: "idle" });
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const live = useRef(true);

  useEffect(() => {
    live.current = true;
    return () => {
      live.current = false;
      clearTimeout(timer.current);
    };
  }, []);

  const settle = useCallback((next: LinkState) => {
    if (!live.current) return;
    setState(next);
    clearTimeout(timer.current);
    // A failure stays until the next attempt: it carries the reason, and a message that explains
    // why nothing happened is the one message that must not vanish before it is read.
    if (next.status === "opened" || next.status === "copied") {
      timer.current = setTimeout(() => live.current && setState({ status: "idle" }), NOTE_MS);
    }
  }, []);

  const open = useCallback(
    async (url: string) => {
      if (!url) return;
      setState({ status: "opening" });
      try {
        settle({ status: "opened", how: await openExternal(url) });
      } catch (e) {
        try {
          await navigator.clipboard?.writeText(url);
          settle({ status: "copied" });
        } catch {
          settle({ status: "failed", reason: String(e) });
        }
      }
    },
    [settle],
  );

  return { open, state };
}

/** The note to show beside a link control, or `""` when there is nothing to say. */
export function linkNote(state: LinkState): string {
  switch (state.status) {
    case "copied":
      return "no browser here — address copied";
    case "failed":
      return state.reason;
    default:
      return "";
  }
}

/**
 * A link.
 *
 * Renders a `<button>` and not an `<a>`, deliberately: there is no href this webview could follow
 * that would do the right thing, and an anchor would invite exactly the middle-click and
 * copy-link-address gestures that silently do nothing here. The control carries the address as its
 * `title` so hovering still reveals where it goes.
 */
export function ExternalLink(props: {
  href: string;
  children: React.ReactNode;
  className?: string;
  /** Announced to assistive technology in place of the visible text, if the text is an icon. */
  label?: string;
}) {
  const link = useExternalLink();
  return (
    <button
      type="button"
      title={props.href}
      aria-label={props.label}
      disabled={link.state.status === "opening"}
      onClick={() => link.open(props.href)}
      className={props.className}
    >
      {props.children}
    </button>
  );
}
