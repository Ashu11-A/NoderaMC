# Telemetry — Refactoring Register

Source: `build/jscpd/jscpd-report.json` (2026-07-28 run) filtered to `telemetry/` and
`peer/src/main/java/dev/nodera/diagnostics/`, plus manual review of `docker/telemetry/`.
`% duplicated` = (sum of duplicate line-runs touching the file ÷ file lines from `loccount.txt`) × 100.
jscpd finds **zero** clones wholly inside the telemetry modules — every measured row below is a
*cross*-crate or *cross*-class sibling, which is why most elimination paths land in the shared
`nodera-service` crate rather than in this category alone. Scope: only this category's modules.

| File | Lines | % duplicated | Duplicated-with | Refactor plan |
|---|---|---|---|---|
| ~~`telemetry/build.rs`~~ | ~~38~~ | ~~100.0~~ | ~~`rendezvous/build.rs`, `nodera-tracker/build.rs`~~ | **Completed 2026-08-06** (Plan 11 round 2, item 6): all three are two lines calling `nodera_build::stamp_version()`, from `library/rust/nodera-service/build-version` — a dependency-free package rather than a module of `nodera-service`, so a cross-compiled release build does not compile tokio and ureq for the host as well. |
| ~~`telemetry/src/main.rs`~~ | ~~343~~ | ~~51.0~~ | ~~`nodera-rendezvous/src/main.rs`, `nodera-tracker/src/main.rs`~~ | **Completed 2026-08-06** (Plan 11 round 2, item 6): `nodera_service::cli::run` takes the parse and the dispatch, `config::{configure, bind}` the serve preamble, `serve::shutdown_signal` the signal. `--print-schema` stays this service's own and is declared as an extra flag rather than forking the parser — the answer to "what does NoderaMC collect?" has to come from the running binary. |
| `peer/src/main/java/dev/nodera/diagnostics/metric/TpsMeter.java` | 206 | 48.1 | `diagnostics/metric/TickSkewMeter.java` | Extract an abstract `TickWindowMeter` base (sample window, `record`, `snapshot`, monotonic-time seam). `TpsMeter` and `TickSkewMeter` then differ only in what they compute over the same window. Removes ~99 duplicated lines and the dual-test pair (`TpsMeterTest`/`TickSkewMeterTest`). |
| `peer/src/main/java/dev/nodera/diagnostics/metric/TickSkewMeter.java` | 251 | 39.4 | `diagnostics/metric/TpsMeter.java` | Same as above — the meter is the other half of the `TickWindowMeter` extraction. |
| `telemetry/src/wire.rs` | 245 | ~4 | (self) | **Completed 2026-08-06** (Plan 11 round 2, item 6): `now_millis`/`read_frame`/`write_frame` and the accept loop are `nodera_service::{frame, serve}`. This crate's copy of `read_frame` was the **only** one generic over the stream, so it was the only one whose frame-size bound could be tested against an in-memory pipe; that test now covers all three services. `serve_connection` stays: it opens with the public-endpoint admission check, which nothing else has. |
| `telemetry/src/config.rs` | 320 | ~5 | `nodera-rendezvous/src/config.rs`, `nodera-tracker/src/config.rs` (the env-override self-test) | **Completed 2026-08-06** (Plan 11 round 2, item 6): the overlay is a `nodera_service::env_overlay!` table and the file read is `config::load_toml`. `ConfigError` deliberately stays this service's own: its variants are about *what it will collect* (`PublicWithoutTlsFront`, `BadProxyCidr`), not about a file, and a test asserts on them by name. The "every key has an env override" self-test is still written three times and is the remaining share. |
| `peer/src/main/java/dev/nodera/diagnostics/metric/TrafficMeter.java` | 101 | 11.9 | itself (internal clone) | Two ~6-line runs of per-direction accounting repeat inside one file; extract a private `record(Direction, bytes)` helper. Small, low-risk, in-file. |
| `peer/src/main/java/dev/nodera/diagnostics/view/TrackerStatusView.java` | 79 | 11.4 | `diagnostics/view/RendezvousStatusView.java` | Both render a service-status panel (rows + health dot) from the same view-model shape. Extract a `ServiceStatusView` base parameterised by the metric set; the two subclasses supply their rows. |
| `peer/src/main/java/dev/nodera/diagnostics/view/RendezvousStatusView.java` | 115 | 7.8 | `diagnostics/view/TrackerStatusView.java` | The other half of the `ServiceStatusView` extraction above. |
| ~~`telemetry/src/limits.rs`~~ | ~~170~~ | ~~7.1~~ | ~~`nodera-rendezvous/src/limits.rs`~~ | **Completed 2026-08-06** (Plan 11 round 2, item 6): deleted; `nodera_service::limits::PairQuota` is the two-counter variant of the same fixed window, beside the single-counter `Quota` the other two use. Charge-before-verdict is preserved and still asserted — a refused batch must still cost its sender. |
| `peer/src/main/java/dev/nodera/diagnostics/DiagnosticsCollector.java` | 155 | 5.8 | its own test (`DiagnosticsCollectorTest.java`) | Test mirrors the collector's assembly order; extract a tiny `DiagnosticsFixtures` builder the test and any future caller share. Test-only coupling, low priority. |
| `telemetry/src/service.rs` | 524 | 3.4 | itself (internal clone) | A small (~9-line) run repeats inside `write`/`refuse`; a private `bump_reason(counter, reason)` helper absorbs it. Negligible. |
| `docker/telemetry/Dockerfile` | 54 | — | `docker/tracker/Dockerfile`, `docker/rendezvous/Dockerfile` | Documented as deliberately near-identical (see its `AI-AGENT-INSTRUCTION`). Collapse into one `ARG SERVICE=nodera-telemetry` + `cargo build -p $SERVICE` Dockerfile invoked per service; removes the three-way hand-sync discipline. Manual: Dockerfiles are outside the jscpd rust+java scan. |
| `docker/telemetry/ingest/nodera-telemetry.toml` | 33 | — | `Config::default()` in `telemetry/src/config.rs` | Every key except `spool_dir`, `spool_max_bytes`, `spool_max_seconds`, `geo_table_path` equals `Config::default()`. Slim the shipped file to the deployment-specific delta so defaults live in one place. Manual config/config drift, not in jscpd. |

## Sequencing

Re-ranked 2026-08-06, after item 2 below landed.

1. **`TickSkewMeter` / `TpsMeter` → `TickWindowMeter`** — now the largest remaining row in the
   category (~40–48%, ~99 lines), fully contained in `peer/.../diagnostics/metric/`, no
   cross-category coordination. Do first.
2. ~~**Lift the Rust service scaffold into `nodera-service`.**~~ Landed 2026-08-06 (Plan 11 round 2,
   item 6), as one commit across all three service crates as this row required. Workspace Rust
   duplication went from 109 clones / 1,373 lines / 3.10% to 81 clones / 777 lines / 1.80%.
3. **`TrackerStatusView` / `RendezvousStatusView` → `ServiceStatusView`** — small, in-category,
   low-risk.
4. **Slim `ingest/nodera-telemetry.toml`** — drop keys that equal `Config::default()`. Now unblocked:
   the defaults did not move in the refactor above.
5. **One `ARG`-driven service Dockerfile** — lowest priority; the three build paths now agree by
   construction on the version stamp, which was the part discipline was holding together.

Out of scope but noted: `../plans/Plan.6.md` §4 ("What is collected") does not name `tracker.services`
or `service.drain`, both of which `schema.rs` now declares. That is a programme-plan drift, not a
refactor; it is recorded here only because this register is where a maintainer hunting "why does the
registry have events the plan does not list?" will first look.
