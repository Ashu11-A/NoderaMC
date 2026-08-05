// The published service list, as the site shows it.
//
// The list does not live in this tree. It lives on the orphan `services` branch, because a list that
// changes when an operator joins is data rather than source, and `scripts/services.py` is the one
// resolver this repository has for it — local ref, fetched remote ref, raw URL, cache, then fail.
// Three builds already compile that list in (the app's `build.rs`, `:core`'s
// `generateOfficialServices`, and the validator), and a fourth resolver would be a fourth answer to
// which list a build is showing.
//
// So this shells out to that script rather than re-implementing it. The flag is `--resolve`, which
// prints the path of the resolved index; there is no `--json` flag, whatever an earlier plan said.
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import path from "node:path";
import { dir, layoutValue, repositoryDirectory } from "nodera-ui/layout";
import { PROBE_BRANCH, PROBE_FILE, probeAll, readPublishedProbe } from "./probe-services.mjs";

const OUT = path.join(dir("web"), "src/generated/services.json");

const die = (message) => {
  console.error(`build-services: ${message}`);
  process.exit(1);
};

let resolved;
try {
  resolved = execFileSync("python3", ["scripts/services.py", "--resolve"], {
    cwd: repositoryDirectory,
    encoding: "utf8",
  }).trim();
} catch (error) {
  die(
    `scripts/services.py --resolve failed. It resolves the ${layoutValue("services.branch")} ` +
      `branch; \`git fetch origin ${layoutValue("services.branch")}\` is usually what is missing.\n` +
      String(error.stderr ?? error.message),
  );
}

const index = JSON.parse(readFileSync(resolved, "utf8"));
if (!Array.isArray(index.services)) die(`${resolved} has no services array`);

// Every row on `/services/` offers the list it came from, not itself: `/add-store` hands the app a
// service *index*, and a person adding a store is trusting a publisher rather than one endpoint.
//
// This is the PUBLISHER'S URL, not a link. It used to be a finished `/add-store?url=…` href, and the
// service table then composed that href a second time — which double-encoded the address and shipped
// a button the deep-link page refused with "The address in this link is not a valid URL". The
// contract is now unambiguous in both directions: this file emits data (an address), the view
// composes the link, and `storeOfferHref` throws if it is ever handed its own output.
const storeIndexUrl = layoutValue("services.rawUrl");

/* ---------------------------------------------------------------------------- what is answering
 *
 * A browser cannot open a TCP socket and the deployed CSP is `connect-src 'self'`, so the question
 * "is this tracker up, and how fast" cannot be asked from the visitor's machine at all. It is
 * measured elsewhere, dated, and published as data — see `probe-services.mjs` for both halves.
 *
 * The DEFAULT source is the published record on the orphan `probes` branch, because a probe from
 * one build machine at one instant is a poor measurement: a PR runner behind a restrictive egress
 * policy would publish "unreachable" about a service that is perfectly well, and the site would say
 * so with a straight face.
 *
 * `NODERA_SITE_PROBE=1` dials from this machine instead, with the same prober, and stamps the record
 * as such. It is for a build that wants to see the page against a live network — a workstation, or a
 * deployment lane that has decided its own runner is the right vantage point.
 *
 * Neither path may fail the build. A status page that takes the site down when a service is down is
 * a status page nobody can read at exactly the moment they need it, so a probe that throws is an
 * absent record, which the page renders as *unknown*.
 */
let probe = null;
if (process.env.NODERA_SITE_PROBE === "1") {
  try {
    probe = await probeAll(index, "this build");
  } catch (error) {
    console.warn(`build-services: the local probe failed (${error.message}); status is unknown`);
  }
} else {
  probe = readPublishedProbe();
}

/** What was measured about one endpoint, or `null` — which the page says as *never probed*. */
const statusOf = (endpoint) =>
  probe?.probes.find((entry) => entry.endpoint === endpoint) ?? null;

const services = index.services.map((entry) => {
  for (const required of ["kind", "name", "endpoints"]) {
    if (!entry[required]) die(`${resolved}: a service has no ${required}`);
  }
  if (entry.kind !== "tracker" && entry.kind !== "rendezvous") {
    die(`${resolved}: ${entry.name} has kind "${entry.kind}", which this site has no column for`);
  }
  return {
    name: entry.name,
    kind: entry.kind,
    // Exactly as published, scheme included. `/services/` is a directory of what the list SAYS; a
    // page that tidied `tcp://host:port` into `host:port` would be showing a value nobody published
    // and hiding the one a person has to paste.
    endpoints: [...entry.endpoints],
    operator: entry.operator ?? null,
    storeIndexUrl,
    // One reading per endpoint, keyed by the published address rather than by the service's name —
    // the two services on the official list are both called "noderamc.org", and a lookup by name
    // would have shown the tracker's latency next to the relay.
    endpointStatus: entry.endpoints.map((endpoint) => ({ endpoint, ...(statusOf(endpoint) ?? {}) })),
  };
});

const data = {
  source: path.relative(repositoryDirectory, resolved),
  fetchedAt: new Date().toISOString(),
  /**
   * When and where the reachability above was measured. `null` when nobody has ever measured it.
   *
   * The page renders an age from `measuredAt` IN THE BROWSER, not at build time: a static site can
   * sit in a cache for a week, and "checked 4 minutes ago" baked into HTML is a sentence that
   * becomes false quietly. The absolute timestamp is what ships; the relative age is computed
   * against the reader's own clock.
   */
  probe: probe
    ? {
        measuredAt: probe.measuredAt,
        measuredBy: probe.measuredBy ?? "unknown",
        attempts: probe.attempts ?? null,
        source: process.env.NODERA_SITE_PROBE === "1" ? "build" : `${PROBE_BRANCH}:${PROBE_FILE}`,
      }
    : null,
  services,
};

mkdirSync(path.dirname(OUT), { recursive: true });
writeFileSync(OUT, `${JSON.stringify(data, null, 2)}\n`);
console.log(
  `build-services: ${services.length} service(s) from ${data.source} ` +
    `(${services.filter((s) => s.kind === "tracker").length} tracker, ` +
    `${services.filter((s) => s.kind === "rendezvous").length} rendezvous); ` +
    `reachability ${data.probe ? `measured ${data.probe.measuredAt} by ${data.probe.measuredBy}` : "never measured"}`,
);
