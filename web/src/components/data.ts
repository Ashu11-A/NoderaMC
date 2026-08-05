import rawRelease from "../generated/release.json";
import rawServices from "../generated/services.json";
import rawStatus from "../generated/status.json";
import type { ReleaseAsset, ReleaseData, ServicesData, StatusData } from "../generated/types";

/**
 * The four facts this site is not allowed to type by hand.
 *
 * Every number on the download page, the status page and the services page is generated from the
 * repository at build time — the release manifest, the roadmap tables, the measured test totals on
 * the `badges` branch, the limitation registers, and the published service index. Nothing here is
 * transcribed, because a transcribed count is a count that is wrong the week after it is written
 * and has no way of saying so.
 *
 * The accessors below all fail loudly. A missing key throws during prerender, which is a build
 * failure naming the key — the alternative is an optional chain that renders a link with no `href`
 * and a table with no rows, which looks completely correct and does nothing.
 */

export const release = rawRelease as unknown as ReleaseData;
export const services = rawServices as unknown as ServicesData;
export const status = rawStatus as unknown as StatusData;

/** The four groups the twelve release assets are presented in, and the rule for each. */
export type AssetGroup = "installers" | "jars" | "services" | "integrity";

const GROUP_RULES: { group: AssetGroup; test: (name: string) => boolean }[] = [
  { group: "installers", test: (n) => n.startsWith("nodera-app-") },
  { group: "jars", test: (n) => /^nodera-(neoforge|paper|peer)-/.test(n) },
  { group: "services", test: (n) => /^nodera-(tracker|rendezvous)-/.test(n) },
  { group: "integrity", test: (n) => n.startsWith("SHA256SUMS") },
];

/**
 * Sorts every asset the generator produced into exactly one group, and throws if any asset lands in
 * none or in two.
 *
 * The names carry the release tag (`nodera-tracker-arm64-latest`), so they cannot be checked as
 * literals at compile time the way a fixed key set could be. This is the runtime equivalent, and it
 * runs during prerender: a new asset kind that nobody taught this page about stops the build with
 * its own name in the message, rather than silently disappearing from the table.
 */
export function groupedAssets(): Record<AssetGroup, [string, ReleaseAsset][]> {
  const out: Record<AssetGroup, [string, ReleaseAsset][]> = {
    installers: [],
    jars: [],
    services: [],
    integrity: [],
  };
  for (const [name, asset] of Object.entries(release.assets)) {
    const matches = GROUP_RULES.filter((rule) => rule.test(name));
    if (matches.length !== 1) {
      throw new Error(
        `download: the release asset "${name}" matches ${matches.length} of the four groups on ` +
          `/download. Every asset belongs to exactly one. Teach web/src/components/data.ts about it.`,
      );
    }
    out[matches[0].group].push([name, asset]);
  }
  for (const list of Object.values(out)) list.sort((a, b) => a[0].localeCompare(b[0]));
  return out;
}

/** Limitation rows that have not retired: what "not done yet" actually means, counted. */
export function openLimitations() {
  return status.limitations.filter((row) => row.state === "OPEN" || row.state === "RETIRING");
}

/** Bytes, for a download table. Decimal units, because that is what a release page means by MB. */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) throw new Error(`download: bad asset size ${bytes}`);
  const units = ["B", "kB", "MB", "GB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1000 && unit < units.length - 1) {
    value /= 1000;
    unit += 1;
  }
  return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
}
