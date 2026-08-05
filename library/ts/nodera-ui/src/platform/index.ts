// The seam as callers see it.
//
// Two shapes are exported deliberately, and they are not redundant:
//
//   * `platform` — the interface. `supports`/`reason` are on it because asking is a decision a
//     screen makes, and a free function called `supports()` would read as a fact about the world
//     rather than a question put to a host.
//   * `invoke` / `listen` — free functions with the shape the desktop already calls. Adopting the
//     seam was six import lines and no other edit in `api.ts`, `ipc.ts` and `play.ts`, which is the
//     only reason the launcher redesign happening in a sibling worktree does not have to be
//     rebased onto this one.
//
// `listen` hands the callback an object rather than the payload directly, because 55 existing call
// sites are written `(event) => cb(event.payload)`. The object carries the payload and nothing
// else — see `EventEnvelope` for why it does not invent the rest of Tauri's event shape.
import { platform } from "./impl";
import type { EventEnvelope, UnlistenFn } from "./types";

export * from "./types";
export { platform };

/** Call a command on the host. Rejects with `Unsupported` where there is no host to call. */
export function invoke<T>(command: string, args?: Record<string, unknown>): Promise<T> {
  return platform.invoke<T>(command, args);
}

/** Subscribe to a host event. On a host that pushes nothing, this resolves and stays silent. */
export function listen<T>(
  event: string,
  handler: (message: EventEnvelope<T>) => void,
): Promise<UnlistenFn> {
  return platform.listen<T>(event, (payload) => handler({ payload }));
}
