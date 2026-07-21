// Task 32/33: the bridge from the Rust backend to the React dashboard. The backend emits a
// `nodera://metrics` event each second and answers a `get_metrics` command on demand. The shapes
// mirror `rust/nodera-app/src/metrics.rs`, which in turn mirror the worker's NODERA-STATE JSON.
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";

export interface PeerRow {
  node_id: string;
  route: string;
  path: string;
  up_bytes_per_sec: number;
  down_bytes_per_sec: number;
}

export interface WorldRow {
  world_id: string;
  name: string;
  players: number;
}

export interface EndpointHealth {
  host: string;
  port: number;
  reachable: boolean;
}

export interface Metrics {
  node_id: string;
  worker_version: string;
  uptime_seconds: number;
  is_gateway: boolean;
  self_route: string;
  roles: string[];
  maintained_pieces: number;
  maintained_bytes: number;
  total_sent_bytes: number;
  total_received_bytes: number;
  peers: PeerRow[];
  connected_worlds: WorldRow[];
  trackers: EndpointHealth[];
  rendezvous: EndpointHealth[];
  daemon_up: boolean;
}

export const EMPTY_METRICS: Metrics = {
  node_id: "",
  worker_version: "",
  uptime_seconds: 0,
  is_gateway: false,
  self_route: "",
  roles: [],
  maintained_pieces: 0,
  maintained_bytes: 0,
  total_sent_bytes: 0,
  total_received_bytes: 0,
  peers: [],
  connected_worlds: [],
  trackers: [],
  rendezvous: [],
  daemon_up: false,
};

export async function fetchMetrics(): Promise<Metrics> {
  return invoke<Metrics>("get_metrics");
}

export function onMetrics(cb: (m: Metrics) => void): Promise<UnlistenFn> {
  return listen<Metrics>("nodera://metrics", (event) => cb(event.payload));
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
