# Tracker Task 3 — Operations Hardening

<!-- AI-AGENT-INSTRUCTION: This task serves community OPERATORS, not correctness. Nothing added here
     may become load-bearing for the network: a missing STATS reply, a disabled listing policy, or a
     restarted tracker must degrade discovery only. Keep this header's status accurate. -->

**Status:** 🚧 IN PROGRESS
**Category:** tracker · **Owns:** — · **Last audit:** 2026-07-25
**Depends on:** [tracker 1](Task.1.md)
**Consumed by:** community operators

---

## Goal

Make the service comfortable to run for someone who did not write it: operator-visible counters over
the wire, deployment documentation, a public-listing policy, and the small CLI conveniences an
operator expects.

## Status detail

Partially landed. Operator counters currently ship as a **structured log line**, which is workable but
not queryable. Quotas, bounds, TTLs, and the bind address are already configurable via
`nodera-tracker.toml`. `scripts/dev.sh` builds, runs, and health-checks the binary, and CI produces a
release build.

Remaining: the `STATS` wire message, `--healthcheck` and `--version` polish, a public-listing policy
switch, and deployment notes for community operators.

## Dependencies

- [tracker 1](Task.1.md) — the service being hardened.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | Configurable quotas, bounds, TTLs, bind address | ✅ |
| 2 | Graceful SIGTERM drain | ✅ |
| 3 | Structured operator log line | ✅ |
| 4 | `STATS` wire message — counters answered over the protocol | ⬜ |
| 5 | `--healthcheck` / `--version` | 🚧 |
| 6 | Public-listing policy configuration | ⬜ |
| 7 | Deployment documentation for operators | ⬜ |

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

- `rust/nodera-tracker/src/{main,config,limits}.rs`
- `scripts/dev.sh`, `.github/workflows/`

## Testing

- Config parsing and defaults; invalid configuration refused with a clear message.
- Quota and bound enforcement under synthetic flood.
- Planned: `STATS` round-trip with a golden fixture and the Rust mirror; `--healthcheck` exit codes.

## Acceptance criteria

1. ⬜ `STATS` is answered over the wire with a fixture and mirror test.
2. 🚧 `--healthcheck` and `--version` behave correctly for process supervisors.
3. ⬜ A public-listing policy is configurable and tested.
4. ⬜ Deployment notes are committed and accurate.
5. ✅ Quotas and bounds remain configurable and enforced.

## Limitations

None owned.
