// The left navigation rail.
//
// # Why the navigation moved out of the top bar
//
// The old `TopNav` was a 64px strip carrying the wordmark, three destinations and a right-hand
// cluster of node facts — link fault, node id, gateway badge, transfer rates. That cluster was
// `hidden … narrow:flex`, so **below 900px the app showed no node identity at all**: the one thing
// a peer-to-peer launcher should never be shy about. A rail has a bottom, so the identity block
// has somewhere to live at every width, and the facts move into a popover anchored to it rather
// than competing with the destinations for horizontal space.
//
// # The active state carries three signals, not one
//
// The reference marks the current destination by tinting its glyph and nothing else. At 44px in a
// 72px rail that is one violet glyph among five, and it is invisible to anyone who cannot separate
// violet from grey by hue. So the active item carries `aria-current="page"` (assistive tech), a
// 3px bar flush to the rail's left edge (survives monochrome and colour-blindness), and the tint
// plus a `--brand-soft` fill (the reference's own signal). Any one of them can be lost without the
// user losing their place.
//
// # Zero is not the same as not-yet-known
//
// Every figure in the identity block goes through `show()`, and the health dot has **four** states,
// not three. A grey dot that means "we have not heard from the worker yet" and a red dot that means
// "we heard, and it is broken" are different facts about the world, and a launcher that renders the
// first as the second is lying about its own network.
import { useEffect, useRef, useState, type ReactNode } from "react";
import { FiChevronRight } from "react-icons/fi";
import { cx, MONO } from "./components";
import {
  formatAge,
  formatRate,
  isStale,
  linkFault,
  shortId,
  show,
  type Dashboard,
} from "./api";

/** One rail item. Structural on purpose, so `DESTINATIONS` can stay exactly as the shell wrote it. */
export interface RailEntry {
  readonly name: string;
  readonly label: string;
  readonly icon: ReactNode;
}

/**
 * The 3px "you are here" bar, flush to the rail's left edge.
 *
 * Named rather than inlined so a later tidy-up cannot quietly drop it: it is the only part of the
 * active state that survives a monochrome rendering, and `shell-navigation.test.mjs` pins it by
 * this name.
 */
const ACTIVE_MARK =
  "before:absolute before:top-1/2 before:left-0 before:h-5 before:w-[3px] before:-translate-y-1/2 " +
  "before:rounded-full before:bg-brand-1 before:content-['']";

const ITEM =
  "relative flex w-full items-center gap-3 rounded-md px-3 text-[13px] " +
  "transition-[color,background-color] duration-[var(--motion-fast)] " +
  "focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus " +
  "max-narrow:justify-center narrow:max-wide:justify-center";

const GLYPH = "grid flex-none place-items-center text-[18px]";

/** The health of the worker link, as a colour and a sentence that mean the same thing. */
function health(d: Dashboard): { dot: string; text: string; title: string } {
  const fault = linkFault(d.link);
  // Never heard from the worker. This is *not* a fault — there is nothing to be wrong yet — and
  // painting it red would claim knowledge the app does not have.
  if (!d.link.has_data) {
    return { dot: "bg-faint", text: "Not heard from yet", title: fault || "Not heard from yet" };
  }
  if (fault) return { dot: "bg-danger", text: fault, title: d.link.last_error || fault };
  if (isStale(d.link)) return { dot: "bg-warn", text: "Last known picture", title: d.link.last_error };
  return { dot: "bg-up", text: "Live", title: `worker ${d.link.worker_version}` };
}

export function Rail(props: {
  d: Dashboard;
  active: string;
  destinations: readonly RailEntry[];
  utilities: readonly RailEntry[];
  onSelect: (name: string) => void;
}) {
  const { d } = props;
  const [open, setOpen] = useState(false);
  const link = health(d);
  const now = useNow(d.link.has_data);
  const age = d.link.last_update_ms ? Math.max(0, now - d.link.last_update_ms) : 0;

  const item = (entry: RailEntry) => {
    const active = props.active === entry.name;
    return (
      <button
        key={entry.name}
        aria-current={active ? "page" : undefined}
        aria-label={entry.label}
        title={entry.label}
        onClick={() => props.onSelect(entry.name)}
        className={cx(
          ITEM,
          "h-[var(--rail-item)]",
          active
            ? cx("bg-brand-soft font-medium text-brand-tint", ACTIVE_MARK)
            : "text-dim hover:bg-surface-hover hover:text-text",
        )}
      >
        <span className={GLYPH} aria-hidden>
          {entry.icon}
        </span>
        <span className="hidden truncate wide:inline">{entry.label}</span>
      </button>
    );
  };

  return (
    <nav
      aria-label="Sections"
      className={cx(
        "relative z-30 flex h-full flex-none flex-col gap-1 border-r border-line-soft bg-rail px-2 py-4",
        "w-16 narrow:w-[var(--rail-w)] wide:w-[var(--rail-w-wide)]",
      )}
    >
      <button
        onClick={() => props.onSelect("play")}
        aria-label="NoderaMC"
        title="NoderaMC"
        className={cx(ITEM, "mb-3 h-[var(--rail-item)] text-text")}
      >
        <span aria-hidden className="grid size-7 flex-none rotate-45 place-items-center border border-brand-edge bg-brand-soft shadow-glow">
          <span className="size-2 bg-brand-1" />
        </span>
        <span className="display-type hidden text-sm font-medium tracking-tight wide:inline">
          NODERA
        </span>
      </button>

      {/* The identity block. The reference's account chip, re-aimed: this app has no account, it
          has a node, and the node's picture is the same deterministic hash the worlds use. */}
      <div className="relative mb-3">
        <button
          onClick={() => setOpen((was) => !was)}
          aria-label="Your node"
          title={link.title || "Your node"}
          aria-expanded={open}
          className={cx(ITEM, "h-[var(--rail-item)] text-dim hover:bg-surface-hover hover:text-text")}
        >
          <span className="relative flex-none" aria-hidden>
            <span className="grid size-7 place-items-center rounded-full border border-line bg-surface-2 text-[11px] font-bold text-brand-tint">
              {(d.node.node_id || "?").slice(0, 1).toUpperCase()}
            </span>
            <span className={cx("absolute -right-0.5 -bottom-0.5 size-2.5 rounded-full border border-rail", link.dot)} />
          </span>
          <span className="hidden min-w-0 flex-1 flex-col items-start wide:flex">
            <span className={cx(MONO, "truncate text-[11px] text-text")}>
              {d.link.has_data ? shortId(d.node.node_id, 6, 4) : "—"}
            </span>
            <span className="truncate text-[10px] text-faint">{link.text}</span>
          </span>
          <FiChevronRight aria-hidden className="hidden flex-none text-faint wide:block" />
        </button>
        {open && <IdentityPopover d={d} age={age} link={link} onClose={() => setOpen(false)} />}
      </div>

      {props.destinations.map(item)}

      {/* Utilities are not peers of the destinations: they are the drawers everything else was
          folded into. Pinned to the bottom behind a hairline, which is what the reference does with
          its own single bottom icon. */}
      <div className="mt-auto flex flex-col gap-1 border-t border-line-soft pt-3">
        {props.utilities.map(item)}
      </div>
    </nav>
  );
}

/**
 * The node facts, in a popover instead of a strip.
 *
 * This is the whole of the old top bar's right-hand cluster. It used to disappear below 900px;
 * anchored to the rail it is reachable at every width the window can be.
 */
function IdentityPopover(props: {
  d: Dashboard;
  age: number;
  link: { dot: string; text: string; title: string };
  onClose: () => void;
}) {
  const { d } = props;
  const box = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function away(event: MouseEvent) {
      if (!box.current?.contains(event.target as Node)) props.onClose();
    }
    function key(event: KeyboardEvent) {
      if (event.key === "Escape") props.onClose();
    }
    // Deferred a tick: the click that opened this popover is still propagating.
    const timer = window.setTimeout(() => document.addEventListener("mousedown", away), 0);
    window.addEventListener("keydown", key);
    return () => {
      window.clearTimeout(timer);
      document.removeEventListener("mousedown", away);
      window.removeEventListener("keydown", key);
    };
  }, [props.onClose]);

  return (
    <div
      ref={box}
      className="absolute top-full left-0 z-40 mt-2 w-[268px] rounded-md border border-line bg-surface p-3 text-xs shadow-e3"
    >
      <div className="flex items-center gap-2">
        <span className={cx("size-2 flex-none rounded-full", props.link.dot)} aria-hidden />
        <span className="text-text">{props.link.text}</span>
      </div>
      <p className="mt-1 text-[11px] text-faint">
        {d.link.has_data
          ? `updated ${formatAge(props.age)}`
          : "This app has not received a picture from your peer yet."}
      </p>

      <dl className="mt-3 flex flex-col gap-1.5">
        <div className="flex items-baseline justify-between gap-3">
          <dt className="text-faint">Node</dt>
          <dd className={cx(MONO, "truncate text-[11px] text-text")} title={d.node.node_id}>
            {d.link.has_data ? shortId(d.node.node_id, 6, 4) : "—"}
          </dd>
        </div>
        <div className="flex items-baseline justify-between gap-3">
          <dt className="text-faint">Upload</dt>
          <dd className="font-mono tabular-nums text-up">
            ▲ {show(d.traffic.up_bytes_per_sec, formatRate)}
          </dd>
        </div>
        <div className="flex items-baseline justify-between gap-3">
          <dt className="text-faint">Download</dt>
          <dd className="font-mono tabular-nums text-down">
            ▼ {show(d.traffic.down_bytes_per_sec, formatRate)}
          </dd>
        </div>
      </dl>

      {d.node.is_gateway && (
        <span className="mt-3 inline-flex rounded-full border border-brand-edge bg-brand-soft px-[7px] py-0.5 text-[10px] tracking-[0.08em] text-brand-tint">
          GATEWAY
        </span>
      )}
    </div>
  );
}

/** A one-second clock, used only where the passage of time is itself the information. */
function useNow(active: boolean): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [active]);
  return now;
}
