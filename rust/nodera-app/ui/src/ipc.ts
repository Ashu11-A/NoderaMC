// The non-dashboard half of the bridge to the Rust backend: settings, telemetry consent, worker
// ownership, resource sampling, logs, and the on-demand piece map.
//
// The dashboard itself moved to `api.ts`, which mirrors `src/api/model.rs` and lives on the pushed
// `nodera://dashboard` event. Anything about "what is the node doing right now" belongs there; what
// is left here is configuration and machine-local facts, which are asked for rather than announced.
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";

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

// Write the whole scrollback to a file the user picks in the OS dialog.
//
// Resolves to the chosen path, or null when the dialog was cancelled — cancelling is a normal
// outcome and is deliberately not an error the page has to explain.
export async function saveWorkerLogs(): Promise<string | null> {
  return invoke<string | null>("save_worker_logs");
}

// The outcome of asking the worker to delete a world its owner administers.
export type DeleteOutcome = {
  deleted: boolean;
  peers_notified: number;
  error: string;
};

// Ask the worker to delete a world, everywhere. IRREVERSIBLE.
//
// The worker signs the request with the world's private key and refuses outright without one, so a
// world this peer merely supports cannot be deleted from here whatever this call sends. Every
// receiving peer re-verifies the record before destroying anything.
export async function deleteWorld(worldId: string, reason: string): Promise<DeleteOutcome> {
  return invoke<DeleteOutcome>("delete_world", { worldId, reason });
}

// One world's piece grid. Pulled on demand rather than pushed with the metrics snapshot: only the
// selected world matters, and a node seeding a dozen worlds should not re-encode every bitmap once
// a second for a tab nobody has open.
export async function fetchPieceMap(worldId: string): Promise<PieceMapView> {
  if (!worldId) return EMPTY_PIECE_MAP;
  return invoke<PieceMapView>("get_piece_map", { worldId });
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

/**
 * Which links this node will move other people's data over.
 *
 * Not a speed limit — a cost rule. `unmetered_only` follows the OS's own metered flag, so it
 * honours a user who marked their SIM unlimited; `wifi_only` is the literal reading and ignores it.
 */
export type NetworkPolicy = "any" | "unmetered_only" | "wifi_only";

export interface NetworkSettings {
  default_trackers: string[];
  /** Relay endpoints. Applied when the worker restarts. */
  rendezvous_endpoints: string[];
  transfer_network: NetworkPolicy;
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
  /** Disk spent holding other people's worlds. 0 = the worker's own default. */
  replication_budget_bytes: number;
  /** Replication sweep period. 0 = the worker's own default. */
  replication_sweep_seconds: number;
}

export interface Settings {
  appearance: Appearance;
  behavior: Behavior;
  network: NetworkSettings;
  storage: StorageSettings;
  /**
   * Present so that a `Settings` built by the UI round-trips it.
   *
   * `save_settings` takes the whole document, and `setup` is `#[serde(default)]` on the Rust side —
   * so any save from an object that omitted this field would silently reset `completed` to false
   * and re-trigger the first-run flow on the next launch.
   */
  setup: SetupState;
}

export async function fetchSettings(): Promise<Settings> {
  return invoke<Settings>("get_settings");
}

export async function saveSettings(next: Settings): Promise<void> {
  return invoke<void>("save_settings", { next });
}

/**
 * Why the stored settings could not be read, or `""`.
 *
 * Non-empty means the screen is showing defaults while the real file is still on disk untouched.
 * Saying nothing here would let a user conclude the app forgot their configuration and re-enter it
 * on top of a file that was never the problem.
 */
export async function fetchSettingsFault(): Promise<string> {
  return invoke<string>("settings_fault");
}

/* --------------------------------------------------------------------------------- connection */

export type Transport = "unknown" | "offline" | "wifi" | "cellular" | "ethernet" | "other";

/** Mirrors `android::network::NetworkState`. */
export interface NetworkState {
  /** false on desktop — the whole subject is hidden rather than shown as a control that decides nothing. */
  supported: boolean;
  transport: Transport;
  metered: boolean;
  vpn: boolean;
  error: string;
}

export const EMPTY_NETWORK_STATE: NetworkState = {
  supported: false,
  transport: "unknown",
  metered: false,
  vpn: false,
  error: "",
};

export const TRANSPORT_LABEL: Record<Transport, string> = {
  unknown: "Unknown",
  offline: "Offline",
  wifi: "Wi-Fi",
  cellular: "Mobile data",
  ethernet: "Ethernet",
  other: "Other",
};

export async function fetchNetworkState(): Promise<NetworkState> {
  return invoke<NetworkState>("network_state");
}

/**
 * Why transfers are paused, in words, or `""`.
 *
 * A node that quietly stops seeding on mobile data looks exactly like a node that crashed. This is
 * the difference.
 */
export async function fetchPauseReason(): Promise<string> {
  return invoke<string>("pause_reason");
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


/* ------------------------------------------------------------------------------ the mobile peer */

// One tracker and how the last exchange with it went (mirrors `peer::TrackerStatus`).
export type TrackerStatus = {
  endpoint: string;
  reachable: boolean;
  accepted: boolean;
  next_announce_seconds: number;
  latency_ms: number | null;
  error: string;
};

// What this device's own peer is doing (mirrors `peer::PeerStatus`).
export type PeerStatus = {
  node_id: string;
  public_key: string;
  trackers: TrackerStatus[];
  announced: boolean;
  last_announce_ms: number;
  known_peers: number;
};

// The announce round trip that proves this device is on the network (mirrors `peer::tracker::SelfTest`).
export type PeerSelfTest = {
  passed: boolean;
  trackers: TrackerStatus[];
  peers: string[];
  found_self: boolean;
  error: string;
};

export const EMPTY_PEER: PeerStatus = {
  node_id: "",
  public_key: "",
  trackers: [],
  announced: false,
  last_announce_ms: 0,
  known_peers: 0,
};

// Announce to every configured tracker and report each one's answer.
export async function fetchPeerStatus(): Promise<PeerStatus> {
  return invoke<PeerStatus>("peer_status");
}

// Announce, then query the tracker back and look for THIS device's own entry. An accepted announce
// alone would only say the tracker took the bytes.
export async function runPeerSelfTest(): Promise<PeerSelfTest> {
  return invoke<PeerSelfTest>("peer_self_test");
}

// Whether this build runs its own peer instead of supervising a Java worker. Compiled in, so it
// answers "is this the Android build", not "is the window narrow".
export async function fetchIsMobileBuild(): Promise<boolean> {
  return invoke<boolean>("is_mobile_build");
}

// The peer loop's own announcements, pushed as each round completes.
export function onPeerStatus(cb: (s: PeerStatus) => void): Promise<UnlistenFn> {
  return listen<PeerStatus>("nodera://peer", (event) => cb(event.payload));
}

/* ------------------------------------------------------------------------- storage & first run */

// One place world data could live (mirrors `api::storage::StorageOption`).
export type StorageOption = {
  path: string;
  label: string;
  detail: string;
  free_bytes: number | null;
  writable: boolean;
};

// Where world data is kept, and what else this device offers.
export type StorageInfo = {
  current: string;
  is_default: boolean;
  options: StorageOption[];
};

export async function fetchStorageInfo(): Promise<StorageInfo> {
  return invoke<StorageInfo>("storage_info");
}

// Whether the first-run questions have been answered.
export type SetupState = { completed: boolean };

export async function fetchSetupState(): Promise<SetupState> {
  return invoke<SetupState>("setup_state");
}

// Record the answers. Telemetry consent is set separately, through the worker, because that is
// where consent lives — this call only stores the storage choice and closes the flow.
export async function completeSetup(worldsDir: string): Promise<void> {
  return invoke("complete_setup", { worldsDir });
}

/* ---------------------------------------------------------------------------------- battery */

// Whether the OS may stop this node in the background (mirrors `android::battery::BatteryPolicy`).
export type BatteryPolicy = {
  supported: boolean;
  restricted: boolean;
  manufacturer: string;
  help_url: string;
  error: string;
};

export async function fetchBatteryPolicy(): Promise<BatteryPolicy> {
  return invoke<BatteryPolicy>("battery_policy");
}

// Open the system screen where the exemption is granted. The app never grants it itself.
export async function openBatterySettings(): Promise<void> {
  return invoke("open_battery_settings");
}

// Open this vendor's page on dontkillmyapp.com.
export async function openBatteryHelp(): Promise<void> {
  return invoke("open_battery_help");
}

// What the Android system folder picker came back with (mirrors `api::storage::PickedFolder`).
export type PickedFolder = {
  pending: boolean;
  uri: string;
  label: string;
  path: string;
  writable: boolean;
  error: string;
};

// Open the system folder picker. Returns once it is on screen; poll `pickedFolder` for the answer.
export async function pickStorageFolder(): Promise<void> {
  return invoke("pick_storage_folder");
}

export async function pickedFolder(): Promise<PickedFolder> {
  return invoke<PickedFolder>("picked_folder");
}

/* ------------------------------------------------------------------ tracker stores */

/** One service in a store's index (mirrors `stores::ServiceEntry`). */
export interface StoreService {
  kind: "tracker" | "rendezvous";
  name: string;
  endpoints: string[];
  node_id?: string;
  operator: string;
  region: string;
  notes: string;
}

/** A store this install trusts (mirrors `stores::TrackerStore`). */
export interface TrackerStore {
  url: string;
  name: string;
  description: string;
  homepage: string;
  services: StoreService[];
  last_refreshed_epoch_millis: number;
  /** Why the last refresh failed. The services above are the last ones that worked. */
  last_error: string;
  /** The store the app shipped with. Deletable like any other. */
  built_in: boolean;
}

export const trackerStores = (): Promise<TrackerStore[]> => invoke("get_tracker_stores");

/**
 * A store URL that arrived by `nodera://tracker-store?url=…` and is waiting to be confirmed.
 *
 * Reading it clears it: the dialog owns it from here, and a link that is offered twice because two
 * components polled would be two dialogs for one intent.
 */
export const pendingTrackerStore = (): Promise<string | null> =>
  invoke("take_pending_tracker_store");

/** Fetch, validate and remember a store. Only ever called after the user has confirmed. */
export const addTrackerStore = (url: string): Promise<TrackerStore> =>
  invoke("add_tracker_store", { url });

export const removeTrackerStore = (url: string): Promise<void> =>
  invoke("remove_tracker_store", { url });

/** Re-read every store. One that fails keeps what it last said, with the error beside it. */
export const refreshTrackerStores = (): Promise<TrackerStore[]> =>
  invoke("refresh_tracker_stores");
