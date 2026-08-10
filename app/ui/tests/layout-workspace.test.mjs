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
//
// The last rule here is about the same table and the same silence from the other side: a `package.*`
// row is a claim that a package exists, never a claim that anything runs what is in it, and a suite
// nothing runs is a suite whose assertions are decoration.
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync, readdirSync, existsSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { packageDirectories, pkg, repositoryDirectory } from "./layout.mjs";

const rootManifest = JSON.parse(
  readFileSync(path.join(repositoryDirectory, "package.json"), "utf8"),
);

test("the root package.json's workspaces are exactly layout.properties' package.* directories", () => {
  // The count first. `deepEqual` between two empty arrays holds, so a manifest reader that returned
  // nothing — a moved `layout.properties`, a `package.*` prefix that changed — would pass this rule
  // and leave the one below iterating no packages at all. Three today: the kit and two applications.
  assert.ok(packageDirectories().length >= 3, `layout.properties declares ${packageDirectories().length} package(s)`);
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

/** Does this package directory ship a `node --test` suite of its own? */
const shipsASuite = (directory) =>
  existsSync(path.join(repositoryDirectory, directory, "tests")) &&
  readdirSync(path.join(repositoryDirectory, directory, "tests")).some((f) => f.endsWith(".test.mjs"));

test("every package declaring a suite has a script that runs it and counts the result", () => {
  // The second thing `layout.properties` cannot make anybody do. A `package.*` row plus a `tests/`
  // directory is not a suite that runs: `scripts/build-app-ui.sh` and `scripts/build-site.sh` each
  // hardcode the one package they build, so a third package holding real assertions would be
  // executed by nothing at all. `scripts/test-counts.sh` then reports it `skipped` and asks only for
  // a README row, and a row carrying an em dash in its count cell satisfies that — so the gate would
  // agree with itself for ever while a failing assertion sat in the tree unexecuted.
  //
  // `--runners` is that script naming the file that both runs and counts each package, which is the
  // rule itself; it is driven here rather than restated, because a second copy of a rule is how this
  // tree ends up with two that disagree. It reads the manifest and greps `scripts/`, so it needs
  // neither bun nor cargo and costs this suite a few milliseconds.
  //
  // Invoked through `bash` rather than executed directly, and given a repository-relative path.
  // Windows cannot exec a `.sh` file at all — `execFileSync` on one raises `EFTYPE`, which is how
  // both Windows legs of the release run died inside this package's `build` script, which is how
  // the `latest` release lost both `.msi` assets, which is how `web/scripts/fetch-release.mjs`
  // then failed closed and turned `web` and `companion` red on every open pull request, including
  // pull requests touching no web file at all. The error names this suite; the damage was three
  // jobs away. Git Bash is on PATH on GitHub's Windows runners, and passing the path relative to
  // `cwd` keeps it a POSIX path on every platform.
  const rows = execFileSync("bash", ["scripts/test-counts.sh", "--runners"], {
    cwd: repositoryDirectory,
    encoding: "utf8",
  }).trim().split("\n");

  // The count first, then the domain: a script reporting a runner for every package it looked at is
  // worth nothing if it looked at none of them, and the list it looked at is derived, so it is held
  // to the manifest here exactly as the workspaces array is above. `nodera-ui` is absent from both
  // sides by the rule rather than by name — the kit's `tests/` holds only the audit modules both
  // applications import, and it declares no `*.test.mjs` of its own.
  assert.ok(rows.length >= 2, `--runners answered about ${rows.length} package(s)`);
  assert.deepEqual(
    rows.map((row) => row.split("\t")[0]).sort(),
    packageDirectories()
      .filter(shipsASuite)
      .map((d) => JSON.parse(readFileSync(path.join(repositoryDirectory, d, "package.json"), "utf8")).name)
      .sort(),
    "scripts/test-counts.sh --runners and layout.properties disagree about which packages ship a suite",
  );
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
