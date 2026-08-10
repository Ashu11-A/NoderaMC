// A rule that iterates nothing passes, and the runner counts it.
//
// This suite is the site's half of the vacuity guard; `app/ui/tests/vacuity.test.mjs` is the
// launcher's. The rule and its reasoning live in `nodera-ui/vacuity-audit`, once, because the
// failure it describes is a property of how tests are written here rather than of either package.
//
// Kept as a lint over the sources rather than as a convention, for the same reason
// `ux-honesty.test.mjs` polices the app's sources rather than trusting a review to: nine of these
// existed at once, in a tree that already had the correct pattern written down three times and
// commented as the thing that "stops it reporting green over an empty set".
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { auditVacuity } from "nodera-ui/vacuity-audit";

const TESTS = fileURLToPath(new URL(".", import.meta.url));

/**
 * The loops that are correct with nothing to iterate, and why.
 *
 * A ratchet: the length is asserted below, so an entry can be removed and one cannot be added
 * without moving a number in the same commit. Each line is `file:line`, and the reason is the
 * comment above it — an allowlist whose entries carry no reason is a list of things nobody can
 * later judge.
 */
const ALLOW = [
  // The loop walks the registers that contributed NO rows, and asserts each of them genuinely has
  // no §B table. Empty is the passing state — every register parsed — so a non-emptiness guard here
  // would assert the opposite of what the suite wants to be true.
  "status.test.mjs:47",
];

test("no asserting loop walks a collection nothing has counted", () => {
  const { offenders, inspected } = auditVacuity({ roots: [TESTS], allow: ALLOW });

  // The check's own cardinality, before its verdict — this file is subject to the rule it enforces.
  // Zero offenders out of zero loops is what a walker pointed at a directory the tests have left
  // produces, and it renders identically to a clean tree.
  assert.ok(inspected > 30, `only ${inspected} asserting loop(s) were inspected`);
  assert.deepEqual(
    offenders,
    [],
    `these rules hold over an empty collection, which is not the same as holding:\n  ${offenders.join("\n  ")}`,
  );
});

test("the vacuity allowlist only shrinks, and every entry still earns its place", () => {
  assert.ok(ALLOW.length <= 1, `${ALLOW.length} exemptions; the ratchet was set at 1`);
  // An exemption that no longer suppresses anything is a licence somebody will spend later, on a
  // loop that moved onto that line. Measured by asking the audit what it would say WITHOUT the list.
  const unfiltered = auditVacuity({ roots: [TESTS] }).offenders;
  for (const entry of ALLOW) {
    assert.match(entry, /^[\w.-]+\.test\.mjs:\d+$/, `"${entry}" is not a file:line`);
    assert.ok(
      unfiltered.some((line) => line.startsWith(`${entry} `)),
      `${entry} is exempt from a rule it no longer breaks — delete the entry`,
    );
  }
});
