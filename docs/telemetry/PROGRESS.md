# Telemetry — Progress Ledger

<!-- AI-AGENT-INSTRUCTION: Per-task status ledger for the telemetry category. On every
     outcome-changing commit touching this category: update the §1 row, append a dated §2 milestone
     note naming the EVIDENCE (test name), then reconcile ../ROADMAP.md §2. Never rewrite an old
     note. -->

**Category:** telemetry · **Last audit:** 2026-07-25 · Tasks completed: **1 / 3** (plus 6 of the 7 emitter tasks in other categories — see [`../plans/Plan.6.md`](../plans/Plan.6.md) §6)

Tests: [`TESTING.md`](TESTING.md) · open gaps: [`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps:
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) · charter: [`Task.0.md`](Task.0.md) ·
programme: [`../plans/Plan.6.md`](../plans/Plan.6.md).

---

## 1. Task status

| Task | Title | Status | Notes |
|---|---|---|---|
| [1](Task.1.md) | The `nodera-telemetry` ingest service | ✅ COMPLETED | 83 Rust tests; clippy clean; the gate is at the receiver, and the shared reporter lives here too |
| [2](Task.2.md) | The Big Data plane | 🚧 IN PROGRESS | Compose + warehouse + rollups landed; `stack-smoke` green in CI. Parquet export + TTL assertion remain |
| [3](Task.3.md) | Analysis, dashboards, alerting, report | ⬜ NOT STARTED | The emitters exist now; what is missing is a **population** that has opted in |

---

## 2. Milestone notes (newest first)

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

One thing deliberately not claimed: nothing has *collected* anything yet. **L-76** stays RETIRING
and [telemetry 3](Task.3.md) stays ⬜ until a deployment has a population that opted in — the
mechanism is finished, the measurement has not started.

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
