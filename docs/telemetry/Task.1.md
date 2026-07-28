# Telemetry Task 1 — The `nodera-telemetry` Ingest Service

<!-- AI-AGENT-INSTRUCTION: This task owns the GATE. Every privacy property of the whole programme is
     enforced in this crate, at the receiver, so that it holds even for a client that is buggy,
     modified, or hostile. Adding an event or an attribute to schema.rs is a POLICY change: it must
     land with the ../plans/Plan.6.md §4 table in the same commit, and a free-text value may never be
     added at all. Keep this header's status accurate. -->

**Status:** ✅ COMPLETED
**Category:** telemetry · **Owns:** L-73 · **Last audit:** 2026-07-28
**Depends on:** [network 1](../network/Task.1.md) (framing)
**Consumed by:** [telemetry 2](Task.2.md), [network 12](../network/Task.12.md), [tracker 4](../tracker/Task.4.md), [rendezvous 4](../rendezvous/Task.4.md), [worker 5](../worker/Task.5.md)

---

## Goal

A standalone Rust service that receives telemetry batches over the project's existing framing,
refuses everything it was not explicitly permitted to keep, de-identifies what is left, and spools it
as NDJSON for the Big Data plane — small enough that a sceptical reader can audit every privacy claim
in one sitting.

## Status detail

Complete and green: **93 tests** (`cargo test -p nodera-telemetry`), clippy clean at `-D warnings`.

The crate is a **library as well as a binary**: `reporter.rs` — the service-side emitter used by
`nodera-tracker` and `nodera-rendezvous` — lives here so the thing that builds events and the thing
that validates them cannot drift the way two hand-maintained copies would.

Landed: the event registry with closed value domains; batch parsing with a hard consent gate;
per-event rejection and per-attribute dropping with counted reasons; **forward-secret rotating
pseudonymisation** (per-period CSPRNG key, memory-only, wiped on rotation — L-72 retired);
longest-prefix country/ASN lookup with the address discarded; per-source batch and event quotas;
rotating NDJSON spool; the framed TCP surface with a probe frame; `--print-schema`, `--healthcheck`,
`--print-env`, `--version`; configuration whose only privacy knob is the rotation period, because the
pseudonymisation key is no longer an operator-supplied secret.

The decisive test is `wire::tests::a_batch_submitted_over_tcp_is_answered_and_written` — framing,
service, and sink only agree in practice if a real frame produces a real reply and a real row.

## Dependencies

- [network 1](../network/Task.1.md) — `nodera-codec`'s `u32`-length framing, reused so an emitter
  writes telemetry with the same writer it already has.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | Event registry (`schema.rs`) — closed value domains, no free text representable | ✅ |
| 2 | Batch envelope + consent gate + validation (`event.rs`) | ✅ |
| 3 | Forward-secret rotating pseudonymisation (`subject.rs`) | ✅ |
| 4 | Coarse geolocation, address discarded (`geo.rs`) | ✅ |
| 5 | Per-source batch/event quotas (`limits.rs`) | ✅ |
| 6 | Rotating NDJSON spool (`sink.rs`) | ✅ |
| 7 | Ingest decision + honest reply + counters (`service.rs`) | ✅ |
| 8 | Framed TCP surface, sweep, operator log line (`wire.rs`) | ✅ |
| 9 | `--print-schema` / `--healthcheck` / `--version`; secret-or-refuse config | ✅ |
| 10 | Crate README + this specification | ✅ |
| 11 | `reporter` — the shared service-side emitter (`ServiceEvent`: numbers and `'static` labels only) | ✅ |

## Design

**The gate is at the receiver, not in the emitters.** Emitters are the hardest part of the system to
audit — three languages, several processes, and a mod anyone can fork. Putting validation at ingest
makes "NoderaMC stores exactly this registry" true regardless of what any client does, and it makes
the claim checkable by reading one crate.

**Unknown event ⇒ reject; unknown attribute ⇒ drop.** The asymmetry is deliberate. A newer client
that adds an attribute must not lose its entire event stream, but the extra value must still never be
written. Both outcomes are counted and reported back, so a fleet cannot silently believe it is
reporting something the receiver is discarding.

**Out-of-range values are rejected, not clamped.** A clamped value looks like a real measurement and
would quietly bias every aggregate; a rejected one shows up as a counted reason.

**Reports are unsigned.** Signing with a node's Ed25519 identity would tie every measurement to the
key the network knows that peer by — the exact linkage the programme exists to avoid
(`../plans/Plan.6.md` D3). The cost is an unauthenticated surface, paid for with quotas and with
treating telemetry as aggregate-only evidence. Since telemetry has no authority (D2), a poisoned
aggregate costs a wrong graph, never a wrong world.

**The install id is never stored, and the key that links it back does not survive rotation.**
`HMAC(period_key, period ‖ source ‖ install)[..8]` rotates every period, where `period_key` is minted
fresh from the OS CSPRNG the first time a period is observed and held only in process memory, wiped
the moment the period rolls. The configuration carries no key material at all, so a warehouse dump
plus the operator's full config cannot walk a stored subject back to its install id, and no subject
can be followed across periods by anyone — including the analysts who legitimately query it. This is
forward secrecy across rotation; it retired L-72 (see
[`../plans/Plan.6.md`](../plans/Plan.6.md) §10).

**The service writes files, not Kafka.** A broker outage must never block ingest or drop a consented
report, and a Kafka client would drag a heavyweight native dependency into a workspace that
deliberately has none. The file is the buffer; Vector owns delivery.

**JSON bodies inside canonical framing.** The one deliberate departure from the frozen encoding
contract: rows leave the Nodera world immediately for a warehouse that speaks JSON, and a canonical
round trip on both ends would buy byte-exactness for data whose entire purpose is aggregation. The
*framing* stays shared, so operators debug one framing rule for the whole system.

**A probe needs no consent, no install id, and no quota slot.** Monitoring polls every few seconds;
if liveness checks spent the same budget as real clients, a healthy service would rate-limit itself.

## Files

- `rust/nodera-telemetry/src/{schema,event,subject,geo,limits,sink,service,wire,config,reporter,main}.rs`
- `rust/nodera-telemetry/{Cargo.toml,build.rs,README.md}`
- `docker/telemetry/ingest/nodera-telemetry.toml` — the deployment's configuration

## Testing

```bash
cd rust && cargo test -p nodera-telemetry     # 93 tests
cargo clippy -p nodera-telemetry --all-targets -- -D warnings
```

The privacy-decisive tests, by name:

| Test | Property it pins |
|---|---|
| `no_attribute_accepts_free_text` | The registry cannot grow a field that carries prose |
| `a_batch_without_consent_writes_nothing_and_says_why` | Consent is a gate, not a recorded preference |
| `a_row_carries_the_country_and_never_the_address` | The source address does not survive into a row |
| `the_install_identifier_is_replaced_by_a_rotating_subject` | Pseudonymisation happens before the sink |
| `after_rotation_and_restart_a_previous_period_subject_is_not_reproducible_from_configuration` | **L-72 exit**: past periods are unrecoverable even with identical config, once process memory is gone |
| `an_undeclared_attribute_is_dropped_without_losing_the_event` | Forward compatibility without storing the stowaway |
| `an_out_of_range_integer_is_rejected_rather_than_clamped` | Aggregates are not quietly biased |
| `a_peer_may_not_report_service_level_events` | A peer cannot forge operator-facing rows |
| `the_agent_string_is_reduced_to_a_bounded_identifier` | A home directory cannot ride in on the agent field |
| `the_printed_schema_is_json_and_lists_every_declared_event` | The published notice cannot drift from the enforcement |

## Acceptance criteria

1. ✅ Nothing outside the registry can be stored, proven at the receiver.
2. ✅ A batch without explicit consent writes nothing.
3. ✅ No stored row contains an address, an install id, a node id, or free text.
4. ✅ Refusals are counted per reason and reported back to the sender.
5. ✅ The pseudonymisation key is memory-only and wiped on rotation — a previous period's subjects
   cannot be reproduced from configuration alone (the L-72 exit test).
6. ✅ The running binary can print exactly what it accepts.

## Limitations

Owns **L-73** (the listener is plaintext; TLS is terminated by a separate proxy). **L-72** (the
operator held the pseudonymisation secret) is **retired** — see
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md). Both are in [`LIMITATIONS.md`](LIMITATIONS.md) with
elimination paths and exit tests.
