# Tracker Task 3 — Operations Hardening

<!-- AI-AGENT-INSTRUCTION: This task serves community OPERATORS, not correctness. Nothing added here
     may become load-bearing for the network: a missing STATS reply, a disabled listing policy, or a
     restarted tracker must degrade discovery only. Keep this header's status accurate. -->

**Status:** 🚧 IN PROGRESS
**Category:** tracker · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [tracker 1](Task.1.md)
**Consumed by:** community operators

---

## Goal

Make the service comfortable to run for someone who did not write it: operator-visible counters over
the wire, deployment documentation, a public-listing policy, and the small CLI conveniences an
operator expects.

## Status detail

Partially landed. Operator counters currently ship as a **structured log line** emitted each sweep
(`wire.rs::run` — worlds/announces accepted+rejected/queries/expired/deleted/services/draining/
reports), which is workable but not queryable over the wire. Quotas, bounds, TTLs, and the bind
address are configurable via `nodera-tracker.toml` and the `NODERA_TRACKER_*` environment. `scripts/dev.sh`
builds, runs, and health-checks the binary, and CI produces a release build.

Two deliverables landed via [Task 6](Task.6.md) rather than here: `--healthcheck` and `--version`
(`main.rs:64,67` → `Command::Version`/`Command::Healthcheck`, the latter speaking the real protocol
in `main.rs:357`), and the deployment notes an operator follows ([`SELF-HOSTING.md`](SELF-HOSTING.md)).

Remaining: the `STATS` wire message (counters answered over the protocol, not just logged) and a
public-listing policy switch (who may announce and be listed).

## Dependencies

- [tracker 1](Task.1.md) — the service being hardened.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | Configurable quotas, bounds, TTLs, bind address | ✅ |
| 2 | Graceful SIGTERM drain | ✅ |
| 3 | Structured operator log line | ✅ (`wire.rs` sweep log) |
| 4 | `STATS` wire message — counters answered over the protocol | ⬜ |
| 5 | `--healthcheck` / `--version` | ✅ (`main.rs:64,67`; landed via [Task 6](Task.6.md)) |
| 6 | Public-listing policy configuration | ⬜ |
| 7 | Deployment documentation for operators | ✅ ([`SELF-HOSTING.md`](SELF-HOSTING.md); landed via [Task 6](Task.6.md)) |

## Design

**Counters over the wire, not over a second protocol.** Exposing metrics via HTTP would mean a second
serialization, a second port, and a second attack surface on a service whose entire discipline is "one
wire contract". `STATS` is an ordinary message with a tag, a fixture, and a Rust mirror.

**A listing policy is a social feature with a technical shape.** An operator may reasonably want to
run a tracker for a private group. That is a policy switch on *who may announce and be listed*, not a
new trust relationship — a peer still verifies everything it receives.

**Health checks belong in the binary.** An operator running under systemd or a container runtime needs
a liveness answer that does not require a Java client. `--healthcheck` keeps the operational contract
inside the service.

**Documentation is a deliverable here, not a nicety.** The service is meant to be run by people other
than the project's authors; without deployment notes, the practical answer to "who runs a tracker" is
"nobody", which reintroduces centralisation by neglect.

## Files

- `tracker/src/{main,config,wire,limits}.rs`
- `scripts/dev.sh`, `.github/workflows/`, `docker/`, `scripts/deploy-vps.sh`
- Operator documentation: [`SELF-HOSTING.md`](SELF-HOSTING.md)

## Testing

- Config parsing and defaults; invalid configuration refused with a clear message
  (`config.rs::unknown_keys_are_refused_rather_than_silently_ignored` and the env-twin drift guard
  `config.rs::every_config_key_has_an_environment_override`).
- Quota and bound enforcement under synthetic flood (`limits.rs`, `service.rs`).
- `--healthcheck` / `--version` arg parsing (`main.rs::version_and_help_are_recognised`,
  `a_flag_missing_its_value_is_usage_not_a_panic`).
- Planned: `STATS` round-trip with a golden fixture and the Rust mirror; listing-policy enforcement.

## Acceptance criteria

1. ⬜ `STATS` is answered over the wire with a fixture and mirror test.
2. ✅ `--healthcheck` and `--version` behave correctly for process supervisors.
3. ⬜ A public-listing policy is configurable and tested.
4. ✅ Deployment notes are committed and accurate ([`SELF-HOSTING.md`](SELF-HOSTING.md)).
5. ✅ Quotas and bounds remain configurable and enforced.

## Limitations

None owned.
