// The mirror's five fail-closed properties, and the one that makes "output only" enforceable.
//
// Nine pages on this site are files in this repository, rendered. That arrangement is only safe
// while the mirror is a READER — and "the agent-instruction comments are stripped from the output"
// is a claim about a transformation nobody can see from the output alone. So the fourth test below
// hashes every source before and after a full build. If the mirror ever edited `docs/` in passing,
// that is where it shows up.
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { readFileSync, existsSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { layoutValue, repositoryDirectory } from "nodera-ui/layout";
import { sourceFiles } from "nodera-ui/token-audit";
import { distDirectory, siteDirectory } from "./layout.mjs";

const manifest = JSON.parse(readFileSync(path.join(siteDirectory, "content/mirror.json"), "utf8"));
const generated = JSON.parse(readFileSync(path.join(siteDirectory, "src/generated/mirror.json"), "utf8"));
const { routes } = await import(path.join(siteDirectory, ".ssr/main.js"));

/** A source's ref and path. `services:README.md` lives on the orphan branch of that name. */
function split(source) {
  const at = source.indexOf(":");
  return at === -1 ? { ref: null, file: source } : { ref: source.slice(0, at), file: source.slice(at + 1) };
}

/* ------------------------------------------------------------------------ 1. the allowlist */

test("the manifest is an allowlist, and every entry is well formed", () => {
  assert.ok(Array.isArray(manifest) && manifest.length > 0);
  const routesSeen = new Set();
  const sourcesSeen = new Set();
  for (const entry of manifest) {
    assert.ok(entry.route.startsWith("/docs/"), `${entry.route} is not under /docs/`);
    assert.ok(!routesSeen.has(entry.route), `${entry.route} is claimed twice`);
    assert.ok(!sourcesSeen.has(entry.source), `${entry.source} is mirrored twice`);
    assert.ok(entry.title && entry.title.length > 0, `${entry.route} has no title`);
    assert.ok(entry.description.length > 0 && entry.description.length <= 160);
    routesSeen.add(entry.route);
    sourcesSeen.add(entry.source);

    const { ref, file } = split(entry.source);
    if (ref) {
      // A ref is checked against the manifest rather than accepted as written: naming some other
      // branch would be a second answer to which list this project publishes.
      assert.equal(ref, layoutValue("services.branch"));
      assert.doesNotThrow(() =>
        execFileSync("git", ["show", `${ref}:${file}`], { cwd: repositoryDirectory, stdio: "pipe" }),
      );
    } else {
      assert.ok(existsSync(path.join(repositoryDirectory, file)), `${file} is not on disk`);
      assert.ok(
        file.startsWith("docs/") || !file.includes("/"),
        `${file} is outside docs/ and outside the repository root`,
      );
    }
  }
});

test("every mirrored route is a route, and every mirror produced output", () => {
  const declared = new Set(routes.map((route) => route.path));
  for (const entry of manifest) {
    assert.ok(declared.has(entry.route), `${entry.route} is mirrored but is not a route`);
    const produced = generated.entries.find((page) => page.route === entry.route);
    assert.ok(produced, `${entry.route} produced no output`);
    assert.ok(produced.html.length > 500, `${entry.route} rendered ${produced.html.length} bytes`);
    assert.ok(produced.editUrl.startsWith("https://github.com/"));
  }
  assert.equal(generated.entries.length, manifest.length);
});

/* ------------------------------------------------------- 2. front matter stays in the manifest */

test("no mirrored source has grown a YAML header", () => {
  // `docs/README.md` §2 makes the format of those files binding. A front-matter block added for the
  // website's convenience would change a contract the website is not a party to.
  for (const entry of manifest) {
    const { ref, file } = split(entry.source);
    const text = ref
      ? execFileSync("git", ["show", `${ref}:${file}`], { cwd: repositoryDirectory, encoding: "utf8" })
      : readFileSync(path.join(repositoryDirectory, file), "utf8");
    assert.ok(!text.startsWith("---\n"), `${entry.source} starts with front matter`);
  }
});

/* ------------------------------------------------------------------ 3. links, resolved once */

test("the mirror rewrote its links, and left none of them relative", () => {
  for (const page of generated.entries) {
    for (const [, href] of page.html.matchAll(/href="([^"]+)"/g)) {
      assert.ok(
        href.startsWith("/") || href.startsWith("#") || /^(https?:|mailto:)/.test(href),
        `${page.route} kept the relative link ${href}, which resolves against the page's URL`,
      );
    }
  }
});

test("a link into a mirrored file became a site route, not a GitHub URL", () => {
  // The rule has three branches and only this one is easy to lose: the two self-hosting guides link
  // to each other, and rendering those as GitHub URLs would walk a reader off the site in the middle
  // of a task.
  const tracker = generated.entries.find((page) => page.route === "/docs/operate/tracker");
  assert.ok(tracker.html.includes('href="/docs/operate/rendezvous"'));
});

test("a link to a file that is NOT mirrored became a GitHub URL", () => {
  // `docs/ROADMAP.md` alone carries about a hundred links to task files that are deliberately not
  // mirrored. If this branch were missing they would all be dead.
  const roadmap = generated.entries.find((page) => page.route === "/docs/develop/roadmap");
  assert.match(roadmap.html, /href="https:\/\/github\.com\/[^/]+\/[^/]+\/blob\/main\/docs\/[^"]*Task\.\d+\.md"/);
});

/* ------------------------------------- 4. stripped from the OUTPUT, and only from the output */

test("no page this build RENDERED contains AI-AGENT-INSTRUCTION", () => {
  // `add-store.html` is excluded by name and it is the one honest exception: those are the bytes
  // that were reviewed, installed unchanged, and its header comment opens with that very marker.
  // Stripping it would mean this build had edited the page — which is the thing the digest
  // assertion in `add-store.test.mjs` exists to make impossible.
  const offenders = sourceFiles(distDirectory, /\.(html|js|css|xml|txt)$/)
    .filter((file) => path.relative(distDirectory, file) !== "add-store.html")
    .filter((file) => readFileSync(file, "utf8").includes("AI-AGENT-INSTRUCTION"))
    .map((file) => path.relative(distDirectory, file));
  assert.deepEqual(offenders, []);
});

test("every mirrored source is byte-identical to what git has for it", () => {
  // This is what makes the test above mean "stripped from the output" rather than "stripped".
  // A mirror that edited its sources in place would satisfy the previous assertion perfectly.
  const dirty = [];
  for (const entry of manifest) {
    const { ref, file } = split(entry.source);
    if (ref) continue; // a file on another branch is read with `git show`; nothing can write to it
    const onDisk = createHash("sha256").update(readFileSync(path.join(repositoryDirectory, file))).digest("hex");
    const committed = execFileSync("git", ["show", `HEAD:${file}`], {
      cwd: repositoryDirectory,
      encoding: "buffer",
      maxBuffer: 32 * 1024 * 1024,
    });
    const inGit = createHash("sha256").update(committed).digest("hex");
    if (onDisk !== inGit) dirty.push(file);
  }
  assert.deepEqual(dirty, [], "the mirror is a reader; these sources differ from their committed form");
});

/* ------------------------------------------------------- 5. the page says it is a mirror */

test("every mirrored page carries its source, its edit link and its headings", () => {
  for (const page of generated.entries) {
    assert.ok(page.source.length > 0);
    assert.equal(page.editUrl.includes(split(page.source).file), true);
    assert.ok(Array.isArray(page.toc));
    // `updatedAt` may legitimately be null in a shallow clone — that renders as "unknown", never as
    // the clone date. What it must never be is a value that is not a timestamp.
    if (page.updatedAt !== null) assert.match(page.updatedAt, /^\d{4}-\d{2}-\d{2}T/);
  }
});

test("a mirrored page reaches the site, with its content", () => {
  const route = manifest[0].route;
  const emitted = readFileSync(path.join(distDirectory, `${route.replace(/^\//, "")}/index.html`), "utf8");
  const source = generated.entries.find((page) => page.route === route);
  const firstHeading = /<h2[^>]*id="([^"]+)"/.exec(source.html);
  assert.ok(firstHeading, `${route} rendered no headings at all`);
  assert.ok(emitted.includes(firstHeading[1]), `${route}'s rendered body did not reach the page`);
});
