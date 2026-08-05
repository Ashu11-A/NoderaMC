// The download page's data, and the gate that stops it lying.
//
// # The failure this file exists to prevent
//
// Mihon's download page shipped an `<a>` whose `href` came back `undefined`: a button that looked
// completely correct and did nothing, with no build error and no runtime warning. There are TWELVE
// assets in a NoderaMC release, so there are twelve chances to ship it — and the page they feed is
// the one place a visitor's whole impression of the project rests on a link working.
//
// So there is no optional chaining anywhere downstream of this file, and there is no default. The
// generator holds the release to a **bijection** with `scripts/lib/release.sh`'s `release_manifest`,
// which is this repository's own definition of what a complete release contains:
//
//   * every manifest line resolves to exactly one published asset;
//   * every published asset is either in the manifest or is one of the two integrity files;
//   * a missing, duplicated or unknown asset exits 1 and prints its name.
//
// # Why it walks backwards through releases
//
// `release.yml` republishes the rolling `latest` prerelease on every push to main, with `strict=0`,
// so a run whose Windows leg failed leaves a real release that is one asset short. That must not
// wedge the site — but it must never be *published* as if it were complete either. Walking
// newest-first and taking the first release that satisfies the bijection is both: the site shows the
// newest complete release, and an incomplete one is skipped rather than rendered with a hole in it.
// `NODERA_SITE_RELEASE_TAG` pins one release and disables the walk, which is how a release build
// asserts its own release.
import { execFileSync } from "node:child_process";
import { writeFileSync, mkdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { dir, repositoryDirectory } from "nodera-ui/layout";
import { SLUG } from "./repo.mjs";

const OUT = path.join(dir("web"), "src/generated/release.json");
const PINNED = process.env.NODERA_SITE_RELEASE_TAG ?? "";
const CHECKSUMS = "SHA256SUMS";
const SIGNATURE = "SHA256SUMS.sig";

const die = (message) => {
  console.error(`fetch-release: ${message}`);
  process.exit(1);
};

/**
 * The complete contents of a release with this tag, one name per line.
 *
 * `layout.sh` is sourced BEFORE `release.sh` and that is not stylistic: `release_version_token`
 * calls `layout_root` to find `/VERSION`, so the bare invocation both earlier plans for this site
 * wrote dies with `layout_root: command not found` and then reports `/VERSION is empty` — with exit
 * status 0, because the failing command is inside a substitution.
 */
function manifestFor(tag) {
  const script =
    "source scripts/lib/layout.sh; source scripts/lib/release.sh; release_manifest";
  const out = execFileSync("bash", ["-c", script], {
    cwd: repositoryDirectory,
    encoding: "utf8",
    env: { ...process.env, NODERA_RELEASE_TAG: tag },
  });
  const names = out.split("\n").map((line) => line.trim()).filter(Boolean);
  if (names.length === 0) die("release_manifest printed nothing");
  return names;
}

async function json(url) {
  const response = await fetch(url, {
    headers: {
      accept: "application/vnd.github+json",
      "user-agent": "noderamc-site-build",
      ...(process.env.GITHUB_TOKEN ? { authorization: `Bearer ${process.env.GITHUB_TOKEN}` } : {}),
    },
  });
  if (!response.ok) die(`${url} answered ${response.status} ${response.statusText}`);
  return response.json();
}

/**
 * Hold one release to the manifest.
 *
 * Returns the reason it does not qualify, or `null` when it does. A reason rather than a boolean,
 * because "the newest ten releases were all rejected" is useless without the twelve sentences saying
 * why each one was.
 */
function shortfall(release, names) {
  const published = new Map();
  for (const asset of release.assets) {
    if (published.has(asset.name)) return `two assets are named ${asset.name}`;
    published.set(asset.name, asset);
  }
  const missing = names.filter((name) => !published.has(name));
  if (missing.length > 0) return `no asset named ${missing.join(", ")}`;
  const extra = [...published.keys()].filter(
    (name) => !names.includes(name) && name !== CHECKSUMS && name !== SIGNATURE,
  );
  if (extra.length > 0) {
    return `published ${extra.join(", ")}, which release_manifest does not know how to name`;
  }
  return null;
}

/** `SHA256SUMS`, as `name -> digest`. Absent means every `sha256` is null, never a made-up digest. */
async function digests(release) {
  const asset = release.assets.find((candidate) => candidate.name === CHECKSUMS);
  if (!asset) return new Map();
  const response = await fetch(asset.browser_download_url, {
    headers: { "user-agent": "noderamc-site-build" },
  });
  if (!response.ok) die(`${CHECKSUMS} answered ${response.status}`);
  const text = await response.text();
  return new Map(
    text
      .split("\n")
      .map((line) => /^([0-9a-f]{64})\s+\*?(.+)$/.exec(line.trim()))
      .filter(Boolean)
      .map((match) => [match[2].trim(), match[1]]),
  );
}

/* ------------------------------------------------------------------ the release body, safely */

const escape = (value) =>
  String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");

/**
 * The first section of a release body, as HTML that cannot carry a script.
 *
 * Everything is escaped FIRST and a short list of inline forms is re-introduced afterwards, so the
 * output is safe by construction rather than by a sanitiser's blocklist. An `<a>` is emitted only
 * for an `https:` target: a release body is text somebody typed into a web form, and this page
 * renders it with `dangerouslySetInnerHTML`.
 *
 * A rendering dependency was considered and is not worth it. The whole input is a handful of
 * sentences produced by `release.yml`, and a markdown-to-HTML library on this path would be a
 * dependency whose only job is to be trusted with a string from outside this repository.
 */
function whatChanged(body) {
  const section = String(body ?? "").split(/\n#{1,6}\s/)[0].trim();
  if (!section) return "";
  return section
    .split(/\n\s*\n/)
    .map((paragraph) => {
      const inline = (text) =>
        escape(text)
          .replace(/`([^`]+)`/g, "<code>$1</code>")
          .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
          .replace(
            /\[([^\]]+)\]\((https:[^)\s]+)\)/g,
            '<a href="$2" rel="noopener noreferrer">$1</a>',
          );
      const lines = paragraph.split("\n").map((line) => line.trim());
      if (lines.every((line) => /^[-*]\s+/.test(line))) {
        return `<ul>${lines.map((line) => `<li>${inline(line.slice(2))}</li>`).join("")}</ul>`;
      }
      return `<p>${inline(lines.join(" "))}</p>`;
    })
    .join("\n");
}

/* ----------------------------------------------------------------------------------- the run */

const releases = PINNED
  ? [await json(`https://api.github.com/repos/${SLUG}/releases/tags/${encodeURIComponent(PINNED)}`)]
  : await json(`https://api.github.com/repos/${SLUG}/releases?per_page=10`);

if (!Array.isArray(releases) || releases.length === 0) die("this repository has published no releases");

const rejected = [];
let chosen = null;
let names = null;
for (const release of releases) {
  const expected = manifestFor(release.tag_name);
  const reason = shortfall(release, expected);
  if (reason === null) {
    chosen = release;
    names = expected;
    break;
  }
  rejected.push(`${release.tag_name}: ${reason}`);
}

if (!chosen) {
  die(
    `no release satisfies the manifest:\n  ${rejected.join("\n  ")}\n` +
      "A release short of an asset is a platform that failed to build. Re-run that leg, or pin a " +
      "complete release with NODERA_SITE_RELEASE_TAG.",
  );
}
if (rejected.length > 0) {
  console.warn(`fetch-release: skipped ${rejected.length} incomplete release(s):\n  ${rejected.join("\n  ")}`);
}

const sums = await digests(chosen);
const assets = {};
for (const name of names) {
  const asset = chosen.assets.find((candidate) => candidate.name === name);
  assets[name] = {
    name,
    url: asset.browser_download_url,
    size: asset.size,
    sha256: sums.get(name) ?? null,
  };
}
// The two integrity files are published with the deliverables and are not deliverables themselves,
// so `release.sh` names them separately. They are still downloadable, and `/download` is the only
// page that can offer them.
for (const name of [CHECKSUMS, SIGNATURE]) {
  const asset = chosen.assets.find((candidate) => candidate.name === name);
  if (!asset) continue;
  assets[name] = { name, url: asset.browser_download_url, size: asset.size, sha256: null };
}

// The product version, not the release tag. They differ on purpose: the rolling prerelease is tagged
// `latest` and republished on every push while `/VERSION` stays put, which is why the asset names
// carry one and the site's header shows the other.
const version = readFileSync(path.join(repositoryDirectory, "VERSION"), "utf8")
  .split("\n")
  .map((line) => line.split("#")[0].trim())
  .find(Boolean);
if (!version) die("/VERSION is empty");

const release = {
  tag: chosen.tag_name,
  version,
  publishedAt: chosen.published_at,
  htmlUrl: chosen.html_url,
  whatChanged: whatChanged(chosen.body),
  assets,
  hasSignature: chosen.assets.some((asset) => asset.name === SIGNATURE),
};

mkdirSync(path.dirname(OUT), { recursive: true });
writeFileSync(OUT, `${JSON.stringify(release, null, 2)}\n`);
console.log(
  `fetch-release: ${chosen.tag_name} — ${names.length} manifest assets, ` +
    `${sums.size} digests, signature ${release.hasSignature ? "present" : "absent"}`,
);
