// Applying a custom theme, and the escape hatch that survives one.
//
// # Why this is not in `theme.ts`
//
// `theme.ts` resolves a preference into a scheme and writes **one** attribute,
// `document.documentElement.dataset.theme`. It has no dependency on the settings document, on a
// sanitiser, or on a managed `<style>` element, and that is what lets it be shared with a surface
// that has no user-CSS plane at all. Putting custom-theme application inside it would give it all
// three. So this is a second file, and `dataset.theme` still has exactly one writer.
//
// # The four recoveries
//
// Letting a user inject authored CSS into the app's own document means accepting that they can lock
// themselves out of it, and a lockout costs an install. There are four independent ways back, in
// descending order of how much has to still be working:
//
//   1. `@layer nodera.guard`, declared **first**, which is what outranks an important declaration
//      in a later layer — the order of layers is inverted for important declarations, and this was
//      the wrong way round until it was measured (`styles.css` carries the full note);
//   2. **inline `!important` on the escape button**, re-asserted here after every apply. An
//      important declaration in a `style` attribute outranks every important author *rule*, layered
//      or not. This is the one that matters: an engine with no `@layer` support drops the guard
//      block whole, so the guard fails *open*, and this is what is left;
//   3. the countdown on first apply, which reverts a theme nobody confirmed;
//   4. only a kept theme is written to disk, so a theme that locked the window out cannot survive
//      a restart.

import { useEffect } from "react";
import { sanitise } from "./themecss";
import { tokenDeclarations } from "./themetokens";
import type { CustomTheme } from "./ipc";

/** The one `<style>` element a custom theme is allowed to own. */
const STYLE_ID = "nodera-theme";

/** The escape button's own id, so it can be found without a query over the whole document. */
export const ESCAPE_ID = "nodera-escape";

/**
 * The inline styles the escape button carries, in its two states.
 *
 * # Why there are two
 *
 * A permanent labelled button floating over a working app is wrong, and one that can be dismissed
 * is not an escape hatch. So the question is not *whether* it is on screen but *when* it is
 * **announced**: it is a full labelled button for {@link ANNOUNCE_MS} after every change of
 * appearance — which includes the window opening with one already in force, because that is the
 * moment a kept theme reveals what it did — and it stays announced while it is hovered or focused.
 * Otherwise it withdraws to a 14px handle against the right edge, at the same height, half opacity,
 * with its label clipped out of the box and three dots drawn where the label was.
 *
 * The handle is never the *first* thing a user sees. Every path into it begins with the labelled
 * button standing in the same place for twenty seconds, so the handle is the residue of something
 * they have already read — and touching it brings the words back.
 *
 * # Why both states are inline, and both are `!important`
 *
 * Every declaration in both tables is set with the `"important"` priority, which is the only
 * declaration class that beats an important author rule in any layer. That is what makes the
 * withdrawn state as unbreakable as the announced one: an engine with no `@layer` support drops
 * `nodera.guard` whole, and on that engine these tables are the entire defence. Position, size,
 * visibility, opacity, z-index and `pointer-events` are all pinned in **both**, so no theme can
 * shrink the handle to nothing, move it off-screen or make it unclickable.
 *
 * The two tables carry the **same keys** on purpose. Switching state is then a straight rewrite of
 * a known list, disarming is a removal of that same list, and there is no property that one state
 * sets and the other silently inherits from whatever was there before.
 *
 * Written as tables rather than `style` attribute strings because `setProperty(name, value,
 * "important")` is the API that takes a priority at all — assigning to `element.style.cssText`
 * cannot express one per declaration. Property names are camelCase and converted on the way out,
 * so these are lists of CSS properties rather than lists of strings a class-name scanner has to be
 * taught to ignore.
 */
const ANNOUNCED: Record<string, string> = {
  position: "fixed",
  inset: "auto 16px 16px auto",
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  gap: "8px",
  visibility: "visible",
  opacity: "1",
  transform: "none",
  clipPath: "none",
  filter: "none",
  pointerEvents: "auto",
  zIndex: "2147483647",
  width: "auto",
  height: "auto",
  minWidth: "0",
  // 44px, not the height a 13px label and 8px of padding happen to add up to. This is the one
  // control on the screen that has to be hittable by somebody who is already fighting the window.
  minHeight: "44px",
  padding: "8px 12px",
  overflow: "visible",
  textIndent: "0",
  whiteSpace: "nowrap",
  border: "1px solid #ffffff",
  borderRadius: "8px",
  backgroundColor: "#16171b",
  backgroundImage: "none",
  color: "#ffffff",
  font: "500 13px/1.4 system-ui, sans-serif",
  cursor: "pointer",
};

/**
 * The withdrawn handle.
 *
 * The label is still in the DOM — it is only clipped, by `textIndent` out of a 14px box that is
 * `overflow: hidden`, with `color: transparent` as the second belt — so the button keeps its
 * accessible name and a screen reader still reads "Reset appearance". Three dots are painted with
 * `radial-gradient` rather than an icon: this app's CSP is `default-src 'self'`, under which a
 * `data:` URI in a `background-image` is a blocked fetch, and a pseudo-element cannot be styled
 * from a `style` attribute at all.
 */
const WITHDRAWN: Record<string, string> = {
  ...ANNOUNCED,
  inset: "auto 0 20px auto",
  opacity: "0.5",
  width: "14px",
  height: "46px",
  minWidth: "14px",
  minHeight: "46px",
  padding: "0",
  overflow: "hidden",
  textIndent: "-9999px",
  borderRadius: "8px 0 0 8px",
  backgroundImage: [
    "radial-gradient(circle at 50% calc(50% - 7px), #ffffff 0 1.5px, transparent 1.6px)",
    "radial-gradient(circle at 50% 50%, #ffffff 0 1.5px, transparent 1.6px)",
    "radial-gradient(circle at 50% calc(50% + 7px), #ffffff 0 1.5px, transparent 1.6px)",
  ].join(", "),
  color: "transparent",
};

/**
 * How long the hatch stays announced after the appearance changes.
 *
 * Long enough to read a sentence, look at what the theme did to the window, and decide — and short
 * enough that it is not still there when the answer was "this is fine".
 */
const ANNOUNCE_MS = 20_000;

/** How long it lingers after the pointer or the focus leaves it. */
const LINGER_MS = 900;

/**
 * The chord that brings the hatch back, and it is the route that has to work when everything else
 * has failed: a theme that paints the whole window one colour leaves the handle findable only by
 * luck, and a pointer is no use if the theme also covered the screen in something that eats clicks.
 *
 * It announces and focuses. It deliberately does **not** reset — a global chord that silently threw
 * away the appearance somebody had just spent an hour on would be its own kind of damage, so it
 * puts the labelled button under the keyboard and lets Enter finish the job.
 */
const RECALL = { key: "r", alt: true, ctrlOrMeta: true } as const;

const kebab = (name: string) => name.replace(/[A-Z]/g, (ch) => `-${ch.toLowerCase()}`);

let countdown = 0;
let wired = false;

/** The hatch, or nothing. Looked up each time: React owns this node and may replace it. */
const hatch = () => document.getElementById(ESCAPE_ID);

/** Is there an appearance to escape from? The attribute is React state, not a stylesheet. */
const armed = (element: HTMLElement) => element.hasAttribute("data-nodera-escape");

function paint(element: HTMLElement, table: Record<string, string>): void {
  for (const [property, value] of Object.entries(table)) {
    element.style.setProperty(kebab(property), value, "important");
  }
}

function announce(element: HTMLElement, then: number): void {
  paint(element, ANNOUNCED);
  schedule(element, then);
}

/** Withdraw, unless the pointer or the keyboard is on it — in which case wait and ask again. */
function withdraw(element: HTMLElement): void {
  if (element.matches(":hover") || document.activeElement === element) {
    schedule(element, LINGER_MS);
    return;
  }
  paint(element, WITHDRAWN);
}

function schedule(element: HTMLElement, after: number): void {
  window.clearTimeout(countdown);
  countdown = window.setTimeout(() => withdraw(element), after);
}

/**
 * Bind the four events that make the two states reachable, once, for the life of the window.
 *
 * On the document rather than on the element for the chord, because the point of the chord is to
 * work when the hatch cannot be seen or clicked — so it cannot be the hatch that listens for it.
 * Every handler re-reads the element and does nothing unless it is armed, so an unarmed window has
 * no behaviour attached to it at all.
 */
function wire(): void {
  if (wired) return;
  wired = true;

  const back = () => {
    const element = hatch();
    if (element && armed(element)) {
      window.clearTimeout(countdown);
      paint(element, ANNOUNCED);
    }
  };
  const go = () => {
    const element = hatch();
    if (element && armed(element)) schedule(element, LINGER_MS);
  };

  // Hover and focus keep it announced, so the handle is never a control anyone has to click blind —
  // and focus is the other keyboard route: the hatch is a portal into `document.body` *after*
  // `#root`, so it is the last thing in the tab order and Shift+Tab from the top reaches it.
  document.addEventListener("pointerover", (event) => {
    if (event.target === hatch()) back();
  });
  document.addEventListener("pointerout", (event) => {
    if (event.target === hatch()) go();
  });
  document.addEventListener("focusin", (event) => {
    if (event.target === hatch()) back();
  });
  document.addEventListener("focusout", (event) => {
    if (event.target === hatch()) go();
  });

  document.addEventListener("keydown", (event) => {
    if (event.key.toLowerCase() !== RECALL.key) return;
    if (event.altKey !== RECALL.alt) return;
    if (RECALL.ctrlOrMeta && !(event.ctrlKey || event.metaKey)) return;
    const element = hatch();
    if (!element || !armed(element)) return;
    event.preventDefault();
    announce(element, ANNOUNCE_MS);
    element.focus();
  });
}

/**
 * Arm or disarm the escape button, at a priority nothing in a layer can reach.
 *
 * # The arming rule, and why it is this one
 *
 * A permanent labelled button floating over a working app is wrong — that is what was reported —
 * and a button that can be dismissed is not an escape hatch, because the moment it is worth having
 * is the moment the user can no longer find anything. So neither "always" nor "until dismissed".
 *
 * It is armed **only while a custom appearance is in force**, because with none the app's own
 * stylesheet is the only thing painting and there is nothing to escape from. While armed it is
 * *announced* — a full labelled button — for {@link ANNOUNCE_MS} after every change of appearance,
 * which includes the window opening with one already kept, because that is the moment a theme
 * reveals what it did. After that it withdraws to a 14px handle against the right edge. It is never
 * removed and never dismissible.
 *
 * The invariant the whole shape exists to hold: **a person locked out by their own CSS always has a
 * way back.** There are three, and none of them needs the app to be legible — hover or click the
 * handle, Shift+Tab to it (it is the last thing in the tab order), or press the {@link RECALL}
 * chord. Its size, position, opacity and `pointer-events` are pinned inline at `!important` in both
 * states, so no theme can take any of the three away.
 */
export function armEscapeHatch(active: boolean): void {
  const element = hatch();
  if (!element) return;
  window.clearTimeout(countdown);
  for (const property of Object.keys(ANNOUNCED)) element.style.removeProperty(kebab(property));
  if (!active) return;
  wire();
  // Every call with `true` is a change of appearance, and every change of appearance is a fresh
  // chance that the window just became unusable — so every one of them announces.
  announce(element, ANNOUNCE_MS);
}

/**
 * Put a custom theme into force, or take the last one out.
 *
 * Three effects and no others: the attribute that scopes every rule the theme owns, the single
 * managed `<style>` element, and the escape button's inline armour. It never writes
 * `dataset.theme` — that attribute has one owner, and it is `theme.ts`.
 */
export function applyCustomTheme(custom: CustomTheme | undefined): void {
  const root = document.documentElement;
  const existing = document.getElementById(STYLE_ID);

  if (!custom) {
    delete root.dataset.customTheme;
    existing?.remove();
    armEscapeHatch(false);
    return;
  }

  root.dataset.customTheme = custom.id;

  const scope = `:root[data-theme="${custom.base}"][data-custom-theme="${custom.id}"]`;
  const declarations = tokenDeclarations(custom.tokens ?? {});
  const authored = sanitise(custom.css ?? "", custom.id, custom.base);

  // Tokens first, then the CSS body: a theme's own free-form rules are meant to be able to override
  // the swatches it also set, which is the order a person writing both would expect.
  const sheet =
    (declarations ? `@layer nodera.user {\n${scope} {\n${declarations}\n}\n}\n` : "") + authored.css;

  const style = existing ?? document.createElement("style");
  style.id = STYLE_ID;
  // One element, one write, idempotent. Appending would leave every previous version of the theme
  // still in the document, each one still winning wherever it was more specific.
  style.textContent = sheet;
  if (!existing) document.head.appendChild(style);

  armEscapeHatch(true);
}

/** The same thing as a hook, for the shell. */
export function useCustomTheme(custom: CustomTheme | undefined): void {
  const fingerprint = custom
    ? `${custom.id}|${custom.base}|${custom.css}|${JSON.stringify(custom.tokens ?? {})}`
    : "";
  useEffect(() => {
    applyCustomTheme(custom);
    // `custom` is a fresh object on every dashboard push, so the identity of the object is not the
    // thing that changed — its content is.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fingerprint]);
}
