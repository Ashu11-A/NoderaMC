// Where a crate's sources live, according to `/layout.properties`.
//
// The tests in this folder assert over Rust sources as well as TypeScript ones, and those files
// moved once already — from the shell crate into `nodera-core`. A relative path spelled out in each
// test would have made that move a silent skip rather than a failure, which is the one outcome a
// source-text assertion cannot survive. The manifest is the single table every other language in
// this repository reads for the same question; this is the ten-line Node version of it.
import { readFileSync } from "node:fs";
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
