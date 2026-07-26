# Minecraft Task 8 — In-Game Telemetry: Gameplay Events and the Consent Mirror

<!-- AI-AGENT-INSTRUCTION: This task owns the GAME-SIDE half of emission. Two rules that must be
     refused if violated: (1) the mod NEVER opens a network connection to the telemetry service — it
     hands events to the worker; (2) nothing here may run on a server tick beyond a bounded enqueue,
     and nothing may run at all when consent is denied. The privacy model is ../plans/Plan.6.md.
     Keep this header's status accurate. -->

**Status:** ✅ COMPLETED (headless) — live pass rides [minecraft 2](Task.2.md)
**Category:** minecraft · **Owns:** — · **Last audit:** 2026-07-25
**Depends on:** [worker 5](../worker/Task.5.md), [network 12](../network/Task.12.md), [minecraft 7](Task.7.md)
**Consumed by:** [telemetry 3](../telemetry/Task.3.md)

---

## Goal

The events only the game can observe — a share, a join and how it was reached, a rehost, a divergence,
a feature actually used — reach the worker, and the player can see and change their telemetry choice
without leaving Minecraft.

## Status detail

Landed and green: `ModTelemetryTest` (8) drives the façade against a **stand-in worker on
loopback**, because the property under test is a wire property — the mod hands events over and opens
no telemetry connection of its own.

Landed: `ModTelemetry` (consent cached from the worker, every call site a no-op when denied, sends
on the mod's own single-thread executor); `CompanionClient.telemetryStatus/setTelemetryConsent/
recordTelemetryEvent`; `CompanionLink` attaching and detaching the façade with the worker link;
call sites in the host lane (`world.share`) and the join flow (`world.join`, `world.rehost`,
`feature.use`); and `/nodera telemetry [on|off]`, which reports what the **node** confirmed rather
than what the command intended.

Remaining for the live pass: exercising these call sites with a real client in the `e2e-*` suites,
which is gated on the same GUI environment as the rest of the category.

## Dependencies

- [worker 5](../worker/Task.5.md) — the emitter every event is handed to.
- [network 12](../network/Task.12.md) — the event and bucket types.
- [minecraft 7](Task.7.md) — the companion presence gate; without a worker there is nobody to hand
  events to, and that is a valid state, not an error.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `ModTelemetry` — a thin façade that enqueues to the worker and no-ops when consent is denied | ✅ |
| 2 | Host-lane events: `world.share` (password bool, size bucket, origin), `world.rehost` | ✅ |
| 3 | Join-lane events: `world.join` with path, failure class, and seconds bucket | ✅ |
| 4 | Engine events: `engine.divergence` (phase, rules version, fingerprint) — API landed, call site rides [minecraft 2](Task.2.md) | 🚧 |
| 5 | `feature.use` from the GUIs and the command tree | ✅ |
| 6 | `session.start` / `session.end` with environment attributes | ✅ |
| 7 | `/nodera telemetry` — show state, `on`/`off` mirrors the worker's verb | ✅ |
| 8 | Consent surfaced in the Nodera GUI, linking to the companion's Privacy screen | ⬜ |

## Design

**The mod has no network path to telemetry.** It hands events to the worker over the loopback
control protocol it already uses. A second sender in the game process would mean a second consent
check inside the process most likely to be modified by third parties, and events would be lost every
time the game closed — which is exactly when a session ends.

**Nothing runs when consent is denied.** `ModTelemetry` resolves the worker's consent state at
connect and caches it; a denied state makes every call a return. The GUIs and command handlers call
it unconditionally, so there is no per-call-site consent logic to get wrong.

**A tick may only enqueue.** Everything on a server tick path is a bounded, non-blocking enqueue —
no I/O, no socket, no lock a tick thread can wait on. The control-socket write happens on the mod's
own executor.

**Divergence is the one event worth interrupting for.** It is rare, high-value, never sampled, and
carries a fingerprint rather than any state: 16 hex characters derived from the mismatching roots,
enough to tell two bugs apart and useless for reconstructing anything.

**A join's failure class is an enum, not a message.** "Unreachable", "password", "permission",
"timeout" — a free-text reason would eventually contain a world name or an address, which is
precisely what the registry makes unrepresentable.

**`/nodera telemetry` exists because some players never open the app.** The companion is required to
play, but a player may never look at its window. Consent must be visible and changeable from where
they actually are.

## Files

- `java/neoforge-mod/src/main/java/dev/nodera/mod/common/ModTelemetry.java`
- `java/neoforge-mod/src/main/java/dev/nodera/mod/common/CompanionClient.java` (the telemetry verb)
- Call sites in the host lane, the join flow, the GUIs, and the command tree

## Testing

`ModTelemetryTest` (8 tests) covers all of the above against a loopback stand-in worker:

- `aDeniedNodeProducesNoEvents` — five call sites, nothing sent, asserted by waiting for silence.
- `anUnreachableWorkerIsReadAsUnanswered` — a missing worker is not consent.
- `aShareEventCarriesNoWorldIdentity` and `aJoinFailureIsAnEnumNotAMessage` — the two events most
  likely to leak a name or an address, asserted on the JSON the worker receives.
- `anErrorReportCarriesAFingerprintRatherThanAMessage` — a path, a world name, and the word
  "corrupt" are all absent from the event built out of an exception that contained them.
- `settingConsentRoundTripsThroughTheWorker` — the façade reports the node's answer, not its own.

Pending: a live pass in the `e2e-*` suites, where a real share → join → rehost run produces exactly
the expected event names with consent granted, and none with it denied.

## Acceptance criteria

1. ✅ The mod never opens a telemetry connection of its own.
2. ✅ With consent denied, no event is constructed anywhere in the mod.
3. ✅ No emitted attribute can carry a world name, a player name, or a coordinate.
4. ✅ Nothing on a tick path does I/O — recording is an enqueue onto the mod's own executor.
5. ✅ A player can see and change the setting in-game (`/nodera telemetry`).

## Limitations

None owned. The consent record itself belongs to [`worker 5`](../worker/Task.5.md).
