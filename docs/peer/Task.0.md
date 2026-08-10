# Peer — Category Charter

<!-- AI-AGENT-INSTRUCTION: Option B is LOCKED: the worker is the existing, tested Java peer run
     headlessly. Do NOT port peer logic to Rust and do NOT create a second region engine in any
     language — the single-engine determinism rule forbids it. The control channel is loopback-only,
     versioned, and NON-AUTHORITATIVE: requiring the worker is a persistence and reachability
     convenience, never a new trust anchor. Keep the task index in agreement with ../ROADMAP.md §2. -->

**Category:** `peer` · **Status:** 🚧 IN PROGRESS (6 of 8 tasks completed) ·
**Last audit:** 2026-07-30

---

## 1. What this category is

**The node.** A Minecraft-free process that boots the full Java `PeerRuntime` — persistent identity,
membership, gateway candidacy, tracker announce, rendezvous registration, distribution seeding, and
out-of-game committee validation — as a long-lived OS process, and serves the **loopback control
endpoint** (`127.0.0.1:25610`) that the mod probes, queries, and delegates hosting to.

> **Renamed from `worker` on 2026-07-30, and the rename is the point.** `dev.nodera.headless` was a
> separate `:worker` Gradle module from 2026-07-26 until then. That kept a launchable `main` out of
> every player's `mods/` folder, which was worth having — but it also left a peer WITHOUT the
> always-on services constructible, so "every peer serves" was a convention rather than a fact.
> Both guarantees now hold at once: the services are in `peer`'s `src/main`, so depending on the
> peer stack means getting them and `PeerNode.start` is the only way to build one; the entry point
> is alone in `src/headless`, which `tasks.jar` does not carry, asserted on the built artefact by
> `ModJarCarriesNoEntryPointTest`. Task numbers 0–8 are unchanged — they are cited by issues and
> commits, and this category never renumbers.
>
> The artefact is still `nodera-headless`; only the Gradle task moved
> (`:worker:installDist` → `:peer:installDist`).

This is what makes player-hosted worlds survive the host closing their game. It is also the project's
answer to the "separate OS process for emergency flush" requirement: the worker is a different
process **by construction**, so a Minecraft crash cannot take the node down.

## 2. Why this exists

Before the worker, the peer lived inside the NeoForge JVM and died when the game closed. Every
property the project promises — a world stays listed, its data stays served, a committee keeps a
quorum, a crash does not lose unshared pieces — was true only while someone had Minecraft open. The
worker decouples **being a Nodera node** from **running Minecraft**.

It also changes what a "player leaving" means: closing the game is now a *player-session* leave, not
a *node* leave.

## 3. Architecture

```
   ┌──────────────┐  probe / STATE / HOST / …   ┌──────────────────────────────┐
   │ NeoForge mod │ ◄────── loopback 25610 ────►│  worker (HeadlessPeerMain)   │
   └──────────────┘                             │  PeerRuntime + persistent id │
   ┌──────────────┐  NODERA-STATE (1 Hz)        │  ControlServer (protocol v2) │
   │  Tauri app   │ ◄───────────────────────────│  WorldArchiveService         │
   └──────────────┘                             │  WorkerValidationService     │
        supervises ────────────────────────────►└──────────────┬───────────────┘
                                                               │ PeerTransport
                                                  tracker · rendezvous · peers
```

The control protocol has **three implementations that must stay in lockstep**: `ControlProtocol`
(Java, the single source of truth), the mod's `CompanionProtocol`, and the app's `control.rs`. Change
all three in one commit, bump the version, and rely on the gate's skew classification.

## 4. Dependencies

**Depends on:** [`network/Task.2.md`](../network/Task.2.md) (it *is* the peer runtime run as a
process), [`network/Task.5.md`](../network/Task.5.md) (persistent identity),
[`tracker/`](../tracker/Task.0.md) and [`rendezvous/`](../rendezvous/Task.0.md) (the services it
dials), [`engine/Task.5.md`](../engine/Task.5.md) (the validation stack for task 4),
[`minecraft/Task.5.md`](../minecraft/Task.5.md) (genesis production for task 3).

**Consumed by:** [`minecraft/Task.7.md`](../minecraft/Task.7.md) (the gate probes it),
[`minecraft/Task.4.md`](../minecraft/Task.4.md) and [`minecraft/Task.6.md`](../minecraft/Task.6.md)
(they read it), [`frontend/`](../frontend/Task.0.md) (which supervises it).

## 5. Task index

| Task | Title | Status |
|---|---|---|
| [1](Task.1.md) | Boot + presence endpoint | ✅ COMPLETED |
| [2](Task.2.md) | Control protocol v2 + live telemetry | ✅ COMPLETED |
| [3](Task.3.md) | Host/join delegation + world seeding | 🚧 IN PROGRESS |
| [4](Task.4.md) | Out-of-game committee validation | ✅ COMPLETED (headless) |
| [5](Task.5.md) | Telemetry emitter + consent record | ✅ COMPLETED |
| [6](Task.6.md) | World ownership and the durable world registry | ✅ COMPLETED |
| [7](Task.7.md) | The LAN lane: playing together without a mod | ✅ COMPLETED |
| [8](Task.8.md) | One world, one identity | 🚧 IN PROGRESS |

Status ledger: [`PROGRESS.md`](PROGRESS.md) · tests: [`TESTING.md`](TESTING.md) · open gaps:
[`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps: [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) ·
refactoring register: [`REFACTORING.md`](REFACTORING.md) · control-protocol wire reference:
[`REFERENCE.md`](REFERENCE.md).

## 6. Files

> Paths reflect the 2026-07-26 module split: `dev.nodera.headless` moved from `:peer` into the new
> `:worker` Gradle module (`peer/`). The control endpoint and the tunnel lane stay in
> `:peer` (`peer/.../dev/nodera/peer/control/`) because the Paper plugin reuses that library
> code. `WorkerState` is **not** a separate file in this category — the live state snapshot lives in
> `WorkerControlHandler.stateJson` (a `dev.nodera.shadow.WorkerState` exists in `:engine`, unrelated).

| Path | Contents |
|---|---|
| `peer/.../headless/HeadlessPeerMain.java` | Boots the runtime, holds the identity, serves the control endpoint |
| `peer/.../headless/WorkerControlHandler.java` | Answers the verb table; builds the live `STATE` snapshot from runtime state |
| `peer/.../peer/control/ControlProtocol.java` | The wire: verbs and version — the single source of truth for the constants; full grammar in [`REFERENCE.md`](REFERENCE.md) |
| `peer/.../peer/control/ControlServer.java` | Loopback listener + handler dispatch |
| Mirrors | `endpoints/neoforge-mod/.../common/CompanionProtocol.java`, `app/src/control.rs` |

Package architecture: [`peer/README.md`](../../peer/README.md),
[`peer/README.md`](../../peer/README.md).

## 7. Conventions specific to this category

- **Option B is locked.** The worker reuses the tested Java peer wholesale. A Rust-native peer is
  forbidden from re-executing regions by the single-engine rule; it could only seed, relay, and route.
  A lightweight Rust-only mode remains a possible later addition, never a validator.
- **`ControlProtocol` is the single source of truth**; the mod and app files are mirrors. Bump all
  three in one commit.
- **The control channel carries no secret material** beyond the loopback trust boundary. Passwords are
  hashed or derived before they reach a verb, never logged, never serialized.
- **The worker is untrusted by peers, like any node.** Everything it serves verifies by hash and
  signature. Requiring it locally does not make it authoritative remotely.
- **The worker owns author authority for the worlds it hosts:** it holds the signing key, mints signed
  world identities, and enforces author-only re-key.
