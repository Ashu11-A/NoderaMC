// Exit tests for two rules that regress silently and that no compiler checks.
//
//   1. **First run must not need a running node.** The telemetry question used to be pushed
//      straight at the worker, and a worker that had not finished starting — on a fresh Android
//      install, the normal case — left the flow stuck on "Your answer could not be recorded on this
//      node (connection refused). Try again." with nothing behind Try again that could ever work.
//      The app was unusable from its first screen.
//   2. **A shipped build must not default to a service on the user's own machine.** Loopback is a
//      developer's stack; on a phone it is the phone. The addresses a release uses come from
//      `services/official.json`.
//
// Both are properties of the sources, not of a render, so they are asserted over the sources.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(new URL(path, import.meta.url), "utf8");

const setup = read("../src/mobile/Setup.tsx");
const ipc = read("../src/ipc.ts");
const telemetry = read("../../src/telemetry.rs");
const settings = read("../../src/settings.rs");

test("the first-run telemetry answer is recorded locally rather than requiring the worker", () => {
  // The Rust command cannot report an unreachable worker as a failure: its answer is a status, and
  // the undelivered case is a flag on that status.
  assert.match(
    telemetry,
    /pub async fn set\(\s*control_addr: &str,\s*settings: &SettingsHandle,\s*granted: bool,?\s*\) -> TelemetryStatus/,
    "telemetry::set must always answer with a status — a Result turns a busy node into a dead end",
  );
  assert.match(
    telemetry,
    /pub async fn deliver_pending\(/,
    "an answer the worker never took has to be delivered later, or it is lost",
  );
  assert.match(
    telemetry,
    /pub async fn reconcile_loop\(/,
    "nothing retries the delivery, so a pending answer would sit forever",
  );

  // The flow itself moves on. The old shape returned early on a failed push; the fixed shape has
  // no branch that stops the flow because of one.
  assert.doesNotMatch(
    setup,
    /Your answer could not be recorded on this node/,
    "the dead-end message is back",
  );
  assert.match(
    setup,
    /status\.pending/,
    "the setup flow no longer distinguishes a saved-but-undelivered answer",
  );
});

test("the status the UI renders keeps the node's answer separate from the person's", () => {
  for (const field of ["pending: boolean", "answer: boolean | null"]) {
    assert.ok(
      ipc.includes(field),
      `TelemetryStatus must carry \`${field}\` — a badge that cannot tell "you chose this" from ` +
        `"the node is doing this" will claim consent that was never delivered`,
    );
  }
});

test("a release build starts with no tracker on the machine it is running on", () => {
  assert.match(
    settings,
    /fn default_trackers_for\(development: bool\) -> Vec<String> \{\s*if development \{/,
    "the loopback default must be reachable only through the development branch",
  );
  assert.match(
    settings,
    /pub fn dev_mode\(\) -> bool/,
    "there must be exactly one predicate deciding whether local services are allowed",
  );
});
