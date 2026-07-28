# Tracker — Progress Ledger

<!-- AI-AGENT-INSTRUCTION: Per-task status ledger for the tracker category. On every outcome-changing
     commit touching this category: update the §1 row, append a dated §2 milestone note naming the
     EVIDENCE (test name), then reconcile ../ROADMAP.md §2. Never rewrite an old note. -->

**Category:** tracker · **Last audit:** 2026-07-28 · Tasks completed: **5 / 6**

Tests: [`TESTING.md`](TESTING.md) · open gaps: [`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps:
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) · charter: [`Task.0.md`](Task.0.md).

---

## 1. Task status

| Task | Title | Status | Notes |
|---|---|---|---|
| [1](Task.1.md) | The service binary | ✅ COMPLETED | 109 Rust `#[test]`s in the crate; driven by `TrackerServiceIT` against the real binary |
| [2](Task.2.md) | The Java client | ✅ COMPLETED | Announce loop moves into the worker; GUI rows ride the live pass |
| [3](Task.3.md) | Operations hardening | 🚧 IN PROGRESS | `--healthcheck`/`--version` + deployment docs landed via Task 6; `STATS` + listing policy remain |
| [4](Task.4.md) | Service telemetry | ✅ COMPLETED | Off unless configured; counters only, proven on the rendered JSON |
| [5](Task.5.md) | Service directory + scoring | ✅ COMPLETED | 109 Rust tests; operator docs landed in SELF-HOSTING.md; L-81 RETIRING (credential only) |
| [6](Task.6.md) | Published image + self-hosting | ✅ COMPLETED | Live in production; GHCR amd64+arm64; relay announces into the directory |

---

## 2. Milestone notes (newest first)

### 2026-07-28 — Documentation sweep: the directory lane closes, the operator surface reconciled

A read-only sweep against the current tree (Rust `nodera-tracker`, Java `dev.nodera.peer.discovery`,
and `docs/tracker/*`) reconciled the task ledger with the code. Two status changes landed:

- **Task 5 → ✅ COMPLETED.** Its last open deliverable — operator documentation for the new
  configuration keys — shipped with [Task 6](Task.6.md)'s [`SELF-HOSTING.md`](SELF-HOSTING.md) §3
  (`NODERA_TRACKER_MAX_SERVICES`, `NODERA_TRACKER_PER_IP_REPORT_QUOTA`,
  `NODERA_TRACKER_SERVICE_REPORT_MAX_REPORTERS`, `NODERA_TRACKER_PEER_TRACKER_ENDPOINTS`). Every
  acceptance criterion is now green. The crate carries 109 `#[test]`s (grep-verified, up from the 102
  recorded when the lane landed). The one open row, **L-81**, is RETIRING rather than blocking: the
  provenance-verification mechanism is built and its exit test
  (`a_validly_digested_but_wrongly_signed_manifest_is_refused`, in `nodera-service`) is green; the only
  remaining step is minting the release signing key — a project-owner credential action, explicitly not
  code.
- **Task 3 partial close.** `--healthcheck`/`--version` (`main.rs:64,67`) and the deployment notes
  ([`SELF-HOSTING.md`](SELF-HOSTING.md)) are now ✅; `STATS` (over the wire) and the public-listing
  policy remain ⬜. Task 3 stays 🚧 IN PROGRESS.

The sweep also corrected the charter's file list (`expiry.rs` never existed as a file — the TTL sweep
lives in `registry.rs`; `deletion.rs`, `service.rs`, `telemetry.rs`, and `bin/nodera-query.rs` are now
named), normalised Task 6's "✅ DONE" to the template's "✅ COMPLETED", and added a
[`REFACTORING.md`](REFACTORING.md) register from the jscpd + loccount report. No new limitations
opened; no limitation retired.

### 2026-07-27 — The tracker learns a second question, and still answers neither with authority

A tracker knew *which worlds exist and who seeds them*. It now also answers *which rendezvous points
exist and which ones work* — the missing half of "a peer can reach the network knowing one address".
`src/services.rs` holds signed `ServiceRecord`s, aggregates the measurements peers report about them,
and answers a directory query best-first. 102 Rust tests, up from 72.

The interesting decisions are all about not becoming authority. The composite score is published **and**
recomputed by the peer from the components (`ServiceScore::composite`, byte-identical in Java and
asserted across the language boundary by the golden fixture), so a tracker that inflates a favourite's
number changes nothing about where traffic goes. Peers send **counters**, never verdicts, so the service
aggregates evidence instead of trusting one peer's arithmetic. Per-reporter influence is capped at 100
probes and RTT is a **median** across reporters — without both, scoring would have been the cheapest
denial-of-service in the system: report every rival relay as dead and take over routing.

One convention did change, deliberately. A service now holds a signing key and signs exactly one thing:
its own address record. A drain notice is an eviction primitive, and unsigned it is a cheap way to herd a
target's traffic onto a relay the attacker runs. The invariant that still holds absolutely is the one
that matters — **nothing a service signs is authority over world state.**

Two rows opened with the update lane rather than being papered over: **L-81** (the release digest proves
integrity, not provenance) and **L-82** (the fetcher shells out to `curl`). Both are inert while
`update_channel` is empty, which is the default.

### 2026-07-27 — A published image, and a tracker anyone can run (Task 6)

[Task 6](Task.6.md) landed the container and the deployment surface: every TOML key has an
`NODERA_TRACKER_*` environment twin, an unrecognised variable refuses the start, a multi-arch
(amd64+arm64) image is published to GHCR, and a self-hoster follows [`SELF-HOSTING.md`](SELF-HOSTING.md)
end to end with no checkout. The project's own tracker and relay are live; the relay announces into the
tracker's service directory, verified from a third machine. Two real defects the deployment exposed
that no unit test had — the documented `tcp://host:port` route form announced to nobody, and a container
cannot hairpin to its own published port — were fixed in the shared `nodera-service` endpoint helper.

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
