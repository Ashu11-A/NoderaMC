// The non-dashboard half of the bridge to the Rust backend: settings, telemetry consent, worker
// ownership, resource sampling, logs, and the on-demand piece map.
//
// The dashboard itself moved to `api.ts`, which mirrors `src/api/model.rs` and lives on the pushed
// `nodera://dashboard` event. Anything about "what is the node doing right now" belongs there; what
// is left here is configuration and machine-local facts, which are asked for rather than announced.
import { invoke } from "nodera-ui";
import { listen, type UnlistenFn } from "nodera-ui";

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
  themes: Themes;
}

/**
 * Custom appearances authored on this computer, and which one is in force.
 *
 * One field on the settings document rather than several: `Settings::keys()` derives the key set
 * from the serialised document and every key must be covered by exactly one `ENFORCEMENT` row, so
 * the whole feature rides on a single key, `appearance.themes`.
 */
export interface Themes {
  /** The custom theme in force, by id. Empty means none. */
  selected: string;
  custom: CustomTheme[];
}

/** A patch on a base scheme, never a replacement. Tokens it does not set fall through. */
export interface CustomTheme {
  /** Minted by the app, never user-supplied — it becomes a selector fragment. */
  id: string;
  name: string;
  /** The built-in scheme this theme sits on top of. `system` cannot be patched. */
  base: "dark" | "light";
  tokens: Record<string, string>;
  css: string;
  updated_at: number;
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

/**
 * Mirrors `TelemetryStatus`. `consent` and everything under it comes from the worker; `asked`,
 * `answer` and `pending` are what this installation recorded.
 *
 * The two halves are deliberately separate. `consent` is the state of the NODE and is the only
 * thing a badge may claim; `answer` is what the person tapped, which exists even when no worker was
 * listening at the time. They differ exactly while `pending` is true.
 */
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
  /** The answer this installation recorded, or null if the question was never answered. */
  answer: boolean | null;
  /** true ⇒ an answer is recorded here and no worker has accepted it yet. */
  pending: boolean;
  /** Why the last delivery attempt failed; empty when none did. */
  delivery_error: string;
}

export const EMPTY_TELEMETRY: TelemetryStatus = {
  consent: "unanswered",
  supported: false,
  endpoint: "",
  queued: 0,
  sent: 0,
  last_error: "",
  asked: false,
  answer: null,
  pending: false,
  delivery_error: "",
};

export async function fetchTelemetryStatus(): Promise<TelemetryStatus> {
  return invoke<TelemetryStatus>("get_telemetry_status");
}

/**
 * Record the person's answer.
 *
 * Stored on this installation first and handed to the node second, so an answer given while the
 * worker is still starting is delivered later rather than refused. Does not reject when the node is
 * unreachable: the returned status carries `pending: true` instead, and the caller shows that as a
 * note rather than as a failure. The badge still follows `consent`, which is the node's own word.
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

/**
 * Who owns the worker process, and whether anything is waiting on a restart.
 *
 * Everything needed to draw the Restart affordance, answered by the one side that can. Do not
 * re-derive {@link WorkerOwnership.unavailable_reason} from `attached` here: the backend's sentence
 * is the one the command itself would give, and a second copy is a second thing to keep in step.
 */
export interface WorkerOwnership {
  /** The worker was started outside this app (attach mode, e.g. scripts/dev.sh). */
  attached: boolean;
  /** Only true when this app spawned the worker and may therefore stop it. */
  can_restart: boolean;
  /**
   * When `can_restart` is false, what the user should do instead — render it verbatim. Empty when a
   * restart is available. Never hide the control without showing this: a control that vanishes with
   * no explanation reads exactly like a control that does nothing.
   */
  unavailable_reason: string;
  /**
   * Dotted settings keys the **running** worker is out of date on. Empty means nothing is waiting.
   *
   * Not to be confused with {@link ConfigStatus.restart_required}, which is the worker naming every
   * bind-time key it was pushed, whether or not the value moved — a description of *scope*, true
   * from first launch to uninstall. This one is a description of *difference*, computed by the app
   * against the environment it actually spawned the running worker with.
   */
  pending_restart_keys: string[];
  /**
   * False when the app cannot know what the running worker was started with — attach mode, or the
   * in-process worker on Android. `pending_restart_keys` is then not evidence of anything, and an
   * empty list must NOT be rendered as "nothing pending" (A-UX-1).
   */
  pending_known: boolean;
}

export async function fetchWorkerOwnership(): Promise<WorkerOwnership> {
  return invoke<WorkerOwnership>("get_worker_ownership");
}

/**
 * Restart the worker so env-shaped settings (port range, rendezvous relays) apply.
 *
 * Rejects with a message when the worker was started outside the app — the app must not kill a
 * process it did not start; show {@link WorkerOwnership.unavailable_reason} rather than gating the
 * control into invisibility.
 *
 * Rarely the only way those settings apply: the app watches for a bind-time change of its own and
 * restarts the worker a couple of seconds after the edit settles. This is the manual path, for a
 * worker that is wedged, or one whose settings have not changed at all.
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

// There is no `nodera://peer` subscription here any more. Nothing in Rust ever emitted that event,
// so it was a listener that could not fire — and a permanently silent subscription is
// indistinguishable from a peer that never reports. The peer screen reads `peer_status`, which is
// a round trip that actually happens.

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

/** A store index as published, before anything has decided to keep it (mirrors `stores::ServiceIndex`). */
export interface StoreIndex {
  schema_version: number;
  name: string;
  description: string;
  homepage: string;
  updated: string;
  services: StoreService[];
}

/** Where the project publishes its own list. Read from the app so the UI never spells a URL. */
export const officialStoreUrl = (): Promise<string> => invoke("official_store_url");

/** One endpoint in the effective list, with the store that contributed it (empty if typed). */
export interface ResolvedEndpoint {
  endpoint: string;
  store: string;
}

/**
 * The endpoints this node will actually dial — what the user typed **plus** what the stores add.
 *
 * Not the same thing as `settings.network.default_trackers`, and that difference was a bug for as
 * long as the screens rendered the setting: an install whose only tracker came from the built-in
 * store showed "0 tracker(s)" in Settings while the store screen listed it. Anything that reports a
 * count of trackers or relays to a person must read this.
 */
export const resolvedServices = (): Promise<Record<"trackers" | "rendezvous", ResolvedEndpoint[]>> =>
  invoke("resolved_services");

/**
 * Fetch and validate an index **without** keeping it.
 *
 * What turns adding a store from a leap into a decision: the screen shows what was actually served
 * — the publisher's own name for the list, and every service in it — before anything is trusted.
 * Nothing is persisted, so a preview the user walks away from leaves no trace.
 */
export const previewTrackerStore = (url: string): Promise<StoreIndex> =>
  invoke("preview_tracker_store", { url });

/** Fetch, validate and remember a store. Only ever called after the user has confirmed. */
export const addTrackerStore = (url: string): Promise<TrackerStore> =>
  invoke("add_tracker_store", { url });

export const removeTrackerStore = (url: string): Promise<void> =>
  invoke("remove_tracker_store", { url });

/** Re-read one store. For retrying the row that is failing without relabelling every other row. */
export const refreshTrackerStore = (url: string): Promise<TrackerStore> =>
  invoke("refresh_tracker_store", { url });

/** Re-read every store. One that fails keeps what it last said, with the error beside it. */
export const refreshTrackerStores = (): Promise<TrackerStore[]> =>
  invoke("refresh_tracker_stores");

/* --------------------------------------------------------------------------------------- links */

/**
 * Open a web link outside this window.
 *
 * **Every outbound link goes through here.** In a Tauri v2 webview `<a target="_blank">` opens
 * nothing — there is no tab for it to open in — so an anchor is at best decorative, and an anchor
 * that *did* navigate would be worse: this window would follow it, replacing the interface with
 * somebody's web page and no way back.
 *
 * The host validates the URL before the platform sees it (`http`/`https` only). That is not
 * ceremony: a tracker store's `homepage` is written by whoever published the index, and trusting a
 * publisher to list trackers is not trusting them to hand this device a `file://` or an `intent://`.
 *
 * Resolves to how it opened — `browser` on the desktop, `custom-tab` / `browser` / `webview` on
 * Android — and rejects when nothing on the device could. Callers should offer the URL to copy on
 * rejection; `useExternalLink` does that for you.
 */
export const openExternal = (url: string): Promise<string> => invoke("open_external", { url });
