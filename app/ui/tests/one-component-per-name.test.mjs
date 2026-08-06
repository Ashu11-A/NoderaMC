// One name, one component.
//
// # What this catches, and what it deliberately does not
//
// The launcher had two components called `Banner` — one exported from `components.tsx` with tones
// `warn`/`danger`/`info`, one declared privately in `TrackerStores.tsx` with tones `error`/`hint` —
// and they disagreed about something that matters: only the local one set `role="alert"`, so eleven
// of the launcher's thirteen banners announced a failure to a screen reader as ordinary text that
// had appeared somewhere. Nothing could have noticed. `tsc` is happy (different scopes), the token
// audit is happy (both sets of classes resolve), and a reviewer reading either file sees one
// `Banner` and no reason to look for another.
//
// So this is the whole rule: **no component name is declared in two modules of one package.** It is
// arithmetic over declarations, which is why it is here — a gate that judges has a false-positive
// rate, and a gate that counts does not.
//
// The tempting second half — "a screen must not open-code an element the shared module exports" —
// is NOT here, and that is a measured decision rather than a cautious one. Run over this tree it
// flags around forty places, and the majority are correct as written: a `<button>` carrying
// `role="tab"` and `aria-selected` is not a `Button`, and pushing it through one would mean either
// widening `Button` to pass arbitrary ARIA through or fighting Tailwind's class ordering with an
// override that silently loses. A check that is wrong two times in five is a check people learn to
// suppress, and a suppressed check is the same as no check with extra ceremony.
//
// # Why "declared", not "exported"
//
// The `Banner` that caused this was NOT exported — it was a module-private `function Banner(…)`.
// Only counting exports would have found nothing.
import assert from "node:assert/strict";
import { readFileSync, readdirSync, statSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { frontendRoots } from "./layout.mjs";

/** Every `.ts`/`.tsx` file under a root, recursively. */
function sourceFiles(root) {
  const found = [];
  for (const name of readdirSync(root)) {
    const full = path.join(root, name);
    if (statSync(full).isDirectory()) found.push(...sourceFiles(full));
    else if (/\.tsx?$/.test(name)) found.push(full);
  }
  return found;
}

/**
 * The React components a file declares, by name.
 *
 * A component is a `function` whose name starts with a capital — which is not a style rule here but
 * JSX's own: `<foo />` is the HTML element `foo` and `<Foo />` is the binding `Foo`, so a component
 * that is not capitalised cannot be rendered as one. `const X = (props) => …` is deliberately not
 * matched: this tree does not write components that way, and a regex that tried would also catch
 * every capitalised constant in the file.
 */
function componentsIn(source) {
  return [...source.matchAll(/^(?:export )?function ([A-Z][A-Za-z0-9]*)\s*[(<]/gm)].map(
    (match) => match[1],
  );
}

test("no component name is declared in two modules", () => {
  const declaredIn = new Map();
  for (const root of frontendRoots()) {
    for (const file of sourceFiles(root)) {
      for (const name of componentsIn(readFileSync(file, "utf8"))) {
        if (!declaredIn.has(name)) declaredIn.set(name, []);
        declaredIn.get(name).push(path.relative(root, file));
      }
    }
  }

  // A count first, then the assertion over it. Zero collisions out of zero components is what a
  // walker pointed at a directory the code has left produces, and it renders identically to a clean
  // tree — the failure this whole folder keeps finding, in one more place.
  assert.ok(
    declaredIn.size > 40,
    `only ${declaredIn.size} components were inspected — the source roots are wrong`,
  );

  const collisions = [...declaredIn]
    .filter(([, files]) => new Set(files).size > 1)
    .map(([name, files]) => `${name}: ${[...new Set(files)].sort().join(", ")}`)
    .sort();
  assert.deepEqual(
    collisions,
    [],
    "two modules declare a component with the same name, so a caller's import decides which "
      + "behaviour they get and a reader of either file has no reason to look for the other:\n  "
      + collisions.join("\n  "),
  );
});
