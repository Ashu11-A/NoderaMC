# App — Progress Ledger

<!-- AI-AGENT-INSTRUCTION: Per-task status ledger for the app category. On every outcome-changing
     commit touching this category: update the §1 row, append a dated §2 milestone note naming the
     EVIDENCE, then reconcile ../ROADMAP.md §2. Never rewrite an old note. -->

**Category:** app · **Last audit:** 2026-07-25 · Tasks completed: **2 / 4**

Tests: [`TESTING.md`](TESTING.md) · open gaps: [`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps:
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) · charter: [`Task.0.md`](Task.0.md).

---

## 1. Task status

| Task | Title | Status | Notes |
|---|---|---|---|
| [1](Task.1.md) | Scaffold + supervisor | ✅ COMPLETED | Attach mode; workspace-excluded crate |
| [2](Task.2.md) | Live metrics dashboard | ✅ COMPLETED | Real worker data; 56 crate tests |
| [3](Task.3.md) | Packaging + CI | 🚧 IN PROGRESS | Build job green; installers and per-OS autostart remain |
| [4](Task.4.md) | End-to-end acceptance | ⏳ BLOCKED | Gate-both-ways green; cross-machine half needs worker 3 + minecraft 1 |

---

## 2. Milestone notes (newest first)

### 2026-07-25 — The configuration lane and honest badges

The app gained a settings surface backed by the worker's `CONFIG` verb, and with it a decision worth
recording: **a setting the node cannot honour is badged, not faked.** Results come back per key as
applied, rejected with a reason, or restart-required, and the UI renders each distinctly.

Two connection settings are permanently unsupportable against the current architecture — a per-world
connection cap (the socket transport has no world dimension at all) and an unlimited-connections-only
filter (no peer advertises a connection cap on the wire). Both are **kept in the UI on purpose**, with
a muted "not supported" badge carrying the worker's own reason, deliberately distinct from the amber
"not enforced yet" which would imply the feature is coming. Deleting them would silently drop values
users had already saved and would hide that the limitation is known (**L-56**).

Same period, from the storage side: relocating the archive directory stopped being restart-only, and
the restart button the app offers is shown **only when it supervises the worker** — in attach mode it
says to restart it where it was started, because the app must not kill a process it did not spawn.

### 2026-07-24 — The dashboard stops showing zeros

An audit of the tracker → rendezvous → worker → mod → companion chain found capabilities that were
built and tested but never connected, and two of them surfaced here.

**Per-peer throughput was hardcoded to zero.** `PeerTrafficMeter` gave the Peers tab real totals and
rates.

**The piece map had no source at all.** The seam that installs a piece-map source had never been
called anywhere, so "View pieces" always opened an empty grid. A `PIECES` control verb, a parser, and
a feed connected it — and the grid gained scrolling, which it had silently lacked (it simply stopped
drawing at the bottom edge, with nothing said), plus a "peers sharing this world" count distinct from
complete seeders.

The app was rebuilt in the same pass as a torrent-client-shaped **Info / State / Peers / Trackers /
Pieces** tab set inside VPN-client connection chrome — the mental model players already have for a
background process moving data on their behalf. Crate tests grew to cover bitmap decoding against
Java's `BitSet` byte order, bounded/short/undecodable bitmaps, additive-field tolerance, the log ring,
and system sampling.

### 2026-07-23 — The companion CI job; L-47's first form RETIRED

The job builds the app end to end, runs the gate both ways, and proves hosted-world survival with a
real daemon and a SIGKILLed stand-in game process. It immediately paid for itself by surfacing four
packaging gaps that only a **clean-checkout** build could reveal: distribution staging, a gitignored
UI build, gitignored bundle icons, and a gitignored tray icon. All four had the same shape — files
that worked locally because they happened to exist on disk.

A narrower row of the same id stays open for the genuinely cross-machine half.

### 2026-07-21 — Live metrics

A one-second pump probes the worker for liveness, fetches `STATE`, and emits real chunks, bytes,
peers, and world data to the dashboard. The parser uses defaults for unknown fields, because worker
`STATE` fields are additive: a worker newer than its dashboard must show fewer panels, never an error.

### 2026-07-20 — Scaffold and supervisor

Tray, window, single-instance guard, autostart registration, and an **attach-aware** supervisor. The
attach semantics were a correctness decision, not a convenience: two supervisors both believing they
own one worker would fight over its lifecycle, so quitting stops only a worker the app itself spawned.
`scripts/dev.sh --with-app` runs the app in attach mode against an externally started worker.

The crate was excluded from the headless Rust workspace gate because Tauri's native webkit
dependencies would burden every unrelated Rust change — a justified exclusion whose cost (CI never
compiling the app) became a named deliverable rather than an unnoticed gap.
