import { useEffect, useState } from "react";
import type { ReleaseAsset } from "../generated/types";
import { type AssetGroup, VERSION, formatBytes, groupedAssets, release } from "./data";
import { Badge } from "./primitives";

/**
 * The download page's moving parts.
 *
 * The failure this page is built to avoid is a specific one: choosing an asset with `find()` and an
 * optional chain, so that a naming change renders a button with no `href` — no build error, no
 * runtime warning, and a page that looks entirely correct and does nothing. With twelve assets that
 * is twelve chances to ship it. Everything here reads a generated table that was itself checked
 * against the repository's one naming function, and throws rather than rendering an empty anchor.
 */

type Platform = "windows" | "linux" | "android";

const PLATFORMS: { id: Platform; label: string; match: RegExp; requirement: string }[] = [
  {
    id: "windows",
    label: "Windows",
    match: /^nodera-app-windows-/,
    requirement: "Windows 10 or later. Pick x64 unless you know your machine is arm64.",
  },
  {
    id: "linux",
    label: "Linux",
    match: /^nodera-app-linux-/,
    requirement: "A .deb for Debian and Ubuntu derivatives. Other distributions: build from source.",
  },
  {
    id: "android",
    label: "Android",
    match: /^nodera-app-android-/,
    requirement: "One APK carrying every ABI. Sideloaded — there is no store listing.",
  },
];

function detectPlatform(): Platform | null {
  if (typeof navigator === "undefined") return null;
  const agent = navigator.userAgent.toLowerCase();
  if (agent.includes("android")) return "android";
  if (agent.includes("windows")) return "windows";
  if (agent.includes("linux")) return "linux";
  return null;
}

export function DownloadPill(props: { name: string; asset: ReleaseAsset; primary?: boolean }) {
  return (
    <a
      href={props.asset.url}
      className={`inline-flex h-10 items-center gap-2 rounded-full px-5 text-sm font-medium ${
        props.primary ? "bg-brand text-on-play" : "border border-line text-text hover:border-brand-1"
      }`}
    >
      <span>{props.name}</span>
      <span className="text-xs opacity-80">{formatBytes(props.asset.size)}</span>
    </a>
  );
}

/**
 * Three neutral buttons in the prerendered HTML, one of them promoted after mount.
 *
 * It deliberately does not guess before it knows. There are five installers here, and a page that
 * opens with a filled **Download for Windows** button in front of a Linux arm64 user is worse than
 * a page that asks — and with JavaScript off, or on a platform this cannot recognise, the asking
 * version is the one that stays.
 */
export function PlatformPicker() {
  const [detected, setDetected] = useState<Platform | null>(null);
  useEffect(() => setDetected(detectPlatform()), []);

  const installers = groupedAssets().installers;
  return (
    <div className="mt-8">
      <div className="flex flex-wrap gap-3">
        {PLATFORMS.map((platform) => {
          const matches = installers.filter(([name]) => platform.match.test(name));
          if (matches.length === 0) {
            throw new Error(
              `download: this release publishes no installer for ${platform.id}. Either the release ` +
                `is short or /download is describing a platform the release lane dropped.`,
            );
          }
          const active = detected === platform.id;
          return (
            <div key={platform.id} className="min-w-[220px] flex-1">
              <p className="mb-2 text-sm font-medium text-text">{platform.label}</p>
              <div className="flex flex-wrap gap-2">
                {matches.map(([name, asset]) => (
                  <DownloadPill key={name} name={name.replace(/^nodera-app-\w+-/, "")} asset={asset} primary={active} />
                ))}
              </div>
              <p className="mt-2 text-xs text-faint">{platform.requirement}</p>
            </div>
          );
        })}
      </div>
    </div>
  );
}

/** The publication date, absolute in the HTML and relative once there is a clock to compare to. */
export function ReleaseDate() {
  const [relative, setRelative] = useState<string | null>(null);
  useEffect(() => {
    const then = new Date(release.publishedAt).getTime();
    const days = Math.round((then - Date.now()) / 86_400_000);
    const format = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });
    setRelative(Math.abs(days) < 1 ? format.format(0, "day") : format.format(days, "day"));
  }, []);
  const absolute = new Date(release.publishedAt).toLocaleDateString("en-GB", {
    year: "numeric",
    month: "long",
    day: "numeric",
  });
  return (
    <time dateTime={release.publishedAt} title={absolute}>
      {relative ?? absolute}
    </time>
  );
}

function CopyDigest(props: { digest: string | null }) {
  const [copied, setCopied] = useState(false);
  if (!props.digest) return <span className="text-xs text-faint">no digest published</span>;
  return (
    <button
      type="button"
      className="rounded-sm border border-line px-2 py-1 text-xs text-dim hover:border-brand-1 hover:text-text"
      onClick={() => {
        void navigator.clipboard.writeText(props.digest as string).then(() => {
          setCopied(true);
          window.setTimeout(() => setCopied(false), 1500);
        });
      }}
    >
      {copied ? "copied" : "Copy SHA-256"}
    </button>
  );
}

export function AssetRow(props: { name: string; asset: ReleaseAsset }) {
  return (
    <li className="flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-line-soft px-4 py-3 last:border-b-0">
      <a href={props.asset.url} className="min-w-0 flex-1 font-mono text-sm break-all text-brand-1 hover:text-text">
        {props.name}
      </a>
      <span className="text-sm text-faint tabular-nums">{formatBytes(props.asset.size)}</span>
      <CopyDigest digest={props.asset.sha256} />
    </li>
  );
}

const GROUP_TITLES: Record<AssetGroup, string> = {
  installers: "Installers",
  jars: "Server-side jars",
  services: "Service binaries",
  integrity: "Integrity",
};

const GROUP_NOTES: Record<AssetGroup, string> = {
  installers: "The companion app. It supervises the worker, and the mod refuses to start without it.",
  jars: "The NeoForge mod, the Paper endpoint plugin, and the headless node you can run with java -jar.",
  services: "A tracker and a relay, for anyone who wants to run one. Neither holds authority over a world.",
  integrity: "The digest list this release published, and its signature when there is one.",
};

/**
 * Four groups, not twelve buttons.
 *
 * The three audiences here are disjoint. A player looking for an installer should not have to
 * scroll past `nodera-rendezvous-arm64-latest` to find it, and an operator should not have to guess
 * which of twelve filenames is the tracker.
 */
export function AssetTable() {
  const groups = groupedAssets();
  return (
    <div className="mt-8 flex flex-col gap-3">
      {(Object.keys(GROUP_TITLES) as AssetGroup[]).map((group) => (
        <details key={group} className="rounded-lg border border-line-soft bg-surface" open={group === "installers"}>
          <summary className="flex cursor-pointer items-center gap-3 px-4 py-3 text-body text-text">
            <span>{GROUP_TITLES[group]}</span>
            <Badge>{groups[group].length}</Badge>
            <span className="hidden text-sm text-faint sm:inline">{GROUP_NOTES[group]}</span>
          </summary>
          <ul className="border-t border-line-soft">
            {groups[group].map(([name, asset]) => (
              <AssetRow key={name} name={name} asset={asset} />
            ))}
          </ul>
        </details>
      ))}
    </div>
  );
}

/**
 * What verification actually proves today.
 *
 * The signature half renders only when the release published one. Otherwise the page says, without
 * softening it, that a digest proves the file is the file this release published and proves nothing
 * about who published it. The mechanism to do better is built, tested, and waiting on a private key
 * that by definition cannot live in this repository — tracker limitation L-81.
 */
export function ChecksumBlock() {
  return (
    <div className="mt-6">
      <pre className="overflow-x-auto rounded-md border border-line-soft bg-surface p-4 font-mono text-sm text-dim">
        {`sha256sum -c SHA256SUMS`}
      </pre>
      {release.hasSignature ? (
        <p className="mt-4 text-body leading-7 text-dim">
          This release published <code className="font-mono text-sm text-text">SHA256SUMS.sig</code>.
          Verify the signature against the project's public key <em>before</em> you check a digest:
          checking the digest first lets a substituted list choose which binary you are told to
          trust.
        </p>
      ) : (
        <p className="mt-4 text-body leading-7 text-dim">
          <strong className="font-medium text-text">Releases are not signed yet.</strong>{" "}
          <code className="font-mono text-sm text-text">SHA256SUMS</code> proves the file you got is
          the file this release published; it does not prove who published it. The verification lane
          is built and tested and is waiting on a key that does not exist yet — tracker limitation{" "}
          <a href="/status" className="text-brand-1 hover:text-text">
            L-81
          </a>
          .
        </p>
      )}
    </div>
  );
}

export function ReleaseHeading() {
  const rolling = release.tag === "latest";
  return (
    <p className="mt-4 text-lg leading-8 text-dim">
      NoderaMC <strong className="font-medium text-text">{VERSION}</strong>. This page shows the{" "}
      <code className="font-mono text-sm text-text">{release.tag}</code> release, published{" "}
      <ReleaseDate />.{" "}
      {rolling ? (
        <>
          <code className="font-mono text-sm text-text">latest</code> is a rolling prerelease, re-cut
          from every push to <code className="font-mono text-sm text-text">main</code> — which is why
          its filenames carry <em>latest</em> rather than a version number.
        </>
      ) : null}
    </p>
  );
}
