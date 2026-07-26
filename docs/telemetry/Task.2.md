# Telemetry Task 2 — The Big Data Plane: Bus, Warehouse, Retention

<!-- AI-AGENT-INSTRUCTION: This task owns STORAGE and RETENTION. Two rules: (1) retention lives in the
     warehouse schema as a TTL, never in a cleanup job someone has to remember; (2) no long-lived
     table may carry `subject` outside an aggregate state. A change that lengthens retention or adds
     a subject-carrying table is a privacy change and must update ../plans/Plan.6.md §2 D8 in the same
     commit. Keep this header's status accurate. -->

**Status:** 🚧 IN PROGRESS
**Category:** telemetry · **Owns:** L-74 · **Last audit:** 2026-07-25
**Depends on:** [telemetry 1](Task.1.md)
**Consumed by:** [telemetry 3](Task.3.md)

---

## Goal

The stack that turns a spool directory into queryable history: collection with at-least-once
delivery, a log bus, a column store with rollups and retention expressed as table TTLs, and a
single `docker compose up -d` that stands the whole thing up on one machine.

## Status detail

Landed: `docker/telemetry/docker-compose.yml` (ingest, Vector, Redpanda, ClickHouse, Grafana, plus
`ops`/`analytics`/`public` profiles), the Vector pipeline, the warehouse schema with three
materialised rollups and their TTLs, the ingest Dockerfile, HAProxy TLS termination, and the
example environment.

The smoke test is **green in CI**: `scripts/telemetry-stack.sh smoke` submits a framed batch to the
running collector, polls ClickHouse until the row appears, and asserts the warehouse schema has no
column that could hold an address. Run output:

```
PASS the collector accepted the batch
PASS the row is queryable in ClickHouse (1 matching row(s))
PASS the warehouse schema has no column that could hold an address
```

Getting there took four fixes, and every one of them was a real defect this job existed to find: the
image pinned the workspace's `rust-version` floor instead of the toolchain (a transitive dependency
needed edition 2024); `v_join_success` aliased a merged column after the aggregate state it merges,
so ClickHouse refused the whole schema file; the healthcheck probed with `wget`, which that image
does not ship; and the alpine ClickHouse variant would not start at all. The stack had *looked*
fine until something ran it.

Remaining: the Parquet export that feeds the Spark jobs, a TTL-expiry assertion, and operator notes
for real volume rather than a laptop.

## Dependencies

- [telemetry 1](Task.1.md) — the service whose spool this plane consumes.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | Compose stack with profiles (`ops`, `analytics`, `public`) | ✅ |
| 2 | Vector pipeline: spool tail → Redpanda, unparsable lines quarantined | ✅ |
| 3 | ClickHouse `events` table + Kafka-engine consumer + materialised view | ✅ |
| 4 | Rollups: `events_hourly`, `join_outcomes_hourly`, `divergence_daily` | ✅ |
| 5 | Retention as TTLs — 30 days raw, 400 days aggregated | ✅ |
| 6 | Ingest container image built from the repository root (with `VERSION`) | ✅ |
| 7 | TLS termination profile (HAProxy, TCP mode) | ✅ |
| 8 | CI smoke test: batch in → row queryable (`.github/workflows/telemetry.yml`, `scripts/telemetry-stack.sh smoke`) | ✅ |
| 9 | Parquet export feeding the Spark jobs | ⬜ |
| 10 | Operator notes: sizing, backup, secret rotation | ⬜ |

## Design

**Redpanda rather than Kafka.** One binary, no ZooKeeper or KRaft ceremony, the same protocol. The
target operator is the project itself and, eventually, anyone who wants to run their own analytics
for their own community — both of which are one-machine deployments.

**ClickHouse because the queries are all `GROUP BY`.** Every question in `../plans/Plan.6.md` §1.1 is
an aggregate over billions of narrow rows sliced by event, country, and day. That is the shape a
column store answers in milliseconds and a row store answers in minutes.

**Rollups exist so the raw table can expire.** `events_hourly`, `join_outcomes_hourly`, and
`divergence_daily` aggregate `subject` away through `uniqState`, so year-long trend data contains no
per-installation identifier *at all* — not a rotated one, not a hashed one. The 30-day raw table is
the only place a subject exists, and it deletes itself.

**Retention is a table TTL.** A deletion policy in cron is a deletion policy that stops running one
day and nobody notices for a year. Expressed as `TTL`, it is enforced by the same engine that serves
the queries, and changing it is a schema change reviewed like one.

**Attributes stay typed Maps.** The ingest service already split them into `num`/`str`/`flag`, so a
query reads `num['tps_bucket']` with no per-row JSON parsing, and a value that changed type between
two client builds shows up as a column mismatch rather than a silent coercion.

**Spark reads exports, not the warehouse.** Divergence clustering is an all-pairs pass with an
iterative grouping step; running it against the instance that serves Grafana turns a dashboard
refresh into a timeout for everyone.

**Nothing here is a dependency of NoderaMC.** Every container can be down without any peer, tracker,
or rendezvous service behaving differently — the property [`worker 5`](../worker/Task.5.md) proves
with `TelemetryOutageIT`.

## Files

- `docker/telemetry/docker-compose.yml`, `Dockerfile`, `README.md`, `.env.example`
- `docker/telemetry/vector/vector.yaml`
- `docker/telemetry/clickhouse/init/01-schema.sql`
- `docker/telemetry/grafana/provisioning/`, `docker/telemetry/grafana/dashboards/`
- `docker/telemetry/edge/haproxy.cfg`, `docker/telemetry/spark/jobs/`

## Testing

- Schema validity: the init SQL applies cleanly to an empty ClickHouse (manual today, CI in
  deliverable 8).
- Planned `telemetry-compose-smoke` CI job: `docker compose up -d`, submit one batch with the
  reference client, poll ClickHouse until the row appears, assert `country`/`subject` shapes and the
  absence of any address column, then `docker compose down -v`.
- Retention: a row inserted with a back-dated `received_at` is gone after a TTL merge; asserted in
  the same job.

## Acceptance criteria

1. ✅ One command stands up ingest → bus → warehouse → dashboards.
2. ✅ Retention is expressed as table TTLs, not as an external job.
3. ✅ No table that outlives 30 days carries a subject outside an aggregate state.
4. 🚧 CI proves a submitted batch becomes a queryable row (green); the **expiry** half still needs
   a TTL-merge assertion.
5. ⬜ Spark jobs read an export rather than the serving warehouse.

## Limitations

Owns **L-74** — the compose stack is a single-node deployment with no replication or backup story,
so a disk loss loses collected history. See [`LIMITATIONS.md`](LIMITATIONS.md).
