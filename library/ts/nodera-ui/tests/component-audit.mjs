// One name, one component.
//
// # The bug this is here to stop shipping
//
// The launcher had two components called `Banner` — one exported from `components.tsx`, one declared
// privately in `TrackerStores.tsx` — and they disagreed about something that matters: only the local
// one set `role="alert"`, so eleven of the launcher's thirteen banners announced a failure to a
// screen reader as ordinary text that had appeared somewhere.
//
// Nothing could have noticed. `tsc` is happy, because they are different scopes and both are used
// where they are declared. The token audit is happy, because both class sets resolve. And a reviewer
// reading either file sees one `Banner` and no reason to go looking for another — which is the whole
// failure: the cost of a duplicate name is paid by the person who *doesn't* know it exists.
//
// # DECLARED, not exported
//
// The one that caused this was module-private. An export-only check would have found nothing.
//
// # `function X(` only
//
// This tree does not write components as arrow constants — measured, not assumed: across the site,
// the launcher and the kit, every `const NAME =` at module scope is a data table (`DESTINATIONS`,
// `PLATFORMS`, `TONE`), and a regex that allowed the arrow form would report every one of them.
//
// A `export default function` is excluded for a different reason, and it is the reason a name
// collision matters at all. A default export's name is local to its module — nothing outside can
// refer to it — so two of them are never the ambiguity this check is about. The site proves the
// point at scale: **26** route modules each declare `export default function Page`, which is the
// convention, not a collision twenty-six ways.
//
// # Why the SECOND half of this rule is deliberately absent
//
// "A screen must not open-code an element the shared module exports" was measured over this tree at
// around forty hits, most of them correct as written: a `<button>` carrying `role="tab"` and
// `aria-selected` is not a `Button`, and forcing it through one means either widening `Button` to
// pass arbitrary ARIA or fighting Tailwind's class ordering with an override that silently loses. A
// check that is wrong two times in five gets suppressed, and takes the half that works with it.
import { readFileSync } from "node:fs";
import path from "node:path";
import { sourceFiles } from "./token-audit.mjs";

/** A named component declaration, at the start of a line. `export default` is excluded — see above. */
const DECLARATION = /^(?:export )?function ([A-Z][A-Za-z0-9]*)\s*[(<]/gm;

/**
 * Every component name declared under `roots`, and the files declaring it.
 *
 * @param {string[]} roots directories to walk, from `layout.properties` — never a relative literal.
 * @returns {Map<string, string[]>} name → the files that declare it, relative to their own root.
 */
export function declaredComponents(roots) {
  const declaredIn = new Map();
  for (const root of roots) {
    for (const file of sourceFiles(root, /\.tsx?$/)) {
      const label = path.relative(root, file);
      for (const [, name] of readFileSync(file, "utf8").matchAll(DECLARATION)) {
        declaredIn.set(name, [...new Set([...(declaredIn.get(name) ?? []), label])]);
      }
    }
  }
  return declaredIn;
}

/**
 * The names declared in more than one module, as `name: fileA, fileB` lines.
 *
 * Sorted, so a failure message is stable across filesystems and reads as a diff when it changes.
 */
export function duplicateComponents(roots) {
  return [...declaredComponents(roots)]
    .filter(([, files]) => files.length > 1)
    .map(([name, files]) => `${name}: ${files.sort().join(", ")}`)
    .sort();
}
