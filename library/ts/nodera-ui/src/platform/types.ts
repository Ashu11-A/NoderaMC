// What a host can do, and what it says when it cannot.
//
// Two frontends are built from this repository: the desktop launcher, which runs inside a Tauri
// webview and can reach a node on this machine, and the website, which runs in a browser and cannot
// reach anything. Before this seam existed the difference was expressed by importing
// `@tauri-apps/api` and hoping — which works exactly until the same module is bundled for the web,
// where `invoke` throws an error about a missing internal global and the screen shows nothing at
// all.
//
// So the difference is named. A capability is asked about before it is used, the host answers in its
// own words when the answer is no, and those words are what the interface renders. That is the same
// rule the dashboard already follows for data: **a value nobody has reported is not zero**, and a
// capability nobody has is not a silent failure.

/** Which host this bundle was built for. Decided at build time by a Vite alias, never probed. */
export type PlatformId = "tauri" | "browser";

/**
 * The four things a host either can or cannot do.
 *
 * Deliberately short. A capability with no method behind it is decoration, and every entry here is
 * exercised by one of the two methods below — `worker-link`, `local-files` and `process-control` by
 * `invoke`, `push-events` by `listen`.
 */
export type Capability = "worker-link" | "local-files" | "process-control" | "push-events";

/** Cancels a subscription. Same shape as Tauri's own, so the desktop call sites are unchanged. */
export type UnlistenFn = () => void;

/**
 * What a subscriber receives.
 *
 * It carries the payload and nothing else. Tauri's own event object also has an `event` name and a
 * numeric `id`, and this deliberately does not fabricate either: an envelope invented by the seam
 * would be a value nobody reported, dressed as one that was.
 */
export interface EventEnvelope<T> {
  readonly payload: T;
}

/** Refusal, with the host's reason attached rather than a generic failure. */
export class Unsupported extends Error {
  constructor(
    readonly platform: PlatformId,
    readonly capability: Capability,
    reason: string,
  ) {
    super(reason);
    this.name = "Unsupported";
  }
}

export interface Platform {
  readonly id: PlatformId;
  /** Never throws. A screen asks this before it offers a control. */
  supports(capability: Capability): boolean;
  /** The host's own words for why not; the empty string when it is supported. */
  reason(capability: Capability): string;
  invoke<T>(command: string, args?: Record<string, unknown>): Promise<T>;
  listen<T>(event: string, cb: (payload: T) => void): Promise<UnlistenFn>;
}
