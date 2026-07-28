// The commands behind "play with other people".
//
// Everything here is a round trip rather than pushed state, and each is a thing a person pressed:
// answering the LAN prompt, browsing what is joinable, opening a tunnel, minting an invitation,
// installing the mod. The dashboard stream carries what the node *is*; these carry what to *do*.
//
// One phrase to keep straight in every string shown to a user: joining a world here does **not**
// download it. The worker opens a tunnel to whoever is hosting and binds a port on this machine;
// the player's own Minecraft then connects to that port. No save is copied, ever.
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";

/** Mirrors `api::network::Outcome` — the worker's own words when something did not work. */
export interface Outcome {
  ok: boolean;
  error: string;
  /** The loopback address for a join, the URI for a share link, empty otherwise. */
  value: string;
}

/** Mirrors `api::model::LanSession`. */
export interface LanSession {
  session_id: string;
  name: string;
  port: number;
  /** "offered" — waiting on an answer. "shared" — on the network. "declined" — answered no. */
  state: string;
  detected_at: number;
}

/** Mirrors `api::model::Lan`. */
export interface Lan {
  /** false ⇒ this worker is not watching; `reason` says why. Never show a bare empty list for it. */
  supported: boolean;
  /** The worker's own words for why it is not watching. Empty when it is. */
  reason: string;
  sessions: LanSession[];
}

/** Mirrors `api::network::DirectoryEntry`. */
export interface DirectoryEntry {
  session_id: string;
  name: string;
  /** Live announcing **peers** for the world, as the tracker counts them — never players. */
  peers: number;
  pieces: number;
  health: string;
  mine: boolean;
}

/* ---------------------------------------------------------------------------- worker events */

/**
 * Something that happened on the node, announced by the worker.
 *
 * Mirrors `api::events::WorkerEvent`. This is the counterpart to the dashboard stream: that carries
 * what is **true** of the node, this carries what **happened** to it. A prompt needs the second —
 * "a world was just opened to LAN" is a moment, and a UI that had to notice it by diffing snapshots
 * would need to have been connected at the instant the player pressed the button.
 */
export interface WorkerEvent {
  /** Monotonic position in the node's event sequence. */
  seq: number;
  /** Dotted name: "lan.opened" | "lan.shared" | "lan.declined" | "lan.stopped" | "lan.closed". */
  event: string;
  at: number;
  /** Flat detail — for the LAN family: session, world, port, state. */
  attributes: Record<string, string>;
}

/**
 * Subscribe to what the worker announces.
 *
 * The backend replays from a sequence number, so an app started *after* a world was opened still
 * receives the event. Keepalives are consumed in Rust and never arrive here.
 */
export function onWorkerEvent(cb: (event: WorkerEvent) => void): Promise<UnlistenFn> {
  return listen<WorkerEvent>("nodera://event", (message) => cb(message.payload));
}

export type LanAction = "SHARE" | "DECLINE" | "STOP";

/**
 * Answer the LAN prompt.
 *
 * `DECLINE` is not `STOP`: declining records an answer so the modal stops asking, stopping returns a
 * shared world to merely offered. Both keep the world listed so it can be shared later — which is
 * the only way "not now" means anything.
 */
export async function lanAction(action: LanAction, port: number): Promise<Outcome> {
  return invoke<Outcome>("lan_action", { action, port });
}

/** What is joinable right now. An unreachable tracker yields an empty list, not an error. */
export async function browseNetwork(limit = 50): Promise<DirectoryEntry[]> {
  return invoke<DirectoryEntry[]>("browse_network", { limit });
}

/** Join a world. Resolves to the `127.0.0.1:<port>` to type into Minecraft's Direct Connect. */
export async function joinWorld(sessionId: string): Promise<Outcome> {
  return invoke<Outcome>("join_world", { sessionId });
}

/** Close the local port opened by {@link joinWorld}. */
export async function leaveWorld(sessionId: string): Promise<Outcome> {
  return invoke<Outcome>("leave_world", { sessionId });
}

/** Mint the pasteable invitation for a world. */
export async function worldShareLink(worldId: string): Promise<Outcome> {
  return invoke<Outcome>("world_share_link", { worldId });
}

/** Save an invitation as a `.nodera` file; resolves to where it was written. */
export async function saveShareFile(name: string, uri: string): Promise<string> {
  return invoke<string>("save_share_file", { name, uri });
}

/** Read an invitation somebody sent as a file; resolves to its URI. */
export async function openShareFile(path: string): Promise<string> {
  return invoke<string>("open_share_file", { path });
}

/** Validate a pasted link locally; resolves to the world id it names. */
export async function parseShareLink(uri: string): Promise<string> {
  return invoke<string>("parse_share_link", { uri });
}

/* ------------------------------------------------------------------------------- mod installer */

/** Mirrors `api::modinstall::MinecraftInstall`. */
export interface MinecraftInstall {
  path: string;
  mods_path: string;
  has_mods_folder: boolean;
  installed_jar: string;
  up_to_date: boolean;
}

/** Mirrors `api::modinstall::ModInstallStatus`. */
export interface ModInstallStatus {
  bundled_version: string;
  /** false ⇒ this build of the app carries no mod jar; the buttons must say so, not fail on press. */
  bundled_available: boolean;
  installs: MinecraftInstall[];
}

export async function modInstallStatus(): Promise<ModInstallStatus> {
  return invoke<ModInstallStatus>("mod_install_status");
}

/** Copy the bundled mod jar into one installation; resolves to the file written. */
export async function installMod(gameDir: string): Promise<string> {
  return invoke<string>("install_mod", { gameDir });
}

/** Remove the Nodera jar from one installation; resolves to the file removed. */
export async function uninstallMod(gameDir: string): Promise<string> {
  return invoke<string>("uninstall_mod", { gameDir });
}

/* -------------------------------------------------------------------------------------- about */

/** One third-party package this project depends on directly. Mirrors `api::about::Package`. */
export interface Package {
  name: string;
  version: string;
  /** SPDX expression or the licence's own name. **Empty means it could not be read** — say so. */
  licence: string;
  /** "rust" | "npm" | "java". */
  ecosystem: string;
}

/** Mirrors `api::about::About`. Everything here describes the running binary. */
export interface AboutBuild {
  name: string;
  version: string;
  description: string;
  licence: string;
  repository: string;
  protocol_version: number;
  config_dir: string;
  share_dir: string;
  /** When the dependency list was generated by scripts/collect-licenses.py. */
  licences_generated_at: string;
  packages: Package[];
}

/** What this build is and what it is built out of. Embedded at compile time, not fetched. */
export async function aboutBuild(): Promise<AboutBuild> {
  return invoke<AboutBuild>("about_build");
}
