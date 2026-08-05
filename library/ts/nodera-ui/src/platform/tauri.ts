// The Tauri host. **The only file in this repository that imports `@tauri-apps`.**
//
// That is the whole point of the seam, and it is asserted rather than intended:
// `library/ts/nodera-ui/tests/platform-seam.test.mjs` fails if a second importer appears, and
// `web/tests/no-tauri-in-the-browser-bundle.test.mjs` fails if the string reaches the site's build
// output. The website never imports this module — `impl.ts` is aliased away before the bundler ever
// resolves it — so `@tauri-apps/api` is not in the site's graph at all, rather than in it and
// unreachable.
import { invoke as tauriInvoke } from "@tauri-apps/api/core";
import { listen as tauriListen } from "@tauri-apps/api/event";
import type { Capability, Platform, UnlistenFn } from "./types";

export const platform: Platform = {
  id: "tauri",

  // The desktop is the host every capability was named for. It answers yes to all four, and the
  // reason string is empty because there is nothing to explain.
  supports(_capability: Capability): boolean {
    return true;
  },

  reason(_capability: Capability): string {
    return "";
  },

  invoke<T>(command: string, args?: Record<string, unknown>): Promise<T> {
    return tauriInvoke<T>(command, args);
  },

  listen<T>(event: string, cb: (payload: T) => void): Promise<UnlistenFn> {
    return tauriListen<T>(event, (message) => cb(message.payload));
  },
};
