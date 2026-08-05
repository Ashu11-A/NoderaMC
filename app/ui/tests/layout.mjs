// Where a crate's sources live, according to `/layout.properties`.
//
// The tests in this folder assert over Rust sources as well as TypeScript ones, and those files
// moved once already — from the shell crate into `nodera-core`. A relative path spelled out in each
// test would have made that move a silent skip rather than a failure, which is the one outcome a
// source-text assertion cannot survive. The manifest is the single table every other language in
// this repository reads for the same question; this is the ten-line Node version of it.
import { readFileSync, statSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

/** The repository root: the directory holding both `VERSION` and `settings.gradle.kts`. */
function repositoryRoot() {
  let directory = path.dirname(fileURLToPath(import.meta.url));
  for (;;) {
    try {
      readFileSync(path.join(directory, "VERSION"));
      readFileSync(path.join(directory, "settings.gradle.kts"));
      return directory;
    } catch {
      const parent = path.dirname(directory);
      if (parent === directory) throw new Error("cannot find the repository root");
      directory = parent;
    }
  }
}

const ROOT = repositoryRoot();

const ENTRIES = new Map(
  readFileSync(path.join(ROOT, "layout.properties"), "utf8")
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith("#") && !line.startsWith("!"))
    .map((line) => {
      const at = line.indexOf("=");
      return at === -1 ? null : [line.slice(0, at).trim(), line.slice(at + 1).trim()];
    })
    .filter(Boolean),
);

/** The repository root, for the handful of assertions that reach outside a component. */
export const repositoryDirectory = ROOT;

/** The directory of a crate named in the manifest. Throws rather than guessing. */
export function crate(name) {
  const value = ENTRIES.get(`crate.${name}`);
  if (!value) throw new Error(`layout.properties has no crate.${name}`);
  return path.join(ROOT, value);
}

/** Read a file inside a crate, e.g. `readCrate("nodera-core", "src/settings.rs")`. */
export function readCrate(name, relative) {
  return readFileSync(path.join(crate(name), relative), "utf8");
}

/**
 * The directory of a frontend package named in the manifest — `pkg("nodera-app-ui")`.
 *
 * The key suffix is the package's own `name` field, which is what makes this resolvable from a
 * dependency string: `"@nodera/ui": "workspace:*"` names a package, not a path.
 */
export function pkg(name) {
  const value = ENTRIES.get(`package.${name}`);
  if (!value) throw new Error(`layout.properties has no package.${name}`);
  return path.join(ROOT, value);
}

/** Every `package.*` value in the manifest, relative to the root, in declaration order. */
export function packageDirectories() {
  return [...ENTRIES]
    .filter(([key]) => key.startsWith("package."))
    .map(([, value]) => value);
}

/**
 * Every directory holding TypeScript that ships in the desktop app.
 *
 * The source-text rules in this folder — "no anchor for a web link", "every class resolves", "every
 * registered command has a caller" — are about the app's whole frontend surface, and that surface
 * stopped being one directory when the platform seam moved into `library/ts/nodera-ui`. A rule
 * pointed at `../src` alone would keep passing while the code it was written for moved out from
 * under it, which is a SKIP dressed as a green.
 *
 * Missing directories are dropped rather than thrown on: a package can legitimately have no `src`
 * yet, and a test that cannot run at all is worse than one running over one root.
 */
export function frontendRoots() {
  return [path.join(pkg("nodera-app-ui"), "src"), path.join(pkg("nodera-ui"), "src")].filter(
    (dir) => {
      try {
        return statSync(dir).isDirectory();
      } catch {
        return false;
      }
    },
  );
}
