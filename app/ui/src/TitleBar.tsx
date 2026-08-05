// The window's own chrome: the bar the app draws instead of the one the desktop drew for it.
//
// # Why the app draws it
//
// With `decorations: true` the minimise / maximise / close buttons come from the window manager, so
// they wear the operating system's palette, its corner radius and its hover states — three signals
// that say "this strip is not part of the program underneath it". The launcher is a dark, brand-led
// surface with a rail running its full height; a GNOME headerbar or a Windows caption bar sitting on
// top of that reads as a frame somebody put the app inside. `tauri.conf.json` therefore says
// `decorations: false`, and everything the frame used to provide has to be provided here: the drag
// region, the three buttons, the double-click-to-maximise gesture, and — the one that is easy to
// forget until the window cannot be made bigger — the eight resize grips.
//
// # Why it talks to the window through `invoke` and not `getCurrentWindow()`
//
// `@tauri-apps/api/window` would be the obvious import and it is the one thing this file may not
// do. Exactly one module in this repository imports `@tauri-apps` — the platform seam's Tauri host
// — because the same components are built a second time for the website, where that import is a
// native bridge shipped to a browser that has no internals for it to read: not a build error, not a
// warning, just a blank page on first render. `platform-seam.test.mjs` fails the build if a second
// importer appears.
//
// So the calls go through the seam's `invoke`, addressed to the same IPC commands
// `getCurrentWindow()` would have called. That is not a workaround with a cost: it is what makes the
// grants auditable. Each command name maps one-for-one onto the permission that admits it
// (`plugin:window|start_resize_dragging` ⇄ `core:window:allow-start-resize-dragging`), and
// `window-chrome.test.mjs` walks this file's command table against `capabilities/desktop.json` — so
// a fourth button added without its grant fails a test instead of failing silently at runtime, in
// front of a user, as a button that does nothing at all.
//
// # Why it renders nothing off the desktop
//
// Two other hosts run these components. The website has no window to control, and Android draws no
// window at all — it has a system status bar and a back gesture, and a close button in a phone app
// is a control that lies. Both are refused before a pixel: the seam reports which host built the
// bundle, and `is_mobile_build` — compiled in, not measured from the viewport — answers the second.
// Until that answer arrives the bar renders nothing, because a chrome that appears and then vanishes
// on a phone is worse than one that arrives a frame late on a desktop.
import { useCallback, useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { FiCopy, FiMinus, FiSquare, FiX } from "react-icons/fi";
import { invoke, platform } from "nodera-ui";
import { cx } from "./components";
import { fetchIsMobileBuild } from "./ipc";

/**
 * The window this bar drives, by label.
 *
 * `main` is not a name this file chose — it is the label in `tauri.conf.json`, the label both
 * capability files scope their grants to (`"windows": ["main"]`), and the label the deep-link plugin
 * routes `nodera://…` to. Four statements about one window, and they only work while they agree, so
 * `window-chrome.test.mjs` asserts the four spellings against each other rather than trusting this
 * constant.
 */
export const WINDOW_LABEL = "main";

/**
 * The IPC commands the bar sends.
 *
 * Spelled as literals in one table on purpose. These strings are the entire trust surface of the
 * window chrome: every one of them is admitted by exactly one entry in `capabilities/desktop.json`,
 * a missing entry fails at **runtime** rather than at build time, and a button that silently does
 * nothing is indistinguishable from a frozen application. Keeping the names in a table is what lets
 * a test read them out of the source and check the grant list against them.
 */
const WINDOW = {
  minimize: "plugin:window|minimize",
  toggleMaximize: "plugin:window|toggle_maximize",
  /** Tauri's own name for "a double-click on the drag region asked for this". */
  internalToggleMaximize: "plugin:window|internal_toggle_maximize",
  close: "plugin:window|close",
  startDragging: "plugin:window|start_dragging",
  startResizeDragging: "plugin:window|start_resize_dragging",
  isMaximized: "plugin:window|is_maximized",
} as const;

/** Send one window command. Rejections are the caller's to swallow; see `fire` below. */
function windowCommand<T = void>(command: string, args: Record<string, unknown> = {}): Promise<T> {
  return invoke<T>(command, { label: WINDOW_LABEL, ...args });
}

/**
 * A window command whose failure the user cannot act on.
 *
 * Deliberately swallowed rather than surfaced, and this is the one place in the app where that is
 * the honest choice: there is no interface left to report into if the close button cannot close, and
 * a toast reading "start_dragging was refused" describes a packaging mistake to somebody who did not
 * make it. The test suite is where a missing grant is meant to be caught, which is why one exists.
 */
function fire(command: string, args?: Record<string, unknown>): void {
  windowCommand(command, args).catch(() => {});
}

/**
 * The eight edges, and why a decoration-less window needs them spelled out.
 *
 * A window manager gives an undecorated window no resize border, so an app that draws its own
 * chrome has to grow its own frame. Each grip is a four-pixel strip — twelve at the corners, so a
 * diagonal drag has something to hit — that hands the direction back to the compositor through
 * `start_resize_dragging`, which is the same call `getCurrentWindow().startResizeDragging()` makes.
 *
 * tao does carry a fallback of its own: a `button_press_event` handler on the GTK toplevel that
 * hit-tests a five-pixel border and begins the drag itself. It is not a reason to delete these.
 * It only runs when the press reaches the toplevel handler rather than being consumed by the
 * webview, and it is gated on `!window.is_maximized()`, which is permanently true on at least one
 * compositor this app is used on — see `ResizeGrips` for what that gate did to the grips when they
 * shared it.
 *
 * The corners are listed last because they overlap the edges and equal `z-index` resolves in
 * document order — the later element wins, which is the behaviour a corner needs.
 */
const GRIPS = [
  { direction: "North", grab: "top-0 right-0 left-0 h-[4px] cursor-n-resize" },
  { direction: "South", grab: "right-0 bottom-0 left-0 h-[4px] cursor-s-resize" },
  { direction: "West", grab: "top-0 bottom-0 left-0 w-[4px] cursor-w-resize" },
  { direction: "East", grab: "top-0 right-0 bottom-0 w-[4px] cursor-e-resize" },
  { direction: "NorthWest", grab: "top-0 left-0 size-[12px] cursor-nw-resize" },
  { direction: "NorthEast", grab: "top-0 right-0 size-[12px] cursor-ne-resize" },
  { direction: "SouthWest", grab: "bottom-0 left-0 size-[12px] cursor-sw-resize" },
  { direction: "SouthEast", grab: "right-0 bottom-0 size-[12px] cursor-se-resize" },
] as const;

const ACTION =
  "grid size-8 flex-none place-items-center rounded-md text-[13px] text-faint " +
  "transition-[color,background-color] duration-[var(--motion-fast)] " +
  "focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus";

/**
 * The caption strip: a drag region across the top of the content column, and three buttons.
 *
 * It lives in the content column rather than across the whole window so the rail keeps running to
 * the top edge — the reference panels put the wordmark at the very top of the rail and the window
 * buttons at the top right of the page beside it, which is the arrangement that reads as one
 * surface rather than as an app with a lid on it.
 */
export function TitleBar() {
  // `undefined` is "we have not established whether this host has a window", and it renders
  // nothing. The three states are deliberate: a boolean here would have to guess, and both guesses
  // are wrong somewhere — `true` flashes a close button onto a phone, `false` leaves a desktop
  // window with no way to close it if the probe is slow.
  const [chrome, setChrome] = useState<boolean | undefined>(undefined);
  const [maximized, setMaximized] = useState(false);

  useEffect(() => {
    if (platform.id !== "tauri") {
      // The website. There is no window, and the seam would reject every command by design.
      setChrome(false);
      return;
    }
    let alive = true;
    fetchIsMobileBuild()
      .then((mobile) => {
        if (alive) setChrome(!mobile);
      })
      // A host that cannot answer is a desktop host older than the question. Showing the chrome is
      // the recoverable failure; hiding it strands the window with no close button.
      .catch(() => {
        if (alive) setChrome(true);
      });
    return () => {
      alive = false;
    };
  }, []);

  // Which glyph the middle button wears is a fact about the window, so it is asked rather than
  // remembered: the window manager can maximise a window without this app pressing anything —
  // a keyboard shortcut, a tiling rule, a double-click on a snap zone. The DOM resize event fires
  // for every one of those, and re-asking is cheaper than tracking them.
  //
  // It is the only source there is, and on a tiling compositor it is not always right: Hyprland
  // reports `is_maximized() == true` for an ordinary floating window, so the glyph reads Restore
  // when nothing has been maximised. That is worth knowing and not worth guessing around — the
  // button still toggles correctly, and inventing a second opinion about the window's state would
  // be this app claiming to know better than the window system. What must NOT depend on it is
  // anything the user cannot recover from; `ResizeGrips` says why.
  const sync = useCallback(() => {
    windowCommand<boolean>(WINDOW.isMaximized)
      .then(setMaximized)
      .catch(() => {});
  }, []);

  useEffect(() => {
    if (!chrome) return;
    sync();
    window.addEventListener("resize", sync);
    return () => window.removeEventListener("resize", sync);
  }, [chrome, sync]);

  if (!chrome) return null;

  return (
    <>
      <div
        // The bar carries no background and no border. It is the top of the page, not a strip laid
        // over it; the only thing that marks it out is that the three glyphs live there.
        className="flex h-9 flex-none items-center justify-end gap-1 px-2 select-none"
        onMouseDown={(event) => {
          if (event.button !== 0) return;
          // A button is not the bar. Without this the press that closes the window also starts a
          // window drag, and on a slow compositor the click never lands.
          if ((event.target as HTMLElement).closest("button")) return;
          // The second press of a double-click, answered the way Tauri's own drag script answers
          // it. Starting a drag on the first press means the pair never arrives as a `dblclick`,
          // so the gesture has to be recognised here or the bar loses double-click-to-maximise.
          if (event.detail === 2) {
            fire(WINDOW.internalToggleMaximize);
            return;
          }
          fire(WINDOW.startDragging);
        }}
      >
        <button
          type="button"
          aria-label="Minimise"
          title="Minimise"
          onClick={() => fire(WINDOW.minimize)}
          className={cx(ACTION, "hover:bg-surface-hover hover:text-text")}
        >
          <FiMinus aria-hidden />
        </button>
        <button
          type="button"
          aria-label={maximized ? "Restore" : "Maximise"}
          title={maximized ? "Restore" : "Maximise"}
          onClick={() => {
            fire(WINDOW.toggleMaximize);
            // The DOM resize event settles this too, but only once the compositor has finished.
            // Asking again on the next frame is what stops the glyph lagging a whole animation
            // behind the window it describes.
            window.requestAnimationFrame(sync);
          }}
          className={cx(ACTION, "hover:bg-surface-hover hover:text-text")}
        >
          {maximized ? <FiCopy aria-hidden /> : <FiSquare aria-hidden />}
        </button>
        <button
          type="button"
          aria-label="Close"
          title="Close"
          onClick={() => fire(WINDOW.close)}
          // The only destructive control in the chrome, and the only one that changes colour rather
          // than shade on hover. Everything else in this app reserves `--danger` for exactly that.
          className={cx(ACTION, "hover:bg-danger hover:text-white")}
        >
          <FiX aria-hidden />
        </button>
      </div>
      <ResizeGrips />
    </>
  );
}

/**
 * The window frame, as eight transparent strips.
 *
 * Portalled to `document.body` for the reason the escape hatch is: `position: fixed` is only
 * viewport-relative while no ancestor establishes a containing block, and a downloaded appearance
 * that sets `transform` or `filter` anywhere up the tree would silently reposition the whole frame
 * onto some inner element. A sibling of `#root` has only `html` and `body` above it, and the guard
 * layer pins their `transform` to `none`.
 *
 * # Always present, never gated on `isMaximized()`
 *
 * These used to be withdrawn while the window reported itself maximised — there is no edge to drag
 * on a maximised window, and a strip that answers the press by unmaximising is a frame that moves
 * the moment it is touched. That reasoning is sound and the condition it rests on is not: it
 * assumes every window system reserves "maximised" for a window the user maximised.
 *
 * Hyprland does not. It hands its windows the maximised state as a matter of course — a plain
 * floating 1100×720 window on an empty workspace reports `is_maximized() == true` — so the gate was
 * permanently closed, every grip was permanently absent, and the window could not be resized by
 * mouse at all. Which is precisely the failure the grips exist to prevent, arrived at through the
 * code that was meant to prevent it.
 *
 * So the frame is unconditional. Being wrong in the other direction costs nothing: a press on the
 * edge of a genuinely maximised window is a `begin_resize_drag` the compositor declines, which is
 * the same nothing the user would have got from a strip that was not there. tao's own built-in edge
 * hit-test carries the identical `!window.is_maximized()` condition and is dead here for the same
 * reason, which is worth knowing before anyone concludes this file is duplicating it.
 */
function ResizeGrips() {
  return createPortal(
    <>
      {GRIPS.map((grip) => (
        <div
          key={grip.direction}
          aria-hidden
          // Above the modal layer (`z-50`): a dialog is a reason to stop using the app for a
          // moment, never a reason to be unable to resize the window it is in.
          className={cx("fixed z-[60]", grip.grab)}
          onMouseDown={(event) => {
            if (event.button !== 0) return;
            event.preventDefault();
            fire(WINDOW.startResizeDragging, { value: grip.direction });
          }}
        />
      ))}
    </>,
    document.body,
  );
}
