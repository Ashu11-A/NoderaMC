// A rule that iterates nothing passes, and the runner counts it.
//
// This suite is the launcher's half of the vacuity guard; `web/tests/vacuity.test.mjs` is the site's.
// The rule and its reasoning live in `nodera-ui/vacuity-audit`, once, because the failure it
// describes is a property of how tests are written here rather than of either package.
//
// Kept as a lint over the sources rather than as a convention, for the same reason the rules in
// `ux-honesty.test.mjs` police the app's sources rather than trusting a review to: nine of these
// existed at once across the two suites, in a tree that already had the correct pattern written down
// three times and commented as the thing that "stops it reporting green over an empty set".
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { auditVacuity } from "nodera-ui/vacuity-audit";

const TESTS = fileURLToPath(new URL(".", import.meta.url));

/**
 * The loops that are correct with nothing to iterate, and why.
 *
 * Empty, and the assertion below is what keeps it that way. The site's copy of this file carries
 * one entry with its reason written above it; that is the form an addition here has to take.
 */
const ALLOW = [];

test("no asserting loop walks a collection nothing has counted", () => {
  const { offenders, inspected } = auditVacuity({ roots: [TESTS], allow: ALLOW });

  // The check's own cardinality, before its verdict — this file is subject to the rule it enforces.
  // Zero offenders out of zero loops is what a walker pointed at a directory the tests have left
  // produces, and it renders identically to a clean tree. The launcher's suites assert mostly over
  // source text rather than over collections, so this floor is low by nature.
  assert.ok(inspected >= 5, `only ${inspected} asserting loop(s) were inspected`);
  assert.deepEqual(
    offenders,
    [],
    `these rules hold over an empty collection, which is not the same as holding:\n  ${offenders.join("\n  ")}`,
  );
});

test("the vacuity allowlist only shrinks", () => {
  assert.deepEqual(ALLOW, [], "the launcher's suites need no exemption; adding one needs a reason");
});
