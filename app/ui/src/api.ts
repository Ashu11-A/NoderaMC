// The dashboard API, as React sees it.
//
// One rule runs through this file and the pages built on it: **a value that nobody has reported is
// not zero.** The Rust side models that with `Option`, which arrives here as `null`, and every
// formatter below turns `null` into an em dash rather than into a plausible number. The screen this
// replaces rendered `0 B`, `0 peers` and `0 ms` with total confidence whether or not a worker had
// ever answered — which is how a dashboard ends up lying without anyone writing a lie.
//
// Steady state is the `nodera://dashboard` event, which the backend emits **when a snapshot is
// accepted**, not on a timer. An event therefore means "this just changed"; the absence of one
// means the node is quiet, and `link.age_ms` is what tells the two apart.
import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import type { Lan } from "./play";

/* --------------------------------------------------------------------------------------- model */

/** Mirrors `api::model::LinkStatus`. */
export type LinkStatus = "connecting" | "live" | "polling" | "offline";

/** Mirrors `api::model::Link` — where this picture came from and how much to trust it. */
export interface Link {
  status: LinkStatus;
  /** "stream" (the worker pushes) | "poll" (the app asks) | "none". */
  transport: string;
  worker_version: string;
  /** Epoch millis of the newest accepted snapshot. 0 = never heard from a worker. */
  last_update_ms: number;
  age_ms: number;
  /** Climbs on every accepted snapshot, even when no value changed. */
  revision: number;
  last_error: string;
  reconnects: number;
  /** false ⇒ every number below is a placeholder; render "—". */
  has_data: boolean;
}

export interface NodeInfo {
  node_id: string;
  client: string;
  self_route: string;
  roles: string[];
  uptime_seconds: number;
  is_gateway: boolean;
  transfers_paused: boolean;
}

export interface Traffic {
  total_sent_bytes: number;
  total_received_bytes: number;
  /** null until two snapshots exist to measure between. Measured in Rust, never here. */
  up_bytes_per_sec: number | null;
  down_bytes_per_sec: number | null;
  /** null when nothing has been downloaded — a ratio with no denominator. */
  share_ratio_permille: number | null;
}

export interface Content {
  maintained_pieces: number;
  maintained_bytes: number;
  tracked_pieces: number;
  /** null when nothing is tracked: 100% of nothing is not a health reading. */
  availability_permille: number | null;
}

export interface World {
  world_id: string;
  name: string;
  /** This peer holds the world's private key. Answers "may I speak for it". */
  administered: boolean;
  /** This node hosts it rather than only keeping its bytes. Answers "what am I doing with it". */
  hosting: boolean;
  /**
   * Somebody on this machine is in the world right now. Answers "am I playing it" — which neither
   * of the flags above can, because the ordinary case is a world you neither run nor authored.
   */
  connected: boolean;
  world_public_key: string;
  /**
   * Players in-world, or null when no node that is *in* the world has reported recently.
   *
   * Only a node with a game in the world can count them. Rendering the unknown as 0 is what made
   * this number wrong on every peer except the one hosting the game — always show it via `show()`.
   */
  players: number | null;
  /** null once the host's game closes. */
  game_endpoint: string | null;
  added_at: number;
  updated_at: number;
  total_bytes: number;
  checksum: string;
  version: number;
  piece_count: number;
  pieces_held: number;
  seeders: number;
  /** Full copies believed to exist, this node's included when it holds one. */
  backup_copies: number;
  /**
   * Copies a network this size should keep. Capped by the peers that exist, so a small network
   * wants a full copy on every peer — which is why this equals `backup_copies` once reached.
   */
  backup_copies_wanted: number;
  /** Chance nobody holds it right now, permille. null once it rounds to nothing. */
  loss_risk_permille: number | null;
  /** null until the manifest is known — unmeasured, not 0%. */
  completeness_permille: number | null;
  /**
   * Whether any tracker lists this world; null when it has never been announced.
   *
   * false is not a warning about quality of service — it means no peer on the network can find
   * this world, whatever else this screen says about it.
   */
  discoverable: boolean | null;
  /**
   * Regions of the live world this node validates and seeds snapshots for.
   *
   * Holding a world and running one are different contributions. This is the second one, and the
   * only field on this screen that can distinguish a peer carrying part of the simulation from a
   * peer that is merely storing somebody else's save.
   */
  regions_held: number;
}

export interface Peer {
  node_id: string;
  route: string;
  path: string;
  client: string;
  up_bytes_per_sec: number;
  down_bytes_per_sec: number;
  total_up_bytes: number;
  total_down_bytes: number;
}

export interface Endpoint {
  host: string;
  port: number;
  scheme: string;
  reachable: boolean;
  /** null when unreachable or not yet probed — never 0, which claims an instant handshake. */
  latency_ms: number | null;
}

export interface Discovery {
  trackers: Endpoint[];
  rendezvous: Endpoint[];
  relay_latency_ms: number | null;
}

export interface Counts {
  administered_worlds: number;
  supported_worlds: number;
  /** Worlds somebody on this machine is in right now. */
  connected_worlds: number;
  /** Worlds kept alive for other people — supported and not being played here. */
  shared_for_others: number;
  /** Content bytes verified present here across every world this node does not administer. */
  shared_bytes: number;
  players: number;
  peers: number;
}

export interface Dashboard {
  link: Link;
  node: NodeInfo;
  traffic: Traffic;
  content: Content;
  worlds: World[];
  peers: Peer[];
  discovery: Discovery;
  counts: Counts;
  /** Worlds opened to LAN on this machine — the lane that lets an unmodified Minecraft play here. */
  lan: Lan;
}

/** What the UI shows before the first payload arrives: nothing known, and it says so. */
export const EMPTY_DASHBOARD: Dashboard = {
  link: {
    status: "connecting",
    transport: "none",
    worker_version: "",
    last_update_ms: 0,
    age_ms: 0,
    revision: 0,
    last_error: "",
    reconnects: 0,
    has_data: false,
  },
  node: {
    node_id: "",
    client: "",
    self_route: "",
    roles: [],
    uptime_seconds: 0,
    is_gateway: false,
    transfers_paused: false,
  },
  traffic: {
    total_sent_bytes: 0,
    total_received_bytes: 0,
    up_bytes_per_sec: null,
    down_bytes_per_sec: null,
    share_ratio_permille: null,
  },
  content: {
    maintained_pieces: 0,
    maintained_bytes: 0,
    tracked_pieces: 0,
    availability_permille: null,
  },
  worlds: [],
  peers: [],
  discovery: { trackers: [], rendezvous: [], relay_latency_ms: null },
  counts: {
    administered_worlds: 0,
    supported_worlds: 0,
    connected_worlds: 0,
    shared_for_others: 0,
    shared_bytes: 0,
    players: 0,
    peers: 0,
  },
  lan: { supported: false, reason: "", sessions: [] },
};

/* ------------------------------------------------------------------------------------ transport */

/** The whole current picture. For the initial paint and for re-syncing, not for polling. */
export async function fetchDashboard(): Promise<Dashboard> {
  return invoke<Dashboard>("dashboard");
}

/**
 * Subscribe to the live stream.
 *
 * Emitted by the backend when a snapshot is **accepted**, so a burst of events means the node is
 * busy and a gap means it is quiet — neither of which a fixed-cadence emit could express.
 */
export function onDashboard(cb: (d: Dashboard) => void): Promise<UnlistenFn> {
  return listen<Dashboard>("nodera://dashboard", (event) => cb(event.payload));
}

/** Mirrors `api::link::DiscoveryChange`. */
export interface DiscoveryChange {
  /** At least one tracker answered — the question every screen actually asks. */
  connected: boolean;
  reachable: number;
  total: number;
  /** The endpoints that answered, `host:port`. */
  endpoints: string[];
}

export const EMPTY_DISCOVERY: DiscoveryChange = {
  connected: false,
  reachable: 0,
  total: 0,
  endpoints: [],
};

/**
 * Subscribe to tracker-reachability **transitions**.
 *
 * Edge-triggered in the backend, so this fires when something moved and not on every snapshot —
 * the worker pushes up to four times a second, and a listener that refetched on each one would turn
 * a status line into a load generator.
 *
 * Screens built on the dashboard do not need it: they already re-render. It exists for everything
 * that is not — the settings screens and the peer self-test read tracker state through their own
 * commands and had no way to learn a tracker had come back.
 */
export function onDiscovery(cb: (d: DiscoveryChange) => void): Promise<UnlistenFn> {
  return listen<DiscoveryChange>("nodera://discovery", (event) => cb(event.payload));
}

/**
 * The latest discovery transition, or `null` until one has been observed.
 *
 * `null` is load-bearing: it means *nobody has said yet*, which must not render as "no trackers are
 * answering". That conflation is what put a full-screen error over a working node.
 */
export function useDiscovery(): DiscoveryChange | null {
  const [d, setD] = useState<DiscoveryChange | null>(null);

  useEffect(() => {
    let alive = true;
    let unlisten: (() => void) | undefined;
    onDiscovery((next) => {
      if (alive) setD(next);
    })
      .then((un) => {
        if (alive) unlisten = un;
        else un();
      })
      .catch(() => {});
    return () => {
      alive = false;
      unlisten?.();
    };
  }, []);

  return d;
}

/** The outcome of asking the worker to prove it administers a world. */
export interface AdminProof {
  signed: boolean;
  challenge: string;
  proof: string;
  error: string;
}

/**
 * Ask your own peer to sign a fresh challenge with a world's private key.
 *
 * A success means this worker holds that key. It is **not** a cryptographic verification — the app
 * carries no Ed25519 implementation, and verification is what the network does against the world's
 * public key. Label it accordingly in the UI.
 */
export async function proveWorldAdmin(worldId: string): Promise<AdminProof> {
  return invoke<AdminProof>("prove_world_admin", { worldId });
}

/* ------------------------------------------------------------------------------------ rendering */

/** The one string that means "nobody told us". Never render a number in its place. */
export const UNKNOWN = "—";

/** Render a nullable value, or the em dash. Every optional number on screen goes through this. */
export function show<T>(value: T | null | undefined, render: (v: T) => string): string {
  return value === null || value === undefined ? UNKNOWN : render(value);
}

/**
 * Binary units, labelled as binary units.
 *
 * These divided by 1024 and wrote `KB`/`MB`/`GB`, which are decimal names for decimal quantities —
 * so every size and every rate in the app was understated by 2.4 % at KiB and 7.4 % by GiB against
 * its own label. Dividing by 1024 is the right choice for a piece store whose sizes are powers of
 * two; saying so is the part that was missing.
 */
export function formatBytes(bytes: number): string {
  const KI = 1024;
  if (bytes < KI) return `${bytes} B`;
  if (bytes < KI ** 2) return `${(bytes / KI).toFixed(1)} KiB`;
  if (bytes < KI ** 3) return `${(bytes / KI ** 2).toFixed(1)} MiB`;
  return `${(bytes / KI ** 3).toFixed(2)} GiB`;
}

export function formatRate(bytesPerSec: number): string {
  const KI = 1024;
  if (bytesPerSec < KI) return `${bytesPerSec} B/s`;
  if (bytesPerSec < KI ** 2) return `${(bytesPerSec / KI).toFixed(1)} KiB/s`;
  return `${(bytesPerSec / KI ** 2).toFixed(1)} MiB/s`;
}

export function formatUptime(seconds: number): string {
  if (seconds <= 0) return UNKNOWN;
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  if (d > 0) return `${d}d ${h}h ${m}m`;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${s}s`;
  return `${s}s`;
}

export function shortId(id: string, head = 8, tail = 4): string {
  if (!id) return UNKNOWN;
  if (id.length <= head + tail + 1) return id;
  return `${id.slice(0, head)}…${id.slice(-tail)}`;
}

/**
 * Bytes of a world verified present on this node.
 *
 * Prorated from the piece counts, mirroring `World::held_bytes` in Rust so the two never disagree.
 * Zero pieces is zero bytes, never the world's full size — a node holding nothing contributes
 * nothing, and rounding that up is how a screen tells somebody they are seeding when they are not.
 */
export function heldBytes(w: World): number {
  if (w.piece_count === 0) return 0;
  return Math.floor((w.total_bytes * w.pieces_held) / w.piece_count);
}

/**
 * What this node is to a world, in one word, from the three independent flags.
 *
 * Order is deliberate: "playing" is what the player came to the screen to see, and it outranks the
 * bookkeeping — you are still supporting a world you are playing in, and the section it is listed
 * under already says so.
 */
export function worldRole(w: World): "playing" | "administered" | "hosting" | "supporting" {
  if (w.connected) return "playing";
  if (w.administered) return "administered";
  return w.hosting ? "hosting" : "supporting";
}

/** Permille integers cross the wire so every surface renders the same number. */
export function formatPermille(permille: number, digits = 2): string {
  return (permille / 1000).toFixed(digits);
}

export function formatPercent(permille: number): string {
  return `${(permille / 10).toFixed(1)}%`;
}

export function formatDate(epochMillis: number): string {
  if (!epochMillis) return UNKNOWN;
  return new Date(epochMillis).toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/** A dialable route as a URI, the way a torrent client shows a peer source. */
export function formatSource(route: string, scheme = "tcp"): string {
  if (!route) return UNKNOWN;
  return route.includes("://") ? route : `${scheme}://${route}`;
}

/** How old the picture is, in words. Used next to the live indicator, never instead of it. */
export function formatAge(ms: number): string {
  if (ms < 1500) return "just now";
  if (ms < 60_000) return `${Math.round(ms / 1000)}s ago`;
  if (ms < 3_600_000) return `${Math.round(ms / 60_000)}m ago`;
  return `${Math.round(ms / 3_600_000)}h ago`;
}

/**
 * What is wrong with the link, in the fewest words that let somebody act — or "" when nothing is.
 *
 * Deliberately empty for a healthy link. The old version produced a sentence in every state,
 * including the good one, which made a status line into a paragraph nobody reads. A healthy link is
 * shown by its facts (worker version, when it last reported), not by being described.
 */
export function linkFault(link: Link): string {
  switch (link.status) {
    case "live":
    case "polling":
      return "";
    case "connecting":
      return link.has_data ? "Reconnecting" : "Looking for your peer";
    case "offline":
      return link.last_error || "Your peer is not answering";
  }
}

/**
 * A picture exists but the link is not delivering current data.
 *
 * True only when `has_data` is set: a screen that has never heard from the worker shows "—" for
 * every figure (there is nothing to mark), while a screen that *has* heard and then lost the link
 * is showing the last known picture — which must be said, because those numbers look identical to
 * live ones. This is the flag every figure-bearing screen marks itself with when the worker is
 * stopped, so "last known" is stated rather than implied (A-UX-1).
 */
export function isStale(link: Link): boolean {
  return link.has_data && link.status !== "live" && link.status !== "polling";
}

/* ----------------------------------------------------------------------------------- the hook */

export function useDashboard(empty: Dashboard): Dashboard {
  const [d, setD] = useState<Dashboard>(empty);

  useEffect(() => {
    let alive = true;
    let unlisten: (() => void) | undefined;

    fetchDashboard()
      .then((first) => {
        if (alive) setD(first);
      })
      .catch(() => {});
    onDashboard((next) => {
      if (alive) setD(next);
    })
      .then((un) => {
        // The subscription can resolve after unmount; drop it immediately if so.
        if (alive) unlisten = un;
        else un();
      })
      .catch(() => {});

    return () => {
      alive = false;
      unlisten?.();
    };
  }, []);

  return d;
}
