// The handful of facts about this repository that four generators would otherwise each work out.
//
// The GitHub slug is derived from `layout.properties`'s `services.rawBase` rather than typed. That
// key exists because three builds have to resolve the same published service list and a URL
// assembled independently in four languages is four chances to assemble it differently — the same
// argument applies to the fifth and sixth places that want to say "Ashu11-A/NoderaMC".
import { execFileSync } from "node:child_process";
import { layoutValue, repositoryDirectory } from "nodera-ui/layout";

const RAW_BASE = layoutValue("services.rawBase");
const slug = /^https:\/\/raw\.githubusercontent\.com\/([^/]+\/[^/]+)\//.exec(RAW_BASE);
if (!slug) {
  throw new Error(`repo: services.rawBase is not a raw.githubusercontent.com URL (${RAW_BASE})`);
}

/** `owner/name`, e.g. `Ashu11-A/NoderaMC`. */
export const SLUG = slug[1];

/** Where a repository file is read on GitHub. The target for any link this site does not mirror. */
export function blobUrl(repoRelativePath, anchor = "") {
  return `https://github.com/${SLUG}/blob/main/${repoRelativePath}${anchor}`;
}

/**
 * Where a repository file is edited on GitHub. The target of every mirrored page's edit link.
 *
 * `ref` is for the one mirrored source that does not live on `main`: the service-list README is on
 * the orphan `services` branch. An edit link pointing at `main` for that file offers to edit a path
 * that does not exist there, which GitHub answers with a new-file editor — an invitation to create a
 * second copy of the document the page is a mirror of.
 */
export function editUrl(repoRelativePath, ref = "main") {
  return `https://github.com/${SLUG}/edit/${ref ?? "main"}/${repoRelativePath}`;
}

/**
 * Run git in the repository and return stdout.
 *
 * `null` on failure rather than a throw, for the two callers where "git cannot answer" is a real
 * state a build has to survive: a shallow clone has no history to date a file from, and a checkout
 * that has never fetched the orphan branches has no test totals. Both render as "unknown" — never
 * as a fabricated date or a zero.
 */
export function git(args) {
  try {
    return execFileSync("git", args, { cwd: repositoryDirectory, encoding: "utf8" }).trim();
  } catch {
    return null;
  }
}

export { repositoryDirectory };
