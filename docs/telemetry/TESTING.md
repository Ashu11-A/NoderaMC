# Telemetry — Testing

<!-- AI-AGENT-INSTRUCTION: Counts come from `cargo test -p nodera-telemetry`, never from memory. The
     decisive tests in this category are the PRIVACY tests: if a change makes one of them awkward,
     the change is wrong, not the test. Any new event or attribute needs a validation test proving
     both its accepted and its rejected forms. Keep counts and Last run current. -->

**Category:** telemetry · **Last run:** 2026-07-28 · **93 Rust tests · 0 failing** (ingest crate), plus the
emitter suites in the categories that emit and one end-to-end suite that drives the real binaries

> **The live suites are Java scenarios now.** Every `scripts/e2e-<id>.sh` became `dev.nodera.testkit.scenario.<Id>Scenario` and runs through one command:
> `scripts/nodera-test.sh run <id>` (`list` shows them all). The stages, evidence strings and timeouts were carried over, so a report maps onto an old run line by line. The tooling is documented in [`docs/testing/`](../testing/Task.0.md).

```bash
cd rust && cargo test -p nodera-telemetry          # the receiver (93)
cd rust && cargo clippy -p nodera-telemetry --all-targets -- -D warnings
./gradlew :peer:test --tests '*Telemetry*'        # the emitter core + the worker + the verb
./gradlew :neoforge-mod:test --tests '*Telemetry*' # the game-side façade
scripts/nodera-test.sh run telemetry                          # THE decisive one: real binaries, end to end
scripts/telemetry-stack.sh smoke                  # a batch becomes a queryable ClickHouse row
nodera-telemetry --print-schema | jq              # what the running binary will accept
```

---

## 1. The testing strategy

| Level | What it proves |
|---|---|
| Registry tests | The collection policy is well-formed: unique names, no empty enums, **no free-text value anywhere** |
| Validation tests | Each refusal and each drop happens for the stated reason, and nothing else is stored |
| De-identification tests | The install id and the source address do not survive into a row |
| Quota tests | One flooding source cannot silence a quiet one, and a refused batch still spends its budget |
| Sink tests | Rotation by size and age; a missing directory is created; names sort in write order |
| **End-to-end over a socket** | The framing, the service, and the sink agree in practice — a real frame produces a real reply and a real row on disk |

The last level is the one that matters, for the same reason `TrackerServiceIT` matters in the
tracker category: unit tests of a validator prove the validator, not the service.

## 2. The privacy tests, by name

These are the tests a reviewer should read first, because they are the executable form of every
claim in [`../plans/Plan.6.md`](../plans/Plan.6.md) §2.

| Test | Locked decision it enforces |
|---|---|
| `schema::tests::no_attribute_accepts_free_text` | D5 — no free text, anywhere |
| `event::tests::a_batch_without_granted_consent_writes_nothing` | D1 — nothing without a yes |
| `event::tests::an_unknown_event_name_is_refused_and_counted` | D4 — the registry is the policy |
| `event::tests::an_undeclared_attribute_is_dropped_without_losing_the_event` | D4 — forward compatibility without storage |
| `event::tests::an_out_of_range_integer_is_rejected_rather_than_clamped` | D6 — buckets, not fabricated measurements |
| `event::tests::the_agent_string_is_reduced_to_a_bounded_identifier` | D5 — a home directory cannot ride along |
| `event::tests::a_peer_may_not_report_service_level_events` | D3 — an unsigned surface still cannot forge operator rows |
| `subject::tests::the_subject_changes_when_the_period_rolls` | D7 — identifiers rotate |
| `subject::tests::the_install_id_never_appears_in_the_subject` | D7 — pseudonymisation is real |
| `subject::tests::after_rotation_and_restart_a_previous_period_subject_is_not_reproducible_from_configuration` | D7 — forward secrecy: past periods unrecoverable even with identical config (L-72 exit) |
| `service::tests::a_row_carries_the_country_and_never_the_address` | D7 — addresses are discarded |
| `config::tests::the_default_configuration_validates` | D7 — no persistent secret to set; the key is memory-only |
| `main::tests::the_printed_schema_is_json_and_lists_every_declared_event` | D9 — the notice cannot drift from the enforcement |

## 3. Coverage by module

Counts grep-verified 2026-07-28 (`#[test]` + `#[tokio::test]` per file). The crate total is **93**
(87 in the library, 6 in the binary).

| Module | Tests | Focus |
|---|---|---|
| `schema.rs` | 8 | Registry well-formedness, source admission |
| `event.rs` | 14 | Envelope, consent, per-event and per-attribute validation, agent sanitisation |
| `subject.rs` | 11 | Rotation, namespacing, forward secrecy across rotation+restart, key-source seam |
| `geo.rs` | 7 | Longest prefix, IPv4-mapped IPv6, unknown answers unknown, `/0` without overflow |
| ~~`limits.rs`~~ | ~~8~~ | **Moved 2026-08-06** (`2f79850`, issue #213) — batch/event budgets are `nodera_service::limits::{PairQuota, Verdict}`, shared with the tracker and rendezvous. Count not re-measured here; run `cargo test -p nodera-service` |
| `sink.rs` | 7 | Rotation triggers, ordering, directory creation |
| `service.rs` | 11 | The ingest decision, typed attribute split, honest replies, counters, restart pseudonymisation |
| `wire.rs` | 4 | Framing bounds, clean EOF, **end-to-end over TCP** |
| `config.rs` | 8 | Defaults, refusals, every-key override, bounds conversion, stale-secret-key refused |
| `reporter.rs` | 9 | The shared service-side emitter: queue bounds, envelope, refusal handling, install-id derivation |
| `main.rs` | 6 | CLI parsing, `--print-schema` fidelity |

## 4. The end-to-end suite

`scripts/nodera-test.sh run telemetry` is the decisive level for the whole programme, and it is **headless** —
no GUI, no Minecraft — so it runs in CI unchanged and first in `scripts/nodera-test.sh run`.

| Step | What it proves |
|---|---|
| T1 | A worker nobody asked lets two collection windows pass and writes **nothing** |
| T2 | The Java registry is a subset of what the real collector accepts (mirror, real binaries) |
| T3 | A consented worker's rows carry a rotating subject and a country — and neither the source address nor the install id |
| T4 | An undeclared event and a batch without consent are both refused, with reasons |
| T5 | Revocation clears the queue and deletes the installation identifier |
| T6 | **The outage lane** — with the collector killed, the node's whole state answer is unchanged apart from the telemetry block and the clock |

CI runs it in `.github/workflows/telemetry.yml`, alongside the registry mirror against a freshly
built binary and a compose smoke test.

## 5. What is not tested yet

- The compose smoke job is **green** (`stack-smoke`: a batch becomes a queryable ClickHouse row and
  the warehouse schema carries no address column). What it does **not** yet assert is expiry — the
  TTL is read from the schema rather than observed after a merge.
- Retention TTL behaviour is asserted by reading the schema, not by running a merge.
- The app's consent modal has no component test: "neither button is favoured" is held by the markup
  and by review ([frontend 5](../frontend/Task.5.md)).
- No dashboard has answered a real question yet, because nothing has collected a population
  ([telemetry 3](Task.3.md), and the reason **L-75** is OPEN rather than retired).
