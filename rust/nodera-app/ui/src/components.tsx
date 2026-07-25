// Shared UI primitives. Kept in one file so every screen reuses the same controls rather than
// re-styling a checkbox each time they need one — a settings page assembled from ad-hoc inputs is
// how a dark theme ends up with four different shades of "off".
//
// Styling is Tailwind utilities on the elements themselves. The repeated strings below are the
// former CSS classes, kept as module constants so "the row layout" is still one edit, but they are
// now inert text a component opts into rather than a selector reaching across the app.
import type { ReactNode } from "react";
import { FiChevronDown, FiInfo } from "react-icons/fi";

/** Joins class names, dropping the falsy branches of a conditional. */
export function cx(...parts: (string | false | null | undefined)[]): string {
  return parts.filter(Boolean).join(" ");
}

/** Tabular data and identifiers: monospace so digits and hashes line up column-wise. */
export const MONO = "font-mono text-[12px]";

/** The world's initial in a gradient tile — the app's only avatar. Size is the caller's. */
export const AVATAR = "grid flex-none place-items-center rounded-sm bg-brand font-bold text-white";

/** Auto-filling tile grid shared by Home and the world State tab. */
export const STAT_GRID = "grid grid-cols-[repeat(auto-fill,minmax(190px,1fr))] gap-3";

/* --------------------------------------------------------------------------------------- cards */

export function Card(props: { title?: string; hint?: string; right?: ReactNode; children: ReactNode }) {
  return (
    <section className="overflow-hidden rounded-md border border-line bg-surface">
      {(props.title || props.right) && (
        <header className="flex items-start justify-between gap-3 border-b border-line-soft px-4 py-[13px]">
          <div>
            {props.title && <h2 className="text-sm font-semibold">{props.title}</h2>}
            {props.hint && <p className="mt-[3px] max-w-[70ch] text-xs text-faint">{props.hint}</p>}
          </div>
          {props.right}
        </header>
      )}
      <div className="px-4 pt-1.5 pb-3">{props.children}</div>
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
  "min-w-0 rounded-sm border border-line bg-surface-2 px-2.5 py-[7px] focus:border-brand-2 focus:outline-none";

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
            props.checked ? "bg-brand-2" : "bg-line",
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
                ? "bg-surface-hover text-text shadow-[inset_0_0_0_1px_var(--line)]"
                : "text-dim",
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
          className="w-[190px] accent-brand-2"
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
 * Marks a control the app stores but nothing yet applies.
 *
 * Worth the visual noise: a limit that looks active and is not is a worse lie than an obviously
 * pending one, because the user will size their bandwidth around it.
 */
/**
 * A small badge next to a control's label, saying what is actually happening to it.
 *
 * `tone` matters more than it looks. "Saved but nothing reads it yet" and "this can never work"
 * are different promises to a user — the first implies *coming soon*, and rendering a permanent
 * structural limitation in the same amber as a pending one quietly tells people to keep waiting
 * for something that is not coming. The caller decides; this only paints.
 */
export function StatusBadge(props: {
  tone: "warn" | "muted" | "info";
  label: string;
  title: string;
}) {
  const TONE = {
    warn: "border-warn/40 bg-warn/12 text-warn",
    muted: "border-line bg-surface-2 text-faint",
    info: "border-down/40 bg-down/12 text-down",
  } as const;
  return (
    <span
      className={cx(
        "inline-flex cursor-help items-center gap-1 rounded-full px-1.5 py-px text-[10px] tracking-[0.02em]",
        TONE[props.tone],
      )}
      title={props.title}
    >
      <FiInfo aria-hidden /> {props.label}
    </span>
  );
}

/** Back-compat shim: the plain "not enforced yet" badge, unchanged. */
export function NotEnforced(props: { note: string }) {
  return <StatusBadge tone="warn" label="not enforced yet" title={props.note} />;
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

const TONE = { up: "text-up", down: "text-down", warn: "text-warn" } as const;

export function Stat(props: {
  label: string;
  value: string;
  sub?: string;
  icon?: ReactNode;
  tone?: "up" | "down" | "warn";
}) {
  return (
    <div className="flex gap-3 rounded-md border border-line bg-surface px-4 py-3.5">
      {props.icon && (
        <span className="grid h-8 w-8 flex-none place-items-center rounded-sm bg-surface-2 text-[15px] text-brand-2">
          {props.icon}
        </span>
      )}
      <div className="min-w-0">
        <div className="text-2xs tracking-[0.08em] text-faint uppercase">{props.label}</div>
        <div
          className={cx(
            "mt-0.5 text-[20px] font-semibold tabular-nums",
            props.tone && TONE[props.tone],
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
    <div className="flex flex-col items-center gap-1.5 px-5 py-11 text-center text-dim">
      {props.icon && (
        <span className="mb-1 grid h-[46px] w-[46px] place-items-center rounded-md bg-surface-2 text-[20px] text-faint">
          {props.icon}
        </span>
      )}
      <p className="font-semibold text-text">{props.title}</p>
      {props.children && <p className="max-w-[46ch] text-sm">{props.children}</p>}
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
        "border-b border-line px-2.5 py-2 text-[10px] tracking-[0.07em] text-faint uppercase whitespace-nowrap",
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
        "px-2.5 py-2 whitespace-nowrap",
        props.num && "text-right font-mono tabular-nums",
        props.mono && MONO,
      )}
      title={props.title}
    >
      {props.children}
    </td>
  );
}
