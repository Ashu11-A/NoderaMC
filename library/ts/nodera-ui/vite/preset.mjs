// The Vite configuration both frontends share, and the one line that makes them different.
//
// # Why this is `.mjs` and not `.ts`
//
// A Vite config file is bundled by esbuild before Node ever sees it, and whether a linked workspace
// package is inlined into that bundle or left as an external import is an implementation detail of
// Vite's config loader. Left external, a `.ts` preset reaches Node as a `.ts` import and the build
// dies before the first plugin runs. Plain JavaScript with JSDoc types cannot fail that way, and
// this file has no types worth the risk.
//
// # The seam
//
// The desktop and the website differ in exactly one resolvable module: `nodera-ui/platform/impl`,
// which re-exports the Tauri host. The website aliases it to the browser host, so
// `@tauri-apps/api` is not merely unreachable in the site bundle — it is not in the module graph at
// all, which is the property `web/tests/no-tauri-in-the-browser-bundle.test.mjs` asserts.
//
// A `window.__TAURI_INTERNALS__` runtime probe would have been the smaller diff and it is the wrong
// answer: both branches survive bundling, so the Tauri import ships to the browser regardless.
import { fileURLToPath } from "node:url";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

/** The specifier every consumer imports, and the only one the alias rewrites. */
const IMPL = "nodera-ui/platform/impl";

const BROWSER_IMPL = fileURLToPath(new URL("../src/platform/browser.ts", import.meta.url));

/**
 * React + Tailwind + the platform seam.
 *
 * @param {{ platform: "tauri" | "browser" }} options
 *   `platform` decides which host the bundle is built against. It is a build-time decision on
 *   purpose — see the note above.
 * @returns {import("vite").PluginOption[]}
 */
export function noderaVite(options) {
  const { platform } = options;
  if (platform !== "tauri" && platform !== "browser") {
    // Named rather than defaulted. A typo that silently produced a Tauri bundle for the website
    // would fail as a blank page in a browser console, four steps from its cause.
    throw new Error(`nodera-ui/vite: unknown platform "${platform}" (expected tauri or browser)`);
  }

  /** @type {import("vite").Plugin} */
  const seam = {
    name: "nodera-platform-seam",
    enforce: "pre",
    config() {
      return {
        // Both applications are built by scripts that print their own progress; a config that wipes
        // the scrolled-back output of the step before it is a build log nobody can read.
        clearScreen: false,
        build: { target: "es2021" },
        resolve:
          platform === "browser"
            ? { alias: [{ find: IMPL, replacement: BROWSER_IMPL }] }
            : undefined,
      };
    },
  };

  return [react(), tailwindcss(), seam];
}
