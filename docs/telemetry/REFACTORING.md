# Telemetry — Refactoring Register

Source: `build/jscpd/jscpd-report.json` (2026-07-28 run) filtered to `telemetry/` and
`peer/src/main/java/dev/nodera/diagnostics/`, plus manual review of `docker/telemetry/`.
`% duplicated` = (sum of duplicate line-runs touching the file ÷ file lines from `loccount.txt`) × 100.
jscpd finds **zero** clones wholly inside the telemetry modules — every measured row below is a
*cross*-crate or *cross*-class sibling, which is why most elimination paths land in the shared
`nodera-service` crate rather than in this category alone. Scope: only this category's modules.

| File | Lines | % duplicated | Duplicated-with | Refactor plan |
|---|---|---|---|---|
| `telemetry/build.rs` | 38 | 100.0 | `rendezvous/build.rs` (+ `nodera-tracker/build.rs` sibling) | Lift the walk-up `VERSION` lookup + `NODERA_VERSION` env stamp into `nodera-service` (a shared `build_util` or an `xtask`), so each service crate's `build.rs` becomes a one-line call. All three stays in sync by construction. |
| `telemetry/src/main.rs` | 343 | 51.0 | `nodera-rendezvous/src/main.rs`, `nodera-tracker/src/main.rs` | Move the shared CLI skeleton — arg parse, `serve` shell (load → `apply_env` → `validate` → bind → print "listening on"), `shutdown_signal`, `healthcheck` — into `nodera-service::cli`. Each binary keeps only its `Config`/service wiring. |
| `peer/src/main/java/dev/nodera/diagnostics/metric/TpsMeter.java` | 206 | 48.1 | `diagnostics/metric/TickSkewMeter.java` | Extract an abstract `TickWindowMeter` base (sample window, `record`, `snapshot`, monotonic-time seam). `TpsMeter` and `TickSkewMeter` then differ only in what they compute over the same window. Removes ~99 duplicated lines and the dual-test pair (`TpsMeterTest`/`TickSkewMeterTest`). |
| `peer/src/main/java/dev/nodera/diagnostics/metric/TickSkewMeter.java` | 251 | 39.4 | `diagnostics/metric/TpsMeter.java` | Same as above — the meter is the other half of the `TickWindowMeter` extraction. |
| `telemetry/src/wire.rs` | 287 | 18.5 | `nodera-rendezvous/src/wire.rs`, `nodera-tracker/src/wire.rs` | `read_frame`/`write_frame`/`serve_connection`/`run`/`maintenance_loop` are service-agnostic; move them into `nodera-service::net` (framing already lives in `nodera-codec`). The telemetry-specific body is the `Ingest::handle_frame` call, which stays. |
| `telemetry/src/config.rs` | 341 | 15.5 | `nodera-rendezvous/src/config.rs`, `nodera-tracker/src/config.rs` | The env-overlay application + `validate` + `bounds` pattern repeats. The env machinery is already shared (`nodera_service::env`); lift the `Config::validate`-against-`ConfigError` + "every key has an env override" self-test into a shared trait/test-helper. |
| `peer/src/main/java/dev/nodera/diagnostics/metric/TrafficMeter.java` | 101 | 11.9 | itself (internal clone) | Two ~6-line runs of per-direction accounting repeat inside one file; extract a private `record(Direction, bytes)` helper. Small, low-risk, in-file. |
| `peer/src/main/java/dev/nodera/diagnostics/view/TrackerStatusView.java` | 79 | 11.4 | `diagnostics/view/RendezvousStatusView.java` | Both render a service-status panel (rows + health dot) from the same view-model shape. Extract a `ServiceStatusView` base parameterised by the metric set; the two subclasses supply their rows. |
| `peer/src/main/java/dev/nodera/diagnostics/view/RendezvousStatusView.java` | 115 | 7.8 | `diagnostics/view/TrackerStatusView.java` | The other half of the `ServiceStatusView` extraction above. |
| `telemetry/src/limits.rs` | 170 | 7.1 | `nodera-rendezvous/src/limits.rs` | `IngestQuota` (fixed-window batch+event counters, charge-before-verdict) mirrors the rendezvous limiter. A shared fixed-window quota in `nodera-service::quota` removes it; keep the per-service limits in config. |
| `peer/src/main/java/dev/nodera/diagnostics/DiagnosticsCollector.java` | 155 | 5.8 | its own test (`DiagnosticsCollectorTest.java`) | Test mirrors the collector's assembly order; extract a tiny `DiagnosticsFixtures` builder the test and any future caller share. Test-only coupling, low priority. |
| `telemetry/src/service.rs` | 524 | 3.4 | itself (internal clone) | A small (~9-line) run repeats inside `write`/`refuse`; a private `bump_reason(counter, reason)` helper absorbs it. Negligible. |
| `docker/telemetry/Dockerfile` | 54 | — | `docker/tracker/Dockerfile`, `docker/rendezvous/Dockerfile` | Documented as deliberately near-identical (see its `AI-AGENT-INSTRUCTION`). Collapse into one `ARG SERVICE=nodera-telemetry` + `cargo build -p $SERVICE` Dockerfile invoked per service; removes the three-way hand-sync discipline. Manual: Dockerfiles are outside the jscpd rust+java scan. |
| `docker/telemetry/ingest/nodera-telemetry.toml` | 33 | — | `Config::default()` in `telemetry/src/config.rs` | Every key except `spool_dir`, `spool_max_bytes`, `spool_max_seconds`, `geo_table_path` equals `Config::default()`. Slim the shipped file to the deployment-specific delta so defaults live in one place. Manual config/config drift, not in jscpd. |

## Sequencing

Ordered by independence and payoff, with the constraint that the cross-crate refactors must land
*with* the tracker and rendezvous categories' sign-off (they are the other two ends of the clone).

1. **`TickSkewMeter` / `TpsMeter` → `TickWindowMeter`** — highest in-category duplication
   (~40–48%, ~99 lines), fully contained in `peer/.../diagnostics/metric/`, no cross-category
   coordination, pure correctness-preserving extraction. Do first.
2. **Lift the Rust service scaffold into `nodera-service`** — `build.rs`, `main.rs`, `wire.rs`,
   `config.rs`, `limits.rs` together account for ~330 duplicated lines, the single biggest absolute
   win. It is cross-crate (tracker + rendezvous), so schedule it as one PR after `nodera-service`
   grows the `cli`/`net`/`quota`/`build_util` helpers, and all three service crates switch in the
   same commit.
3. **`TrackerStatusView` / `RendezvousStatusView` → `ServiceStatusView`** — small, in-category,
   low-risk; natural follow-up to #1 (same package, same review surface).
4. **Slim `ingest/nodera-telemetry.toml`** — drop keys that equal `Config::default()`, leaving a
   reviewable deployment delta. Trivial, but do it after #2 so any default moves with the config
   refactor rather than under it.
5. **One `ARG`-driven service Dockerfile** — already documented as deliberately shared, so the
   lowest-priority change; only worth it once #2 lands and the three build paths already agree by
   construction rather than by discipline.

Out of scope but noted: `../plans/Plan.6.md` §4 ("What is collected") does not name `tracker.services`
or `service.drain`, both of which `schema.rs` now declares. That is a programme-plan drift, not a
refactor; it is recorded here only because this register is where a maintainer hunting "why does the
registry have events the plan does not list?" will first look.
