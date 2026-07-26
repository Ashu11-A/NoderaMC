// Task 32/33: the bridge from the Rust backend to the React dashboard. The backend emits a
// `nodera://metrics` event each second and answers a `get_metrics` command on demand. The shapes
// mirror `rust/nodera-app/src/metrics.rs`, which in turn mirror the worker's NODERA-STATE JSON.
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";

export interface PeerRow {
  node_id: string;
  /** Dialable route, rendered as tcp://host:port. */
  route: string;
  /** direct | relayed | unknown. */
  path: string;
  /** The peer's self-declared client agent, e.g. "NoderaMC 0.1.0". */
  client: string;
  up_bytes_per_sec: number;
  down_bytes_per_sec: number;
  total_up_bytes: number;
  total_down_bytes: number;
}

export interface WorldRow {
  world_id: string;
  name: string;
  players: number;
  /** The host's open Minecraft endpoint while its game runs; empty once it closes. */
  mc_route: string;
  /** Epoch millis this world entered the network from this node. */
  added_at: number;
  /** Epoch millis of this world's most recent content change here. */
  updated_at: number;
  total_bytes: number;
  /** Manifest root — the content checksum identifying these exact bytes. */
  checksum: string;
  version: number;
  piece_count: number;
  pieces_held: number;
  seeders: number;
  /** true = this node only keeps the bytes alive; false = it hosts the world. */
  seeding: boolean;
}

export interface EndpointHealth {
  host: string;
  port: number;
  /** tcp | udp — how this endpoint is actually reached. */
  scheme: string;
  reachable: boolean;
  /** Handshake round-trip in ms, or -1 when unreachable / not yet probed. */
  latency_ms: number;
}

/** One world's piece grid, pulled on demand (mirrors metrics.rs PieceMapView). */
export interface PieceMapView {
  world_id: string;
  manifest_root: string;
  version: number;
  piece_count: number;
  held_count: number;
  total_bytes: number;
  /** One entry per piece: true = verified present locally (the green cell). */
  held: boolean[];
  holders: string[];
}

export const EMPTY_PIECE_MAP: PieceMapView = {
  world_id: "",
  manifest_root: "",
  version: 0,
  piece_count: 0,
  held_count: 0,
  total_bytes: 0,
  held: [],
  holders: [],
};

export interface Metrics {
  node_id: string;
  worker_version: string;
  client: string;
  uptime_seconds: number;
  is_gateway: boolean;
  self_route: string;
  roles: string[];
  maintained_pieces: number;
  maintained_bytes: number;
  total_sent_bytes: number;
  total_received_bytes: number;
  total_chunks: number;
  /** Upload ÷ download, permille (1000 = 1.00). */
  share_ratio_permille: number;
  /** Verified-present pieces as a permille of all tracked pieces. */
  availability_permille: number;
  peers: PeerRow[];
  connected_worlds: WorldRow[];
  trackers: EndpointHealth[];
  rendezvous: EndpointHealth[];
  daemon_up: boolean;
  /**
   * The worker's own report that it is not moving bytes — from battery rules, the tray's Pause
   * seeding, or a manual pause. Mirrored from NODERA-STATE rather than from the app's own request,
   * so the UI shows what is happening and not what was asked for.
   */
  transfers_paused: boolean;
}

export const EMPTY_METRICS: Metrics = {
  node_id: "",
  worker_version: "",
  client: "",
  uptime_seconds: 0,
  is_gateway: false,
  self_route: "",
  roles: [],
  maintained_pieces: 0,
  maintained_bytes: 0,
  total_sent_bytes: 0,
  total_received_bytes: 0,
  total_chunks: 0,
  share_ratio_permille: 0,
  availability_permille: 0,
  peers: [],
  connected_worlds: [],
  trackers: [],
  rendezvous: [],
  daemon_up: false,
  transfers_paused: false,
};

export async function fetchMetrics(): Promise<Metrics> {
  return invoke<Metrics>("get_metrics");
}

export function onMetrics(cb: (m: Metrics) => void): Promise<UnlistenFn> {
  return listen<Metrics>("nodera://metrics", (event) => cb(event.payload));
}

// Machine + worker RAM/CPU (mirrors rust/nodera-app/src/system.rs).
export interface SystemStats {
  machine_cpu_percent: number;
  mem_used_bytes: number;
  mem_total_bytes: number;
  worker_found: boolean;
  worker_pid: number;
  worker_cpu_percent: number;
  worker_rss_bytes: number;
}

export const EMPTY_SYSTEM: SystemStats = {
  machine_cpu_percent: 0,
  mem_used_bytes: 0,
  mem_total_bytes: 0,
  worker_found: false,
  worker_pid: 0,
  worker_cpu_percent: 0,
  worker_rss_bytes: 0,
};

export async function fetchSystemStats(): Promise<SystemStats> {
  return invoke<SystemStats>("get_system_stats");
}

export function onSystemStats(cb: (s: SystemStats) => void): Promise<UnlistenFn> {
  return listen<SystemStats>("nodera://system", (event) => cb(event.payload));
}

// The worker's recent log lines (oldest first, bounded ring on the Rust side).
export async function fetchWorkerLogs(): Promise<string[]> {
  return invoke<string[]>("get_worker_logs");
}

// One world's piece grid. Pulled on demand rather than pushed with the metrics snapshot: only the
// selected world matters, and a node seeding a dozen worlds should not re-encode every bitmap once
// a second for a tab nobody has open.
export async function fetchPieceMap(worldId: string): Promise<PieceMapView> {
  if (!worldId) return EMPTY_PIECE_MAP;
  return invoke<PieceMapView>("get_piece_map", { worldId });
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

export function formatRate(bytesPerSec: number): string {
  if (bytesPerSec < 1024) return `${bytesPerSec} B/s`;
  if (bytesPerSec < 1024 * 1024) return `${(bytesPerSec / 1024).toFixed(1)} KB/s`;
  return `${(bytesPerSec / 1024 / 1024).toFixed(1)} MB/s`;
}

export function formatUptime(seconds: number): string {
  if (seconds <= 0) return "—";
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
  if (!id) return "—";
  if (id.length <= head + tail + 1) return id;
  return `${id.slice(0, head)}…${id.slice(-tail)}`;
}

// Permille integers cross the wire so every surface renders the same number; formatting is the
// only place they become decimal.
export function formatPermille(permille: number, digits = 2): string {
  return (permille / 1000).toFixed(digits);
}

export function formatPercent(permille: number): string {
  return `${(permille / 10).toFixed(1)}%`;
}

/* ------------------------------------------------------------------------------------ settings */

export type Theme = "system" | "dark" | "light";

export interface Appearance {
  theme: Theme;
  notifications: boolean;
}

export interface Behavior {
  auto_start: boolean;
  only_when_charging: boolean;
  battery_control: boolean;
  battery_threshold_percent: number;
  power_rules_during_game: boolean;
}

export interface NetworkSettings {
  default_trackers: string[];
  unlimited_connections_only: boolean;
  use_random_port: boolean;
  port_range_start: number;
  port_range_end: number;
  max_connections: number;
  max_connections_per_world: number;
  max_upload_slots_per_world: number;
  max_upload_bytes_per_sec: number;
  max_download_bytes_per_sec: number;
}

export interface StorageSettings {
  peer_worlds_dir: string;
}

export interface Settings {
  appearance: Appearance;
  behavior: Behavior;
  network: NetworkSettings;
  storage: StorageSettings;
}

export async function fetchSettings(): Promise<Settings> {
  return invoke<Settings>("get_settings");
}

export async function saveSettings(next: Settings): Promise<void> {
  return invoke<void>("save_settings", { next });
}

// ---- telemetry consent (app task 5) ------------------------------------------------------------

/** Mirrors `rust/nodera-app/src/telemetry.rs::Consent`. */
export type Consent = "unanswered" | "denied" | "granted";

/** Mirrors `TelemetryStatus`. Everything except `asked` comes from the worker. */
export interface TelemetryStatus {
  consent: Consent;
  /** false ⇒ the worker did not answer, or predates the verb: say so, do not show a live toggle. */
  supported: boolean;
  endpoint: string;
  queued: number;
  sent: number;
  last_error: string;
  /** Whether this installation has ever answered the question — the modal's gate. */
  asked: boolean;
}

export const EMPTY_TELEMETRY: TelemetryStatus = {
  consent: "unanswered",
  supported: false,
  endpoint: "",
  queued: 0,
  sent: 0,
  last_error: "",
  asked: false,
};

export async function fetchTelemetryStatus(): Promise<TelemetryStatus> {
  return invoke<TelemetryStatus>("get_telemetry_status");
}

/**
 * Record the person's answer on the NODE.
 *
 * Returns the status the worker reports afterwards — the UI badges what was confirmed, never what
 * was requested.
 */
export async function setTelemetryConsent(granted: boolean): Promise<TelemetryStatus> {
  return invoke<TelemetryStatus>("set_telemetry_consent", { granted });
}

/** Ask the configured collector what it accepts. Rejects when it cannot be reached. */
export async function fetchCollectedSchema(endpoint: string): Promise<string> {
  return invoke<string>("get_collected_schema", { endpoint });
}

/**
 * Which settings are persisted but not yet acted on by the worker.
 *
 * @deprecated A flat list of names cannot say *why*, and "the worker is too old" and "nobody has
 * implemented this" are different things to a user. Use {@link fetchSettingStatus}. Kept so an
 * older bundle keeps working against a newer backend.
 */
export async function fetchUnenforcedSettings(): Promise<string[]> {
  return invoke<string[]>("get_unenforced_settings");
}

/* --------------------------------------------------------------- settings enforcement + config */

/**
 * How a settings key is actually being treated right now.
 *
 * - `live` — in force. The backend reports this **only** when the connected worker named the key in
 *   its own `applied` list (or when this app enforces it itself, e.g. theme). It cannot be claimed.
 * - `restart_required` — saved; takes effect when the worker restarts (see {@link restartWorker}).
 * - `unsupported_by_worker` — the worker on the other end does not do this, usually because it is
 *   older than the app. Fixed by updating the worker.
 * - `unenforced` — nothing is applying it. A **non-empty `reason`** means the limitation is
 *   permanent and structural, not "coming soon" — badge those two differently.
 */
export type SettingState =
  | "live"
  | "restart_required"
  | "unsupported_by_worker"
  | "unenforced";

export interface SettingStatus {
  /** Dotted key, e.g. "network.max_upload_bytes_per_sec". Matches the settings document. */
  key: string;
  state: SettingState;
  /** Empty when there is nothing to explain; otherwise render it verbatim in the tooltip. */
  reason: string;
}

/** Every settings key's honest status, computed from the worker's own last reply. */
export async function fetchSettingStatus(): Promise<SettingStatus[]> {
  return invoke<SettingStatus[]>("get_setting_status");
}

/**
 * The last configuration push's outcome, as *soft* status.
 *
 * Settings are persisted before any push is attempted, so a failure here never means the user lost
 * a setting — only that the worker has not got it yet. Render it as an advisory, not an error.
 */
export interface ConfigStatus {
  /** The worker answered the last push at all — including by refusing it. */
  pushed: boolean;
  /** It understood NODERA-CONFIG. `false` with `pushed` means a worker older than the app. */
  supported: boolean;
  applied: string[];
  restart_required: string[];
  /** key → the worker's own reason for declining it. */
  rejected: Record<string, string>;
  /** Empty when the last push succeeded; otherwise the failure in the worker's own words. */
  error: string;
}

export const EMPTY_CONFIG_STATUS: ConfigStatus = {
  pushed: false,
  supported: false,
  applied: [],
  restart_required: [],
  rejected: {},
  error: "",
};

export async function fetchConfigStatus(): Promise<ConfigStatus> {
  return invoke<ConfigStatus>("get_config_status");
}

/** Who owns the worker process — decides whether to offer a Restart button at all. */
export interface WorkerOwnership {
  /** The worker was started outside this app (attach mode, e.g. scripts/dev.sh). */
  attached: boolean;
  /** Only true when this app spawned the worker and may therefore stop it. */
  can_restart: boolean;
}

export async function fetchWorkerOwnership(): Promise<WorkerOwnership> {
  return invoke<WorkerOwnership>("get_worker_ownership");
}

/**
 * Restart the worker so env-shaped settings (trackers, port range, archive directory) apply.
 *
 * Rejects with a message when the worker was started outside the app — the app must not kill a
 * process it did not start. Gate the button on {@link WorkerOwnership.can_restart}.
 */
export async function restartWorker(): Promise<void> {
  return invoke<void>("restart_worker");
}

/**
 * Toggle the manual "pause seeding" flag, mirroring the tray item. Resolves to the new *effective*
 * pause state, which the battery rules can also be holding on independently.
 */
export async function togglePause(): Promise<boolean> {
  return invoke<boolean>("toggle_pause");
}

/** Emitted when the tray's Pause seeding item is used, so the window can follow along. */
export function onPauseToggled(cb: (paused: boolean) => void): Promise<UnlistenFn> {
  return listen<boolean>("nodera://pause", (event) => cb(event.payload));
}

// Epoch millis → a short absolute date. 0 means "never recorded", not 1970.
export function formatDate(epochMillis: number): string {
  if (!epochMillis) return "—";
  const d = new Date(epochMillis);
  return d.toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

// A dialable route as a URI, the way a torrent client shows a peer source.
export function formatSource(route: string, scheme = "tcp"): string {
  if (!route) return "—";
  return route.includes("://") ? route : `${scheme}://${route}`;
}

export function formatLatency(ms: number): string {
  return ms < 0 ? "—" : `${ms} ms`;
}

// 0 means "unlimited" in every limit field, which is not the same as "zero allowed".
export function formatLimit(value: number, unit = ""): string {
  return value === 0 ? "Unlimited" : `${value}${unit}`;
}
