// A loop that asserts over an empty collection asserts nothing, and reports green for it.
//
// # Why this check exists
//
// This is the repository's most persistent defect shape, stated once: a value is produced and
// nothing asserts anything about the count of things that value covered. Every instance found so
// far is the same —
//
//   for (const result of rank(index, "tracker")) assert.ok(…);
//
// If `rank` returns nothing, the rule "a section is offered only when the query is in that heading"
// passes without ever looking at a section. The suite is green, the runner counts one more passing
// test, and the rule it names has never been evaluated. Nothing in `node --test` can see that: an
// empty iteration is not an error in any language, and the test did exactly what it was written to
// do.
//
// The repository already knows the answer and applies it in three places. `token-audit.mjs` returns
// an `inspected` counter and both design-token suites assert `inspected > 100` — "the `inspected`
// count below is what stops it reporting green over an empty set". `site-csp.test.mjs` opens with
// "the build emitted pages at all", `assert.ok(pages.length >= 20)`. And `platform-seam.test.mjs`
// uses the other correct form, `assert.deepEqual(walked, [expected])`, which fails on an empty walk
// because the expectation is a literal with something in it.
//
// So the instrument exists; it was just never generalised. This is the generalisation: find every
// asserting loop over a collection the file DERIVED, and require the file to have said how big that
// collection is.
//
// # What counts as a guard
//
// A guard is an `assert…` call that (a) reads a `.length` or a `.size`, or (b) compares against an
// array or object literal with something actually written in it — the `deepEqual(walked, [expected])`
// form — and that mentions the collection the loop walks.
//
// Both halves are load-bearing. Without the cardinality half, `assert.deepEqual(offenders, [])` —
// the shape of half the rules in this tree — would count as a guard for the very loop that filled
// `offenders`, which is precisely the vacuity being looked for. Without the "mentions it" half, the
// check is satisfied by any nearby length assertion: `add-store.test.mjs` asserts
// `context.document.querySelectorAll("script[src]").length === 0` four lines below a loop over
// `context.document.querySelectorAll("[src], [href]")`, and an identifier-level match would read the
// first as a guard for the second because both mention `context` and `document`.
//
// "Mentions it" is therefore source text, not identifiers — and strict in the safe direction: it can
// ask for a guard that morally exists, and it cannot accept one that does not. A loop over
// `pages.filter(…)` is not guarded by `pages.length >= 20`, and that is the correct answer, because
// a filter is exactly how a non-empty collection becomes an empty one.
//
// # Two normalisations, each for a false positive that was measured rather than imagined
//
// **Local names are followed.** `stubs.test.mjs` guards two loops over `routes.filter(e => e.stub)`
// with `assert.equal(flagged.length, 3)`, where `flagged` is that same filter bound to a name. A
// check that could not see through the binding would call a correct guard missing.
//
// **Parameter names are erased.** The same file spells the filter `(route) => route.stub` in the
// guard and `(entry) => entry.stub` in the loop. Those are the same collection and a textual
// comparison says they are not, so every identifier bound as a function parameter anywhere in the
// file is normalised to `_` before anything is compared.
//
// # What is not a derived collection
//
// A literal written in the file — including one bound to a `const`, which is the same thing with a
// name on it. `for (const host of BANNED)` cannot silently become empty without somebody deleting
// the list in front of them, and demanding a length assertion beside a list you can count is noise.
// Also `String.prototype.split`, which by specification never returns an empty array.
import { parse } from "acorn";
import { readFileSync } from "node:fs";
import path from "node:path";
import { sourceFiles } from "./token-audit.mjs";

/** Every node in a parse tree, in no particular order. Hand-rolled: `acorn-walk` adds a package. */
function* nodes(node) {
  if (node === null || typeof node !== "object") return;
  if (Array.isArray(node)) {
    for (const item of node) yield* nodes(item);
    return;
  }
  if (typeof node.type !== "string") return;
  yield node;
  for (const [key, value] of Object.entries(node)) {
    if (key === "type" || key === "start" || key === "end" || key === "loc") continue;
    yield* nodes(value);
  }
}

/** Is this call an assertion? `assert.x(…)` or a bare `assert(…)`. */
function isAssertion(node) {
  if (node.type !== "CallExpression") return false;
  let callee = node.callee;
  while (callee.type === "MemberExpression") callee = callee.object;
  return callee.type === "Identifier" && callee.name === "assert";
}

/** Does this subtree contain an assertion? A loop with none is not making a claim it could lose. */
function asserts(node) {
  for (const child of nodes(node)) if (isAssertion(child)) return true;
  return false;
}

/** A `.length` or `.size` read — the shape every cardinality claim in this tree is written in. */
function isSizeRead(node) {
  return (
    node.type === "MemberExpression" &&
    !node.computed &&
    node.property.type === "Identifier" &&
    (node.property.name === "length" || node.property.name === "size")
  );
}

/** A literal with something written in it. A spread is not "something": `[...xs]` can be empty. */
function isPopulatedLiteral(node) {
  if (node.type === "ArrayExpression") {
    return node.elements.some((element) => element !== null && element.type !== "SpreadElement");
  }
  if (node.type === "ObjectExpression") {
    return node.properties.some((property) => property.type !== "SpreadElement");
  }
  return false;
}

/**
 * Does this assertion say something about a cardinality?
 *
 * Either it reads a size anywhere, or one of its OWN ARGUMENTS is a literal with something in it —
 * `assert.deepEqual(walked, [expected])`, which cannot pass over an empty walk.
 *
 * An empty literal is excluded by `isPopulatedLiteral`: `deepEqual(offenders, [])` is the assertion
 * this whole check exists to stop being satisfied by itself. And the populated form is only read at
 * the top of an argument, never nested, because nesting is how a literal stops being a claim about
 * size: `download-manifest.test.mjs` asserts `deepEqual(extra.sort(), ["SHA256SUMS"].filter(…))`,
 * where the expected side collapses to `[]` for exactly the empty release the loop below it needs
 * protecting from. Read as a cardinality guard it would certify the vacuity it shares.
 */
function isCardinality(node) {
  for (const child of nodes(node)) if (isSizeRead(child)) return true;
  return node.arguments.some(isPopulatedLiteral);
}

/**
 * The collection a loop walks, or `null` when there is nothing to guard.
 *
 * A `for…of` walks its right-hand side. A C-style `for` walks whatever bound its test reads a size
 * off, which is the same shape wearing a counter. `.forEach` walks its receiver.
 */
function iterated(node) {
  if (node.type === "ForOfStatement" || node.type === "ForInStatement") return node.right;
  if (node.type === "ForStatement") {
    for (const child of nodes(node.test ?? {})) if (isSizeRead(child)) return child.object;
    return null;
  }
  if (
    node.type === "CallExpression" &&
    node.callee.type === "MemberExpression" &&
    !node.callee.computed &&
    node.callee.property.name === "forEach"
  ) {
    return node.callee.object;
  }
  return null;
}

/** The body a loop asserts in — a statement for the `for` forms, the callback for `.forEach`. */
function loopBody(node) {
  return node.type === "CallExpression" ? node.arguments : node.body;
}

/** Source text with every run of whitespace removed, so a reformat cannot change an answer. */
const squeeze = (text) => text.replace(/\s+/g, "");

/** Every identifier bound as a function parameter — see the header on why they are erased. */
function parameterNames(tree) {
  const names = new Set();
  for (const node of nodes(tree)) {
    if (!Array.isArray(node.params)) continue;
    for (const param of node.params) {
      for (const child of nodes(param)) if (child.type === "Identifier") names.add(child.name);
    }
  }
  return names;
}

/**
 * Every name bound exactly once, and what it was bound to.
 *
 * Once, because a name declared twice in a file has two answers and neither is safe to substitute.
 */
function bindings(tree) {
  const seen = new Map();
  for (const node of nodes(tree)) {
    if (node.type !== "VariableDeclarator" || node.id.type !== "Identifier") continue;
    const name = node.id.name;
    seen.set(name, seen.has(name) ? null : node.init);
  }
  return new Map([...seen].filter(([, init]) => init !== null && init !== undefined));
}

/** Follow a name to what it was bound to, up to `depth` hops. Cycles cannot outlast the counter. */
function resolve(node, binds, depth = 4) {
  if (depth > 0 && node.type === "Identifier" && binds.has(node.name)) {
    return resolve(binds.get(node.name), binds, depth - 1);
  }
  return node;
}

/**
 * The adapters that change how a collection is walked and never how much of it there is.
 *
 * `Object.entries(x)`, `x.entries()` and `x.map(f)` all have exactly as many elements as `x`, so a
 * statement about `x`'s size is a statement about theirs. This is what lets a guard be written as
 * `assert.equal(offers.length, …)` beside a loop over `offers.entries()` — asking for the guard to
 * be spelled in terms of `.entries()` would be asking for a worse assertion.
 *
 * @returns the adapted collection, or `null` if this is not an adapter call.
 */
const ADAPTERS = new Set(["entries", "keys", "values", "map"]);
function adapted(node) {
  if (node.type !== "CallExpression") return null;
  const callee = node.callee;
  if (callee.type !== "MemberExpression" || callee.computed) return null;
  if (!ADAPTERS.has(callee.property.name)) return null;
  // `Object.entries(x)` names its collection as an argument; `x.entries()` is the receiver.
  if (callee.object.type === "Identifier" && callee.object.name === "Object") {
    return node.arguments.length === 1 ? node.arguments[0] : null;
  }
  return callee.object;
}

/**
 * A collection whose size is visible where it is written, so a guard would add nothing.
 *
 * Followed through names: `const BANNED = [...]` then `for (const x of BANNED)` is a literal with a
 * label on it. `split` is here because the specification says it: it returns at least one element
 * for every input, including the empty string.
 */
function isFixed(node, binds, depth = 4) {
  if (depth <= 0) return false;
  const target = resolve(node, binds, depth);
  if (isPopulatedLiteral(target)) return true;
  if (target.type === "Literal" || target.type === "TemplateLiteral") return true;
  if (
    target.type === "CallExpression" &&
    target.callee.type === "MemberExpression" &&
    !target.callee.computed &&
    target.callee.property.name === "split"
  ) {
    return true;
  }
  const inner = adapted(target);
  return inner !== null && isFixed(inner, binds, depth - 1);
}

/**
 * The texts a guard may mention to count as guarding this loop.
 *
 * The collection itself, whatever it adapts, and — for a concatenation — each of its parts, because
 * `[...a, ...b]` is non-empty as soon as either is. `status.test.mjs` guards a loop over
 * `[...status.limitations, ...status.envelope]` by saying how many staged rows there are, which is
 * the honest guard: it is the half that could realistically arrive empty.
 */
function subjectTexts(node, render, depth = 4) {
  const texts = [render(node)];
  if (depth <= 0) return texts;
  const inner = adapted(node);
  if (inner) texts.push(...subjectTexts(inner, render, depth - 1));
  if (node.type === "ArrayExpression") {
    for (const element of node.elements) {
      if (element === null) continue;
      texts.push(...subjectTexts(element.type === "SpreadElement" ? element.argument : element, render, depth - 1));
    }
  }
  return texts;
}

/**
 * Audit one file.
 *
 * @returns {{ offenders: string[], inspected: number }} `offenders` are `file:line — subject` lines.
 */
export function auditSource(source, label) {
  const tree = parse(source, { ecmaVersion: "latest", sourceType: "module", locations: true });
  const params = parameterNames(tree);
  const binds = bindings(tree);

  // Where every parameter-named identifier starts, so they can be erased by POSITION rather than by
  // regex. A regex would reach inside literals: `theme-screen.test.mjs` matches CSS declarations
  // with `/[a-z-]+\s*:/`, and a file with a parameter called `a` had that pattern rewritten to
  // `/[_-z-]+/` — a check corrupting the thing it is reading, which is the failure this whole file
  // is about.
  const erased = new Map();
  for (const node of nodes(tree)) {
    if (node.type === "Identifier" && params.has(node.name)) erased.set(node.start, node.end);
  }

  /** A subtree as text: parameter names replaced by `_`, whitespace removed. */
  const render = (node) => {
    let out = "";
    let at = node.start;
    for (const [start, end] of erased) {
      if (start < at || end > node.end) continue;
      out += `${source.slice(at, start)}_`;
      at = end;
    }
    return squeeze(out + source.slice(at, node.end));
  };
  /** The same subtree as the author wrote it — what a failure message has to show. */
  const quote = (node) => squeeze(source.slice(node.start, node.end));

  // Every cardinality assertion, once as written and once with its local names followed to what
  // they were bound to — the `flagged`/`routes.filter(…)` case from the header. File-level on
  // purpose: `stubs.test.mjs` guards a loop from a SIBLING test over the same collection, and the
  // thing that has to be non-empty is the collection, not the test.
  const guards = [];
  for (const node of nodes(tree)) {
    if (!isAssertion(node) || !isCardinality(node)) continue;
    guards.push(render(node));
    let expanded = render(node);
    for (let round = 0; round < 2 && expanded.length < 4000; round += 1) {
      expanded = expanded.replace(/\b[A-Za-z_$][A-Za-z0-9_$]*\b/g, (name) =>
        binds.has(name) ? `(${render(binds.get(name))})` : name,
      );
    }
    guards.push(expanded);
  }

  const offenders = [];
  let inspected = 0;
  for (const node of nodes(tree)) {
    const subject = iterated(node);
    if (!subject) continue;
    if (isFixed(subject, binds)) continue;
    if (!asserts(loopBody(node))) continue;
    inspected += 1;
    const texts = subjectTexts(subject, render);
    if (guards.some((guard) => texts.some((text) => guard.includes(text)))) continue;
    offenders.push(`${label}:${node.loc.start.line} — nothing asserts how many: ${quote(subject)}`);
  }
  return { offenders, inspected };
}

/**
 * Audit every `*.test.mjs` under `roots`.
 *
 * @param {{ roots: string[], allow?: string[] }} options `allow` holds `file:line` prefixes that are
 *   exempt. It is a ratchet: the suite asserts its length, so an entry can be removed and one cannot
 *   be added without the number beside it moving too.
 * @returns {{ offenders: string[], inspected: number }}
 */
export function auditVacuity({ roots, allow = [] }) {
  const offenders = [];
  let inspected = 0;
  for (const root of roots) {
    for (const file of sourceFiles(root, /\.test\.mjs$/)) {
      const label = path.relative(root, file);
      const result = auditSource(readFileSync(file, "utf8"), label);
      inspected += result.inspected;
      offenders.push(
        ...result.offenders.filter((line) => !allow.some((prefix) => line.startsWith(`${prefix} `))),
      );
    }
  }
  return { offenders: offenders.sort(), inspected };
}
