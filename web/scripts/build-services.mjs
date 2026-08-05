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
import { storeOfferHref } from "nodera-ui/store-link";
import { dir, layoutValue, repositoryDirectory } from "nodera-ui/layout";

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
// The URL is composed by the shared kit so the site and the README cannot spell it differently —
// `web/tests/add-store.test.mjs` holds the README badge to this same value.
const storeUrl = storeOfferHref(layoutValue("services.rawUrl"));

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
    storeUrl,
  };
});

const data = {
  source: path.relative(repositoryDirectory, resolved),
  fetchedAt: new Date().toISOString(),
  services,
};

mkdirSync(path.dirname(OUT), { recursive: true });
writeFileSync(OUT, `${JSON.stringify(data, null, 2)}\n`);
console.log(
  `build-services: ${services.length} service(s) from ${data.source} ` +
    `(${services.filter((s) => s.kind === "tracker").length} tracker, ` +
    `${services.filter((s) => s.kind === "rendezvous").length} rendezvous)`,
);
