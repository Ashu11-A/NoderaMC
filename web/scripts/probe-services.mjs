// Whether the published services are answering, measured somewhere a browser cannot measure.
//
// # Why this file exists at all
//
// `/services/` used to list two addresses and say nothing about either. The obvious repair — ask the
// browser — is not available, twice over: a page cannot open a TCP socket, and the deployed CSP is
// `connect-src 'self'`, so it cannot even ask a third party to ask for it. The services are
// `tcp://150.230.84.206:6969` and `:7500`; there is no https endpoint to fetch and no
// `Access-Control-Allow-Origin` to add.
//
// So the measurement happens off the visitor's machine, is DATED, and is published as data. This
// file is the measuring half. `web/scripts/build-services.mjs` is the reading half, and
// `web/src/components/services.tsx` renders what was measured together with how old it is.
//
// # Where the record lives, and why not in this tree
//
// On the orphan branch `probes`, force-pushed as a single commit by
// `.github/workflows/service-probe.yml`. That is exactly the arrangement `badges` already has for
// the measured test totals, for the same two reasons: a value CI measures every half hour is data
// rather than source, and a branch with no history cannot grow seventeen thousand commits a year.
//
// It is deliberately NOT the `services` branch. That branch is the published service list — three
// builds compile it in and every shipped client fetches it at runtime — and a robot committing to it
// twice an hour would put machine output in the path humans edit by pull request.
//
// The branch name is hardcoded here rather than added to `layout.properties` for the reason
// `build-status.mjs` already gives about `badges`: that manifest holds directories, says so in its
// own header, and already carries one documented exception it did not want. `web/tests/services.test.mjs`
// asserts the workflow still creates this branch, which is the drift check a manifest key buys.
//
// # What a probe does and does not prove
//
// It opens a TCP connection and times it. That proves the port accepts, and it proves nothing about
// the protocol: this project has already had a live tracker that accepted connections and closed
// them without answering, which read like a firewall and was not one. So the record says what was
// measured — a connect, and whether the peer then hung up unprompted — and the page says it in those
// words rather than painting a green light labelled "healthy".
//
//   node web/scripts/probe-services.mjs --write DIR   # measure, write DIR/<PROBE_FILE>
//   node web/scripts/probe-services.mjs --print-branch # the orphan branch, for the workflow's git
import { execFileSync } from "node:child_process";
import { createConnection } from "node:net";
import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { repositoryDirectory } from "nodera-ui/layout";

/** The orphan branch the record is published on. Created by `.github/workflows/service-probe.yml`. */
export const PROBE_BRANCH = "probes";

/** The record's file name on that branch. */
export const PROBE_FILE = "service-probe.json";

/** How many connects per endpoint. Odd, so the median below is a measured sample and not a mean. */
const ATTEMPTS = 3;

/** A connect that has not landed by here is not a connect anybody would wait through. */
const CONNECT_TIMEOUT_MS = 4000;

/**
 * How long the socket is watched after it connects, to see whether the peer hangs up unprompted.
 *
 * Short on purpose. This is not an idle timeout measurement — it is the difference between "the port
 * accepted" and "the port accepted and immediately closed", which is a real outage this project has
 * shipped and which a connect-only check reports as healthy.
 */
const OBSERVE_MS = 400;

/**
 * Split `tcp://host:port` into its two halves.
 *
 * The scheme is STRIPPED rather than passed through. Handing `tcp://150.230.84.206` to a resolver as
 * a hostname is a mistake this repository has made in production and it fails silently — the lookup
 * never resolves, the feature never runs, and nothing says so. IPv6 in brackets is accepted because
 * the published format allows it, even though no listed service uses one today.
 */
export function parseEndpoint(endpoint) {
  const withoutScheme = String(endpoint).replace(/^[a-z][a-z0-9+.-]*:\/\//i, "");
  const match = /^(\[[0-9a-f:]+\]|[^:/]+):(\d{1,5})$/i.exec(withoutScheme);
  if (!match) return null;
  const port = Number(match[2]);
  if (!Number.isInteger(port) || port < 1 || port > 65535) return null;
  return { host: match[1].replace(/^\[|\]$/g, ""), port };
}

/** One connect. Resolves to `{ ok, ms, code, peerClosed }` and never rejects. */
function connectOnce(host, port) {
  return new Promise((resolve) => {
    const started = process.hrtime.bigint();
    const socket = createConnection({ host, port });
    let settled = false;
    let connectedMs = null;
    let peerClosed = false;
    let observer = null;

    const finish = (result) => {
      if (settled) return;
      settled = true;
      if (observer) clearTimeout(observer);
      socket.destroy();
      resolve(result);
    };

    socket.setTimeout(CONNECT_TIMEOUT_MS);
    socket.on("connect", () => {
      connectedMs = Number(process.hrtime.bigint() - started) / 1e6;
      observer = setTimeout(() => finish({ ok: true, ms: connectedMs, code: null, peerClosed }), OBSERVE_MS);
    });
    // The peer closing its half after we connected is the "accepts and hangs up" signature. It is
    // recorded, not treated as a failure: the connection genuinely succeeded.
    socket.on("end", () => {
      peerClosed = true;
    });
    socket.on("timeout", () => {
      finish(
        connectedMs === null
          ? { ok: false, ms: null, code: "ETIMEDOUT", peerClosed }
          : { ok: true, ms: connectedMs, code: null, peerClosed },
      );
    });
    socket.on("error", (error) => {
      // A socket error AFTER a successful connect (a reset while observing) does not un-connect it.
      if (connectedMs !== null) {
        finish({ ok: true, ms: connectedMs, code: null, peerClosed: true });
        return;
      }
      finish({ ok: false, ms: null, code: error.code ?? "EFAILED", peerClosed });
    });
  });
}

/** The middle sample. Three connects, so this is a measurement rather than an average of one. */
function median(values) {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)];
}

/**
 * Measure one endpoint.
 *
 * `unreachable` is only ever said about an endpoint that was actually tried and did not answer. An
 * endpoint this file cannot even parse is `unknown` with the reason attached, because "the address
 * in the published list is not one we know how to dial" is a statement about the list, not about
 * the operator's machine.
 */
export async function probeEndpoint(endpoint) {
  const parsed = parseEndpoint(endpoint);
  if (!parsed) {
    return {
      endpoint,
      state: "unknown",
      rttMs: null,
      attempts: 0,
      succeeded: 0,
      peerClosed: false,
      error: "this address is not host:port, so nothing was dialled",
    };
  }
  const results = [];
  for (let i = 0; i < ATTEMPTS; i += 1) {
    // Sequential rather than parallel: three simultaneous connects from one address measure the
    // service's accept backlog as much as its latency, and one of them is what we meant to measure.
    results.push(await connectOnce(parsed.host, parsed.port));
  }
  const good = results.filter((result) => result.ok);
  const codes = [...new Set(results.filter((result) => !result.ok).map((result) => result.code))];
  return {
    endpoint,
    state: good.length > 0 ? "reachable" : "unreachable",
    rttMs: good.length > 0 ? Math.round(median(good.map((result) => result.ms))) : null,
    attempts: results.length,
    succeeded: good.length,
    peerClosed: good.some((result) => result.peerClosed),
    error: good.length > 0 ? null : (codes[0] ?? "EFAILED"),
  };
}

/** Every endpoint of every service in a resolved index, measured. */
export async function probeAll(index, measuredBy) {
  const probes = [];
  for (const service of index.services ?? []) {
    for (const endpoint of service.endpoints ?? []) {
      probes.push({ kind: service.kind, name: service.name, ...(await probeEndpoint(endpoint)) });
    }
  }
  return {
    // The only timestamp on the record, and the one the page renders an age from. Written by the
    // machine that did the dialling, never by the machine that later reads it.
    measuredAt: new Date().toISOString(),
    measuredBy,
    attempts: ATTEMPTS,
    connectTimeoutMs: CONNECT_TIMEOUT_MS,
    probes,
  };
}

/**
 * The published record, or `null` when nobody has ever published one.
 *
 * `null` is a first-class answer here and the page renders it as *unknown*, never as *down*. A
 * fresh clone, a fork whose CI has not run, and a service that is genuinely unreachable are three
 * different facts, and only one of them is bad news about the network.
 */
export function readPublishedProbe() {
  let raw;
  try {
    raw = execFileSync("git", ["show", `${PROBE_BRANCH}:${PROBE_FILE}`], {
      cwd: repositoryDirectory,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    });
  } catch {
    return null;
  }
  try {
    const record = JSON.parse(raw);
    // A record with no timestamp cannot be aged, and a measurement that cannot be dated is one this
    // site is not allowed to render. Dropping it is the honest read of a malformed file.
    if (typeof record.measuredAt !== "string" || !Array.isArray(record.probes)) return null;
    if (Number.isNaN(Date.parse(record.measuredAt))) return null;
    return record;
  } catch {
    return null;
  }
}

/** The resolved service index, through the repository's one resolver. See `build-services.mjs`. */
export function resolveIndex() {
  const resolved = execFileSync("python3", ["scripts/services.py", "--resolve"], {
    cwd: repositoryDirectory,
    encoding: "utf8",
  }).trim();
  return JSON.parse(readFileSync(resolved, "utf8"));
}

/* ------------------------------------------------------------------------------------- the CLI */

// `import.meta.main` is not available on the Node versions this repository builds with, and the
// site's own Dockerfile pins one of them deliberately.
const invoked = process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname);
if (invoked) {
  const args = process.argv.slice(2);
  if (args.includes("--print-branch")) {
    // So the workflow's `git switch --orphan` and this file cannot name two different branches.
    process.stdout.write(`${PROBE_BRANCH}\n`);
  } else if (args.includes("--print-file")) {
    process.stdout.write(`${PROBE_FILE}\n`);
  } else {
    const where = args.indexOf("--write");
    if (where === -1 || !args[where + 1]) {
      console.error("usage: probe-services.mjs --write DIR | --print-branch | --print-file");
      process.exit(2);
    }
    const record = await probeAll(resolveIndex(), process.env.GITHUB_ACTIONS ? "GitHub Actions" : "a workstation");
    const target = path.join(args[where + 1], PROBE_FILE);
    writeFileSync(target, `${JSON.stringify(record, null, 2)}\n`);
    for (const probe of record.probes) {
      console.log(
        `probe: ${probe.kind} ${probe.endpoint} ${probe.state}` +
          `${probe.rttMs === null ? "" : ` ${probe.rttMs} ms`}` +
          `${probe.succeeded === probe.attempts ? "" : ` (${probe.succeeded}/${probe.attempts})`}` +
          `${probe.peerClosed ? " — the peer closed it unprompted" : ""}`,
      );
    }
    console.log(`probe: wrote ${target}`);
  }
}
