// Every styling class written in the source must actually resolve to a rule in the shipped CSS.
//
// # Why this check exists
//
// Tailwind emits a class only when it resolves. A class name that names a token the theme does not
// declare — `bg-panel` when the palette calls it `--color-surface` — is not an error anywhere: `tsc`
// does not read class names, Vite does not warn, and the element simply renders with no background.
// The launcher's first-run consent modal shipped that way: `bg-panel`, `text-brand`, `text-muted`
// and `hover:bg-hover` all resolved to nothing, so the panel was transparent over the scrim, its
// prose rendered at full contrast instead of dimmed, and neither button had a hover state — on the
// first screen a new user ever sees.
//
// The check is the inverse of the bug: take every class token that appears in the source, and assert
// the production stylesheet contains a rule for it. A token Tailwind rejected is a token that is
// missing from `dist`, which is exactly the signature.
//
// # Why it reads the built CSS rather than the theme
//
// Comparing class names against a `@theme` block would only cover colours, and it would
// re-implement Tailwind's own resolution rules — the built-in palette, arbitrary values, every
// variant prefix — in a test file, where they would drift. The built stylesheet is the authority on
// what resolved, because it *is* what resolved.
//
// # Why it lives in the shared kit
//
// Two builds need it now. The website imports the launcher's palette across a package boundary, and
// Tailwind v4 detects its sources relative to the Vite root — so the site's most likely styling
// failure is that **every** class in it resolves to nothing and the page merely looks plain. This
// function is the automated detector for that, and a second copy of it would be a second set of
// rules about what counts as a token.
import { readFileSync, readdirSync, statSync } from "node:fs";
import path from "node:path";

/**
 * The utility families that carry a design decision.
 *
 * Deliberately not "every class": layout utilities (`flex`, `grid-cols-2`) cannot name a missing
 * token, so including them would add noise without adding coverage. These families all take a value
 * out of the theme, which is where a name can be wrong.
 */
export const FAMILIES = [
  "text",
  "bg",
  "border",
  "outline",
  "ring",
  "fill",
  "stroke",
  "from",
  "via",
  "to",
  "shadow",
  "decoration",
  "divide",
  "accent",
  "caret",
  "placeholder",
  "rounded",
];

const TOKEN = new RegExp(
  // optional variant prefixes (`hover:`, `max-narrow:`, `dark:`…), then family-value
  String.raw`^(?:[a-z][a-z0-9-]*:)*(?:${FAMILIES.join("|")})-[a-z0-9[\]()/.,%#_-]+$`,
  "i",
);

/** CSS identifier escaping, the same transformation Tailwind applies when it emits the selector. */
function cssEscape(token) {
  return token.replace(/[^a-zA-Z0-9_-]/g, (ch) => `\\${ch}`);
}

/** Every `.tsx`/`.ts`/`.mdx` under a directory, recursively. A new folder cannot escape the rule. */
export function sourceFiles(dir, extensions = /\.(tsx|ts|mdx)$/) {
  const out = [];
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry);
    if (statSync(full).isDirectory()) out.push(...sourceFiles(full, extensions));
    else if (extensions.test(entry)) out.push(full);
  }
  return out;
}

/** Every `.css` file emitted into an assets directory, concatenated. */
export function builtStylesheet(assetsDir) {
  return readdirSync(assetsDir)
    .filter((name) => name.endsWith(".css"))
    .map((name) => readFileSync(path.join(assetsDir, name), "utf8"))
    .join("\n");
}

/**
 * Is there a rule for this class in the stylesheet?
 *
 * Matches `.token` only at a class boundary, so `.bg-surface` is not satisfied by
 * `.bg-surface-hover` — the near-miss this check most needs to catch, because a token one hyphen
 * away from a real one is exactly what a rename leaves behind.
 */
export function emitted(css, token) {
  const needle = `.${cssEscape(token)}`;
  let at = css.indexOf(needle);
  while (at !== -1) {
    const next = css[at + needle.length];
    if (next === undefined || !/[a-zA-Z0-9_-]/.test(next)) return true;
    at = css.indexOf(needle, at + 1);
  }
  return false;
}

/**
 * Class tokens written as plain string literals.
 *
 * Literals containing `${` are skipped: an interpolated class is composed at runtime, so the pieces
 * around the hole are not tokens and Tailwind never sees them as such either.
 */
export function tokensIn(source) {
  const found = new Set();
  for (const [, ...groups] of source.matchAll(/"([^"\\\n]*)"|'([^'\\\n]*)'|`([^`\\$]*)`/g)) {
    const literal = groups.find((group) => group !== undefined);
    if (literal === undefined || literal.includes("${")) continue;
    for (const word of literal.split(/\s+/)) {
      if (TOKEN.test(word)) found.add(word);
    }
  }
  return found;
}

/**
 * Audit every class token under `sourceRoots` against the CSS emitted into `distAssets`.
 *
 * @param {{ sourceRoots: string[], distAssets: string }} options
 * @returns {{ dead: string[], css: string, inspected: number }}
 *   `dead` holds `file: token` lines, ready to be the body of an assertion message.
 */
export function auditTokens({ sourceRoots, distAssets }) {
  const css = builtStylesheet(distAssets);
  const dead = [];
  let inspected = 0;
  for (const root of sourceRoots) {
    for (const file of sourceFiles(root)) {
      const source = readFileSync(file, "utf8");
      for (const token of tokensIn(source)) {
        inspected += 1;
        if (!emitted(css, token)) dead.push(`${path.relative(root, file)}: ${token}`);
      }
    }
  }
  return { dead, css, inspected };
}
