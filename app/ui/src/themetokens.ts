// The token allowlist, and the grammars a value in each group is allowed to be.
//
// # Why this is not "just let them type CSS"
//
// A theme authored in Tokens mode is a `Record<name, value>` over the list below. The app emits the
// `--name: value` pairs itself, into its own selector, from names it chose. Nothing a user types
// here can become a selector, a property name, or an at-rule — so there is nothing to sanitise,
// and the whole class of injection this feature could have opened simply does not exist on this
// path. CSS mode is the one that needs a rewriter (`themecss.ts`); this one needs a parser, and a
// value that does not parse is refused before it is stored.
//
// # Why the grammars are this narrow
//
// `url()` is absent from every one of them. That is the exfiltration channel — `background:
// url(https://…)` on an attribute selector is the classic CSS value leak — and the cheapest place
// to close it is a grammar that cannot express it at all.

/** Which kind of value a token holds, and therefore which grammar applies. */
export type TokenKind = "colour" | "gradient" | "shadow" | "length" | "duration";

export interface TokenSpec {
  readonly name: string;
  readonly label: string;
  readonly kind: TokenKind;
  readonly group: string;
}

/**
 * Every token a custom theme may set.
 *
 * Deliberately the palette and nothing else: these are the names `styles.css` declares in `:root`
 * and in the two `[data-theme]` blocks, which is exactly the set that repaints the whole tree from
 * one attribute. A token that is not here cannot be overridden, because a theme that could rewrite
 * `--canvas-max` or `--breakpoint-wide` would be editing the layout, not the appearance.
 */
export const THEME_TOKENS: readonly TokenSpec[] = [
  { name: "--brand-1", label: "Accent", kind: "colour", group: "Brand" },
  { name: "--brand-2", label: "Accent, deep", kind: "colour", group: "Brand" },
  { name: "--brand-3", label: "Informational", kind: "colour", group: "Brand" },
  { name: "--brand-tint", label: "Accent text", kind: "colour", group: "Brand" },
  { name: "--brand-soft", label: "Accent fill", kind: "colour", group: "Brand" },
  { name: "--brand-edge", label: "Accent border", kind: "colour", group: "Brand" },
  { name: "--brand-gradient", label: "Accent gradient", kind: "gradient", group: "Brand" },

  { name: "--bg", label: "Page", kind: "colour", group: "Surfaces" },
  { name: "--bg-rail", label: "Rail", kind: "colour", group: "Surfaces" },
  { name: "--surface", label: "Card", kind: "colour", group: "Surfaces" },
  { name: "--surface-2", label: "Inner control", kind: "colour", group: "Surfaces" },
  { name: "--surface-hover", label: "Hover", kind: "colour", group: "Surfaces" },
  { name: "--surface-3", label: "Glass", kind: "colour", group: "Surfaces" },
  { name: "--line", label: "Border", kind: "colour", group: "Surfaces" },
  { name: "--line-soft", label: "Border, soft", kind: "colour", group: "Surfaces" },
  { name: "--scrim", label: "Dialog backdrop", kind: "colour", group: "Surfaces" },

  { name: "--text", label: "Text", kind: "colour", group: "Text" },
  { name: "--text-dim", label: "Text, secondary", kind: "colour", group: "Text" },
  { name: "--text-faint", label: "Text, faint", kind: "colour", group: "Text" },
  { name: "--on-play", label: "Text on the accent", kind: "colour", group: "Text" },

  { name: "--up", label: "Up", kind: "colour", group: "State" },
  { name: "--down", label: "Down", kind: "colour", group: "State" },
  { name: "--warn", label: "Warning", kind: "colour", group: "State" },
  { name: "--danger", label: "Danger", kind: "colour", group: "State" },
  { name: "--focus-ring", label: "Focus ring", kind: "colour", group: "State" },

  { name: "--radius-sm", label: "Radius, small", kind: "length", group: "Shape" },
  { name: "--radius", label: "Radius", kind: "length", group: "Shape" },
  { name: "--radius-lg", label: "Radius, large", kind: "length", group: "Shape" },
  { name: "--radius-xl", label: "Radius, extra", kind: "length", group: "Shape" },
  { name: "--elev-1", label: "Elevation 1", kind: "shadow", group: "Shape" },
  { name: "--elev-2", label: "Elevation 2", kind: "shadow", group: "Shape" },
  { name: "--elev-3", label: "Elevation 3", kind: "shadow", group: "Shape" },
  { name: "--glow-brand", label: "Accent glow", kind: "shadow", group: "Shape" },

  { name: "--motion-fast", label: "Motion, fast", kind: "duration", group: "Motion" },
  { name: "--motion-base", label: "Motion, base", kind: "duration", group: "Motion" },
];

const BY_NAME = new Map(THEME_TOKENS.map((spec) => [spec.name, spec]));

/** The groups, in the order the editor shows them. */
export const TOKEN_GROUPS: readonly string[] = [...new Set(THEME_TOKENS.map((t) => t.group))];

const HEX = /^#(?:[0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})$/i;
const NUMERIC = String.raw`[-+]?(?:\d*\.\d+|\d+)%?`;
const COLOUR_FN = new RegExp(
  String.raw`^(?:rgba?|hsla?|oklch|oklab|lab|lch)\(\s*(?:${NUMERIC}|deg|none|\/|,|\s)+\)$`,
  "i",
);

/** Is this a colour, in one of the notations the editor accepts? */
export function isColour(value: string): boolean {
  const v = value.trim();
  return HEX.test(v) || COLOUR_FN.test(v);
}

const LENGTH = /^(?:0|(?:\d{1,3})(?:\.\d+)?px)$/;
const DURATION = /^(?:0|\d{1,4}ms)$/;

/**
 * Does this value satisfy its token's grammar?
 *
 * The gradient and shadow rules work the same way: strip the structural punctuation, and every
 * remaining word must be either a number-with-unit or a colour. Nothing else survives, which is
 * how `url(`, `var(` chains outside the allowlist, and bare identifiers are all excluded at once
 * without enumerating them.
 */
export function isValid(token: string, value: string): boolean {
  const spec = BY_NAME.get(token);
  if (!spec) return false;
  const v = value.trim();
  if (v.length === 0 || v.length > 240) return false;
  // Comments and escapes are how a hand-written filter gets walked past. Neither has any business
  // in a value, so both are refused outright rather than parsed.
  if (v.includes("/*") || v.includes("\\") || v.includes(";") || v.includes("{")) return false;

  switch (spec.kind) {
    case "colour":
      return isColour(v);
    case "length":
      return LENGTH.test(v) || v === "999px";
    case "duration":
      return DURATION.test(v);
    case "gradient":
      return isGradient(v);
    case "shadow":
      return isShadow(v);
  }
}

function isGradient(value: string): boolean {
  const match = /^(?:linear|radial)-gradient\((.*)\)$/i.exec(value);
  if (!match) return false;
  return everyPieceIsAColourOrLength(match[1]);
}

function isShadow(value: string): boolean {
  // At most four shadows; the sheet never uses more than two.
  const shadows = splitTop(value);
  if (shadows.length === 0 || shadows.length > 4) return false;
  return shadows.every((shadow) => everyPieceIsAColourOrLength(shadow));
}

/**
 * Split on commas that are not inside parentheses.
 *
 * `rgba(0, 0, 0, .5)` is one value with three commas in it, so a plain `split(",")` would tear a
 * shadow's colour into pieces and then reject the pieces.
 */
function splitTop(value: string): string[] {
  const out: string[] = [];
  let depth = 0;
  let current = "";
  for (const ch of value) {
    if (ch === "(") depth += 1;
    if (ch === ")") depth -= 1;
    if (ch === "," && depth === 0) {
      out.push(current);
      current = "";
      continue;
    }
    current += ch;
  }
  if (current.trim()) out.push(current);
  return out.map((piece) => piece.trim()).filter(Boolean);
}

const ANGLE = /^[-+]?(?:\d*\.\d+|\d+)(?:deg|rad|turn|%|px)?$/;
const POSITION = /^(?:to|top|bottom|left|right|center|circle|ellipse|at|closest-side|farthest-corner)$/i;

function everyPieceIsAColourOrLength(body: string): boolean {
  for (const piece of splitTop(body)) {
    for (const word of piece.split(/\s+/).filter(Boolean)) {
      if (isColour(word) || ANGLE.test(word) || POSITION.test(word)) continue;
      return false;
    }
  }
  return true;
}

/** The `--name: value` pairs a Tokens-mode theme contributes, already filtered to what is valid. */
export function tokenDeclarations(tokens: Record<string, string>): string {
  return THEME_TOKENS.filter((spec) => {
    const value = tokens[spec.name];
    return typeof value === "string" && isValid(spec.name, value);
  })
    .map((spec) => `  ${spec.name}: ${tokens[spec.name].trim()};`)
    .join("\n");
}
