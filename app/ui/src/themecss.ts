// The CSS-mode rewriter.
//
// # A rewriter, not a filter
//
// A regular expression over CSS text is defeated by comments, escapes, `\0` padding and a dozen
// other things the tokeniser normalises away before anything else sees them. So user CSS is never
// inspected as text: it is handed to the platform's own parser, walked as a rule tree, and
// **rebuilt** from what the walk found. A rule that is not understood does not survive by default —
// it is simply not written back out.
//
// The parser is borrowed rather than constructed. `new CSSStyleSheet()` and `adoptedStyleSheets`
// are WebKit 15.4-era APIs and this app pins no webview floor anywhere, so instead a
// `<style media="not all">` element is appended (it can never apply, whatever is in it), read
// through `element.sheet.cssRules`, and removed in a `finally`.
//
// # What actually makes the guard unbypassable
//
// Not the at-rule allowlist — the `@layer nodera.user { … }` wrapper. Because everything the user
// wrote is inside a named layer, any `@layer` they write of their own becomes a *sub*-layer of
// `nodera.user` and therefore still precedes `nodera.guard`, and nothing they write can reach the
// unlayered author origin, which would otherwise outrank every layer including the guard.
//
// # What is refused, in full
//
// `@import` and `@font-face` (both are fetches, and a blocked request still tells a remote host
// that this user opened this theme); any at-rule outside `AT_ALLOWED`; `behavior`, `-moz-binding`
// and any value containing `expression(` — legacy script vectors, kept for completeness rather
// than because any current engine honours them; any `url()` whose argument is not a `data:` URI;
// any selector mentioning `[data-nodera-truth]`, which is how the app marks the elements that
// render a proven fact; and anything the platform parser rejects outright.

/** At-rules a theme may use. Everything else is dropped and named in the report. */
const AT_ALLOWED = ["media", "supports", "layer", "keyframes", "container"] as const;

/** Property names refused on sight, whatever their value. */
const PROPERTY_DENIED = ["behavior", "-moz-binding", "expression("] as const;

/** The attribute marking an element that renders a signed or proven fact. */
const TRUTH_ATTRIBUTE = "data-nodera-truth";

export interface Rewritten {
  /** The finished stylesheet text, already wrapped in `@layer nodera.user`. */
  css: string;
  /** What was removed, in words a person can read before they install a shared theme. */
  dropped: string[];
  /** A parse failure, or "". Nothing is applied when this is set. */
  error: string;
}

/**
 * Rewrite user CSS so it can only ever paint while its own theme is selected.
 *
 * `:root`, `html` and `body` all become the theme's own selector; everything else is prefixed with
 * it. Deselecting the theme is therefore a complete uninstall — no rule can match anything once the
 * `[data-custom-theme]` attribute is gone.
 */
export function sanitise(css: string, themeId: string, base: "dark" | "light"): Rewritten {
  const dropped: string[] = [];
  if (!css.trim()) return { css: "", dropped, error: "" };

  const scope = `:root[data-theme="${base}"][data-custom-theme="${cssString(themeId)}"]`;
  const holder = document.createElement("style");
  // `media="not all"` can never match, so this element cannot paint anything while it is attached.
  holder.media = "not all";
  holder.textContent = css;
  document.head.appendChild(holder);
  try {
    const sheet = holder.sheet;
    if (!sheet) return { css: "", dropped, error: "This CSS could not be parsed." };
    const body = walk(Array.from(sheet.cssRules), scope, dropped);
    if (!body.trim()) {
      return { css: "", dropped, error: dropped.length ? "" : "Nothing in this CSS could be used." };
    }
    return { css: `@layer nodera.user {\n${body}}\n`, dropped, error: "" };
  } catch (failure) {
    return { css: "", dropped, error: String(failure) };
  } finally {
    holder.remove();
  }
}

function walk(rules: CSSRule[], scope: string, dropped: string[], indent = "  "): string {
  let out = "";
  for (const rule of rules) {
    // Dropped by class rather than by name: both fetch, and both are how a stylesheet talks to a
    // host that is not this machine.
    if (typeof CSSImportRule !== "undefined" && rule instanceof CSSImportRule) {
      dropped.push("@import (a theme may not fetch anything)");
      continue;
    }
    if (typeof CSSFontFaceRule !== "undefined" && rule instanceof CSSFontFaceRule) {
      dropped.push("@font-face (its src: is a fetch)");
      continue;
    }

    if (rule instanceof CSSStyleRule) {
      const selector = rewriteSelector(rule.selectorText, scope);
      if (!selector) {
        dropped.push(`${rule.selectorText} (it names a proven fact)`);
        continue;
      }
      const declarations = rewriteDeclarations(rule.style, dropped);
      if (declarations) out += `${indent}${selector} {\n${declarations}${indent}}\n`;
      continue;
    }

    if (rule instanceof CSSKeyframesRule) {
      // Keyframe selectors are percentages, not element selectors, so they are copied as written.
      let frames = "";
      for (const frame of Array.from(rule.cssRules) as CSSKeyframeRule[]) {
        const declarations = rewriteDeclarations(frame.style, dropped, `${indent}    `);
        if (declarations) frames += `${indent}  ${frame.keyText} {\n${declarations}${indent}  }\n`;
      }
      if (frames) out += `${indent}@keyframes ${rule.name} {\n${frames}${indent}}\n`;
      continue;
    }

    if (rule instanceof CSSGroupingRule) {
      const at = atName(rule.cssText);
      if (!at || !AT_ALLOWED.includes(at as (typeof AT_ALLOWED)[number])) {
        dropped.push(`@${at || "unknown"} (not an at-rule a theme may use)`);
        continue;
      }
      const prelude = rule.cssText.slice(0, rule.cssText.indexOf("{")).trim();
      const inner = walk(Array.from(rule.cssRules), scope, dropped, `${indent}  `);
      if (inner) out += `${indent}${prelude} {\n${inner}${indent}}\n`;
      continue;
    }

    dropped.push(`${atName(rule.cssText) ? `@${atName(rule.cssText)}` : "a rule"} (not understood)`);
  }
  return out;
}

/**
 * Root-level selectors become the theme's scope; everything else is prefixed with it.
 *
 * Returns "" when the rule must be dropped entirely.
 */
function rewriteSelector(selectorText: string, scope: string): string {
  const parts: string[] = [];
  for (const raw of selectorText.split(",")) {
    const selector = raw.trim();
    if (!selector) continue;
    // A theme that could style the elements carrying a proven fact could forge one — a `content:`
    // on a pseudo-element is enough to write an owner's name that nobody signed.
    if (selector.includes(TRUTH_ATTRIBUTE)) return "";
    if (/^(?::root|html|body)$/i.test(selector)) {
      parts.push(scope);
      continue;
    }
    const stripped = selector.replace(/^(?::root|html|body)\s+/i, "");
    parts.push(`${scope} ${stripped}`);
  }
  return parts.join(", ");
}

function rewriteDeclarations(style: CSSStyleDeclaration, dropped: string[], indent = "    "): string {
  let out = "";
  for (let i = 0; i < style.length; i += 1) {
    const property = style.item(i);
    const value = style.getPropertyValue(property);
    const priority = style.getPropertyPriority(property);
    const probe = `${property}:${value}`.toLowerCase();

    if (PROPERTY_DENIED.some((denied) => probe.includes(denied))) {
      dropped.push(`${property} (a legacy script vector)`);
      continue;
    }
    if (/url\(/i.test(value) && !/url\(\s*["']?data:/i.test(value)) {
      dropped.push(`${property}: url(…) (only data: URIs may be named)`);
      continue;
    }
    out += `${indent}${property}: ${value}${priority ? " !important" : ""};\n`;
  }
  return out;
}

/** `@media (min-width: 900px) { … }` → `media`. */
function atName(cssText: string): string {
  const match = /^@([a-z-]+)/i.exec(cssText.trim());
  return match ? match[1].toLowerCase() : "";
}

/** Escape a value being placed inside a double-quoted attribute selector. */
function cssString(value: string): string {
  return value.replace(/["\\]/g, "");
}

export { AT_ALLOWED, PROPERTY_DENIED };
