# `nodera-telemetry` — telemetry ingest

The service that receives consented operational reports from peers, trackers, rendezvous services,
and Paper/Folia endpoints, and spools them for the Big Data plane in
[`docker/telemetry/`](../../docker/telemetry/README.md).

Specification: [`docs/telemetry/Task.1.md`](../../docs/telemetry/Task.1.md) ·
programme: [`docs/plans/Plan.6.md`](../../docs/plans/Plan.6.md).

```bash
nodera-telemetry --config nodera-telemetry.toml
nodera-telemetry --healthcheck 127.0.0.1:25620
nodera-telemetry --print-schema | jq      # exactly what may be collected, from the running binary
nodera-telemetry --version
```

## What it is for

**It carries no authority at all** — more strictly than the tracker and rendezvous services, which
at least influence discovery. Nothing in the network reads telemetry, no peer consults it, and
losing it entirely costs the project insight and costs players nothing. That is what makes the
privacy decisions cheap: data can be refused, sampled, or never collected without any correctness
argument to have.

## Architecture

```
frame ─► probe? ─► quota ─► parse + consent ─► schema validate ─► pseudonymise ─► geolocate ─► NDJSON
```

| Module | Responsibility |
|---|---|
| `schema.rs` | **The collection policy, in code.** Every collectable event and attribute, with a closed value domain. No free-text value is representable |
| `event.rs` | Batch parsing, consent enforcement, per-event and per-attribute validation |
| `subject.rs` | Forward-secret rotating pseudonymisation — each period mints a fresh OS-CSPRNG key, held only in memory and wiped on rotation; the install id is never stored and the operator's config carries no key material |
| `geo.rs` | Longest-prefix `cidr,country,asn` lookup; the address itself is discarded |
| `nodera_service::limits` | Per-source batch and event quotas (the service is unauthenticated by design). Shared with the tracker and rendezvous since 2026-08-06; this crate no longer has its own `limits.rs` |
| `sink.rs` | Rotating NDJSON spool — the file is the buffer, so a broker outage cannot drop a report |
| `service.rs` | The ingest decision and the reply; per-reason counters |
| `wire.rs` | `nodera-codec` framing over TCP, the sweep, and the operator counter line |
| `config.rs` | `nodera-telemetry.toml`; the pseudonymisation key is not here — it is minted in memory |
| `reporter.rs` | The **service-side** emitter (`nodera-tracker`, `nodera-rendezvous`): off unless an operator configures an endpoint, and its event type holds numbers and `'static` labels only |

## The three properties it enforces rather than promises

1. **Consent is a gate.** A batch without `consent: "granted"` writes nothing at all.
2. **The registry is the policy.** An undeclared event is rejected; an undeclared attribute is
   dropped. Both are counted and reported back to the sender.
3. **Identifiers are replaced, addresses are discarded, and the link key does not outlive its
   period.** `install` becomes `HMAC(period_key, period ‖ source ‖ install)[..8]`, where
   `period_key` is minted fresh from the OS CSPRNG each rotation period, held only in process memory,
   and wiped on rotation; the source IP becomes a country and an ASN. The configuration carries no
   key material, so a past period's subjects cannot be recomputed by anyone — including the operator.

Why unsigned and unauthenticated: signing a report with a node's Ed25519 identity would tie every
measurement to the key the rest of the network knows that peer by — precisely the linkage this
pipeline exists to avoid. The cost is that anyone can submit, so quotas are the only defence and
the data is treated as aggregate-only, never as evidence about a particular node.

## Tests

```bash
cd rust && cargo test -p nodera-telemetry     # 93 tests
```

The decisive ones: `a_batch_without_consent_writes_nothing_and_says_why`,
`a_row_carries_the_country_and_never_the_address`,
`the_install_identifier_is_replaced_by_a_rotating_subject`,
`after_rotation_and_restart_a_previous_period_subject_is_not_reproducible_from_configuration`
(the L-72 exit test — past periods unrecoverable from configuration alone),
`no_attribute_accepts_free_text`, and the end-to-end
`a_batch_submitted_over_tcp_is_answered_and_written`.
