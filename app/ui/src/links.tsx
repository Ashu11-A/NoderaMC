// The one way this interface opens a web link.
//
// ## Why an anchor is not enough
//
// In a Tauri v2 webview `<a target="_blank">` opens **nothing**: there is no browser chrome and no
// tab for it to open in, so the click is swallowed. An anchor *without* it is worse — this window
// navigates away, and the user is left looking at somebody's web page where the application used to
// be, with no back button because the app has no browser chrome either.
//
// So links are buttons that ask the host, and the host decides where they open.
//
// ## A link always opens something
//
// This used to fall back to the clipboard: when nothing could open a browser it copied the address
// and said so. That is not opening a link — it is telling the user to go and do it themselves, in
// an app that is itself a webview and could have shown them the page.
//
// The host's ladder ends in a webview on both platforms now: the default browser first, then a
// Custom Tab on Android or a window of our own on the desktop, then a plain WebView. There is no
// clipboard rung, and nothing here should add one back.
import { useCallback, useEffect, useRef, useState } from "react";
import { openExternal } from "./ipc";

/** What the last attempt did, for the caller to render beside the control. */
export type LinkState =
  | { status: "idle" }
  | { status: "opening" }
  /** Opened. `how` is which rung answered: `browser`, `custom-tab` or `webview`. */
  | { status: "opened"; how: string }
  /** Nothing on this device could show the page. `reason` is the host's own words. */
  | { status: "failed"; reason: string };

/** How long the transient "opened" note stays on screen. */
const NOTE_MS = 1500;

/**
 * Open links.
 *
 * ```tsx
 * const link = useExternalLink();
 * <button onClick={() => link.open(store.homepage)}>Homepage</button>
 * {linkNote(link.state) && <span>{linkNote(link.state)}</span>}
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
    // A failure stays until the next attempt: it carries the reason, and a message explaining why
    // nothing happened is the one message that must not vanish before it is read.
    if (next.status === "opened") {
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
        settle({ status: "failed", reason: String(e) });
      }
    },
    [settle],
  );

  return { open, state };
}

/** The note to show beside a link control, or `""` when there is nothing to say. */
export function linkNote(state: LinkState): string {
  return state.status === "failed" ? state.reason : "";
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
