// The repository layout table, for Node.
//
// Where a module, a crate or a frontend package lives is a property of the tree, not of any one
// toolchain, so it is written down once — in `/layout.properties` — and read by Gradle, the Java
// harness, the shell scripts, the Rust tests and this. Before that file existed the same table lived
// in five places and two of them were already stale: a test skipped for months against a directory
// that had moved, and a CI workflow watched a file that no longer existed. Nothing failed; the
// copies just disagreed.
//
// It lives in the shared kit because there are now three Node consumers — the desktop's source-text
// tests, the website's generators, and the website's tests — and the ten-line version of this is
// exactly the thing that gets pasted a fourth time.
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

/** The repository root, for the handful of consumers that reach outside a component. */
export const repositoryDirectory = ROOT;

/** One value from the manifest, exactly as written. Throws rather than guessing. */
export function layoutValue(key) {
  const value = ENTRIES.get(key);
  if (!value) throw new Error(`layout.properties has no ${key}`);
  return value;
}

/** The directory of a crate named in the manifest. */
export function crate(name) {
  return path.join(ROOT, layoutValue(`crate.${name}`));
}

/** Read a file inside a crate, e.g. `readCrate("nodera-core", "src/settings.rs")`. */
export function readCrate(name, relative) {
  return readFileSync(path.join(crate(name), relative), "utf8");
}

/**
 * The directory of a frontend package named in the manifest — `pkg("nodera-app-ui")`.
 *
 * The key suffix is the package's own `name` field, which is the rule `crate.*` follows for the same
 * reason: a dependency is written as `"nodera-ui": "workspace:*"`, so the name is what resolves, and
 * a directory renamed without its key is a dependency pointing at nothing.
 */
export function pkg(name) {
  return path.join(ROOT, layoutValue(`package.${name}`));
}

/** A well-known directory named in the manifest — `dir("web")`. */
export function dir(name) {
  return path.join(ROOT, layoutValue(`dir.${name}`));
}

/** Every `package.*` value, relative to the root, in declaration order. */
export function packageDirectories() {
  return [...ENTRIES]
    .filter(([key]) => key.startsWith("package."))
    .map(([, value]) => value);
}

/**
 * Every directory holding TypeScript that ships in the desktop app.
 *
 * The source-text rules in `app/ui/tests` — "no anchor for a web link", "every class resolves",
 * "every registered command has a caller" — are about the app's whole frontend surface, and that
 * surface stopped being one directory when the platform seam moved into the shared kit. A rule
 * pointed at one directory keeps passing over an ever-smaller set, which is a skip dressed as a
 * green.
 *
 * A missing directory is dropped rather than thrown on: a package can legitimately have no `src`
 * yet, and a rule that cannot run at all is worse than one running over one root.
 */
export function frontendRoots() {
  return [path.join(pkg("nodera-app-ui"), "src"), path.join(pkg("nodera-ui"), "src")].filter(
    (candidate) => {
      try {
        return statSync(candidate).isDirectory();
      } catch {
        return false;
      }
    },
  );
}
