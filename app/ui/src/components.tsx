// Shared UI primitives. Kept in one file so every screen reuses the same controls rather than
// re-styling a checkbox each time they need one — a settings page assembled from ad-hoc inputs is
// how a dark theme ends up with four different shades of "off".
//
// Styling is Tailwind utilities on the elements themselves. The repeated strings below are the
// former CSS classes, kept as module constants so "the row layout" is still one edit, but they are
// now inert text a component opts into rather than a selector reaching across the app.
import { useEffect, useId, useRef, type ReactNode } from "react";
import { FiChevronDown, FiChevronLeft, FiChevronRight, FiInfo, FiSearch } from "react-icons/fi";

/** Joins class names, dropping the falsy branches of a conditional. */
export function cx(...parts: (string | false | null | undefined)[]): string {
  return parts.filter(Boolean).join(" ");
}

/** Tabular data and identifiers: monospace so digits and hashes line up column-wise. */
export const MONO = "font-mono text-[12px]";

/** The id of the desktop shell's single scrolling element. */
export const SCROLLPORT_ID = "nodera-scrollport";

/**
 * Put the desktop scrollport back to the top.
 *
 * The shell scrolls one element for every screen, so a view swapped in underneath keeps whatever
 * offset the last one left. Screen changes handle this with a `key` — a new node starts at zero —
 * but a tab change inside a screen keeps the same node deliberately, and has to say so. Without
 * this, opening a short Settings section after scrolling a long one showed the sticky tab bar above
 * empty space and read as a frozen page.
 */
export function resetScrollport(): void {
  document.getElementById(SCROLLPORT_ID)?.scrollTo({ top: 0 });
}

/** The world's initial in a gradient tile — the app's only avatar. Size is the caller's. */
export const AVATAR = "grid flex-none place-items-center rounded-sm bg-brand font-bold text-white";

/**
 * Auto-fitting tile grid shared by Home and the world State tab.
 *
 * `auto-fit`, not `auto-fill`. The difference only shows on a wide window and it is the whole
 * complaint: with four stats in a 1568px canvas, `auto-fill` lays out eight 190px tracks, fills
 * four and leaves the right half of the row visibly empty. `auto-fit` collapses the tracks nobody
 * is standing in and the four tiles share the width. The floor moves 190px → 210px to match the
 * one the world screen had already hand-written over this constant.
 */
export const STAT_GRID = "grid min-w-0 grid-cols-[repeat(auto-fit,minmax(210px,1fr))] gap-3";

/**
 * A wall of cards, as many across as the window has room for.
 *
 * The counterpart to `STAT_GRID` for things bigger than a figure. Screens that lay cards out with a
 * fixed `wide:col-span-6` get two columns at 1180px and the same two at 1920px; this one reflows,
 * which is how panels 03/04/06 of the reference spend horizontal space. The rule lives in
 * `styles.css` as `@utility card-grid` so the column floor is one edit.
 */
export const CARD_GRID = "card-grid";

/** Centered desktop canvas backed by twelve equal columns. */
export function PageGrid(props: { children: ReactNode; className?: string }) {
  return (
    <div className={cx("page-canvas page-grid py-8", props.className)}>
      {props.children}
    </div>
  );
}

/** Screen title and optional action row. */
export function PageHeader(props: {
  eyebrow?: string;
  title: string;
  description?: string;
  actions?: ReactNode;
  className?: string;
}) {
  return (
    <header className={cx("col-span-12 mb-2 flex items-end justify-between gap-6", props.className)}>
      <div className="min-w-0">
        {props.eyebrow && (
          <p className="mb-2 text-2xs font-medium tracking-[0.16em] text-brand-tint uppercase">
            {props.eyebrow}
          </p>
        )}
        <h1 className="display-type text-title font-bold text-text">{props.title}</h1>
        {props.description && <p className="mt-2 max-w-[72ch] text-sm text-dim">{props.description}</p>}
      </div>
      {props.actions && <div className="flex flex-none items-center gap-2">{props.actions}</div>}
    </header>
  );
}

/**
 * "These figures are the last known picture, not the current one."
 *
 * Shown at the top of a screen that renders worker-derived numbers whenever the link is down but a
 * previous snapshot exists. Keeping the last picture is right; failing to say it is the last
 * picture is the defect A-UX-1 retires. A screen that has never heard from the worker renders "—"
 * for every figure and has nothing to mark, so this only appears when `isStale(link)` is true.
 */
export function StaleDataNotice() {
  return (
    <div className="flex items-center gap-2 rounded-md border border-warn/35 bg-warn/10 px-3.5 py-2 text-xs text-warn">
      <FiInfo aria-hidden /> Showing the last known picture — the worker link is offline.
    </div>
  );
}

/* --------------------------------------------------------------------------------------- cards */

export function Card(props: { title?: string; hint?: string; right?: ReactNode; children: ReactNode }) {
  return (
    <section className="overflow-hidden rounded-md border border-line-soft bg-surface shadow-e1">
      {(props.title || props.right) && (
        <header className="flex items-start justify-between gap-3 border-b border-line-soft px-5 py-4">
          {/* `min-w-0` on the text, `flex-none` around the badge. Without the first the title block
              refuses to go below its own min-content and pushes the badge past the card's right
              edge — which is the sliced "not bundled" pill in the 1000px screenshot. */}
          <div className="min-w-0">
            {/* Panel 06's card title: an 11px uppercase micro-label, not a heading competing with
                the page's own. The subtitle under it is `--text-faint`, which is why it is only
                ever an elaboration — that tier may never be the sole carrier of meaning. */}
            {props.title && (
              <h2 className="text-2xs font-medium tracking-[0.16em] text-dim uppercase">{props.title}</h2>
            )}
            {props.hint && <p className="mt-1.5 max-w-[70ch] text-xs text-faint">{props.hint}</p>}
          </div>
          {props.right && <div className="flex flex-none items-center gap-2">{props.right}</div>}
        </header>
      )}
      <div className="px-5 pt-2 pb-3.5">{props.children}</div>
    </section>
  );
}

/* ------------------------------------------------------------------------------------- controls */

// One settings row: label + hint on the left, the control on the right. Below the narrow
// breakpoint the control drops under its label rather than squeezing the text to nothing.
const ROW =
  "flex items-center justify-between gap-5 border-b border-line-soft py-[11px] last:border-b-0 " +
  "max-narrow:flex-col max-narrow:items-start max-narrow:gap-2";
const ROW_TEXT = "flex min-w-0 flex-col";
const ROW_LABEL = "flex flex-wrap items-center gap-2";
const ROW_HINT = "max-w-[68ch] text-xs text-faint";
const FIELD =
  "min-w-0 rounded-sm border border-line bg-surface-2 px-2.5 py-[7px] focus:border-brand-1 focus:outline-none";

export function Toggle(props: {
  label: string;
  hint?: string;
  checked: boolean;
  disabled?: boolean;
  note?: ReactNode;
  onChange: (v: boolean) => void;
}) {
  return (
    <label className={cx(ROW, props.disabled && "opacity-50")}>
      <span className={ROW_TEXT}>
        <span className={ROW_LABEL}>
          {props.label}
          {props.note}
        </span>
        {props.hint && <span className={ROW_HINT}>{props.hint}</span>}
      </span>
      {/* A real checkbox under a transparent overlay, not a div pretending: it keeps the tab stop,
          space-to-toggle, and — the part no onFocus handler can honestly reproduce — the browser's
          own :focus-visible heuristic, which drives the ring below via peer-focus-visible. */}
      <span className="relative h-[22px] w-10 flex-none">
        <input
          type="checkbox"
          className="peer absolute inset-0 z-10 m-0 cursor-pointer opacity-0 disabled:cursor-default"
          checked={props.checked}
          disabled={props.disabled}
          onChange={(e) => props.onChange(e.target.checked)}
        />
        <span
          aria-hidden
          className={cx(
            "pointer-events-none absolute inset-0 rounded-full transition-colors duration-[180ms]",
            "peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-brand-3",
            props.checked ? "bg-brand-1" : "bg-line",
          )}
        />
        <span
          aria-hidden
          className={cx(
            "pointer-events-none absolute top-[3px] left-[3px] size-4 rounded-full bg-white transition-transform duration-[180ms]",
            props.checked && "translate-x-[18px]",
          )}
        />
      </span>
    </label>
  );
}

export function Segmented<T extends string>(props: {
  label: string;
  hint?: string;
  value: T;
  options: { value: T; label: string; icon?: ReactNode }[];
  onChange: (v: T) => void;
}) {
  return (
    <div className={ROW}>
      <span className={ROW_TEXT}>
        <span className={ROW_LABEL}>{props.label}</span>
        {props.hint && <span className={ROW_HINT}>{props.hint}</span>}
      </span>
      <div
        className="flex flex-none gap-0.5 rounded-sm border border-line bg-surface-2 p-[3px]"
        role="radiogroup"
        aria-label={props.label}
      >
        {props.options.map((o) => (
          <button
            key={o.value}
            role="radio"
            aria-checked={props.value === o.value}
            className={cx(
              "flex items-center gap-1.5 rounded-[6px] px-[11px] py-[5px] text-[12px]",
              props.value === o.value
                ? "bg-brand-soft font-medium text-brand-tint shadow-[inset_0_0_0_1px_var(--brand-edge)]"
                : "text-dim hover:text-text",
            )}
            onClick={() => props.onChange(o.value)}
          >
            {o.icon}
            {o.label}
          </button>
        ))}
      </div>
    </div>
  );
}

export function NumberField(props: {
  label: string;
  hint?: string;
  value: number;
  min?: number;
  max?: number;
  suffix?: string;
  note?: ReactNode;
  disabled?: boolean;
  onChange: (v: number) => void;
}) {
  return (
    <label className={cx(ROW, props.disabled && "opacity-50")}>
      <span className={ROW_TEXT}>
        <span className={ROW_LABEL}>
          {props.label}
          {props.note}
        </span>
        {props.hint && <span className={ROW_HINT}>{props.hint}</span>}
      </span>
      <span className="inline-flex flex-none items-center gap-1.5">
        <input
          className={cx(FIELD, "w-[110px] text-right font-mono")}
          type="number"
          value={props.value}
          min={props.min}
          max={props.max}
          disabled={props.disabled}
          onChange={(e) => props.onChange(Number(e.target.value))}
        />
        {props.suffix && <span className="text-[12px] text-faint">{props.suffix}</span>}
      </span>
    </label>
  );
}

export function TextField(props: {
  label: string;
  hint?: string;
  value: string;
  placeholder?: string;
  note?: ReactNode;
  onChange: (v: string) => void;
}) {
  return (
    <label className={ROW}>
      <span className={ROW_TEXT}>
        <span className={ROW_LABEL}>
          {props.label}
          {props.note}
        </span>
        {props.hint && <span className={ROW_HINT}>{props.hint}</span>}
      </span>
      <input
        className={FIELD}
        type="text"
        value={props.value}
        placeholder={props.placeholder}
        onChange={(e) => props.onChange(e.target.value)}
      />
    </label>
  );
}

export function Slider(props: {
  label: string;
  hint?: string;
  value: number;
  min: number;
  max: number;
  suffix?: string;
  note?: ReactNode;
  disabled?: boolean;
  onChange: (v: number) => void;
}) {
  return (
    <label className={cx(ROW, props.disabled && "opacity-50")}>
      <span className={ROW_TEXT}>
        <span className={ROW_LABEL}>
          {props.label}
          {props.note}
        </span>
        {props.hint && <span className={ROW_HINT}>{props.hint}</span>}
      </span>
      <span className="flex flex-none items-center gap-2.5">
        <input
          type="range"
          className="w-[190px] accent-brand-1"
          min={props.min}
          max={props.max}
          value={props.value}
          disabled={props.disabled}
          onChange={(e) => props.onChange(Number(e.target.value))}
        />
        <span className={cx(MONO, "min-w-[44px] text-right text-dim")}>
          {props.value}
          {props.suffix}
        </span>
      </span>
    </label>
  );
}

/**
 * A small badge next to a control's label, saying what is actually happening to it.
 *
 * Worth the visual noise: a limit that looks active and is not is a worse lie than an obviously
 * pending one, because the user will size their bandwidth around it.
 *
 * `tone` matters more than it looks. "Saved but nothing reads it yet" and "this can never work"
 * are different promises to a user — the first implies *coming soon*, and rendering a permanent
 * structural limitation in the same amber as a pending one quietly tells people to keep waiting
 * for something that is not coming. The caller decides; this only paints.
 */
// Named for what it paints rather than `TONE`: there is a second `TONE` in this file, forty-six
// lines further down, holding a different set of keys for `Stat`. Two module-scoped tables cannot
// both be `TONE`, so one of them was a `const` inside a component body shadowing the other — legal,
// invisible in review, and exactly the reading a person does not do twice.
const BADGE_TONE = {
  warn: "border-warn/40 bg-warn/12 text-warn",
  muted: "border-line bg-surface-2 text-faint",
  info: "border-down/40 bg-down/12 text-down",
} as const;

export function StatusBadge(props: {
  tone: keyof typeof BADGE_TONE;
  label: string;
  title: string;
}) {
  return (
    <span
      className={cx(
        "inline-flex cursor-help items-center gap-1 rounded-full px-1.5 py-px text-[10px] tracking-[0.02em]",
        BADGE_TONE[props.tone],
      )}
      title={props.title}
    >
      <FiInfo aria-hidden /> {props.label}
    </span>
  );
}

/* ---------------------------------------------------------------------------------- disclosure */

// Native <details>: keyboard-operable and expanded by find-in-page for free. The caret reads the
// element's own open state through `group-open:`, so React never mirrors it.
export function Disclosure(props: { title: string; children: ReactNode }) {
  return (
    <details className="group border-t border-line-soft pt-2 pb-0.5">
      <summary className="flex cursor-pointer list-none items-center gap-1.5 text-[12px] text-dim [&::-webkit-details-marker]:hidden">
        <FiChevronDown
          className="transition-transform duration-150 group-open:rotate-180"
          aria-hidden
        />
        {props.title}
      </summary>
      <div className="max-w-[72ch] pt-1 pb-1.5 pl-5 text-[12px] text-faint [&_p]:my-1">
        {props.children}
      </div>
    </details>
  );
}

/* ------------------------------------------------------------------------------- data displays */

const STAT_TONE = { up: "text-up", down: "text-down", warn: "text-warn" } as const;

export function Stat(props: {
  label: string;
  value: string;
  sub?: string;
  icon?: ReactNode;
  tone?: "up" | "down" | "warn";
}) {
  return (
    <div className="flex min-w-0 gap-3 rounded-md border border-line-soft bg-surface px-4 py-3.5 shadow-e1">
      {props.icon && (
        <span className="grid h-9 w-9 flex-none place-items-center rounded-md bg-brand-soft text-[15px] text-brand-tint">
          {props.icon}
        </span>
      )}
      <div className="min-w-0">
        <div className="text-2xs tracking-[0.16em] text-faint uppercase">{props.label}</div>
        <div
          className={cx(
            "mt-1 text-[20px] font-medium tabular-nums",
            props.tone && STAT_TONE[props.tone],
          )}
        >
          {props.value}
        </div>
        {props.sub && <div className="text-[11px] text-faint">{props.sub}</div>}
      </div>
    </div>
  );
}

export function KeyValue(props: { label: string; value: ReactNode; mono?: boolean; title?: string }) {
  return (
    <div className="grid grid-cols-[220px_1fr] gap-4 border-b border-line-soft py-2 last:border-b-0 max-narrow:grid-cols-1 max-narrow:gap-0.5">
      <dt className="text-dim">{props.label}</dt>
      <dd className={cx("[overflow-wrap:anywhere]", props.mono && MONO)} title={props.title}>
        {props.value}
      </dd>
    </div>
  );
}

export function Empty(props: { icon?: ReactNode; title: string; children?: ReactNode }) {
  return (
    <div className="flex flex-col items-center gap-3.5 px-5 py-12 text-center text-dim">
      {props.icon && (
        <span className="grid size-11 place-items-center rounded-full bg-brand-soft text-[20px] text-brand-tint">
          {props.icon}
        </span>
      )}
      <p className="text-lead font-medium text-text">{props.title}</p>
      {props.children && <p className="max-w-[42ch] text-sm">{props.children}</p>}
    </div>
  );
}

const PILL_TONE = {
  up: "text-up border-up/40",
  down: "text-danger border-danger/40",
  warn: "text-warn border-warn/40",
  muted: "text-faint border-line",
} as const;

export function Pill(props: { tone: "up" | "down" | "warn" | "muted"; children: ReactNode }) {
  return (
    <span
      className={cx(
        "inline-flex items-center gap-1.5 rounded-full border px-[9px] py-0.5 text-xs whitespace-nowrap",
        PILL_TONE[props.tone],
      )}
    >
      <span className="h-1.5 w-1.5 rounded-full bg-current" aria-hidden />
      {props.children}
    </span>
  );
}

/* ------------------------------------------------------------------------------------- tables */

// The row carries the separator, not the cell, so `last:border-b-0` can retire the rule that used
// to reach in as `tbody tr:last-child td`.
export function Tr(props: { children: ReactNode }) {
  return (
    <tr className="border-b border-line-soft last:border-b-0 hover:bg-surface-2">{props.children}</tr>
  );
}

export function Th(props: { children?: ReactNode; num?: boolean }) {
  return (
    <th
      className={cx(
        "border-b border-line-soft px-3 py-2.5 text-2xs tracking-[0.16em] text-faint uppercase whitespace-nowrap",
        props.num ? "text-right" : "text-left",
      )}
    >
      {props.children}
    </th>
  );
}

export function Td(props: { children?: ReactNode; num?: boolean; mono?: boolean; title?: string }) {
  return (
    <td
      className={cx(
        "px-3 py-2.5 whitespace-nowrap",
        props.num && "text-right font-mono tabular-nums",
        props.mono && MONO,
      )}
      title={props.title}
    >
      {props.children}
    </td>
  );
}

export function DataTable(props: { children: ReactNode; label?: string }) {
  return (
    <div className="overflow-x-auto rounded-md border border-line-soft bg-surface">
      <table className="w-full border-collapse text-sm" aria-label={props.label}>
        {props.children}
      </table>
    </div>
  );
}

export function FilterBar(props: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  actions?: ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-center gap-2 rounded-md border border-line-soft bg-surface p-2 shadow-e1">
      <label className="relative min-w-[220px] flex-1">
        <FiSearch aria-hidden className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-faint" />
        <input
          aria-label={props.label}
          value={props.value}
          onChange={(event) => props.onChange(event.target.value)}
          placeholder={props.placeholder}
          className="w-full rounded-sm border border-transparent bg-surface-2 py-2 pr-3 pl-9 text-sm outline-none focus:border-brand-1"
        />
      </label>
      {props.actions}
    </div>
  );
}

export function Pagination(props: {
  page: number;
  pageSize: number;
  total: number;
  onPage: (page: number) => void;
}) {
  const pages = Math.max(1, Math.ceil(props.total / props.pageSize));
  if (pages <= 1) return null;
  const page = Math.min(props.page, pages);
  return (
    <nav aria-label="Pagination" className="flex flex-wrap items-center justify-between gap-3 border-t border-line-soft pt-3 text-xs text-faint">
      <span>
        {(page - 1) * props.pageSize + 1}-{Math.min(page * props.pageSize, props.total)} of {props.total}
      </span>
      <span className="flex items-center gap-2">
        <Button variant="ghost" disabled={page <= 1} onClick={() => props.onPage(page - 1)} title="Previous page">
          <FiChevronLeft aria-hidden /> Previous
        </Button>
        <span className="font-mono text-dim">{page} / {pages}</span>
        <Button variant="ghost" disabled={page >= pages} onClick={() => props.onPage(page + 1)} title="Next page">
          Next <FiChevronRight aria-hidden />
        </Button>
      </span>
    </nav>
  );
}

/* ------------------------------------------------------------------------------------- buttons */

/**
 * The app's only button.
 *
 * There was none. The string
 * `"rounded-sm border border-line bg-surface-2 px-2.5 py-1 text-xs hover:bg-surface-hover …"`
 * was copy-pasted as a local `BUTTON` constant in six screens, `TrackerStores.tsx` carried a
 * seventh set of its own, and the consent modal's two buttons were the same 100-character literal
 * written twice — one of which named a hover colour that did not exist, so it had no hover state at
 * all and nobody noticed.
 *
 * `hero` is the size the Play button uses. It is a size rather than a variant because it is still
 * the same control; what makes it the loudest thing on the screen is `primary` plus the gradient.
 *
 * `shape` is the reference's real system, and it is not obvious from looking at the sheet: the
 * radius is chosen by the button's *role*, not by its size. An action inside a layout is a pill;
 * the one action a screen or dialog exists for is a full-width block; an action bound to an
 * adjacent input is inline and matches that input's radius. `block` therefore also takes the width,
 * because a block button that is not full-width is just a rectangle.
 */
export function Button(props: {
  children: ReactNode;
  onClick?: () => void;
  variant?: "primary" | "secondary" | "ghost" | "danger";
  size?: "sm" | "md" | "hero";
  shape?: "pill" | "block" | "inline";
  disabled?: boolean;
  title?: string;
  type?: "button" | "submit";
  className?: string;
}) {
  const variant = props.variant ?? "secondary";
  const size = props.size ?? "sm";
  const shape = props.shape ?? "inline";
  return (
    <button
      type={props.type ?? "button"}
      title={props.title}
      disabled={props.disabled}
      onClick={props.onClick}
      className={cx(
        "inline-flex flex-none items-center justify-center gap-2 rounded-sm font-medium",
        "transition-[background-color,border-color,box-shadow,transform] duration-[var(--motion-fast)]",
        // One ring, from one token. Every other control in the app had no visible focus state.
        "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus",
        "disabled:pointer-events-none disabled:opacity-50",
        size === "sm" && "px-2.5 py-1 text-xs",
        size === "md" && "px-3.5 py-2 text-sm",
        size === "hero" && "px-8 py-3.5 text-[15px] font-medium tracking-[0.08em] uppercase shadow-e2",
        shape === "pill" && "rounded-full",
        shape === "block" && "w-full",
        variant === "primary" && "bg-play text-on-play hover:brightness-110 active:brightness-95",
        variant === "secondary" && "border border-line bg-surface-2 hover:bg-surface-hover",
        variant === "ghost" && "text-dim hover:bg-surface-hover hover:text-text",
        variant === "danger" && "border border-danger/45 text-danger hover:bg-danger/10",
        props.className,
      )}
    >
      {props.children}
    </button>
  );
}

/* -------------------------------------------------------------------------------------- modals */

/**
 * The app's only dialog.
 *
 * There were four independent implementations — the store dialog, the LAN offer, the consent gate,
 * and the mobile sheet — and only one of them set `role="dialog"`, so three were invisible to a
 * screen reader. Each is folded into this: the good parts came from the store dialog (Escape, the
 * scrim as a real button, refusing to close while an action is in flight) and the two nobody had
 * written are here — a focus trap, and locking the page behind it.
 */
export function Modal(props: {
  title: string;
  children: ReactNode;
  footer?: ReactNode;
  /** Absent means this dialog cannot be dismissed — a gate, not a panel. */
  onClose?: () => void;
  /** While true, Escape and the scrim do nothing: an action is in flight and half-closing it is worse. */
  busy?: boolean;
  width?: "sm" | "md" | "lg";
}) {
  const panel = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const dismissable = Boolean(props.onClose) && !props.busy;

  useEffect(() => {
    // The page behind a dialog must not scroll under it. Restored on unmount rather than set to a
    // literal, because the shell's own `overflow: hidden` is what was there before.
    const previous = document.body.style.overflow;
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    document.body.style.overflow = "hidden";
    const first = panel.current?.querySelector<HTMLElement>(
      'button:not([disabled]), [href], input:not([disabled]), select, textarea, [tabindex]:not([tabindex="-1"])',
    );
    (first ?? panel.current)?.focus();
    return () => {
      document.body.style.overflow = previous;
      previousFocus?.focus();
    };
  }, []);

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape" && dismissable) {
        props.onClose?.();
        return;
      }
      if (event.key !== "Tab" || !panel.current) return;
      // The trap. Without it, Tab walks out of the dialog and into the page behind it, which is
      // still rendered — a keyboard user ends up operating controls they cannot see.
      const focusable = panel.current.querySelectorAll<HTMLElement>(
        'button:not([disabled]), [href], input:not([disabled]), select, textarea, [tabindex]:not([tabindex="-1"])',
      );
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const activeInside = Array.from(focusable).includes(document.activeElement as HTMLElement);
      if (event.shiftKey && (!activeInside || document.activeElement === first)) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && (!activeInside || document.activeElement === last)) {
        event.preventDefault();
        first.focus();
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [dismissable, props.onClose]);

  return (
    <div className="fixed inset-0 z-50 grid place-items-center p-4 pb-[7vh]">
      <button
        type="button"
        aria-label="Close"
        tabIndex={-1}
        disabled={!dismissable}
        onClick={() => props.onClose?.()}
        className="absolute inset-0 cursor-default bg-scrim backdrop-blur-[8px] disabled:cursor-default"
      />
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className={cx(
          // Panel 07 measures an 8px radius on the dialog against 12–14 on the cards behind it:
          // the modal is tighter than a card, not looser, and its elevation is the scrim rather
          // than the shadow.
          "relative flex max-h-[calc(100vh-2rem)] w-full flex-col overflow-hidden rounded-sm border border-line bg-surface shadow-e3 outline-none",
          props.width === "lg" ? "max-w-3xl" : props.width === "md" ? "max-w-xl" : "max-w-[560px]",
        )}
      >
        <header className="px-6 pt-5 pb-1">
          <h2 id={titleId} className="text-lead font-medium">{props.title}</h2>
        </header>
        <div className="min-h-0 overflow-y-auto px-6 py-4">{props.children}</div>
        {props.footer && (
          <footer className="flex justify-end gap-2 px-6 pt-1 pb-5">
            {props.footer}
          </footer>
        )}
      </div>
    </div>
  );
}

/* ---------------------------------------------------------------------------------------- tabs */

/**
 * An underline tab strip.
 *
 * The world detail screen and the settings screen each rolled their own, with different padding,
 * different active colours, and — in one of them — no `role="tab"` at all.
 *
 * A strip of tabs is the app's canonical piece of *irreducibly wide* content: eight labels that
 * cannot wrap and must not be abbreviated. So it carries the horizontal boundary itself —
 * `min-w-0` so it is allowed to be narrower than its own contents, `overflow-x-auto` so what is
 * left over is scrolled here rather than pushed through the page — and each tab is `shrink-0` so
 * the strip crowds instead of squeezing every label into two lines. Without the pair, the last tab
 * is simply gone: the 1000px screenshot has no "Diagnostics" in it.
 */
export function Tabs<T extends string>(props: {
  tabs: readonly { id: T; label: string; icon?: ReactNode }[];
  active: T;
  onSelect: (id: T) => void;
  className?: string;
}) {
  return (
    <div
      role="tablist"
      className={cx("flex min-w-0 gap-1 overflow-x-auto border-b border-line-soft", props.className)}
    >
      {props.tabs.map((tab) => (
        <button
          key={tab.id}
          role="tab"
          aria-selected={tab.id === props.active}
          onClick={() => props.onSelect(tab.id)}
          className={cx(
            "-mb-px flex h-9 shrink-0 items-center border-b-2 px-3.5 text-body whitespace-nowrap",
            "transition-colors duration-[var(--motion-fast)]",
            "focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus",
            tab.id === props.active
              ? "border-brand-1 font-medium text-text"
              : "border-transparent text-dim hover:text-text",
          )}
        >
          {tab.icon && <span aria-hidden className="mr-2 flex">{tab.icon}</span>}
          {tab.label}
        </button>
      ))}
    </div>
  );
}

/* -------------------------------------------------------------------------------------- select */

/** A native select, themed. Needed by the hero's world picker; nothing like it existed. */
export function Select<T extends string>(props: {
  value: T;
  options: readonly { value: T; label: string }[];
  onChange: (value: T) => void;
  ariaLabel: string;
  className?: string;
}) {
  return (
    <select
      aria-label={props.ariaLabel}
      value={props.value}
      onChange={(e) => props.onChange(e.target.value as T)}
      className={cx(
        "min-w-0 max-w-full appearance-none rounded-sm border border-line bg-surface-2 px-3 py-2 text-sm focus:border-brand-1",
        "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus",
        props.className,
      )}
    >
      {props.options.map((option) => (
        <option key={option.value} value={option.value}>
          {option.label}
        </option>
      ))}
    </select>
  );
}

/* -------------------------------------------------------------------------------------- banner */

/**
 * A line of state above a screen: something is wrong, or something is pending.
 *
 * `StaleDataNotice` is this with one tone and one sentence, and stays a named export because the
 * honesty test asserts on it by name — the notice is a rule, not a style.
 */
export function Banner(props: {
  tone: "warn" | "danger" | "info";
  children: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div
      className={cx(
        "flex items-center justify-between gap-3 rounded-md border px-3.5 py-2 text-xs",
        props.tone === "warn" && "border-warn/35 bg-warn/10 text-warn",
        props.tone === "danger" && "border-danger/35 bg-danger/10 text-danger",
        props.tone === "info" && "border-brand-3/35 bg-brand-3/10 text-brand-3",
      )}
    >
      {/* `min-w-0` on the sentence, `flex-none` on the action: the restart banner's "Restart worker"
          button is what fell off the right edge of the 1600px and 1000px screenshots, because the
          sentence beside it would not agree to be narrower than itself. */}
      <span className="flex min-w-0 items-center gap-2">
        <FiInfo aria-hidden className="flex-none" />
        {props.children}
      </span>
      {props.action && <span className="flex flex-none items-center gap-2">{props.action}</span>}
    </div>
  );
}

/* --------------------------------------------------------------------------------- generated art */

/**
 * A world's picture, derived from its id.
 *
 * Two hues and an angle out of an FNV-1a hash. Deterministic on purpose: the same world looks the
 * same on every machine and in every session, which is what makes the art a way to *recognise* a
 * world in a grid rather than decoration.
 *
 * Returned as inline custom properties, which the `.world-art` class in `styles.css` reads. The
 * split matters: a remote image is forbidden by this app's CSP, an inline `style` attribute is not,
 * and keeping the gradients in a class is what lets the token test prove they reach the shipped CSS.
 */
export function worldArt(worldId: string): React.CSSProperties {
  let hash = 0x811c9dc5;
  for (let i = 0; i < worldId.length; i += 1) {
    hash ^= worldId.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  // World identity varies composition and hue inside launcher palette, never into neon rainbow.
  const h1 = 88 + (hash % 42);
  const h2 = 188 + (hash % 28);
  return {
    ["--wa-h1" as string]: String(h1),
    ["--wa-h2" as string]: String(h2),
    ["--wa-a" as string]: `${(hash % 8) * 22.5}deg`,
  };
}
