# Telemetry — Category Charter

<!-- AI-AGENT-INSTRUCTION: This category collects data about people who chose to share it. Three rules
     govern everything here and must be refused if violated: (1) nothing is collected without an
     explicit yes; (2) nothing outside rust/nodera-telemetry/src/schema.rs may be stored, and no
     free-text value may ever be added to it; (3) NOTHING in the network may read telemetry — it has
     no authority of any kind. The privacy model and the locked decisions live in
     ../plans/Plan.6.md — read it before editing any task file here. Keep the task index in
     agreement with ../ROADMAP.md §2. -->

**Category:** `telemetry` · **Status:** 🚧 IN PROGRESS (1 of 3 tasks completed) ·
**Last audit:** 2026-07-28

---

## 1. What this category is

The **measurement plane**: an ingest service that receives consented operational reports from every
kind of Nodera node, and the Big Data stack that turns them into answers.

It exists because a decentralized system has no vantage point. There is no central server whose logs
describe the population, so without telemetry the project's knowledge of the wild is limited to what
its authors observed on their own machines — a sample size of one, repeated.

Two components:

- **`nodera-telemetry`** (`rust/nodera-telemetry`) — the ingest service. It decides what may be
  stored: consent gate, schema allow-list, pseudonymisation, coarse geolocation, quotas, spool.
- **The Big Data plane** (`docker/telemetry/`) — Vector → Redpanda → ClickHouse → Grafana, with
  Spark for batch jobs. Retention lives in the warehouse schema as TTLs.

The **emitters** are not in this category. They belong to the components that measure themselves:
[`network 12`](../network/Task.12.md), [`worker 5`](../worker/Task.5.md),
[`app 5`](../app/Task.5.md), [`minecraft 8`](../minecraft/Task.8.md),
[`tracker 4`](../tracker/Task.4.md), [`rendezvous 4`](../rendezvous/Task.4.md),
[`server 10`](../server/Task.10.md).

## 2. The three rules

**Nothing without a yes.** Telemetry is opt-in and off by default. The question is asked once, in
the companion app's first-run modal, in plain language, at a moment the user is not blocked. "Not
now" is a complete answer and is never asked again.

**Nothing outside the registry.** `rust/nodera-telemetry/src/schema.rs` is the collection policy in
executable form, and the gate is at the *receiver*: a client that is buggy, modified, or hostile
still cannot cause an undeclared value to be stored. No declared value can be free text, so a world
name, a player name, a chat line, or a file path is not representable.

**No authority, ever.** Nothing in the network reads telemetry. This is stricter than the rule the
tracker and rendezvous services live under (`../README.md` §4.3 rule 7): those at least influence
discovery. Telemetry influences nothing, which is what makes it safe to sample, throttle, or switch
off entirely.

## 3. Architecture

```
worker (the ONE emitter per player node) ─┐
nodera-tracker ───────────────────────────┤
nodera-rendezvous ────────────────────────┼─► nodera-telemetry ─► NDJSON spool ─► Vector
NoderaEndpoint ───────────────────────────┘         │                                │
                                                    │                                ▼
                                       consent gate │                            Redpanda
                                       schema gate  │                                │
                                       pseudonymise │                                ▼
                                       geolocate    ▼                           ClickHouse
                                                  (row)                     (rollups + TTLs)
                                                                                     │
                                                                       Grafana ◄───────┴──► Spark
```

The ingest decision order is "cheapest refusal first": probe → quota → parse + consent → schema
validate → pseudonymise → geolocate → write. Consent is checked before anything is stored, and the
address is resolved to a country only for batches that are already admitted.

## 4. External dependencies

| Dependency | Used for | If it is missing |
|---|---|---|
| Vector | Spool tailing, at-least-once delivery | Rows accumulate in the spool; nothing is lost until it fills |
| Redpanda | Kafka-API bus | Vector retries; the spool is the buffer |
| ClickHouse | Warehouse, rollups, retention | The bus retains until it is back |
| Grafana | Dashboards, alerting | Data keeps arriving; nobody is looking |
| Spark | Batch jobs (divergence clustering, cohorts) | Dashboards are unaffected |
| A geolocation table | Country/ASN derivation | Every row records `ZZ`/`0` — honest, and the default |

None of these are dependencies **of NoderaMC**. All of them being down costs the project insight and
costs players nothing.

## 5. Files

- `rust/nodera-telemetry/` — the ingest service (`schema`, `event`, `subject`, `geo`, `limits`,
  `sink`, `service`, `wire`, `config`, `reporter`).
- `docker/telemetry/` — compose stack, warehouse schema, Vector pipeline, Grafana provisioning,
  Spark jobs.
- Emitter code lives in the emitting categories' modules.

## 6. Task index

| Task | Title | Status |
|---|---|---|
| [1](Task.1.md) | The `nodera-telemetry` ingest service | ✅ COMPLETED |
| [2](Task.2.md) | The Big Data plane: bus, warehouse, retention | 🚧 IN PROGRESS |
| [3](Task.3.md) | Analysis, dashboards, alerting, transparency report | ⬜ NOT STARTED |

Programme plan: [`../plans/Plan.6.md`](../plans/Plan.6.md) ·
progress: [`PROGRESS.md`](PROGRESS.md) · tests: [`TESTING.md`](TESTING.md) ·
gaps: [`LIMITATIONS.md`](LIMITATIONS.md).
