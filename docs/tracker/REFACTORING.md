# Tracker — Refactoring Register

<!-- AI-AGENT-INSTRUCTION: A living register of duplication and structural debt in the tracker modules,
     built from the jscpd + loccount report and manual review. Each row has a plan; nothing here is
     "permanent". Re-rank after a sweep, and strike a row when its refactor lands. Source:
     build/jscpd/jscpd-report.json + build/loccount.txt, captured 2026-07-28. -->

Scope: `tracker/**` (excluding `target/`) and
`peer/src/**/dev/nodera/peer/discovery/**`. Lines from `build/loccount.txt`;
`% duplicated` = (sum of duplicated line-runs in this file / file lines) × 100, 1 decimal, from the
jscpd duplicate set.

| File | Lines | % duplicated | Duplicated-with | Refactor plan |
|---|---|---|---|---|
| ~~`tracker/build.rs`~~ | ~~38~~ | ~~100.0~~ | ~~`nodera-rendezvous/build.rs`, `nodera-telemetry` sibling~~ | **Completed 2026-08-06** (Plan 11 round 2, item 6): all three are now two lines calling `nodera_build::stamp_version()`. The reader lives in `library/rust/nodera-service/build-version` — its own package, not a module of `nodera-service`, because a build script may only use `[build-dependencies]` and depending on the service crate would compile tokio/ureq/ed25519-dalek **for the host** on every cross-compiled release build. |
| ~~`tracker/src/main.rs`~~ | ~~426~~ | ~~55.2~~ | ~~`nodera-rendezvous/src/main.rs`~~ | **Completed 2026-08-06** (Plan 11 round 2, item 6): `Command`/`parse_args`/usage/dispatch → `nodera_service::cli`, `shutdown_signal` → `nodera_service::serve`, `fresh_seed`/`random_node_id` → `nodera_service::identity::load_or_generate`, the six-step serve preamble → `nodera_service::config::{configure, bind}`. What is left is this service's own: its `Config`, its `ServiceHost`, its healthcheck. `TrackerHost::capacity` is **not** shared — it reports tracker-specific counters and only looks alike. |
| ~~`tracker/src/limits.rs`~~ | ~~120~~ | ~~53.3~~ | ~~`nodera-rendezvous/src/limits.rs`~~ | **Completed 2026-08-06** (Plan 11 round 2, item 6): deleted; `nodera_service::limits::Quota` is the one implementation, with telemetry's two-counter variant beside it as `PairQuota`. The five tests were identical too and are now written once. |
| `tracker/src/telemetry.rs` | 132 | ~18 | `nodera-rendezvous/src/telemetry.rs` (reporter loop skeleton, `Counters` deltas) | **Partly done 2026-08-06:** `os_label`/`arch_label` moved to `nodera_telemetry::reporter`, beside the registry whose declared enums they have to stay inside. What remains is the start-event → window-delta → flush skeleton; it is worth ~25 lines and needs a `WindowMetrics` closure per service, which is close to the size of what it removes. Re-measure before doing it. |
| `tracker/src/config.rs` | 486 | ~6 | `nodera-rendezvous/src/config.rs` (`validate` shape) | **Completed 2026-08-06** (Plan 11 round 2, item 6): `ConfigError` and `load` now come from `nodera_service::config`, and the 28 `overlay.set(...)` calls are a `nodera_service::env_overlay!` table. Each crate keeps its own `Config` struct — the keys genuinely differ — and keeps `deny_unknown_fields`, which is **not** to be aligned with the app's settings loader: an operator's typo must be loud, a user's config must never be reset. What remains is the `validate` bound-check shape, which is per-service values rather than per-service logic. |
| `tracker/src/wire.rs` | 385 | ~8 | self (test spawn helpers) | **Completed 2026-08-06** (Plan 11 round 2, item 6): `now_millis`/`read_frame`/`write_frame` and the accept `select!` are `nodera_service::{frame, serve}`, and the **UDP amplification cap** is now a named, tested `frame::udp_reply_permitted` rather than an inline comparison inside a datagram loop — it is a security property and it had no home. The shared reader is generic over the stream, so the frame-size bound is asserted against an in-memory pipe for all three services; before, only telemetry's copy could be. What remains is `serve_connection`, which differs per service in what it does with a decoded frame. |
| `tracker/src/bin/nodera-query.rs` | 187 | 22.1 | self (`query` ↔ `ask_services` framing loop) | Extract one `fn framed_exchange(endpoint) -> Result<Vec<u8>>` used by both the world query and the services query; the two call sites then differ only in the request/response decode. **Unrelated fix landed 2026-08-06:** both call sites now go through `nodera_service::endpoint::socket_target`, so the documented `tcp://host:port` form — the one in the compose file — no longer fails with "Name does not resolve". |
| ~~`java/.../discovery/PersistentIdentityStore.java`~~ | ~~167~~ | ~~16.8~~ | ~~`worker/.../headless/LocalFiles.java`~~ | **Completed 2026-07-28:** both wrappers call `storage.io.AtomicFileWriter.writeOwnerOnly`; `AtomicFileWriterTest` pins permissions and cleanup. |
| ~~`tracker/src/announce.rs`~~ | ~~255~~ | ~~16.5~~ | ~~`nodera-rendezvous/src/register.rs`~~ | **Completed 2026-08-06** (Plan 11 round 2, item 6): `IdentityBindings` and `within_window` are `nodera_service::admission`. The rendezvous copy of the freshness check was an inline `abs_diff` rather than a call, which is exactly how the two would have drifted. `Rejection` stays per-service: the variant sets are different answers to different questions. |
| `tracker/src/test_support.rs` | 143 | 15.4 | `nodera-rendezvous/src/test_support.rs` (`TestSigner`, `caps`, `sign`) | `TestSigner` (Ed25519 sign/verify for tests) is duplicated crate-to-crate; move to a shared `nodera-service::test_support` (test-only, gated by `#[cfg(any(test, feature = "test-support"))]`). |
| ~~`java/.../discovery/CachedPeerStore.java`~~ | ~~249~~ | ~~10.8~~ | ~~`InvitationCodec.java`, `DurableActionJournal.java`~~ | **Moot 2026-08-06** — the file was deleted (Plan 11 round 2, issue #210) with `BootstrapClient`, the second discovery resolver. The duplication it carried went with it; no extraction is owed. |
| `tracker/src/service.rs` | 1433 | 8.3 | self (test helpers: `service_ack`/`response`/`ack`, the announce+query test scaffolding), `nodera-codec/tombstone.rs` | **God-class candidate (manual):** `Tracker` holds registry + bindings + deleted + 2 quotas + directory + 3 counters and dispatches discovery, service-directory, and deletion frames. Split: keep `Tracker` as the state owner; extract `service_directory.rs` dispatch (already partly in `services.rs`) and a `deletion` dispatch wrapper. The 1433 lines also include ~400 lines of tests — those should move to an inline `#[cfg(test)] mod service_tests` separation or a dedicated test module to shrink the reviewable surface. |
| `java/.../discovery/TrackerClient.java` | 754 | 4.2 | `transport/.../session/Negotiation.java` (import block), self, many (import boilerplate) | **Multiple-responsibility candidate (manual):** announce + query + catalog + routes + `serviceDirectory` + `reportServiceScores` + UDP/TCP `exchange` + deletion notices in one class. Extract `TrackerExchange` (the TCP/UDP socket round-trip + framing) so the API class holds only request building and response merging. Low jscpd % but high cognitive load. |
| ~~`java/.../discovery/InvitationCodec.java`~~ | ~~198~~ | ~~8.1~~ | ~~`CachedPeerStore.java`, self~~ | **Moot 2026-08-06** — deleted with `BootstrapClient`, which was the only thing that read a pasted invitation blob. |
| ~~`java/.../discovery/ArchiveInventory.java`~~ | ~~286~~ | ~~6.3~~ | ~~self, `PeerDirectory.java`~~ | **Moot 2026-08-06** — deleted (issue #210): only the archival repair lane consulted it, and that lane went too. The seeder index now lives in the Rust tracker's `registry.rs`, fed by `TrackerAnnounce` holdings. |
| `java/.../discovery/RendezvousDirectory.java` | 350 | 5.4 | ~~`peer/.../archival/ArchiveAuditTask.java` + `ArchiveManager.java`~~ (constructor null-check + seeds-copy block) | Minor, and now **self-resolving**: both duplication partners were deleted on 2026-08-06 (issue #210), so this file no longer shares the block with anything. The `Objects.requireNonNull` + `List.copyOf(seeds)` pattern is idiomatic — leave it. |
| `tracker/src/deletion.rs` | 299 | 4.7 | self (test setup helpers) | Test-only self-dup; fold the two `golden()`/`tombstone()` test helpers into one. |
| ~~`java/.../discovery/PeerDirectory.java`~~ | ~~202~~ | ~~3.0~~ | ~~`ArchiveInventory.java`~~ | **Moot 2026-08-06** — deleted (issue #210) along with its only duplication partner; production discovery is `PeerDiscoveryService` + `TrackerClient` + `RendezvousDirectory`, none of which consulted it. |
| `java/.../discovery/PeerDiscoveryService.java` | 279 | 2.5 | `worker/.../headless/WorldHostingService.java` (import block) | Noise; leave. |
| `tracker/src/registry.rs` | 487 | 2.5 | self (test `apply` helper) | Noise; leave. |
| `tracker/src/services.rs` | 889 | 1.8 | self (test `admit`/`observation` helpers) | **Large-file candidate (manual):** cohesive (directory + scoring) but 889 lines; if it grows, split `ServiceDirectory` (state/sweep) from `aggregate` (scoring math). Low jscpd. |
| `tracker/src/query.rs` | 588 | 0.0 | — | Clean. |
| `tracker/src/health.rs` | 68 | 0.0 | — | Clean. |
| `java/.../discovery/ServiceScoreBoard.java` | 274 | 0.0 | — | Clean. |
| `java/.../discovery/CommonsPresence.java` | 156 | 0.0 | — | Clean. |
| ~~`java/.../discovery/BootstrapClient.java`~~ | ~~190~~ | ~~0.0~~ | ~~—~~ | **Moot 2026-08-06** — "Clean" was a duplication verdict, not a reachability one. The file was a second discovery resolver with no production caller and was deleted (issue #210). A jscpd score of 0.0 says nothing about whether anything runs the code. |
| `java/.../discovery/package-info.java` | 39 | 0.0 | — | n/a. |

## Sequencing

Re-ranked 2026-08-06, after Plan 11 round 2 item 6 landed the whole cross-crate extraction wave.
`jscpd --format rust --min-lines 5 --min-tokens 50` over `tracker/`, `rendezvous/`, `telemetry/` and
`library/rust/` went from **109 clones / 1,373 duplicated lines / 3.10%** to **81 clones / 777 lines
/ 1.80%**, and what is left is almost entirely *inside* single files rather than between crates.

What remains, in order:

1. **`service.rs` test scaffolding (self-duplication).** The largest remaining clone runs in the
   category are four ~15-line blocks inside `tracker/src/service.rs`'s own test module. The file is
   417 lines of service and ~1,050 of tests; a shared announce+query fixture would remove most of
   them. This is the *only* remaining row with real line count behind it.
2. **`nodera-query`'s two framing loops.** ~40 lines, self-contained, and the binary is a diagnostic
   — low blast radius.
3. **The windowed-report skeleton** (`telemetry.rs` ×2). Measure first: the parametrisation may cost
   as much as it removes.
4. **`serve_connection` ×3.** Deliberately *not* done: the three bodies differ in what they do with a
   decoded frame (the tracker hangs up on an unsupported family, telemetry admits by source address
   first, the rendezvous hands the socket away on reserve), and an async handler callback would cost
   about what the extraction saves.
5. **The Java god-classes** (`TrackerClient`, and `Tracker` itself). Real structural debt, no
   cross-service leverage, single-file surgery — still last, for the same reason as before.

**Not to be done:** splitting the "Rust monoliths" this register used to rank by `wc -l`. Round 2's
measurement showed `tracker/src/service.rs` is 417 lines of service and 811 of its own tests, and
three of the four named files are ordinary. Splitting them yields approximately zero.
