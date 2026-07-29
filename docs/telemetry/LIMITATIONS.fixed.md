# Telemetry — Retired Limitations

<!-- AI-AGENT-INSTRUCTION: A row arrives here ONLY when its stated exit test is green, and it arrives
      WITH that evidence named. Never delete a row from this file; never move one here on the strength
      of prose. The live register is LIMITATIONS.md. -->

**Category:** telemetry · **Last audit:** 2026-07-28 · Retired rows: **1**

---

## L-73 — the plaintext ingest listener was unenforced

**The row as it stood.** *"The ingest listener is plaintext. Reports carry no secrets and no
identifiers, but a network observer still learns that a given address reports telemetry and roughly
how much. TLS is available only by putting the `edge` proxy in front. Elimination path: native TLS
in the service, or making the proxy non-optional in the shipped deployment."* Owning task
[1](Task.1.md); exit test: *"A test that a non-TLS connection to the public endpoint is refused once
the deployment declares itself public."*

**Retired:** 2026-07-28.

**Why it is closed.** The proxy is now non-optional *and checkable* rather than a convention in a
comment. A deployment declares itself public with `public_endpoint = true` and names the ranges its
TLS terminator dials from (`trusted_proxy_cidrs`). From that point:

- a configuration that declares itself public without naming a TLS front **refuses to start**
  (`ConfigError::PublicWithoutTlsFront`), so the failure is at boot rather than in production;
- an unparseable range is refused rather than silently ignored, because a range that does not parse
  would otherwise become a range that matches nothing and an endpoint that serves nobody — or,
  worse, a policy that was quietly dropped;
- a connection from anywhere other than the declared front (loopback excepted, for the container
  healthcheck) is **closed in `serve_connection` before its first frame is read**, and counted by
  name as `plaintext_not_via_tls_front` so an operator sees it in the counter line.

The compose stack keeps the port on `127.0.0.1` and terminates TLS at `edge`; the shipped
`nodera-telemetry.toml` now carries both keys with the Docker bridge range, so publishing the port
is a one-line, reviewed decision instead of an accident.

**Named exit test.** `nodera_telemetry::wire::tests::a_direct_plaintext_client_is_refused_once_the_endpoint_is_declared_public`
— over a real TCP socket: the stranger's frame gets an end of stream and no reply, the declared
front's frame gets its reply, and exactly one refusal is counted. Supported by
`config::tests::declaring_the_endpoint_public_without_a_tls_front_refuses_the_start`,
`an_unparseable_trusted_range_is_refused_rather_than_ignored`,
`a_public_endpoint_admits_only_the_tls_front_and_loopback`, and
`a_private_deployment_is_unchanged_and_admits_everything`.

**Cost carried forward.** The bytes on the wire between the terminator and the service are still
plaintext — the service itself has no TLS, and the elimination path's *first* option (native TLS)
was not taken. That hop is now a link the operator controls (a container network or a loopback
interface) rather than the open internet, which is what the exit test asks for; a deployment that
puts the terminator on a *different host* still has an unencrypted hop between them, and that
remains the operator's decision to make with a private link. Deciding to be public is also still an
operator declaration: nothing detects a published port on its own.

---

The remaining open rows are in [`LIMITATIONS.md`](LIMITATIONS.md) §B (L-72, L-74, L-75), each with
its owning task and the test that will retire it.
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
