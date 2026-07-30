// Material 3 components, built on the generated colour roles.
//
// Hand-written rather than pulled from a component library on purpose: the app is offline by
// design (its CSP forbids every external host), and Material Web ships web components whose
// styling assumes a build pipeline this project does not have. What Material 3 actually specifies
// is shape, elevation, state layers and which colour role goes where — all of which are CSS. So
// these are real M3 components: correct roles, correct corner radii, and the state layer on every
// interactive surface, which is the thing that makes a Material interface feel like one.
import type { CSSProperties, ReactNode } from "react";

/** Join class names, skipping falsy ones. */
export function cx(...parts: (string | false | null | undefined)[]): string {
  return parts.filter(Boolean).join(" ");
}

/* ------------------------------------------------------------------------------------ surfaces */

/**
 * An M3 card.
 *
 * `filled` is the default because a phone screen full of outlined cards reads as a form. `elevated`
 * is for something that sits above the page — a status the user is meant to notice first.
 */
export function Card(props: {
  children: ReactNode;
  variant?: "filled" | "elevated" | "outlined";
  className?: string;
  onClick?: () => void;
}) {
  const variant = props.variant ?? "filled";
  const base = "rounded-[12px] p-4 transition-shadow";
  const skin =
    variant === "elevated"
      ? "bg-[var(--md-sys-color-surface-container-low)] shadow-[0_1px_3px_rgba(0,0,0,0.3)]"
      : variant === "outlined"
        ? "bg-[var(--md-sys-color-surface)] border border-[var(--md-sys-color-outline-variant)]"
        : "bg-[var(--md-sys-color-surface-container-high)]";
  const interactive = props.onClick
    ? "cursor-pointer active:opacity-90 hover:brightness-[1.04]"
    : "";
  return (
    <div
      className={cx(base, skin, interactive, props.className)}
      onClick={props.onClick}
      role={props.onClick ? "button" : undefined}
      tabIndex={props.onClick ? 0 : undefined}
    >
      {props.children}
    </div>
  );
}

/** A card with the M3 title/body structure already laid out. */
export function TitledCard(props: {
  title: string;
  supporting?: string;
  trailing?: ReactNode;
  children?: ReactNode;
  variant?: "filled" | "elevated" | "outlined";
}) {
  return (
    <Card variant={props.variant}>
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <h2 className="text-[16px] leading-6 font-medium text-[var(--md-sys-color-on-surface)]">
            {props.title}
          </h2>
          {props.supporting && (
            <p className="mt-0.5 text-[14px] leading-5 text-[var(--md-sys-color-on-surface-variant)]">
              {props.supporting}
            </p>
          )}
        </div>
        {props.trailing}
      </div>
      {props.children && <div className="mt-3">{props.children}</div>}
    </Card>
  );
}

/* ------------------------------------------------------------------------------------- buttons */

type ButtonVariant = "filled" | "tonal" | "outlined" | "text";

/**
 * An M3 button.
 *
 * The full-height pill shape and the state layer are not decoration: they are what makes a target
 * readable as pressable on a touch screen, and the 40px minimum is the spec's touch target.
 */
export function Button(props: {
  children: ReactNode;
  onClick?: () => void;
  variant?: ButtonVariant;
  icon?: ReactNode;
  disabled?: boolean;
  className?: string;
  danger?: boolean;
}) {
  const variant = props.variant ?? "filled";
  const danger = props.danger;
  const skin: Record<ButtonVariant, string> = {
    filled: danger
      ? "bg-[var(--md-sys-color-error)] text-[var(--md-sys-color-on-error)]"
      : "bg-[var(--md-sys-color-primary)] text-[var(--md-sys-color-on-primary)]",
    tonal: danger
      ? "bg-[var(--md-sys-color-error-container)] text-[var(--md-sys-color-on-error-container)]"
      : "bg-[var(--md-sys-color-secondary-container)] text-[var(--md-sys-color-on-secondary-container)]",
    outlined: cx(
      "border border-[var(--md-sys-color-outline)] bg-transparent",
      danger
        ? "text-[var(--md-sys-color-error)]"
        : "text-[var(--md-sys-color-primary)]",
    ),
    text: danger
      ? "text-[var(--md-sys-color-error)]"
      : "text-[var(--md-sys-color-primary)]",
  };
  return (
    <button
      type="button"
      disabled={props.disabled}
      onClick={props.onClick}
      className={cx(
        "inline-flex min-h-[40px] items-center justify-center gap-2 rounded-full px-5",
        "text-[14px] leading-5 font-medium transition-[filter,opacity]",
        "hover:brightness-110 active:brightness-95 disabled:opacity-38 disabled:pointer-events-none",
        skin[variant],
        props.className,
      )}
    >
      {props.icon}
      {props.children}
    </button>
  );
}

/** The M3 floating action button. */
export function Fab(props: { icon: ReactNode; label?: string; onClick?: () => void }) {
  return (
    <button
      type="button"
      onClick={props.onClick}
      className={cx(
        "fixed right-4 z-20 inline-flex h-14 items-center gap-3 rounded-[16px] px-4",
        "bg-[var(--md-sys-color-primary-container)] text-[var(--md-sys-color-on-primary-container)]",
        "shadow-[0_3px_8px_rgba(0,0,0,0.35)] active:brightness-95",
      )}
      style={{ bottom: "calc(var(--m3-nav-height) + 16px + env(safe-area-inset-bottom))" }}
    >
      <span className="text-[20px]">{props.icon}</span>
      {props.label && <span className="text-[14px] font-medium">{props.label}</span>}
    </button>
  );
}

/* ---------------------------------------------------------------------------------- navigation */

export type NavDestination = {
  id: string;
  label: string;
  icon: ReactNode;
};

/**
 * The M3 navigation bar: bottom-anchored, 3–5 destinations, an active indicator pill behind the
 * selected icon.
 *
 * Bottom-anchored because a phone is held at the bottom. The desktop build keeps its side rail —
 * this is the part of the dual layout that is a different component, not a different width.
 */
export function NavigationBar(props: {
  destinations: NavDestination[];
  active: string;
  onSelect: (id: string) => void;
}) {
  return (
    <nav
      className={cx(
        "z-30 flex shrink-0 items-stretch justify-around",
        "bg-[var(--md-sys-color-surface-container)] border-t border-[var(--md-sys-color-outline-variant)]",
      )}
      style={{
        height: "calc(var(--m3-nav-height) + env(safe-area-inset-bottom))",
        paddingBottom: "env(safe-area-inset-bottom)",
      }}
      role="tablist"
    >
      {props.destinations.map((d) => {
        const active = d.id === props.active;
        return (
          <button
            key={d.id}
            role="tab"
            aria-selected={active}
            onClick={() => props.onSelect(d.id)}
            className="flex flex-1 flex-col items-center justify-center gap-1 pt-3 pb-2"
          >
            <span
              className={cx(
                "grid h-8 w-16 place-items-center rounded-full text-[20px] transition-colors",
                active
                  ? "bg-[var(--md-sys-color-secondary-container)] text-[var(--md-sys-color-on-secondary-container)]"
                  : "text-[var(--md-sys-color-on-surface-variant)]",
              )}
            >
              {d.icon}
            </span>
            <span
              className={cx(
                "text-[12px] leading-4",
                active
                  ? "font-medium text-[var(--md-sys-color-on-surface)]"
                  : "text-[var(--md-sys-color-on-surface-variant)]",
              )}
            >
              {d.label}
            </span>
          </button>
        );
      })}
    </nav>
  );
}

/** The M3 top app bar (small size), with the status-bar inset built in. */
export function TopAppBar(props: { title: string; leading?: ReactNode; trailing?: ReactNode }) {
  return (
    <header
      className="z-20 shrink-0 bg-[var(--md-sys-color-surface)]"
      style={{ paddingTop: "env(safe-area-inset-top)" }}
    >
      <div className="flex h-16 items-center gap-3 px-4">
        {props.leading}
        <h1 className="min-w-0 flex-1 truncate text-[22px] leading-7 text-[var(--md-sys-color-on-surface)]">
          {props.title}
        </h1>
        {props.trailing}
      </div>
    </header>
  );
}

/* -------------------------------------------------------------------------------------- pieces */

/** An M3 list item: leading icon, headline, supporting text, trailing slot. */
export function ListItem(props: {
  headline: ReactNode;
  supporting?: ReactNode;
  leading?: ReactNode;
  trailing?: ReactNode;
  onClick?: () => void;
}) {
  return (
    <div
      className={cx(
        "flex min-h-[56px] items-center gap-4 px-1 py-2",
        props.onClick && "cursor-pointer rounded-[12px] active:bg-[var(--md-sys-color-surface-container-highest)]",
      )}
      onClick={props.onClick}
    >
      {props.leading && (
        <span className="text-[20px] text-[var(--md-sys-color-on-surface-variant)]">
          {props.leading}
        </span>
      )}
      <div className="min-w-0 flex-1">
        <div className="truncate text-[16px] leading-6 text-[var(--md-sys-color-on-surface)]">
          {props.headline}
        </div>
        {props.supporting !== undefined && (
          <div className="text-[14px] leading-5 text-[var(--md-sys-color-on-surface-variant)]">
            {props.supporting}
          </div>
        )}
      </div>
      {props.trailing}
    </div>
  );
}

/** The M3 assist/filter chip. */
export function Chip(props: {
  children: ReactNode;
  selected?: boolean;
  tone?: "neutral" | "good" | "bad";
  onClick?: () => void;
}) {
  const tone = props.tone ?? "neutral";
  const colours =
    tone === "good"
      ? "bg-[var(--md-sys-color-tertiary-container)] text-[var(--md-sys-color-on-tertiary-container)]"
      : tone === "bad"
        ? "bg-[var(--md-sys-color-error-container)] text-[var(--md-sys-color-on-error-container)]"
        : props.selected
          ? "bg-[var(--md-sys-color-secondary-container)] text-[var(--md-sys-color-on-secondary-container)]"
          : "border border-[var(--md-sys-color-outline)] text-[var(--md-sys-color-on-surface-variant)]";
  return (
    <button
      type="button"
      onClick={props.onClick}
      className={cx(
        "inline-flex h-8 items-center gap-1.5 rounded-[8px] px-3 text-[14px] leading-5",
        colours,
        !props.onClick && "pointer-events-none",
      )}
    >
      {props.children}
    </button>
  );
}

/** The M3 switch. */
export function Switch(props: { checked: boolean; onChange: (next: boolean) => void; label?: string }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={props.checked}
      aria-label={props.label}
      onClick={() => props.onChange(!props.checked)}
      className={cx(
        "relative h-8 w-[52px] shrink-0 rounded-full border-2 transition-colors",
        props.checked
          ? "border-[var(--md-sys-color-primary)] bg-[var(--md-sys-color-primary)]"
          : "border-[var(--md-sys-color-outline)] bg-[var(--md-sys-color-surface-container-highest)]",
      )}
    >
      <span
        className={cx(
          "absolute top-1/2 block -translate-y-1/2 rounded-full transition-all",
          props.checked
            ? "left-[26px] h-6 w-6 bg-[var(--md-sys-color-on-primary)]"
            : "left-[6px] h-4 w-4 bg-[var(--md-sys-color-outline)]",
        )}
      />
    </button>
  );
}

/** A labelled figure — the mobile equivalent of the desktop stat tile. */
export function Metric(props: { label: string; value: ReactNode; supporting?: string }) {
  return (
    <div className="rounded-[12px] bg-[var(--md-sys-color-surface-container-high)] p-4">
      <div className="text-[12px] leading-4 tracking-wide text-[var(--md-sys-color-on-surface-variant)] uppercase">
        {props.label}
      </div>
      <div className="mt-1 text-[24px] leading-8 text-[var(--md-sys-color-on-surface)]">
        {props.value}
      </div>
      {props.supporting && (
        <div className="text-[12px] leading-4 text-[var(--md-sys-color-on-surface-variant)]">
          {props.supporting}
        </div>
      )}
    </div>
  );
}

/** An indeterminate M3 linear progress indicator. */
export function LinearProgress(props: { style?: CSSProperties }) {
  return (
    <div
      className="h-1 w-full overflow-hidden rounded-full bg-[var(--md-sys-color-surface-container-highest)]"
      style={props.style}
    >
      <div className="m3-indeterminate h-full w-1/3 rounded-full bg-[var(--md-sys-color-primary)]" />
    </div>
  );
}

/** A simple padded column. The scrolling is the shell's job, not this component's. */
export function Screen(props: { children: ReactNode }) {
  return <div className="flex flex-col gap-3 px-4 pb-6">{props.children}</div>;
}

/* ---------------------------------------------------------------------------------------- tabs */

export type TabDef = { id: string; label: string; icon?: ReactNode };

/**
 * M3 primary tabs: a full-width row, an active indicator under the selected tab, and horizontal
 * scrolling when the labels do not fit.
 *
 * Scrolling rather than shrinking is the M3 rule and it matters on a phone: tabs squeezed until
 * the labels truncate are tabs nobody can tell apart, which defeats the point of naming the
 * categories at all.
 */
export function Tabs(props: {
  tabs: TabDef[];
  active: string;
  onSelect: (id: string) => void;
  className?: string;
}) {
  return (
    <div
      className={cx(
        "sticky z-10 flex overflow-x-auto border-b border-[var(--md-sys-color-outline-variant)]",
        "bg-[var(--md-sys-color-surface)] [scrollbar-width:none]",
        props.className,
      )}
      role="tablist"
    >
      {props.tabs.map((tab) => {
        const active = tab.id === props.active;
        return (
          <button
            key={tab.id}
            role="tab"
            aria-selected={active}
            onClick={() => props.onSelect(tab.id)}
            className={cx(
              "relative flex min-w-[90px] flex-1 shrink-0 flex-col items-center justify-center gap-1 px-4 py-3",
              "text-[14px] leading-5 font-medium whitespace-nowrap transition-colors",
              active
                ? "text-[var(--md-sys-color-primary)]"
                : "text-[var(--md-sys-color-on-surface-variant)]",
            )}
          >
            {tab.icon && <span className="text-[18px]">{tab.icon}</span>}
            {tab.label}
            <span
              className={cx(
                "absolute inset-x-3 bottom-0 h-[3px] rounded-t-full transition-opacity",
                active ? "bg-[var(--md-sys-color-primary)] opacity-100" : "opacity-0",
              )}
            />
          </button>
        );
      })}
    </div>
  );
}
