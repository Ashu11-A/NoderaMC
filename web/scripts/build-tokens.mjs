// The launcher's palette, as data.
//
// One consumer: `<meta name="theme-color">`, which needs a literal colour in a place where no
// stylesheet can reach. That is the whole reason this generator exists — `web/src/styles.css`
// imports the palette and derives everything from it, and
// `web/tests/design-tokens.test.mjs` fails the build on a hex, `rgb(`, `hsl(` or `oklch(` literal
// anywhere under `web/src/**`. A theme colour typed into the HTML head would be the one value that
// stopped following the palette, and it would stop following it silently: the browser chrome would
// simply be the old colour.
//
// It reads the stylesheet rather than a copy of the values, so the violet repalette landing in the
// launcher redesign moves this without an edit.
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import path from "node:path";
import { dir, pkg } from "nodera-ui/layout";

const STYLESHEET = path.join(pkg("nodera-app-ui"), "src/styles.css");
const OUT = path.join(dir("web"), "src/generated/tokens.json");

// Comments are removed before anything is matched. That stylesheet is more comment than
// declaration by line count, and one of them contains the word `{` sooner or later; a scanner that
// treats a comment as a selector reports the whole file header as the first rule and then skips the
// block it was looking for. Which is what the first version of this file did.
const css = readFileSync(STYLESHEET, "utf8").replace(/\/\*[\s\S]*?\*\//g, "");

/**
 * The declarations of the first rule whose selector list matches.
 *
 * Deliberately not a CSS parser. The file is tokens and document-level base rules by its own stated
 * design, the blocks that matter are flat, and a dependency that understands `@utility` would be a
 * dependency this generator does not need. A block that stops matching exits below rather than
 * yielding an empty map.
 */
function block(matches) {
  for (const match of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    // The text before a `{` also carries whatever statements preceded the rule — the file opens
    // `@import "tailwindcss";` and the very next rule is `:root`, so without this the first
    // selector reads `@import "tailwindcss"; :root` and is discarded as an at-rule.
    const selector = match[1].split(";").pop().trim();
    if (!selector || selector.startsWith("@")) continue;
    if (matches(selector)) {
      return Object.fromEntries(
        [...match[2].matchAll(/(--[a-z0-9-]+)\s*:\s*([^;]+);/gi)].map(([, name, value]) => [
          name,
          value.trim(),
        ]),
      );
    }
  }
  return null;
}

const base = block((selector) => selector === ":root");
const dark = block((selector) => selector.includes('[data-theme="dark"]'));
const light = block((selector) => selector.includes('[data-theme="light"]'));

for (const [name, found] of [["base :root", base], ["dark", dark], ["light", light]]) {
  if (!found || Object.keys(found).length === 0) {
    console.error(`build-tokens: ${STYLESHEET} has no ${name} block, or it declares nothing`);
    process.exit(1);
  }
}

// Merged the way the cascade merges them: a theme block overrides the base, and everything the base
// declares and the theme does not is still in force. A map holding only the overrides would say the
// dark theme has no `--brand-1`, which is true of the block and false of the page.
const tokens = { dark: { ...base, ...dark }, light: { ...base, ...light } };

if (!tokens.dark["--bg"] || !tokens.light["--bg"]) {
  console.error("build-tokens: a theme with no --bg cannot answer <meta name=\"theme-color\">");
  process.exit(1);
}

mkdirSync(path.dirname(OUT), { recursive: true });
writeFileSync(OUT, `${JSON.stringify(tokens, null, 2)}\n`);
console.log(
  `build-tokens: ${Object.keys(tokens.dark).length} dark and ${Object.keys(tokens.light).length} light tokens`,
);
