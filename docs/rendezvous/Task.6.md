# Rendezvous Task 6 — A published image, and a relay anyone can run

<!-- AI-AGENT-INSTRUCTION: The container half for the relay. The shared reasoning lives in
     ../tracker/Task.6.md and is linked rather than restated — the two Dockerfiles are deliberately
     near identical and their justifications should not drift into two versions. What is genuinely
     different here is the drain: a relay's SIGTERM has to move real transfers, not just stop
     answering queries. Keep this header's status accurate.
     Context: the published GHCR image + operator docs. All deliverables ✅. The difference from the
     tracker image is the stop grace period (60s here vs 45s) because a relay drains live transfers.
     Key files: docker/rendezvous/Dockerfile, docker/compose.yml, docker/.env.example,
     rendezvous/src/config.rs (env twins) + main.rs (--print-env),
     .github/workflows/containers.yml, scripts/deploy-vps.sh. Operator doc: SELF-HOSTING.md.
     Depends on: Task.1.md, Task.5.md (the drain), ../tracker/Task.6.md (shared image reasoning).
     Consumed by: ../network/Task.13.md, ../frontend/Task.9.md. -->

**Status:** ✅ DONE
**Category:** rendezvous · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [rendezvous 1](Task.1.md), [rendezvous 5](Task.5.md), [tracker 6](../tracker/Task.6.md)
**Consumed by:** [network 13](../network/Task.13.md), [frontend 9](../frontend/Task.9.md)

---

## Goal

The same as [tracker 6](../tracker/Task.6.md) for the relay, plus the one thing a relay has that a
tracker does not: a stop that moves live transfers instead of cutting them.

## Status detail

Landed 2026-07-27 and running in production alongside the tracker. The relay announces itself into
the tracker's service directory, which is what makes it discoverable rather than merely reachable —
verified from a third machine with `nodera-query --services`. Reviewed 2026-07-28 against the current
config surface: every TOML key still has its environment twin, and `--print-env` enumerates them.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | Every TOML key has an environment twin; a typo refuses the start | ✅ |
| 2 | `--print-env` | ✅ |
| 3 | Multi-stage static musl image, non-root, ~12 MB | ✅ |
| 4 | Published to GHCR for amd64 and arm64 | ✅ |
| 5 | In the shared compose file | ✅ |
| 6 | `SIGTERM` drains: refuse, tell the peers, tell the trackers, wait | ✅ |
| 7 | `stop_grace_period` long enough to matter | ✅ 60s |
| 8 | Operator documentation | ✅ [`SELF-HOSTING.md`](SELF-HOSTING.md) |

## Design

The image, the environment overlay, the multi-architecture build and the deploy script are the
tracker's, and the reasoning for each is in [tracker 6](../tracker/Task.6.md). What differs:

**The drain is the point of the stop timeout here.** A tracker's drain ends a query somebody will
retry. A relay's drain is bridging transfers, and cutting one costs a peer its download. `docker
stop` allows 10 seconds against a 30-second grace, so a compose file without `stop_grace_period`
kills the drain on every deploy — which looks exactly like a deploy that worked. 60 seconds here
against the tracker's 45.

**`NODERA_RENDEZVOUS_TRACKER_ENDPOINTS` is the variable that decides whether any of this matters.** A
relay that announces to no tracker is reachable only by peers already holding its address. It is
deliberately unset in the image rather than defaulted to something plausible: a wrong default here
produces a relay that looks healthy and is invisible.

**Three names in this prefix belong to the peer.** `NODERA_RENDEZVOUS_ENDPOINTS`, `_FANOUT` and
`_SWEEP_SECONDS` are read by `HeadlessPeerMain`, which is a *client* of relays. They are declared as
belonging elsewhere so a shell holding both can start both, rather than being silently tolerated —
an entry there is a naming collision somebody has to understand later.

**The reservation HMAC key is generated once and reused.** The deploy script reads the one already on
the host rather than minting a new one, because a new key invalidates every outstanding reservation.

## Files

- `docker/rendezvous/Dockerfile`
- `rendezvous/src/config.rs` · `src/main.rs`
- `docker/compose.yml` · `docker/.env.example`
- `.github/workflows/containers.yml` · `scripts/deploy-vps.sh`

## Testing

```bash
cd rust && cargo test -p nodera-rendezvous -p nodera-service
docker build -f docker/rendezvous/Dockerfile -t nodera-rendezvous:local .
```

Decisive tests:

- `config::tests::every_config_key_has_an_environment_override`.
- `config::tests::the_java_peers_rendezvous_variables_do_not_stop_this_service`.
- `config::tests::an_environment_value_that_breaks_a_bound_is_still_refused` — env-configurability
  must not become a way around validation.
- `lifecycle::tests::a_drain_refuses_work_before_it_tells_anybody` — the ordering, asserted directly.

Verified live: the image starts, announces into the tracker's directory, answers `--healthcheck` over
the public internet, and drains cleanly on `docker stop`.

## Acceptance criteria

1. ✅ A relay runs from the published image with no config file.
2. ✅ It announces itself and appears in a tracker's service directory.
3. ✅ arm64 and amd64 from one tag.
4. ✅ A stop drains rather than cutting.
5. ✅ A self-hoster can follow one document end to end.

## Limitations

Owns none of its own. **L-83** (a drain's grace can still expire with a circuit live) belongs to
[Task 5](Task.5.md) and this lane does not change it — a longer `stop_grace_period` makes the window
wider, which is not the same as making the transfer survive.
