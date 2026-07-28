<!-- AI-AGENT-INSTRUCTION: This is an ACTIVE PROGRAMME PLAN — the scoping document for the `telemetry`
     category (docs/telemetry/) and for the emitter tasks it added to six other categories. The
     per-task specifications are the source of truth for SCOPE and STATUS; this file is the source
     of truth for the PRIVACY MODEL and the LOCKED DECISIONS behind them. When a task file
     contradicts a locked decision here, the task file is the bug — unless the decision is
     explicitly re-opened in §10. A change that widens what is collected MUST update §4 and
     rust/nodera-telemetry/src/schema.rs in the same commit. Keep every AI-AGENT-INSTRUCTION
     comment intact. -->

> **Active programme plan.** Per-task specifications live in
> [`../telemetry/Task.<n>.md`](../telemetry/Task.0.md) and in the emitter tasks listed in §6; the
> index is [`../ROADMAP.md`](../ROADMAP.md) and the documentation format is
> [`../README.md`](../README.md). This file becomes historical when telemetry task 3 closes.

# Plan 6 — Telemetry: consented measurement of a decentralized network

**Status:** 🚧 IN PROGRESS (7 of 10 tasks complete) · **Opened:** 2026-07-25 ·
**Category:** [`telemetry`](../telemetry/Task.0.md)

---

## 0. The one-sentence version

Peers, trackers, rendezvous services, and Paper/Folia endpoints send **opt-in, schema-bounded,
de-identified** operational events to a Rust ingest service, which spools them into a Big Data plane
(Vector → Redpanda → ClickHouse → Grafana/Spark) that answers the four questions the project cannot
currently answer at all: *does it work in the wild, for whom, over which paths, and where is it
failing?*

---

## 1. Why this exists

NoderaMC is a decentralized system, which means the project has **no vantage point**. A traditional
game has one server operator who can see the whole population; Nodera deliberately has none. The
consequences are already visible in the repository:

- [`docs/engine/LIMITATIONS.md`](../engine/LIMITATIONS.md) carries determinism rows whose exit tests
  are *headless*. Whether two real player machines — different CPUs, JVMs, mod sets — produce the
  same `StateRoot` is currently unknown outside CI.
- [`docs/rendezvous/Task.3.md`](../rendezvous/Task.3.md) ("live cross-internet proof") is blocked in
  part because nobody can measure hole-punch success rates across real NAT populations.
- Every "proven live once by hand" claim in [`../ROADMAP.md`](../ROADMAP.md) is a sample size of one.

Telemetry is how a project with no central server gets a population-level answer. It is *not* how it
gets authority: see §2.

### 1.1 The four questions

| # | Question | The data that answers it | Where it lands |
|---|---|---|---|
| 1 | **Does the central bet hold in the wild?** | `engine.divergence` fingerprints, rules version, ghost fallbacks, interference revocations | `divergence_daily` |
| 2 | **Can players actually reach each other?** | `world.join` outcome × path (direct/punched/relayed) × country, `rendezvous.punch` by NAT pair | `join_outcomes_hourly` |
| 3 | **What does running a node cost?** | `net.traffic`, `storage.archive`, `engine.tick` TPS buckets, region counts | `events_hourly` |
| 4 | **What do people use, and where are they?** | `feature.use`, `session.*`, coarse country + ASN | `events_hourly` |

---

## 2. Locked decisions

These are binding on every task in this programme. Each one is a decision that is *cheap now and
extremely expensive later*, which is why they are locked before the emitters are written.

**D1 — Opt-in, default off.** No telemetry is collected until a person says yes. The first-run modal
in the companion app is the only place that question is asked, and "not now" is a complete answer
that is never asked again. Rationale: an opt-out default would make every subsequent privacy claim
depend on people reading a settings screen, and the project's entire pitch is that players are not
the product of a central operator.

**D2 — Telemetry has no authority, ever.** Nothing in the network reads it. No peer, tracker,
rendezvous, or committee decision may consult a telemetry value. This is `docs/README.md` §4.3
rule 7 taken further: the tracker at least influences discovery; telemetry influences nothing. It
follows that the pipeline can be sampled, throttled, or switched off with no correctness argument.

**D3 — Reports are unsigned and pseudonymous.** A telemetry report is *not* signed with the node's
Ed25519 identity. Signing would bind every measurement to the key the whole network knows that peer
by, which is exactly the linkage this design exists to prevent. The price — anyone can submit — is
paid with per-source quotas and with treating the data as aggregate-only evidence. Given D2, a
poisoned aggregate costs the project a wrong graph, not a wrong world.

**D4 — The registry is the collection policy, and it is enforced at ingest.** Only events and
attributes declared in `rust/nodera-telemetry/src/schema.rs` can be stored. Undeclared *events* are
rejected; undeclared *attributes* are dropped (so a newer client keeps reporting the fields this
build does know). The gate is at the receiver, not in the emitters, because the emitters are the
part that is hardest to audit: three languages, several processes, and a mod anyone can fork.

**D5 — No free text, anywhere.** Every value is an integer in a declared range, a bool, a member of
a closed enum, a fixed-length hex fingerprint, or a bounded version string. A world name, a player
name, a chat line, a file path, or a stack-trace message is *not representable*. This is what makes
"we do not collect that" a structural claim rather than a promise.

**D6 — Buckets, not measurements.** Sizes, durations, and rates are bucketed by the emitter and
re-bounded at ingest. A byte-exact world size or a millisecond-exact session length is a
fingerprint; a bucket index is a statistic.

**D7 — Identifiers rotate; addresses are discarded.** The install id becomes a pseudonymous
subject `HMAC(period_key, period ‖ source ‖ install)[..8]` that rotates every period, and neither
the key nor the source address ever enters the warehouse. The `period_key` is minted fresh from the
OS CSPRNG the first time a period is observed and held **only in process memory**, wiped the instant
the period rolls, so a past period's subjects cannot be recomputed by anyone — including the
operator, whose configuration carries no key material at all. The source IP is used for the quota
and for a country/ASN lookup, then dropped — there is no column for it, which is stronger than a
policy about not filling one in. *(Forward secrecy across rotation landed with the L-72 retirement;
see §10, 2026-07-28.)*

**D8 — Retention is a table property.** 30 days for raw rows, 400 for aggregates that contain no
subject. A deletion policy that lives in a cron job is one that silently stops running.

**D9 — Every consent decision is revocable and visible.** The companion app shows what is collected
(from the running ingest binary's own `--print-schema`, not from a marketing page), and turning it
off takes effect at the emitter — the worker stops collecting, not merely stops sending.

**D10 — The stack is never a dependency.** A peer whose telemetry endpoint is unreachable keeps
playing, hosting, seeding, and validating exactly as before. Reports queue in a bounded local spool
and are dropped when it fills. CI must prove this, not assume it.

---

## 3. Architecture

```
   mod ──┐
         ├─► worker (the ONE emitter for a player's node) ──┐
   app ──┘                                                  │
                                                            ├─► nodera-telemetry ─► NDJSON spool
   nodera-tracker ──────────────────────────────────────────┤          │
   nodera-rendezvous ───────────────────────────────────────┤          ▼
   NoderaEndpoint (Paper/Folia) ────────────────────────────┘      Vector ─► Redpanda ─► ClickHouse
                                                                                            │
                                                                          Grafana ◄─────────┤
                                                                          Spark   ◄─────────┘
```

**One emitter per node, and it is the worker.** The mod, the app, and the worker all observe things
worth reporting, but three emitters would mean three consent checks, three spools, and three chances
to disagree about whether the user said yes. The mod and the app *hand events to the worker* over
the existing control protocol; the worker owns consent, sampling, batching, the spool, and the send.

**The services report about themselves, and their operators opt in separately.** A community tracker
operator has not consented to anything by running the binary: service telemetry is off unless
`telemetry_endpoint` is configured. The project's own deployments turn it on.

---

## 4. What is collected (the complete list)

Authoritative form: `nodera-telemetry --print-schema`. Grouped by question:

| Group | Events | Notable attributes |
|---|---|---|
| Environment | `service.start`, `service.stop`, `session.start`, `session.end` | OS, arch, CPU/RAM buckets, Java major, mod/MC/loader versions, companion present |
| The validated lane | `region.ownership`, `engine.tick`, `engine.divergence`, `engine.interference` | regions owned, committee size, certification (certified/pending/solo), TPS bucket, divergence phase + 64-bit fingerprint + rules version |
| Reachability | `world.share`, `world.join`, `world.rehost`, `net.handshake` | ok, path (direct/punched/relayed), failure class, seconds bucket, password-protected (bool) |
| Cost | `net.traffic`, `storage.archive` | up/down MB buckets, peer counts, relayed peer counts, archive MB bucket, pieces-held percent |
| Usage | `feature.use`, `consent.change`, `error.report` | feature enum, count, error kind + fingerprint + fatal |
| Services | `tracker.window`, `tracker.world_health`, `rendezvous.window`, `rendezvous.punch`, `rendezvous.relay`, `endpoint.window`, `service.latency` | throughput counters, world health counts, NAT-pair punch success, relay volume buckets, latency percentiles |

**Explicitly never collected** — and structurally impossible under D5: world names, world seeds,
player names or UUIDs, chat, coordinates, file paths, IP addresses, node ids, public keys, world
passwords or any derivative, and the contents of any region, chunk, or inventory.

### 4.1 Approximate geolocation, precisely stated

Derived at ingest from the source address: **ISO-3166-1 alpha-2 country and ASN**, nothing finer.
No city, no coordinates, no truncated address. The table is operator-supplied; without one every row
is `ZZ`/`0`. The address itself never reaches the sink.

---

## 5. The Big Data plane

`docker/telemetry/docker-compose.yml`. Component choices and their justifications are in that
directory's README; the shape is Vector (collection) → Redpanda (bus) → ClickHouse (warehouse,
rollups, retention TTLs) → Grafana (dashboards/alerts) with Spark for batch jobs that must not run
on the warehouse serving the dashboards.

The one non-obvious decision: **the ingest service writes files, not Kafka.** A broker outage must
never block ingest or drop a consented report, and the crate must stay small enough that a sceptical
reader can audit the privacy claims in an afternoon. The file is the buffer; Vector owns delivery.

---

## 6. Task breakdown

| # | Task | Category | Delivers |
|---|---|---|---|
| 1 | [`telemetry 1`](../telemetry/Task.1.md) | telemetry | ✅ The `nodera-telemetry` ingest service (registry, consent gate, pseudonymiser, geo, quotas, spool) |
| 2 | [`telemetry 2`](../telemetry/Task.2.md) | telemetry | The Big Data plane: compose stack, warehouse schema, rollups, retention |
| 3 | [`telemetry 3`](../telemetry/Task.3.md) | telemetry | Analysis, dashboards, alerting, and the public transparency report |
| 4 | [`network 12`](../network/Task.12.md) | network | ✅ Minecraft-free emitter core: event model, bucketing, consent gate, bounded spool, sender |
| 5 | [`worker 5`](../worker/Task.5.md) | worker | ✅ The node's single emitter + `NODERA-TELEMETRY` control verb |
| 6 | [`app 5`](../app/Task.5.md) | app | 🚧 First-run consent modal + Privacy settings landed; a component test remains |
| 7 | [`minecraft 8`](../minecraft/Task.8.md) | minecraft | ✅ (headless) In-game consent mirror + gameplay events handed to the worker |
| 8 | [`tracker 4`](../tracker/Task.4.md) | tracker | ✅ Service-side reporter, operator opt-in |
| 9 | [`rendezvous 4`](../rendezvous/Task.4.md) | rendezvous | ✅ Service-side reporter, NAT-pair punch statistics |
| 10 | [`server 10`](../server/Task.10.md) | server | ⬜ Endpoint reporter with the tenant-privacy boundary (the `server` category has not started) |

Dependency order: telemetry 1 → network 12 → worker 5 → {app 5, minecraft 8} and telemetry 1 →
{tracker 4, rendezvous 4, server 10}; telemetry 2 → telemetry 3.

---

## 7. Acceptance for the programme

1. ✅ A fresh install collects **nothing** until the question is answered yes —
   `scripts/e2e-telemetry.sh` **T1** lets two collection windows pass against a worker nobody asked
   and asserts the collector's spool is still empty.
2. ✅ Turning telemetry off stops **collection** at the emitter, not just transmission (**T5**: the
   queue is cleared and the installation identifier is deleted).
3. ✅ `nodera-telemetry --print-schema` and the Java registry agree, enforced by
   `TelemetryRegistryMirrorTest` against the real binary (**T2**).
4. ✅ No stored row contains an address, a node id, or a free-text field (**T3**, asserted on the
   bytes on disk; and structurally, by the registry having no free-text value).
5. ✅ A node with an unreachable collector is unchanged (**T6**: the full `NODERA-STATE` answer is
   compared field by field across the collector being killed).
6. ⬜ The four questions in §1.1 are answerable from the shipped dashboards — this needs a
   population that has opted in, which no deployment has yet.

---

## 8. Risks

| Risk | Why it matters | Mitigation |
|---|---|---|
| **Consent fatigue** — a modal people click through | Consent that was not read is not consent | One question, plain language, at a moment the user is not blocked; "not now" is final and never re-asked |
| **Scope creep in the registry** | Every added field is a privacy claim to re-justify | D4 + a review rule: a registry change is a documentation change in the same commit |
| **Sampling bias** | Only players who opt in are measured, and they are not a random sample | Stated in every report; never used to claim absolute population size |
| **The stack becoming load-bearing** | A dashboard nobody can lose becomes a dependency nobody can remove | D10 + `TelemetryOutageIT` |
| ~~**Operator-held secret**~~ — retired | ~~The pseudonymisation secret can re-link a current period~~ | **Retired** (L-72, 2026-07-28): the derivation moved to a per-period CSPRNG key held only in process memory and wiped on rotation, so past periods are unrecoverable even to the operator. Evidence in [`../telemetry/LIMITATIONS.fixed.md`](../telemetry/LIMITATIONS.fixed.md) |

---

## 9. Relationship to existing work

This programme **reuses** [`network 11`](../network/Task.11.md) (telemetry core: `TelemetrySnapshot`,
meters, per-peer traffic, region certification) rather than duplicating it: those objects already
compute almost every number the peer events carry. Network 11 answers "what is this node doing right
now, for its own screens"; this programme answers "what is the population doing, over time" — the
same measurements, a different consumer, and a hard privacy boundary between them.

---

## 10. Re-opened decisions

### 2026-07-28 — D7 re-opened: forward secrecy for the pseudonymisation key

**Re-opened:** the *form* of D7's identifier rotation, not its intent. The intent (a stored subject
cannot be followed across periods, and the key never enters the warehouse) was always locked; what
changed is the *where* of the key.

**Why.** Under the original D7 a single persistent `subject_secret` derived every period's subjects
via `HMAC(secret ‖ period, …)`. The secret never entered the warehouse, but the **operator held it
in configuration**, so during any *current* rotation period the operator could recompute the
install-id→subject mapping for that period — and, worse, for any past period whose secret had not
been rotated. That was recorded openly as limitation **L-72** with "move the key into memory" as the
elimination path.

**Replacement.** The pseudonymiser now mints a fresh 32-byte key from `/dev/urandom` the first time
a period is observed, caches it for the period, and wipes it (zero-then-drop) the moment the period
rolls — eagerly from the ingest sweep, lazily from the next batch. The configuration carries no key
material whatsoever; a restart mints a brand-new key for whatever period it boots into. Past periods
are unrecoverable even to the operator, because the key that derived them no longer exists anywhere.

**Cost, stated plainly.** Subjects are no longer stable across a process restart *within* a period —
a restart mints a new key and the same install id maps to a new subject for the rest of that period.
That is the intended trade for forward secrecy and is strictly stronger than the persistent-secret
design. Cohort/retention analysis within a single uninterrupted period is unaffected.

**Evidence.** `subject::tests::after_rotation_and_restart_a_previous_period_subject_is_not_reproducible_from_configuration`
and `service::tests::a_previous_period_subject_in_a_written_row_is_not_reproducible_after_restart`
(the L-72 exit test, driven through the real ingest write path with deterministic fake entropy/clock).
L-72 moved to [`../telemetry/LIMITATIONS.fixed.md`](../telemetry/LIMITATIONS.fixed.md).
