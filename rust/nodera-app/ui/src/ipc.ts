// Task 32: the bridge from the Rust backend to the React dashboard. The backend emits a
// `nodera://metrics` event each second and answers a `get_metrics` command on demand.
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";

export interface PeerRow {
  node_id: string;
  route: string;
  path: string;
  up_bytes_per_sec: number;
  down_bytes_per_sec: number;
}

export interface Metrics {
  maintained_pieces: number;
  maintained_bytes: number;
  total_sent_bytes: number;
  total_received_bytes: number;
  peers: PeerRow[];
  connected_worlds: string[];
  daemon_up: boolean;
}

export async function fetchMetrics(): Promise<Metrics> {
  return invoke<Metrics>("get_metrics");
}

export function onMetrics(cb: (m: Metrics) => void): Promise<UnlistenFn> {
  return listen<Metrics>("nodera://metrics", (event) => cb(event.payload));
}

export function formatGb(bytes: number): string {
  return (bytes / 1024 / 1024 / 1024).toFixed(2) + " GB";
}

export function formatRate(bytesPerSec: number): string {
  if (bytesPerSec < 1024) return `${bytesPerSec} B/s`;
  if (bytesPerSec < 1024 * 1024) return `${(bytesPerSec / 1024).toFixed(1)} KB/s`;
  return `${(bytesPerSec / 1024 / 1024).toFixed(1)} MB/s`;
}
