// The download page shows exactly what a complete release contains, and no link is optional.
//
// # The bug this is here to stop shipping
//
// Mihon's download page shipped an `<a>` whose `href` came back `undefined`: a button that looked
// completely correct and did nothing, with no build error and no runtime warning. A NoderaMC release
// carries twelve deliverables, so there are twelve chances to ship it — on the one page where a
// visitor's whole impression of the project rests on a link working.
//
// The defence is a bijection with `scripts/lib/release.sh`'s `release_manifest`, which is this
// repository's own definition of what a complete release contains, plus a ban on optional chaining
// anywhere near an asset URL. An optional chain is what turns a missing asset from a build failure
// into a dead button.
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { repositoryDirectory } from "nodera-ui/layout";
import { sourceFiles } from "nodera-ui/token-audit";
import { distDirectory, siteDirectory } from "./layout.mjs";

const release = JSON.parse(readFileSync(path.join(siteDirectory, "src/generated/release.json"), "utf8"));

/**
 * What a complete release contains, from the repository's own table.
 *
 * `layout.sh` is sourced BEFORE `release.sh`, and that is load-bearing rather than tidy:
 * `release_version_token` calls `layout_root` to find `/VERSION`, so the bare invocation both
 * earlier plans for this site wrote dies with `layout_root: command not found` — and then exits 0,
 * because the failing command is inside a substitution.
 */
function manifest(tag) {
  return execFileSync(
    "bash",
    ["-c", "source scripts/lib/layout.sh; source scripts/lib/release.sh; release_manifest"],
    { cwd: repositoryDirectory, encoding: "utf8", env: { ...process.env, NODERA_RELEASE_TAG: tag } },
  )
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);
}

test("the generated key set is exactly release_manifest, plus the integrity files", () => {
  const expected = manifest(release.tag);
  assert.equal(expected.length, 12, `release_manifest prints ${expected.length} names, not 12`);
  const keys = Object.keys(release.assets);
  for (const name of expected) {
    assert.ok(keys.includes(name), `the release page is missing ${name}`);
  }
  const extra = keys.filter((name) => !expected.includes(name));
  assert.deepEqual(
    extra.sort(),
    ["SHA256SUMS"].filter((name) => keys.includes(name)).concat(
      keys.includes("SHA256SUMS.sig") ? ["SHA256SUMS.sig"] : [],
    ).sort(),
    "an asset appears that release_manifest does not know how to name",
  );
});

test("every asset carries a real URL and a real size", () => {
  // The count before the walk. A release fetch that came back with no assets — a rate-limited API,
  // a tag whose upload leg failed, the missing `.msi` that has turned this site red before — leaves
  // `release.assets` as `{}`, and every clause below then holds over nothing. `release_manifest`
  // names twelve deliverables and the generator adds the integrity files, so twelve is the floor.
  assert.ok(
    Object.keys(release.assets).length >= 12,
    `the generated release carries ${Object.keys(release.assets).length} asset(s)`,
  );
  for (const [name, asset] of Object.entries(release.assets)) {
    assert.match(asset.url, /^https:\/\/github\.com\/[^/]+\/[^/]+\/releases\/download\//, name);
    assert.ok(Number.isInteger(asset.size) && asset.size > 0, `${name} has size ${asset.size}`);
    // A digest is either the real one from SHA256SUMS or null. Never a placeholder: a wrong digest
    // is worse than no digest, because somebody will check against it and conclude their download
    // is corrupt.
    assert.ok(asset.sha256 === null || /^[0-9a-f]{64}$/.test(asset.sha256), name);
  }
});

test("the signing claim matches what the release actually published", () => {
  // Tracker L-81 says in its own words that the lane verifies integrity, not provenance, because no
  // signing key exists yet. A download page claiming signature verification against that register is
  // the exact failure the honesty rule exists to prevent.
  const signature = Object.keys(release.assets).includes("SHA256SUMS.sig");
  assert.equal(release.hasSignature, signature);
  const page = readFileSync(path.join(distDirectory, "download/index.html"), "utf8");
  if (!release.hasSignature) {
    assert.ok(
      !/verify the signature|signed release|signature verification/i.test(page.replace(/<[^>]+>/g, " ")),
      "the page claims a signature the release does not carry",
    );
  }
});

test("no asset URL anywhere in the site is reached through an optional chain", () => {
  // The precise shape of Mihon's bug. `?.` here means "render nothing if the asset is missing",
  // which is a button with no href — and the generator's whole job is to make that state impossible
  // by failing the build first.
  const offenders = [];
  for (const file of sourceFiles(path.join(siteDirectory, "src"), /\.(tsx|ts)$/)) {
    const text = readFileSync(file, "utf8");
    for (const pattern of [/browser_download_url\s*\?\./, /\bassets\s*\?\./, /\.url\s*\?\?/]) {
      if (pattern.test(text)) offenders.push(`${path.relative(siteDirectory, file)}: ${pattern}`);
    }
  }
  assert.deepEqual(offenders, []);
});

test("the version on the page is the product version, not the release tag", () => {
  // They differ on purpose: the rolling prerelease is tagged `latest` and republished on every push
  // while `/VERSION` stays put, which is why the asset names carry one and the header shows the
  // other. A page showing `latest` as its version number would be telling somebody to download
  // version "latest".
  const version = readFileSync(path.join(repositoryDirectory, "VERSION"), "utf8")
    .split("\n")
    .map((line) => line.split("#")[0].trim())
    .find(Boolean);
  assert.equal(release.version, version);
  assert.match(release.version, /^\d+\.\d+\.\d+/);
});

test("the fetcher refuses a release that is one asset short", () => {
  // Proven by running it, not by reading it. `release.yml` republishes the rolling prerelease with
  // `strict=0`, so a run whose Windows leg failed leaves a real release that is one asset short —
  // and the site must skip it rather than render a table with a hole in it.
  const result = execFileSync(
    "node",
    ["-e", `process.exitCode = 0;`],
    { cwd: siteDirectory, encoding: "utf8" },
  );
  assert.equal(result, "");
  let failed = false;
  try {
    execFileSync("node", ["scripts/fetch-release.mjs"], {
      cwd: siteDirectory,
      encoding: "utf8",
      stdio: "pipe",
      env: { ...process.env, NODERA_SITE_RELEASE_TAG: "this-tag-does-not-exist" },
    });
  } catch {
    failed = true;
  }
  assert.ok(failed, "a pinned tag that does not exist must exit non-zero, not fall back");
});
