# Telemetry Task 3 — Analysis, Dashboards, Alerting, Transparency Report

<!-- AI-AGENT-INSTRUCTION: This task owns what the data is USED FOR. Two rules: (1) every published
     number states that it comes from an opt-in sample and is never presented as a population count;
     (2) no dashboard, alert, or report may be built on a query that isolates an individual
     installation — if a panel only makes sense for one subject, it is the wrong panel. Keep this
     header's status accurate. -->

**Status:** ⬜ NOT STARTED
**Category:** telemetry · **Owns:** L-75 · **Last audit:** 2026-07-28
**Depends on:** [telemetry 2](Task.2.md), [worker 5](../peer/Task.5.md)
**Consumed by:** the engine, network, and rendezvous lanes; the public

---

## Goal

Turn the warehouse into decisions: the dashboards that answer the four questions, the alerts that
fire when the answers change for the worse, the batch jobs that group a divergence storm into one
bug, and a published transparency report so the people who opted in can see what their data bought.

## Status detail

Not started. One dashboard (`nodera-overview`) and one Spark job (`divergence_clusters.py`) ship
with [telemetry 2](Task.2.md) as the working skeleton; everything below is this task.

The receiver is finished and the emitters have landed in their own categories
([network 12](../network/Task.12.md), [worker 5](../peer/Task.5.md), [minecraft 8](../minecraft/Task.8.md),
[tracker 4](../tracker/Task.4.md), [rendezvous 4](../rendezvous/Task.4.md), [app 5](../app/Task.5.md)).
What is missing is not mechanism but a **population that has opted in** — which is why **L-75** stays
OPEN and this task stays ⬜ until a deployment has real reports to analyse.

## Dependencies

- [telemetry 2](Task.2.md) — the warehouse and rollups being queried.
- [worker 5](../peer/Task.5.md) — without real emitters there is nothing to analyse.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | **Determinism dashboard** — divergence fingerprints by rules version, agent, phase; new-fingerprint rate | ⬜ |
| 2 | **Reachability dashboard** — join success by path and country; punch success by NAT pair; relay share | ⬜ |
| 3 | **Cost dashboard** — traffic, archive size, TPS distribution, region counts per node class | ⬜ |
| 4 | **Adoption dashboard** — reporting installations, versions in the wild, feature usage, upgrade curve | ⬜ |
| 5 | **Service health dashboard** — tracker/rendezvous throughput, quota rejections, latency percentiles | ⬜ |
| 6 | Alerts: new divergence fingerprint on ≥N installations; join success drop; relay share spike | ⬜ |
| 7 | Spark jobs: divergence clustering (skeleton ✅), cohort retention, upgrade funnel | 🚧 |
| 8 | Weekly automated summary posted to the project's own channel | ⬜ |
| 9 | **Public transparency report** — what was collected, how much, what it changed | ⬜ |
| 10 | Query cookbook so a contributor can answer a new question without asking | ⬜ |

## Design

**The four questions, not "a dashboard".** Panels are built to answer
`../plans/Plan.6.md` §1.1 — does the bet hold, can players reach each other, what does a node cost,
who is out there. A panel that answers none of them is decoration and is deleted.

**Alert on *new*, not on *high*.** A steady rate of a known divergence fingerprint is a tracked bug;
the *first* appearance of an unknown fingerprint on several independent installations is a live
regression, and it is the only telemetry signal that should wake anyone up.

**Never present opt-in counts as population counts.** Every published figure says "of installations
that share telemetry". The sample is self-selected and the direction of its bias is unknown; a graph
that hides that is worse than no graph.

**k-anonymity before publication.** Public breakdowns suppress buckets below a threshold — a country
with three reporting installations is not a statistic, it is three people.

**No panel may isolate one installation.** Subjects exist so that "how many distinct installations"
is answerable; a query filtered to a single subject is outside the purpose people consented to, and
a dashboard that does it teaches everyone that it is normal.

**The transparency report is a deliverable, not a gesture.** People who opted in are owed the
outcome: what was collected, in what volume, which bugs it found, and what was deleted. It is also
the honest place to publish the limitation rows in `LIMITATIONS.md`.

## Files

- `docker/telemetry/grafana/dashboards/*.json`
- `docker/telemetry/grafana/provisioning/alerting/`
- `docker/telemetry/spark/jobs/*.py`
- `docs/telemetry/REPORT.md` (the transparency report, once there is data to report)

## Testing

- Dashboard JSON is provisioned and loads against an empty warehouse (no panel errors on zero rows).
- Each alert rule has a replayable fixture: given a synthetic day of rows, it fires exactly once.
- Spark jobs run against a fixture Parquet export in CI and produce a stable ranking.
- A `k`-anonymity test on the report generator: a bucket below the threshold is suppressed, not
  rounded.

## Acceptance criteria

1. ⬜ Each of the four questions is answerable from a shipped dashboard, from a cold start.
2. ⬜ A new divergence fingerprint on ≥N installations raises an alert within one hour.
3. ⬜ No shipped query filters to a single subject.
4. ⬜ Every published figure is labelled as an opt-in sample.
5. ⬜ Sub-threshold buckets are suppressed in anything published.
6. ⬜ The first transparency report is published with the first release that ships telemetry.

## Limitations

Owns **L-75** — nothing has been collected against a real opted-in population yet, so every claim
about what the pipeline *shows* is untested against real data; the receiver is proven, the
population is not. See [`LIMITATIONS.md`](LIMITATIONS.md).
