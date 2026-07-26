# Tracker — Progress Ledger

<!-- AI-AGENT-INSTRUCTION: Per-task status ledger for the tracker category. On every outcome-changing
     commit touching this category: update the §1 row, append a dated §2 milestone note naming the
     EVIDENCE (test name), then reconcile ../ROADMAP.md §2. Never rewrite an old note. -->

**Category:** tracker · **Last audit:** 2026-07-25 · Tasks completed: **3 / 4**

Tests: [`TESTING.md`](TESTING.md) · open gaps: [`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps:
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) · charter: [`Task.0.md`](Task.0.md).

---

## 1. Task status

| Task | Title | Status | Notes |
|---|---|---|---|
| [1](Task.1.md) | The service binary | ✅ COMPLETED | 60 Rust tests; driven by `TrackerServiceIT` against the real binary |
| [2](Task.2.md) | The Java client | ✅ COMPLETED | Announce loop moves into the worker; GUI rows ride the live pass |
| [3](Task.3.md) | Operations hardening | 🚧 IN PROGRESS | `STATS`, listing policy, deployment docs remain |
| [4](Task.4.md) | Service telemetry | ✅ COMPLETED | Off unless configured; counters only, proven on the rendered JSON |

---

## 2. Milestone notes (newest first)

### 2026-07-25 — Service telemetry, and the type that makes the privacy claim structural

`src/telemetry.rs` reports this service's own counters on a window, and the shared
`nodera_telemetry::reporter` is where the interesting decision lives: `ServiceEvent` holds numbers
and `&'static str` labels, and nothing else.

That type signature is the privacy argument. A tracker sees source addresses, node ids, and genesis
hashes on every request; with a `String` value it would be one careless line away from attaching one
to an event. With `'static`, a value derived from a request cannot be a label at all — it has to be
a constant in the crate. `a_window_event_renders_counters_and_labels_only` then asserts the rendered
bytes, so the claim is checked at both ends.

Also landed: `Tracker::world_health_counts`, deliberately an aggregate. A per-world health list
would be a directory, and this service already has a wire message for that — answered to peers who
ask, rather than pushed to a collector.

### 2026-07-25 — A fourth task, and the reason it is off by default

[Task 4](Task.4.md) lets a tracker operator send their own throughput, rejection, health, and latency
numbers to a telemetry endpoint — and ships with that endpoint **unset**.

The reasoning is the same one that makes this category's trust model work. Someone who downloads and
runs a tracker binary has agreed to run a tracker; they have not agreed to report to the project. The
absence of a configured endpoint is the absence of consent, and it is the default.

The second rule is about what a tracker *sees*: source addresses, node ids, genesis hashes. None of
it may enter an event. `tracker.window` is one row per interval containing totals, which answers "is
this service healthy and how much does it carry" without describing anybody — and the ingest registry
would refuse the identifying fields even if a build tried to send them.

### 2026-07-24 — Scheme-aware endpoints and a real UDP surface

Tracker endpoints became scheme-aware (`tcp://`, `udp://`; a bare host stays TCP, so no existing
configuration broke) with a real UDP datagram surface on both sides: one datagram per request over the
shared registry, bounded against reflection amplification, and **silent rather than truncating** when
an answer would exceed the bound — silence is a recoverable signal, a truncated answer is a corrupt
one. The Java client falls back to TCP. `TrackerEndpointTest`, `TrackerClientUdpTest`.

Same period, from the discovery audit: **trackers had never been a peer-discovery plane.**
`TrackerClient.query` fed only the archive lane, so session membership came exclusively from one
bootstrap route plus its gossip, and a peer whose bootstrap was unreachable never meshed no matter how
many members were listed. `PeerDiscoveryService` now sweeps every tracker and rendezvous per world and
introduces this node to each routable peer — merged, never arbitrated.

### 2026-07-22 — The directory and route queries that made joining real

A tracker row a player could see but not join is a list, not a feature. Two queries closed that:
`TrackerCatalogQuery`/`Response` (the world **directory**, so the GUI can list worlds this node has
never seen) and `TrackerRoutesQuery`/`Response` (the **full-route** query, so a row resolves to a live
endpoint). A host's announce carries its open Minecraft endpoint as an `mc/host:port` route claim; the
single-route peer entry form deliberately skips the `mc/` claim.

### 2026-07-19 — The standalone service lands; L-44 RETIRED

The embedded Java tracker could only list what its host peer could still see. The standalone binary
keeps a world listed by name, with its retention countdown and a DEAD verdict, after **every** seeder
has gone silent — the scenario the embedded implementation structurally could not satisfy. Proven by
`TrackerServiceIT` driving the real release binary, including per-world isolation, cross-language
signature verification, tampered-record refusal, and immediate removal on `STOPPED`.

The embedded `TrackerService` and its tests were deleted rather than kept as a fallback; a fallback
would have preserved the exact failure mode the new service exists to remove. `PeerDirectory` and
`ArchiveInventory` stayed, as peer-local caches.
