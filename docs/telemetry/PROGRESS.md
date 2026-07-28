# Telemetry — Progress Ledger

<!-- AI-AGENT-INSTRUCTION: Per-task status ledger for the telemetry category. On every
     outcome-changing commit touching this category: update the §1 row, append a dated §2 milestone
     note naming the EVIDENCE (test name), then reconcile ../ROADMAP.md §2. Never rewrite an old
     note. -->

**Category:** telemetry · **Last audit:** 2026-07-28 · Tasks completed: **1 / 3** (plus 6 of the 7 emitter tasks in other categories — see [`../plans/Plan.6.md`](../plans/Plan.6.md) §6)

Tests: [`TESTING.md`](TESTING.md) · open gaps: [`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps:
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) · charter: [`Task.0.md`](Task.0.md) ·
programme: [`../plans/Plan.6.md`](../plans/Plan.6.md).

---

## 1. Task status

| Task | Title | Status | Notes |
|---|---|---|---|
| [1](Task.1.md) | The `nodera-telemetry` ingest service | ✅ COMPLETED | 95 Rust tests; clippy clean; the gate is at the receiver, and the shared reporter lives here too |
| [2](Task.2.md) | The Big Data plane | 🚧 IN PROGRESS | Compose + warehouse + rollups landed; `stack-smoke` green in CI. Parquet export + TTL-merge assertion remain |
| [3](Task.3.md) | Analysis, dashboards, alerting, report | ⬜ NOT STARTED | The emitters exist now; what is missing is a **population** that has opted in |

---

## 2. Milestone notes (newest first)

### 2026-07-28 — L-73 retired: the TLS proxy is now enforced, not merely documented

The listener speaks plaintext and still does; what changed is that "put the `edge` proxy in front
of it" stopped being a sentence in a comment and became a condition the service checks. A
deployment sets `public_endpoint = true` and names the ranges its TLS terminator dials from
(`trusted_proxy_cidrs`); declaring the first without the second refuses the start, and a connection
from anywhere else is closed in `serve_connection` **before its first frame is read**, counted as
`plaintext_not_via_tls_front`.

The distinction that made this worth doing: the old row was written as if the only fix were native
TLS. It was not. The exposure was that nothing stopped an operator from publishing the plaintext
port, and an operator who *declares* the deployment public can be held to naming what may reach it.

Evidence: `wire::tests::a_direct_plaintext_client_is_refused_once_the_endpoint_is_declared_public`
(real TCP socket: the stranger gets an end of stream, the declared front gets its reply, exactly one
refusal counted), plus four `config::tests::*` covering the boot refusal, an unparseable range, the
admission ranges themselves, and the unchanged private case. Row moved to
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) with the cost carried forward stated: the hop between
terminator and service is still unencrypted, and it is now a link the operator controls.

**L-74 and L-75 were triaged in the same pass and left OPEN, deliberately.** L-74's exit test is an
*exercised* backup/restore of the ClickHouse volume in CI — a deployment lane, not a defect in this
crate, and writing the row's fix without running that drill would be exactly the "green while
asserting nothing" failure this pass was cleaning up elsewhere. L-75 needs an opted-in population
that does not exist yet; no amount of engineering retires it.

### 2026-07-28 — Documentation sweep: test-count reconciliation, ledger fix, refactoring register

Status reconciliation pass against the current tree. No code changed; the receiver, the Big Data
plane, and the emitters are exactly where the 2026-07-26 note left them. Three documentation
defects corrected, all in this directory:

1. **The Rust test count was stale.** `cargo test -p nodera-telemetry` is **91 tests**, not 83.
   `Task.1.md` and `TESTING.md` carried the old number, and the `TESTING.md` per-module table did
   not list `reporter.rs` at all (9 tests) while `schema.rs` (8), `config.rs` (11), and `main.rs`
   (6) were each undercounted by one or two. Counts now grep-verified.
2. **A limitation row was referenced by the wrong number.** `PROGRESS.md` and `TESTING.md` both
   pointed at **L-76** ("nothing has collected a population yet"), but the normative register
   [`LIMITATIONS.md`](LIMITATIONS.md) §B owns that row as **L-75** (task 3). L-76 does not exist
   anywhere in this category; the two stale references now cite L-75, matching `Task.3.md` and
   `../plans/Plan.6.md` §6.
3. **A refactoring register was added** ([`REFACTORING.md`](REFACTORING.md)). jscpd finds *zero*
   in-module clones; the real duplication is cross-crate (the three service crates share a
   near-identical `build.rs` / `main.rs` / `wire.rs` / `config.rs` / `limits.rs` scaffold) and
   in-diagnostics (`TpsMeter` ↔ `TickSkewMeter`, ~40–48%). Recorded with elimination paths so the
   next maintainer does not rediscover it.

No limitation moved status: **L-72, L-73, L-74, L-75** are all still OPEN — none of their exit
tests exist in this crate yet (a restart-and-rotate pseudonymisation test, a non-TLS refusal test,
a ClickHouse backup/restore drill, and the first real-population dashboard respectively).

### 2026-07-26 — Four defects the smoke job found on its first four runs

`stack-smoke` went green, and the way it got there is the note worth keeping: the compose stack was
committed looking correct and was wrong in four independent ways, each of which only appeared when
something actually ran it.

1. The image pinned `rust:1.82`, the workspace's `rust-version` **floor**, not the 1.93.1 in
   `rust-toolchain.toml` — a transitive dependency needed edition 2024 and the build died.
2. `CREATE VIEW v_join_success` aliased `sumMerge(successes) AS successes`; inside one SELECT the
   alias wins, so the percentage expression re-merged an already-merged `UInt64` and ClickHouse
   rejected the entire schema file. The container then crash-looped.
3. The healthcheck probed `/ping` with `wget`, which that image does not ship, so 150 s of retries
   were spent on a command that could never succeed.
4. The alpine ClickHouse variant does not start at all — `librt.so.1` missing on one host, a
   restart loop on another.

None of these would have been caught by review, and the third and fourth were invisible until
`docker compose up` learned to fail fast (`--wait --wait-timeout`) instead of waiting on a
crash-looping dependency until the job timed out and killed the log-collection step with it.

### 2026-07-25 — The senders arrive, and the outage lane is the proof

The receiver had no senders when this category opened. It has six now: the emitter core
([network 12](../network/Task.12.md)), the worker that owns the consent record
([worker 5](../worker/Task.5.md)), the game-side façade ([minecraft 8](../minecraft/Task.8.md)),
both service reporters ([tracker 4](../tracker/Task.4.md), [rendezvous 4](../rendezvous/Task.4.md)),
and the companion's first-run question ([app 5](../app/Task.5.md)).

`scripts/e2e-telemetry.sh` is what makes that a claim rather than an assertion: it builds the real
collector and the real worker distribution, and drives them. Six steps, and the two that decide
whether the programme was worth doing at all:

- **T1** — a worker nobody asked lets two collection windows pass and the collector's spool stays
  empty. Consent gating *collection* rather than transmission, observed from outside the process.
- **T6** — the collector is killed mid-run and the worker's whole `NODERA-STATE` answer is compared
  field by field with the pre-kill one. Everything except the telemetry block and the clock is
  identical. That is D10 ("the stack is never a dependency") as a test.

One thing deliberately not claimed: nothing has *collected* anything against a real population yet.
**L-75** stays OPEN and [telemetry 3](Task.3.md) stays ⬜ until a deployment has a population that
opted in — the mechanism is finished, the measurement has not started.

### 2026-07-25 — The category opens with its gate already built

The measurement plane starts from the receiver rather than from the emitters, and that ordering is
the decision worth recording. Writing the emitters first would have meant deciding what to collect
in six places (mod, worker, app, tracker, rendezvous, endpoint) and then trying to keep six
implementations honest. Building `nodera-telemetry` first means the answer to "what does NoderaMC
collect?" is one file — `rust/nodera-telemetry/src/schema.rs` — enforced against every client,
including clients the project did not write.

Landed with evidence:

- **The registry**, with closed value domains. `no_attribute_accepts_free_text` makes the privacy
  claim structural: a field that could carry a world name, a chat line, or a path is not
  representable, so it cannot be added by accident.
- **Consent as a gate.** `a_batch_without_consent_writes_nothing_and_says_why` — a batch without an
  explicit `granted` writes nothing, and the service says so rather than accepting silently.
- **De-identification before the sink.** `the_install_identifier_is_replaced_by_a_rotating_subject`
  and `a_row_carries_the_country_and_never_the_address` pin the two properties everything else
  rests on.
- **Honest refusals.** Per-reason counters, returned to the sender: a fleet cannot believe it is
  reporting something the receiver is discarding. `counters_account_for_everything_that_arrived`.
- **The Big Data plane**: compose stack (Vector → Redpanda → ClickHouse → Grafana, Spark under a
  profile), warehouse schema with three rollups, retention as table TTLs.

One design note worth keeping: the rollups aggregate `subject` away through `uniqState`, so the
tables that live 400 days carry no per-installation identifier at all. Only the 30-day raw table has
one, and it deletes itself. That is what makes a long retention window on trend data defensible.

Not yet true, and tracked rather than implied: nothing is emitting. Until
[`worker 5`](../worker/Task.5.md) lands, this category is a receiver with no senders, and
[`telemetry 3`](Task.3.md) has nothing to analyse.
