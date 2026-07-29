# Telemetry — Retired Limitations

<!-- AI-AGENT-INSTRUCTION: A row arrives here ONLY when its stated exit test is green, and it arrives
      WITH that evidence named. Never delete a row from this file; never move one here on the strength
      of prose. The live register is LIMITATIONS.md. -->

**Category:** telemetry · **Last audit:** 2026-07-28 · Retired rows: **1**

---

### L-72 — The operator could re-link a current period's subjects  (RETIRED 2026-07-28)

**Row text as it stood:** *The operator holds the pseudonymisation secret, so during a current
rotation period they could recompute the mapping from install id to subject. Rotation bounds the
window; it does not remove the capability. Elimination path: move the derivation to a key the ingest
process holds only in memory and discards on rotation, so past periods are unrecoverable even to the
operator.*

**Why it retired.** The pseudonymiser (`rust/nodera-telemetry/src/subject.rs`) now mints a fresh
32-byte key from `/dev/urandom` the first time a period is observed, caches it for the period, and
wipes it (zero-then-drop) the instant the period rolls — eagerly from the ingest sweep, lazily from
the next batch. The `subject_secret` field and its environment override were removed from
configuration entirely (`config.rs`, `docker/telemetry/*`, `scripts/*.sh`), so the operator's
configuration carries no key material at all, and a restart mints a brand-new key for whatever
period it boots into. Past periods are unrecoverable even to the operator, because the key that
derived them no longer exists anywhere — not on disk, not in config, not in memory.

**Exit test (the one named in the row), green:**
`subject::tests::after_rotation_and_restart_a_previous_period_subject_is_not_reproducible_from_configuration`
— drives the property with deterministic fake entropy/clock, asserting that after a rotation and a
process restart (identical empty configuration, fresh memory), neither the rolled-to period nor a
back-dated previous period reproduces the original subject. A service-level companion,
`service::tests::a_previous_period_subject_in_a_written_row_is_not_reproducible_after_restart`,
asserts the same through the real ingest write path. Plan.6 §10 records D7's re-opening.

**Cost carried forward.** Subjects are no longer stable across a process restart *within* a period —
the intended trade for forward secrecy, and strictly stronger than the persistent-secret design it
replaces.
