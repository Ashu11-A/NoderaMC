// The one row of the layout table that `layout.properties` cannot make anybody read.
//
// Bun resolves `/package.json`'s `workspaces` array before a line of this repository's own code
// runs, exactly as Tauri parses `tauri.conf.json` and GitHub evaluates a `paths:` filter first.
// `scripts/check-layout-drift.sh` exists for that class of file, and this is its fourth member —
// checked here rather than there because the property is not "the path exists" but "the two lists
// are the same list", and the failure is the one this repository keeps having: a package moves,
// something keeps working against the old answer, and nothing says a word.
//
// A workspace entry that no longer matches a directory does not error either. Bun simply resolves
// one package fewer, `@nodera/ui` becomes an unknown dependency, and the message names a package
// nobody has ever heard of instead of a directory that moved.
import assert from "node:assert/strict";
import { readFileSync, existsSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { packageDirectories, pkg, repositoryDirectory } from "./layout.mjs";

const rootManifest = JSON.parse(
  readFileSync(path.join(repositoryDirectory, "package.json"), "utf8"),
);

test("the root package.json's workspaces are exactly layout.properties' package.* directories", () => {
  assert.deepEqual(
    [...rootManifest.workspaces].sort(),
    [...packageDirectories()].sort(),
    "the workspace array and the layout manifest disagree about which packages exist",
  );
});

test("every package.* key names the package that lives there", () => {
  // The key suffix IS the `name` field, which is the rule `crate.*` follows for the same reason:
  // a dependency is written as `"@nodera/ui": "workspace:*"`, so the name is what resolves and a
  // directory renamed without its key is a dependency pointing at nothing.
  for (const directory of packageDirectories()) {
    const manifest = path.join(repositoryDirectory, directory, "package.json");
    assert.ok(existsSync(manifest), `${directory} is a declared package with no package.json`);
    const { name } = JSON.parse(readFileSync(manifest, "utf8"));
    assert.equal(
      pkg(name),
      path.join(repositoryDirectory, directory),
      `package.json in ${directory} calls itself "${name}", which layout.properties puts elsewhere`,
    );
  }
});

test("the workspace declares one bun and one lockfile", () => {
  // `packageManager` is what makes `bun install` reproducible across a laptop and a runner; the
  // lockfile is what makes `--frozen-lockfile` mean anything. `app/.gitignore` ignored the old
  // per-package lockfile, so the flag had never had a file to freeze against.
  assert.match(rootManifest.packageManager ?? "", /^bun@\d+\.\d+\.\d+$/);
  assert.ok(
    existsSync(path.join(repositoryDirectory, "bun.lock")),
    "bun.lock is not committed, so --frozen-lockfile freezes nothing",
  );
});
