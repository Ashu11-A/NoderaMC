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
//   1. `@layer nodera.guard`, declared last, which outranks every normal *and* important
//      declaration in an earlier layer, and outranks animations too;
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
 * The inline styles the escape button carries.
 *
 * Every one is set with the `"important"` priority, which is the only declaration class that beats
 * an important author rule in any layer. Written as a table rather than a `style` attribute string
 * because `setProperty(name, value, "important")` is the API that takes a priority at all —
 * assigning to `element.style.cssText` cannot express one per declaration.
 *
 * Property names are camelCase and converted on the way out, so this table is a list of CSS
 * properties rather than a list of strings that a class-name scanner has to be taught to ignore.
 */
const ESCAPE_STYLE: Record<string, string> = {
  position: "fixed",
  inset: "auto 16px 16px auto",
  display: "flex",
  alignItems: "center",
  gap: "8px",
  visibility: "visible",
  opacity: "1",
  transform: "none",
  clipPath: "none",
  filter: "none",
  pointerEvents: "auto",
  zIndex: "2147483647",
  padding: "8px 12px",
  border: "1px solid #ffffff",
  borderRadius: "8px",
  background: "#16171b",
  color: "#ffffff",
  font: "500 13px/1.4 system-ui, sans-serif",
  cursor: "pointer",
};

const kebab = (name: string) => name.replace(/[A-Z]/g, (ch) => `-${ch.toLowerCase()}`);

/**
 * Arm or disarm the escape button's own styles, at a priority nothing in a layer can reach.
 *
 * Armed only while a custom appearance is in force. With none, the app's own stylesheet is the only
 * thing painting and there is nothing to escape from, so the button stays out of the way — the
 * element is always in the document, but only carries the guarded attribute when it has a job.
 */
export function armEscapeHatch(active: boolean): void {
  const element = document.getElementById(ESCAPE_ID);
  if (!element) return;
  for (const property of Object.keys(ESCAPE_STYLE)) element.style.removeProperty(kebab(property));
  if (!active) return;
  for (const [property, value] of Object.entries(ESCAPE_STYLE)) {
    element.style.setProperty(kebab(property), value, "important");
  }
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
