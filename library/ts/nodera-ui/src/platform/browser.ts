// The browser host: honest refusals, and no Tauri import anywhere in the file.
//
// The temptation here is a stub that resolves an empty dashboard so a page renders. That is the one
// thing this file must not do. An empty dashboard is a screen claiming *zero peers*, and this
// repository has an explicit rule against it — a value nobody reported is not zero. A page that
// cannot reach a node has nothing to say about one, so `invoke` rejects with the reason and the
// caller renders the reason.
import { Unsupported } from "./types";
import type { Capability, Platform, UnlistenFn } from "./types";

/**
 * Why each capability is missing, in words a visitor can act on.
 *
 * Not "unsupported on this platform": that tells somebody reading a web page that they did
 * something wrong. Each of these says what the page is instead, which is the only useful thing a
 * refusal can carry.
 */
const REASONS: Record<Capability, string> = {
  "worker-link": "A node runs on your own machine. This page is not the app and cannot reach it.",
  "local-files": "A web page cannot write to your disk.",
  "process-control": "Only the app can start or stop the node.",
  "push-events": "There is nothing here for the node to push to.",
};

export const platform: Platform = {
  id: "browser",

  supports(_capability: Capability): boolean {
    return false;
  },

  reason(capability: Capability): string {
    return REASONS[capability];
  },

  invoke<T>(command: string, _args?: Record<string, unknown>): Promise<T> {
    // Every command in this app is a request to a node, so the capability is always the link. The
    // command name is carried into the message because "the app cannot reach a node" with no
    // subject is a sentence a developer cannot debug.
    return Promise.reject(
      new Unsupported("browser", "worker-link", `${REASONS["worker-link"]} (${command})`),
    );
  },

  listen<T>(_event: string, _cb: (payload: T) => void): Promise<UnlistenFn> {
    // Resolves, and delivers nothing, ever. Rejecting here would make every screen that merely
    // *subscribes* throw on mount, which turns "this page has no live data" into "this page is
    // broken". The subscriber simply never hears anything, which is the truth.
    return Promise.resolve(() => {});
  },
};
