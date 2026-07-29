# The Nodera telemetry Big Data plane

<!-- AI-AGENT-INSTRUCTION: This directory is a DEPLOYMENT, not a dependency. Nothing in NoderaMC may
     require it to be running: peers, trackers, and rendezvous services must all work identically
     when every container here is down. If a change makes any part of the game or the network
     depend on this stack, the change is wrong. The task specifications are docs/telemetry/. -->

```
nodera-telemetry ──NDJSON files──► Vector ──Kafka API──► Redpanda ──► ClickHouse ──► Grafana
                                                                          ▲
                                                                        Spark (batch jobs)
```

| Component | Role | Why this one |
|---|---|---|
| `ingest` | The only component that sees client data before de-identification | Ours: `rust/nodera-telemetry` |
| `vector` | Tails the spool, delivers at-least-once to the bus | Checkpointing, retries and backpressure are the hard part; it does them |
| `redpanda` | Kafka-API log bus | One binary, no ZooKeeper — a community operator can run this on one machine |
| `clickhouse` | Warehouse + rollups + retention TTLs | Telemetry queries are `GROUP BY event, country, day` over narrow rows |
| `grafana` | Dashboards and alerting | Provisioned datasource + the overview dashboard |
| `spark` | Batch jobs (divergence clustering, cohorts) | Keeps heavy passes off the warehouse serving the dashboards |
| `edge` | TLS termination (`--profile public`) | Keeps a TLS stack out of the ingest binary |

## Run it

```bash
cp .env.example .env            # then fill in the three secrets
docker compose up -d            # ingest + vector + redpanda + clickhouse + grafana
docker compose --profile ops up -d          # …plus the Redpanda console
docker compose --profile analytics up -d    # …plus Spark master + worker
docker compose --profile public up -d       # …plus TLS termination on 25621
```

Grafana lands on <http://localhost:3000> (admin / `GRAFANA_PASSWORD`), ClickHouse on
<http://localhost:8123>, the Redpanda console on <http://localhost:8080>.

Point a service at it with `telemetry_endpoint = "tcp://<host>:25620"` (or `25621` behind `edge`).

## What actually reaches this stack

Only what `rust/nodera-telemetry/src/schema.rs` declares. Ask the running binary:

```bash
docker compose exec ingest nodera-telemetry --print-schema | jq
```

No world names, no player names or UUIDs, no chat, no coordinates, no file paths, no IP addresses,
no free text of any kind. The install identifier is replaced with a **daily-rotating HMAC subject**
before anything is written, and the source address becomes a country code and an ASN and is then
discarded. The full argument is `docs/telemetry/Task.1.md`; the gaps that remain are
`docs/telemetry/LIMITATIONS.md`.

## Operating notes

- **No pseudonymisation secret is held on disk.** Each rotation period the ingest process mints a
  fresh key from the OS CSPRNG and holds it only in memory, wiping it the moment the period rolls.
  That is what retired `docs/telemetry/LIMITATIONS.fixed.md` L-72: an operator with the full
  deployment config cannot reproduce a previous period's subjects, because the key that derived them
  no longer exists anywhere. The cost is that subjects are not stable across a process restart
  within a period — an acceptable trade for forward secrecy.
- **Retention is a table TTL** (30 days raw, 400 days aggregated) in `clickhouse/init/01-schema.sql`,
  not a cron job. Changing it is a schema change, reviewed like one.
- **Geolocation is operator-supplied.** `ingest/geo.csv` ships empty; without a table every row is
  recorded as `ZZ`/`0`. Nodera bundles no geolocation database — that is a licensing decision, and
  a service that silently degrades to "unknown" is the honest default.
- **The stack is not required.** If it is down, emitters keep their reports in their own spools and
  eventually drop them. No peer, tracker, or rendezvous service changes behaviour.
