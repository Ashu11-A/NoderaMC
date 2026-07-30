# Mobile Task 3 — The phone in the mesh, and how it is proven

<!-- AI-AGENT-INSTRUCTION: The assertion in §Exit is the reason this task exists. If it is ever
     weakened — to "connected", to "the tracker saw it", to anything the app itself reports — this
     file must say so, because those were the claims that were true while no data moved. -->

**Status:** ✅ COMPLETED
**Category:** mobile · **Owns:** `scripts/e2e-android-mesh.sh`
**Last audit:** 2026-07-28
**Depends on:** [mobile 1](Task.1.md), [worker 3](../peer/Task.3.md), [tracker 2](../tracker/Task.2.md)

---

## Goal

Put the Android peer in a mesh with the peers on this machine and prove, from the phone's own
counters, that **bytes arrive**.

## Status detail

Done and green. `scripts/e2e-android-mesh.sh` asserts the phone's own `total_received_bytes` moves
after the join and the membership view is non-empty, observed entirely through `adb` (control socket
+ logcat) so no assertion depends on what the app draws. Both directions checked separately. Last
run evidence and the byte-exchange log are in [`PROGRESS.md`](PROGRESS.md) (2026-07-27 milestone).

## Why "connected" is not the claim

Three weaker statements were all true at points where nothing was actually flowing:

* *the app says Online* — it said that while the link to its own worker was mis-gated and no state
  had ever been received;
* *the tracker accepted an announce* — a receipt for a datagram, nothing more;
* *the peer list is non-empty* — membership can be established in one direction through a NAT.

So the exit is a **counter on the phone**, read over `adb` from the worker's control socket, and it
must have **moved**.

## The topology

```text
   this machine (10.0.0.101)                         the phone (10.0.0.104)
   ├── tracker        0.0.0.0:25600  ◄──── announce ──── worker (in dev.nodera.app)
   ├── rendezvous     0.0.0.0:25601                       │  control 127.0.0.1:25610
   ├── worker peer1   0.0.0.0:25620  ◄──── NODERA-MESH ───┘  (reached only through adb)
   ├── worker peer2   0.0.0.0:25621
   ├── worker spare   0.0.0.0:25622
   ├── Tauri app ×2   (attach mode, one per player)
   └── Minecraft  ×2  (hosted world)
```

The phone dials **out**. That is the direction that works without forwarding a port to a handset,
and it is also how a real phone on a home network would join.

## Exit

| Phase | Assertion |
|---|---|
| P0 | The APK builds and installs over Wi-Fi debugging. A USB-only device is refused — a peer nobody can dial is not in the mesh |
| P3 | The worker answers `NODERA-PROBE` **through adb**, and its `self_route` is a LAN address, not loopback |
| P4 | It reaches the LAN tracker, and `nodera-query` run on this machine finds it there |
| **P5** | **`total_received_bytes` is greater after the join than before, and the membership view is non-empty** |

## What had to change to make this possible

`nodera_start_trackers` and `nodera_start_rendezvous` wrote `bind_addr = "127.0.0.1:<port>"`. Every
suite before this one had every node on one machine, so nothing had noticed. A phone cannot open a
socket to another machine's loopback, and the failure would have looked like a phone problem.

The launcher now takes `NODERA_SERVICE_BIND_ADDR` and `NODERA_SERVICE_ADVERTISE_ADDR`, defaulting to
loopback so every existing suite is unchanged. This suite sets `0.0.0.0` and the host's LAN address —
derived from the route to the phone, so a machine with docker bridges advertises the interface the
phone can actually reach rather than the first one listed.

## Files

| Path | Role |
|---|---|
| `scripts/e2e-android-mesh.sh` | the mesh scenario; P0–P5 assertions |
| `scripts/check-android-bytecode.sh` | CI gate against `invoke-custom` (M-5) |

## Running it

```bash
adb tcpip 5555 && adb connect 10.0.0.104:5555     # once
scripts/e2e-android-mesh.sh                       # the whole thing
scripts/e2e-android-mesh.sh --no-game             # peers only
scripts/run-tests.sh android-mesh                 # through the batch runner
```

It is **not** in the default batch: it needs a physical phone, and a batch that fails on every
machine without one would train people to ignore the batch.
