# Tracker — Refactoring Register

<!-- AI-AGENT-INSTRUCTION: A living register of duplication and structural debt in the tracker modules,
     built from the jscpd + loccount report and manual review. Each row has a plan; nothing here is
     "permanent". Re-rank after a sweep, and strike a row when its refactor lands. Source:
     build/jscpd/jscpd-report.json + build/loccount.txt, captured 2026-07-28. -->

Scope: `rust/nodera-tracker/**` (excluding `target/`) and
`java/peer/src/**/dev/nodera/peer/discovery/**`. Lines from `build/loccount.txt`;
`% duplicated` = (sum of duplicated line-runs in this file / file lines) × 100, 1 decimal, from the
jscpd duplicate set.

| File | Lines | % duplicated | Duplicated-with | Refactor plan |
|---|---|---|---|---|
| `rust/nodera-tracker/build.rs` | 38 | 100.0 | `nodera-rendezvous/build.rs` (identical), `nodera-telemetry` sibling | Promote to a shared `nodera-service::build_support` (or a workspace `build-version` helper) so the three service crates stop carrying byte-identical 38-line `build.rs` files. One `VERSION` reader, one `cargo:rustc-env=NODERA_VERSION`. |
| `rust/nodera-tracker/src/main.rs` | 426 | 55.2 | `nodera-rendezvous/src/main.rs` (arg parse, lifecycle wiring, `fresh_seed`, `shutdown_signal`, `TrackerHost`) | Extract the shared CLI/lifecycle scaffolding into `nodera-service::cli` (parse_args → Command, fresh_seed, random_node_id, shutdown_signal, the `ServiceHost` capacity wiring). The ~60-line `parse_args` block and the 25-line `fresh_seed`/`random_node_id` are the largest clones. Per-service code shrinks to the config + host glue. |
| `rust/nodera-tracker/src/limits.rs` | 120 | 53.3 | `nodera-rendezvous/src/limits.rs` (`AnnounceQuota`, ~identical incl. tests) | `AnnounceQuota` is a generic fixed-window counter; move it to `nodera-service::quota` and re-export. Both services then share one tested implementation; the per-service crate keeps only the window/limit wiring. |
| `rust/nodera-tracker/src/telemetry.rs` | 155 | 40.6 | `nodera-rendezvous/src/telemetry.rs` (reporter loop, label helpers, `Counters` deltas) | Pull the windowed-report loop skeleton into `nodera-service::telemetry_loop` (start-event, window-delta, flush), parametrised by a `WindowMetrics` closure each service fills in. The `os_label`/`arch_label` enums already belong in the shared reporter. |
| `rust/nodera-tracker/src/config.rs` | 541 | 22.4 | `nodera-rendezvous/src/config.rs` (env-overlay boilerplate, `validate` shape, `ConfigError`), `nodera-telemetry/src/config.rs` (the `every_config_key_has_an_environment_override` test) | The `EnvOverlay` call sequence and `ConfigError` enum are the repeated core; `EnvOverlay` already lives in `nodera-service::env` — finish the move by sharing `ConfigError` and a `validate_bounds` helper. Keep each crate's own `Config` struct (the keys genuinely differ). |
| `rust/nodera-tracker/src/wire.rs` | 443 | 22.3 | `nodera-rendezvous/src/wire.rs` (`read_frame`/`write_frame`/`serve_udp`/`serve_connection`), self (test spawn helpers) | `read_frame`/`write_frame`/`serve_udp`/`serve_connection` belong in `nodera-service::wire` (they already wrap `nodera_codec::framing`). Both services then keep only their `run`/`sweep` glue and tests. |
| `rust/nodera-tracker/src/bin/nodera-query.rs` | 181 | 22.1 | self (`query` ↔ `ask_services` framing loop, lines 120–167) | Extract one `fn framed_exchange(endpoint) -> Result<Vec<u8>>` used by both the world query and the services query; the two call sites then differ only in the request/response decode. |
| `java/.../discovery/PersistentIdentityStore.java` | 167 | 16.8 | `worker/.../headless/LocalFiles.java` + `WorldRegistryStore.java` (atomic temp-then-move, `ownerOnly()` perms) | The atomic-write + `rw-------` pattern is duplicated across stores; consolidate behind `storage::io::AtomicFileWriter` (already exists for `CachedPeerStore`) and add a `restrictOwnerOnly` helper there. |
| `rust/nodera-tracker/src/announce.rs` | 255 | 16.5 | `nodera-rendezvous/src/register.rs` (`IdentityBindings` TOFU table + `within_window`), self | `IdentityBindings` and `within_window` are shared discovery primitives — both already imported by `services.rs`; promote them to `nodera-service::identity` so the tracker and rendezvous share one TOFU implementation. |
| `rust/nodera-tracker/src/test_support.rs` | 143 | 15.4 | `nodera-rendezvous/src/test_support.rs` (`TestSigner`, `caps`, `sign`) | `TestSigner` (Ed25519 sign/verify for tests) is duplicated crate-to-crate; move to a shared `nodera-service::test_support` (test-only, gated by `#[cfg(any(test, feature = "test-support"))]`). |
| `java/.../discovery/CachedPeerStore.java` | 249 | 10.8 | `InvitationCodec.java` (record compact-ctor/import block), `peer/.../validation/DurableActionJournal.java` | Minor: the duplicated `Encodable` record encode/decode + `Objects.requireNonNull` boilerplate is the canonical-record pattern; a codegen or base helper would help but is low-value vs. the Rust clones above. |
| `rust/nodera-tracker/src/service.rs` | 1433 | 8.3 | self (test helpers: `service_ack`/`response`/`ack`, the announce+query test scaffolding), `nodera-codec/tombstone.rs` | **God-class candidate (manual):** `Tracker` holds registry + bindings + deleted + 2 quotas + directory + 3 counters and dispatches discovery, service-directory, and deletion frames. Split: keep `Tracker` as the state owner; extract `service_directory.rs` dispatch (already partly in `services.rs`) and a `deletion` dispatch wrapper. The 1433 lines also include ~400 lines of tests — those should move to an inline `#[cfg(test)] mod service_tests` separation or a dedicated test module to shrink the reviewable surface. |
| `java/.../discovery/TrackerClient.java` | 754 | 4.2 | `transport/.../session/Negotiation.java` (import block), self, many (import boilerplate) | **Multiple-responsibility candidate (manual):** announce + query + catalog + routes + `serviceDirectory` + `reportServiceScores` + UDP/TCP `exchange` + deletion notices in one class. Extract `TrackerExchange` (the TCP/UDP socket round-trip + framing) so the API class holds only request building and response merging. Low jscpd % but high cognitive load. |
| `java/.../discovery/InvitationCodec.java` | 198 | 8.1 | `CachedPeerStore.java` (record ctor), self | Low priority; shares only the canonical encode/decode skeleton. |
| `java/.../discovery/ArchiveInventory.java` | 286 | 6.3 | self (`holdersOf` ↔ `manifestsOfWorld` sort+collect), `PeerDirectory.java` (`worlds()` listing) | Extract a shared `sortedById` collector for the two listing methods; small win. |
| `java/.../discovery/RendezvousDirectory.java` | 350 | 5.4 | `peer/.../archival/ArchiveAuditTask.java` + `ArchiveManager.java` (constructor null-check + seeds-copy block) | Minor; the duplicated block is the `Objects.requireNonNull` constructor guard + `List.copyOf(seeds)` pattern — idiomatic, leave it. |
| `rust/nodera-tracker/src/deletion.rs` | 299 | 4.7 | self (test setup helpers) | Test-only self-dup; fold the two `golden()`/`tombstone()` test helpers into one. |
| `java/.../discovery/PeerDirectory.java` | 202 | 3.0 | `ArchiveInventory.java` (`worlds()`/`seedersOfWorld` listing) | Shared `sortedById` collector with ArchiveInventory. |
| `java/.../discovery/PeerDiscoveryService.java` | 279 | 2.5 | `worker/.../headless/WorldHostingService.java` (import block) | Noise; leave. |
| `rust/nodera-tracker/src/registry.rs` | 487 | 2.5 | self (test `apply` helper) | Noise; leave. |
| `rust/nodera-tracker/src/services.rs` | 889 | 1.8 | self (test `admit`/`observation` helpers) | **Large-file candidate (manual):** cohesive (directory + scoring) but 889 lines; if it grows, split `ServiceDirectory` (state/sweep) from `aggregate` (scoring math). Low jscpd. |
| `rust/nodera-tracker/src/query.rs` | 588 | 0.0 | — | Clean. |
| `rust/nodera-tracker/src/health.rs` | 68 | 0.0 | — | Clean. |
| `java/.../discovery/ServiceScoreBoard.java` | 274 | 0.0 | — | Clean. |
| `java/.../discovery/CommonsPresence.java` | 156 | 0.0 | — | Clean. |
| `java/.../discovery/BootstrapClient.java` | 190 | 0.0 | — | Clean. |
| `java/.../discovery/package-info.java` | 39 | 0.0 | — | n/a. |

## Sequencing

The top five, ordered by leverage (clone size × number of services that benefit × blast radius):

1. **`build.rs` → shared version stamp (100% dup, 3 crates).** Smallest, purest win: one byte-identical
   38-line file lives in `nodera-tracker`, `nodera-rendezvous`, and the telemetry build. Promote to a
   workspace helper; all three services inherit `NODERA_VERSION` from one reader.
2. **`limits.rs` `AnnounceQuota` → `nodera-service::quota` (53% dup).** A generic fixed-window counter
   with its 5 tests copied verbatim between tracker and rendezvous. One shared, fully-tested type; each
   service keeps only its window/limit values.
3. **`main.rs` CLI/lifecycle scaffolding → `nodera-service::cli` (55% dup).** `parse_args`,
   `fresh_seed`, `random_node_id`, `shutdown_signal`, and the `ServiceHost` capacity wiring are
   duplicated service-to-service. Extracting them shrinks each `main.rs` to its config + host glue and
   removes the largest single clone block (60 lines of arg parsing).
4. **`wire.rs` frame/UDP helpers → `nodera-service::wire` (22% dup).** `read_frame`/`write_frame`/
   `serve_udp`/`serve_connection` already wrap `nodera_codec::framing`; finish the move so the UDP
   amplification-cap logic lives in exactly one place (it is a security property, not an
   implementation detail).
5. **`telemetry.rs` windowed-report loop → `nodera-service::telemetry_loop` (41% dup).** The
   start-event + window-delta + flush skeleton and the `os_label`/`arch_label` enums are duplicated
   between tracker and rendezvous telemetry. Parametrise by a per-service `WindowMetrics` closure.

Rationale for the ordering: items 1–2 are mechanical and remove the highest-percentage clones with the
lowest design risk; items 3–5 are the same shape (shared `nodera-service` extraction) but touch more
load-bearing code, so they follow once the extraction home is established. The Java god-classes
(`TrackerClient`, `service.rs`) are real structural debt but are *not* cross-crate clones — they are
queued behind the Rust extraction wave because their refactor is single-file surgery with no leverage
on the other services.
