// Nine pages on this site are files in this repository, rendered.
//
// The operator guides, the two service references, the wire protocol, the engine SDK, the
// compatibility contract and the roadmap were all written for outsiders and all have to be correct
// already. Mirroring them is the only arrangement in which the site cannot drift from them.
//
// **The mirror is a reader.** It never writes to `docs/`, and `web/tests/mirror.test.mjs` proves it:
// every source file's SHA-256 is identical before and after a full build. That is what makes
// "`AI-AGENT-INSTRUCTION` comments are stripped from the OUTPUT" an enforceable statement rather
// than an intention.
//
// Five properties, all fail-closed:
//
//  1. An ALLOWLIST manifest (`web/content/mirror.json`), never a glob. Someone adding a document to
//     `docs/` has not thereby published it.
//  2. Front matter lives in the manifest, never in the source — `docs/README.md` §2 makes the format
//     of those files binding, and a YAML header would change it.
//  3. Link rewriting through `scripts/lib/docs_links.py`, the SAME resolver `check-docs.sh` uses. A
//     repo path that does not resolve on disk fails the build; one that resolves but is not mirrored
//     becomes a GitHub URL. That third case is not an edge case — `docs/ROADMAP.md` alone carries
//     about a hundred links to task files that are deliberately not mirrored.
//  4. The agent-instruction comments are stripped from the rendered output.
//  5. Every page says it is a mirror, names its source, and links to where the source is edited.
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import path from "node:path";
import { unified } from "unified";
import remarkParse from "remark-parse";
import remarkGfm from "remark-gfm";
import remarkRehype from "remark-rehype";
import rehypeSlug from "rehype-slug";
import rehypeAutolinkHeadings from "rehype-autolink-headings";
import rehypeShiki from "@shikijs/rehype";
import rehypeStringify from "rehype-stringify";
import { visit } from "unist-util-visit";
import { dir, layoutValue, repositoryDirectory } from "nodera-ui/layout";
import { blobUrl, editUrl, git } from "./repo.mjs";

const SITE = dir("web");
const MANIFEST = path.join(SITE, "content/mirror.json");
const OUT = path.join(SITE, "src/generated/mirror.json");
const AGENT_COMMENT = /<!--\s*AI-AGENT-INSTRUCTION:[\s\S]*?-->/g;

const die = (message) => {
  console.error(`mirror-docs: ${message}`);
  process.exit(1);
};

const manifest = JSON.parse(readFileSync(MANIFEST, "utf8"));
if (!Array.isArray(manifest) || manifest.length === 0) die(`${MANIFEST} is not a non-empty array`);

const seenRoute = new Set();
const seenSource = new Set();
for (const entry of manifest) {
  for (const required of ["route", "source", "title", "description"]) {
    if (!entry[required]) die(`an entry is missing "${required}": ${JSON.stringify(entry)}`);
  }
  if (!entry.route.startsWith("/docs/")) die(`${entry.route} is not under /docs/`);
  if (seenRoute.has(entry.route)) die(`two entries claim ${entry.route}`);
  if (seenSource.has(entry.source)) die(`${entry.source} is mirrored twice`);
  if (entry.description.length > 160) {
    die(`${entry.route}'s description is ${entry.description.length} characters; 160 is the limit`);
  }
  seenRoute.add(entry.route);
  seenSource.add(entry.source);
}

/**
 * A source, split into the git ref it lives on and the path inside it.
 *
 * `docs/tracker/REFERENCE.md` is a file in this working tree. `services:README.md` is a file on the
 * orphan `services` branch, which shares no history with this one — that branch holds the published
 * service list because a list that changes when an operator joins is data rather than source. The
 * `<ref>:<path>` form is exactly what `git show` takes and exactly how every other resolver in this
 * repository reads that branch.
 *
 * The ref is checked against `layout.properties` rather than accepted as written: the branch name is
 * declared there, and a manifest naming some other branch would be a second answer to which list
 * this project publishes.
 */
function split(source) {
  const at = source.indexOf(":");
  if (at === -1) return { ref: null, file: source };
  const ref = source.slice(0, at);
  const declared = layoutValue("services.branch");
  if (ref !== declared) {
    die(`${source} names the ref "${ref}"; layout.properties declares "${declared}"`);
  }
  return { ref, file: source.slice(at + 1) };
}

/** Where a mirrored source's own relative links resolve from, repository-relative. */
const baseOf = (entry) => path.posix.dirname(split(entry.source).file);

/** Route by repository path, for rule 3's second case. */
const routeBySource = new Map(manifest.map((entry) => [split(entry.source).file, entry.route]));

/** The bytes of one source. */
function sourceText(entry) {
  const { ref, file } = split(entry.source);
  if (ref) {
    const text = git(["show", `${ref}:${file}`]);
    if (text === null) {
      die(
        `${entry.source} is not on the "${ref}" ref. ` +
          `\`git fetch ${layoutValue("services.remote")} ${ref}:${ref}\` is what is missing.`,
      );
    }
    return text;
  }
  try {
    return readFileSync(path.join(repositoryDirectory, file), "utf8");
  } catch {
    return die(`${entry.source} is in the manifest and not on disk`);
  }
}

/**
 * Every link in a document, resolved by the resolver `check-docs.sh` uses.
 *
 * Shelling out to Python is the point rather than an accident. There is exactly one definition of
 * what a relative link in `docs/` means, and it is `scripts/lib/docs_links.py`; a JavaScript
 * reimplementation would be a second one, and the failure of a second one is silent — a link that
 * renders as text, or as a URL pointing at a file that moved, looks exactly like a link that works.
 */
function resolvedLinks(entry, text) {
  // Written where the mirror's own scratch lives rather than into `docs/`: this build is a reader.
  const scratch = path.join(SITE, ".mirror-source.md");
  writeFileSync(scratch, text);
  try {
    const out = execFileSync(
      "python3",
      ["scripts/lib/docs_links.py", scratch, "--base", path.join(repositoryDirectory, baseOf(entry))],
      { cwd: repositoryDirectory, encoding: "utf8" },
    );
    return new Map(JSON.parse(out).map((row) => [row.target, row]));
  } finally {
    execFileSync("rm", ["-f", scratch]);
  }
}

/** Rewrite every anchor, and fail the build on one that resolves to nothing. */
function rehypeRepoLinks(entry, links) {
  return () => (tree) => {
    visit(tree, "element", (node) => {
      if (node.tagName !== "a") return;
      const href = String(node.properties?.href ?? "");
      if (!href) return;
      if (/^(https?:|mailto:)/.test(href)) {
        node.properties.rel = "noopener noreferrer";
        return;
      }
      if (href.startsWith("#")) return; // an in-page anchor, already correct

      const link = links.get(href) ?? links.get(href.split(" ")[0]);
      if (!link) return; // not a link this resolver recognises as a path; left alone
      if (!link.exists || !link.resolved) {
        die(
          `${entry.source} links to ${href}, which resolves to nothing. ` +
            "That is the same failure `scripts/check-docs.sh` fails on, and it must not become a " +
            "dead URL on a public page.",
        );
      }
      const route = routeBySource.get(link.resolved);
      node.properties.href = route
        ? `${route}${link.anchor}`
        : blobUrl(link.resolved, link.anchor);
      if (!route) node.properties.rel = "noopener noreferrer";
    });
  };
}

/** Every H2 and H3, with the id `rehype-slug` gave it. Deeper than H3 is a second navigation. */
function collectToc(toc) {
  return () => (tree) => {
    visit(tree, "element", (node) => {
      const depth = /^h([23])$/.exec(node.tagName);
      if (!depth) return;
      const text = [];
      visit(node, "text", (child) => text.push(child.value));
      toc.push({ depth: Number(depth[1]), id: String(node.properties?.id ?? ""), text: text.join("").trim() });
    });
  };
}

const entries = [];
for (const entry of manifest) {
  const raw = sourceText(entry);
  // Rule 4, and it happens HERE — on the string being rendered, never on the file.
  const text = raw.replace(AGENT_COMMENT, "");
  const links = resolvedLinks(entry, text);
  const toc = [];

  const file = await unified()
    .use(remarkParse)
    .use(remarkGfm)
    .use(remarkRehype, { allowDangerousHtml: false })
    .use(rehypeSlug)
    .use(rehypeAutolinkHeadings, { behavior: "wrap" })
    .use(collectToc(toc))
    .use(rehypeRepoLinks(entry, links))
    .use(rehypeShiki, { themes: { light: "github-light", dark: "github-dark" }, defaultColor: "light" })
    .use(rehypeStringify)
    .process(text);

  const html = String(file);
  if (html.includes("AI-AGENT-INSTRUCTION")) {
    die(`${entry.source}'s rendered output still contains AI-AGENT-INSTRUCTION`);
  }

  entries.push({
    source: entry.source,
    route: entry.route,
    title: entry.title,
    description: entry.description,
    html,
    // `git log -1` over a shallow clone has no history to answer with. Null renders as "unknown",
    // never as today's date: a clone date presented as a document's last edit is a fabricated fact,
    // and CI checks out with `fetch-depth: 0` precisely so this is not null there.
    updatedAt: split(entry.source).ref
      ? git(["log", "-1", "--format=%cI", split(entry.source).ref, "--", split(entry.source).file])
      : git(["log", "-1", "--format=%cI", "--", split(entry.source).file]),
    editUrl: editUrl(split(entry.source).file, split(entry.source).ref),
    toc,
  });
  console.log(
    `mirror-docs: ${entry.route} ← ${entry.source} (${links.size} links, ${toc.length} headings)`,
  );
}

mkdirSync(path.dirname(OUT), { recursive: true });
writeFileSync(OUT, `${JSON.stringify({ entries }, null, 2)}\n`);
console.log(`mirror-docs: ${entries.length} page(s)`);
