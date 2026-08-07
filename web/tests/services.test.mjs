// `/services/` says what it measured, when it measured it, and nothing else.
//
// # The rule being defended
//
// A status page is the one page whose entire value is that its states are true. This repository
// already has the rule written down — an interface may not render a confident state it does not
// have — and it has the scar that produced it: the launcher's dashboard shipped a screen of zeros
// for values nobody had reported, which reads as "all quiet" and meant "we did not ask".
//
// A green dot on this page means a machine somewhere opened a TCP connection to a published address
// at a moment that is not now. Every clause of that sentence is load-bearing, and each of them is a
// test below:
//
//   * "a machine somewhere" — not the browser, which cannot open a socket and may not call another
//     origin under `connect-src 'self'`. The prober is exercised here against real sockets.
//   * "a published address" — the reading is joined to the list by ENDPOINT, because the two
//     services on the official list are both called `noderamc.org` and a join by name would show
//     the tracker's latency beside the relay.
//   * "at a moment that is not now" — the emitted page must carry the measurement's own timestamp.
//     A state word with no date beside it is the defect, not a style preference.
//   * a TCP connect — not a protocol handshake. This project has run a live tracker that accepted
//     connections and closed them without answering; the page has to be able to say that.
import assert from "node:assert/strict";
import { createServer } from "node:net";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { JSDOM } from "jsdom";
import { repositoryDirectory } from "nodera-ui/layout";
import { distDirectory, siteDirectory } from "./layout.mjs";
import { PROBE_BRANCH, PROBE_FILE, parseEndpoint, probeEndpoint } from "../scripts/probe-services.mjs";

const generated = JSON.parse(
  readFileSync(path.join(siteDirectory, "src/generated/services.json"), "utf8"),
);
const page = readFileSync(path.join(distDirectory, "services", "index.html"), "utf8");
const { document } = new JSDOM(page).window;

/** Everything the page renders, whitespace-collapsed. */
const prose = document.querySelector("main")?.textContent.replace(/\s+/g, " ").trim() ?? "";

/**
 * The page's own words, with the list itself taken out.
 *
 * The budget below is about EXPLANATION, and the rows are not explanation — they are the data
 * somebody came for, and they grow when an operator joins. Counting them would make the rule "do
 * not let the network grow" instead of "do not let the preamble grow back".
 */
const explanation = (() => {
  const clone = document.querySelector("main").cloneNode(true);
  clone.querySelector('ul[aria-label="Published services"]')?.remove();
  return clone.textContent.replace(/\s+/g, " ").trim();
})();

/* ------------------------------------------------------------------ the prober, against sockets */

/** A listening server that holds the connection open. Resolves to `[endpoint, close]`. */
function listening(onConnection) {
  return new Promise((resolve) => {
    const server = createServer(onConnection);
    server.listen(0, "127.0.0.1", () => {
      const { port } = server.address();
      resolve([`tcp://127.0.0.1:${port}`, () => new Promise((done) => server.close(done))]);
    });
  });
}

test("the scheme is stripped before anything is dialled", () => {
  // A `tcp://` handed to a resolver as a hostname is a feature that silently never runs — this
  // repository has shipped that exact defect in the peer's endpoint handling. It is a lookup that
  // never resolves, with nothing in any log saying why.
  assert.deepEqual(parseEndpoint("tcp://150.230.84.206:6969"), {
    host: "150.230.84.206",
    port: 6969,
  });
  assert.deepEqual(parseEndpoint("150.230.84.206:6969"), { host: "150.230.84.206", port: 6969 });
  assert.deepEqual(parseEndpoint("tcp://[2001:db8::1]:7500"), { host: "2001:db8::1", port: 7500 });
});

test("an address with no port is not dialled and is not called down", () => {
  // The published list could grow a row this file cannot parse. That is a statement about the list,
  // never about the operator's machine, so it is `unknown` with the reason attached.
  for (const bad of ["noderamc.org", "tcp://", "tcp://host:99999", ""]) {
    assert.equal(parseEndpoint(bad), null, bad);
  }
});

test("a port that accepts and holds is reachable, with a time and no hang-up", async () => {
  const [endpoint, close] = await listening(() => {});
  try {
    const status = await probeEndpoint(endpoint);
    assert.equal(status.state, "reachable");
    assert.equal(status.succeeded, status.attempts);
    assert.ok(Number.isInteger(status.rttMs) && status.rttMs >= 0, `rtt was ${status.rttMs}`);
    assert.equal(status.peerClosed, false);
    assert.equal(status.error, null);
  } finally {
    await close();
  }
});

test("a port that accepts and hangs up is reachable AND says it hung up", async () => {
  // The outage that reads like a firewall and is not one. A connect-only check reports this as
  // healthy, which is how a live tracker sat there closing every connection while a status page
  // said it was fine.
  const [endpoint, close] = await listening((socket) => socket.end());
  try {
    const status = await probeEndpoint(endpoint);
    assert.equal(status.state, "reachable", "the connection genuinely succeeded");
    assert.equal(status.peerClosed, true, "the peer's close was smoothed over");
  } finally {
    await close();
  }
});

test("a closed port is unreachable, and carries the errno rather than a guess", async () => {
  const [endpoint, close] = await listening(() => {});
  await close(); // …and now nothing is listening there.
  const status = await probeEndpoint(endpoint);
  assert.equal(status.state, "unreachable");
  assert.equal(status.rttMs, null, "there is no round trip to report when nothing answered");
  assert.equal(status.succeeded, 0);
  assert.match(String(status.error), /^E[A-Z]+$/, `error was ${status.error}`);
});

test("an unparseable address is unknown with a sentence, never unreachable", async () => {
  const status = await probeEndpoint("noderamc.org");
  assert.equal(status.state, "unknown");
  assert.equal(status.attempts, 0, "nothing was dialled, so nothing may be reported about it");
  assert.match(status.error, /not host:port/);
});

/* ------------------------------------------------------- the record, and where it is published */

test("the generated record has services in it at all", () => {
  // Three rules below walk `generated.services` and assert per service. `build-services.mjs` resolves
  // the published index from a git ref, a fetched remote ref, or a URL — three ways to end up with a
  // valid file listing nothing, at which point every one of those rules passes having read no
  // service. The list is the official one and has two entries today; a floor of one says "the
  // resolution worked" without pinning how many operators there are.
  assert.ok(generated.services.length > 0, "the generated service list is empty");
});

test("the reading is joined to the list by address, not by name", () => {
  // Both services on the official list are called `noderamc.org`. A join by name shows the tracker's
  // latency beside the relay and neither row is wrong-looking.
  for (const service of generated.services) {
    assert.equal(
      service.endpointStatus.length,
      service.endpoints.length,
      `${service.kind} ${service.name} has ${service.endpointStatus.length} readings for ` +
        `${service.endpoints.length} addresses`,
    );
    for (const [i, endpoint] of service.endpoints.entries()) {
      assert.equal(service.endpointStatus[i].endpoint, endpoint);
    }
  }
});

test("a reading is either a measured state or absent — never a default", () => {
  for (const service of generated.services) {
    for (const status of service.endpointStatus) {
      if (generated.probe === null) {
        assert.equal(status.state, undefined, "no probe, so no state may be asserted");
        continue;
      }
      assert.ok(
        ["reachable", "unreachable", "unknown"].includes(status.state),
        `${status.endpoint} has state ${JSON.stringify(status.state)}`,
      );
      // Latency is reported only where a connect actually happened.
      if (status.state !== "reachable") assert.equal(status.rttMs, null);
    }
  }
});

test("the workflow that publishes the reading still creates the branch the prober names", () => {
  // The drift check a `layout.properties` key would have bought. The branch name lives in
  // `probe-services.mjs` and the workflow asks that file for it, so the two cannot disagree — this
  // asserts the workflow still exists and still asks.
  const workflow = path.join(repositoryDirectory, ".github/workflows/service-probe.yml");
  assert.ok(existsSync(workflow), "the scheduled probe workflow is gone; nothing measures anything");
  const yaml = readFileSync(workflow, "utf8");
  assert.match(yaml, /probe-services\.mjs --print-branch/, "the workflow spells a branch of its own");
  assert.match(yaml, /probe-services\.mjs --write/, "the workflow no longer runs the prober");
  assert.match(yaml, /git switch --orphan "\$branch"/, "the record must land on an orphan branch");
  assert.match(yaml, /cron:/, "the probe is no longer scheduled, so the reading never refreshes");
  // Never the published service list: that branch is edited by pull request and fetched at runtime
  // by every shipped client.
  assert.notEqual(PROBE_BRANCH, "services");
  assert.ok(PROBE_FILE.endsWith(".json"));
});

test("the site build reads the published record rather than dialling from the image builder", () => {
  const generator = readFileSync(path.join(siteDirectory, "scripts/build-services.mjs"), "utf8");
  assert.match(generator, /readPublishedProbe\(\)/);
  // The local probe exists and is opt-in. A default that dials from whatever machine happens to be
  // building would let a runner behind a restrictive egress policy publish "unreachable" about a
  // service that is perfectly well.
  assert.match(generator, /NODERA_SITE_PROBE/);
});

/* --------------------------------------------------------------------- what the page may render */

test("every published address appears on the page, exactly as published", () => {
  for (const service of generated.services) {
    for (const endpoint of service.endpoints) {
      assert.ok(prose.includes(endpoint), `${endpoint} is not on /services/`);
    }
  }
});

test("no state is rendered without the moment it was measured", () => {
  // The whole rule, as one assertion. "Reachable" with no date is a green light meaning "we did not
  // check", which is precisely what the launcher's StaleDataNotice exists to prevent.
  const states = ["Accepting connections", "Not answering"];
  const claimed = states.filter((word) => prose.includes(word));
  if (generated.probe === null) {
    assert.deepEqual(claimed, [], "the page claimed a state with no measurement behind it");
    assert.match(prose, /Nothing has measured these addresses yet/);
    return;
  }
  assert.ok(claimed.length > 0, "a measured build rendered no state at all");
  const stamps = [...document.querySelectorAll("time[datetime]")].map((node) =>
    node.getAttribute("datetime"),
  );
  assert.ok(
    stamps.includes(generated.probe.measuredAt),
    `the page carries no <time> for ${generated.probe.measuredAt}; it has ${JSON.stringify(stamps)}`,
  );
  // The absolute timestamp ships in the HTML. The relative age is computed in the browser against
  // the reader's clock — a static page can sit in a cache for a week, and "4 minutes ago" baked in
  // is a sentence that becomes false in silence.
  assert.doesNotMatch(prose, /\b(minutes?|hours?|days?) ago\b/, "an age was baked into the HTML");
  assert.match(prose, /A connection is not a conversation/, "the page overstates what it measured");
});

test("the page's explanation is short enough that the list is the page", () => {
  // The page this replaced opened with 180 words of definitions in front of two rows: what a tracker
  // is, what a relay is, why neither is an authority, what the button does and what it does not.
  // Every one of those still exists on the page whose subject it is. This is a budget rather than a
  // style note — preamble grows back one helpful sentence at a time, and nothing notices.
  //
  // It counts the page MINUS the rows, so the network may grow without the budget tightening.
  const words = explanation.split(" ").filter(Boolean).length;
  assert.ok(
    words < 140,
    `/services/ explains itself in ${words} words. The list is what somebody came for; the ` +
      "definitions live in /docs/operate/.",
  );
});
