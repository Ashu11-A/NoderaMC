# Tracker Task 6 — A published image, and a tracker anyone can run

<!-- AI-AGENT-INSTRUCTION: This task owns the CONTAINER and the DEPLOYMENT, not the protocol. Two
     rules. (1) Every setting must be reachable from the environment — a config key with no
     environment twin is a key a container cannot set, and `every_config_key_has_an_environment_override`
     fails on one. (2) An unrecognised variable in the prefix must keep refusing the start; softening
     that to a warning re-creates exactly the failure the strictness exists to prevent. Keep this
     header's status accurate. -->

**Status:** ✅ COMPLETED
**Category:** tracker · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [tracker 1](Task.1.md), [tracker 5](Task.5.md)
**Consumed by:** [rendezvous 6](../rendezvous/Task.6.md), [frontend 9](../frontend/Task.9.md),
[network 13](../network/Task.13.md)

---

## Goal

Someone who is not us can run a tracker in one command, configure all of it without a config file,
and be reachable from the public internet — on amd64 or arm64, from an image they did not build.

## Status detail

Landed 2026-07-27 and **running in production**: the project's own tracker and relay are up, the
relay announces into the tracker's service directory, and both were verified from a third machine.

```
$ nodera-query 150.230.84.206:6969 --services
rendezvous   1 known
  5f0a3f4f661b30fbd6f7c5f79059ee2b  Serving  v0.1.0
  routes=tcp://rendezvous.noderamc.org:7500,tcp://150.230.84.206:7500
```

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | Every TOML key has an exact environment twin | ✅ |
| 2 | An unrecognised variable in the prefix refuses the start | ✅ |
| 3 | Error messages name the variable, never the value | ✅ |
| 4 | `--print-env` — the reference comes from the binary | ✅ |
| 5 | A multi-stage image, static musl, non-root, ~10 MB | ✅ |
| 6 | Published to GHCR for `linux/amd64` **and** `linux/arm64` | ✅ |
| 7 | A compose file a self-hoster runs unedited | ✅ |
| 8 | `SIGTERM` drains rather than cutting | ✅ (already true; the image makes it reachable) |
| 9 | An idempotent deploy script, verified over the public path | ✅ |
| 10 | Operator documentation | ✅ [`SELF-HOSTING.md`](SELF-HOSTING.md) |
| 11 | `nodera-query --services` — check the directory from outside | ✅ |

## Design

**A container is configured by its environment or it is not configurable at all.** An image whose
only configuration path is a bind-mounted TOML file is an image nobody can run from a single
`docker run` line. `nodera_service::env` is one overlay shared by all three services, and the naming
rule is mechanical — prefix plus the TOML key, uppercased — so the two syntaxes can never name the
same setting differently. Precedence is defaults, file, environment, flag: each layer belongs to
someone closer to this particular start than the last.

**Unknown variables refuse the start, exactly like unknown TOML keys.** The reason was already
written down for the file loader: a typo'd key must not leave the operator believing a limit is in
force. An environment variable is *more* prone to that, not less — there is no schema, no file to
re-read, and `NODERA_TRACKER_MAX_SERVICE` looks exactly as authoritative in a compose file as the
real name. Four names in these prefixes belong to the Java peer and are declared as belonging
elsewhere, so a shell holding both can start both.

**Values are never echoed.** Every error names the variable and the type expected. A service may
sit next to another on the same host whose variable *is* a secret, and an error message is the one
place a secret escapes into a log that outlives the process. (The telemetry service no longer holds
a pseudonymisation secret at all — its per-period key is minted in memory — but the rule is general
and stays.)

**Two build jobs, not one.** A single `platforms: amd64,arm64` build emulates arm64 through QEMU,
and a Rust release build under emulation takes the better part of an hour per image. Each platform
builds natively and pushes by digest with no tag; a merge job assembles the manifest list and
**fails if both architectures are not in it**. A manifest that silently covers one architecture pulls
fine on the machine that built it and fails on everyone else's — and the deployment target here is
arm64.

**`COPY rust ./rust`, never a member list.** The telemetry image used to copy workspace members one
line at a time, and adding a crate to the workspace broke the image while every other build stayed
green. Copying the whole workspace makes that class of bug impossible.

**No `VOLUME` instruction.** It forces an anonymous volume onto every `docker run` — they accumulate,
they are never reclaimed, and on some storage drivers the copy-up fails outright — while making
nothing more durable than the compose file's named volume already does.

**The deploy script is idempotent in the way that matters.** It reuses the reservation HMAC key it
finds rather than generating a new one, because regenerating it invalidates every outstanding relay
reservation: a small outage nobody would connect to a deploy that "changed nothing". It manages only
its own compose project, because the target host runs other people's containers.

**It verifies twice.** The in-container healthcheck speaks the real protocol, so a service that is
listening but not answering fails it. The check from the operator's own machine proves the public
path — firewall, NAT, port publishing — which the local one cannot see.

## Two defects the deployment found that no test had

**A service configured the documented way announced to nobody.** `tcp://host:port` is the endpoint
form used by every example config, the compose file, the published index and the Java
`Endpoint.parse` — and `directory::exchange` handed the whole string to the resolver. One error line
at boot, then a service that ran perfectly and was undiscoverable. Fixed with one shared
`nodera_service::endpoint::socket_target`, which the telemetry reporter (the only place that had it
right) now delegates to. The existing round-trip test passed a bare `host:port`; the new one uses the
documented form.

**A container cannot reach its own host's public address.** Announcing to `tcp://<public-ip>:6969`
from inside a container has to hairpin back through the published port and is refused on a default
bridge network. The compose service name is the form that works, and is now the default.

## Files

- `library/rust/nodera-service/src/env.rs` · `library/rust/nodera-service/src/endpoint.rs`
- `tracker/src/config.rs` · `src/main.rs` (`--print-env`)
- `tracker/src/bin/nodera-query.rs` (`--services`)
- `docker/tracker/Dockerfile` · `docker/compose.yml` · `docker/.env.example` · `docker/README.md`
- `.github/workflows/containers.yml`
- `scripts/deploy-vps.sh`
- `.dockerignore`

## Testing

```bash
cd rust && cargo test -p nodera-service -p nodera-tracker
docker build -f docker/tracker/Dockerfile -t nodera-tracker:local .
scripts/deploy-vps.sh --dry-run
```

Decisive tests:

- `config::tests::every_config_key_has_an_environment_override` — the drift guard. A key reachable
  only from a file is a key a container cannot set.
- `env::tests::a_misspelled_variable_is_refused_rather_than_silently_ignored`.
- `env::tests::a_bad_value_names_the_variable_and_never_the_value`.
- `env::tests::an_empty_list_variable_clears_the_file_value` — setting a variable to nothing is an
  instruction, not an omission.
- `directory::tests::an_announce_reaches_a_tracker_written_in_the_documented_tcp_form`.
- `endpoint::tests::*` — including that an unknown scheme is left alone so the connect fails with the
  operator's own string.

Verified by running the images rather than reading them: env settings applied and named at startup, a
misspelled variable refused with the message, `--healthcheck` speaking the real protocol, and SIGTERM
producing a clean drain.

## Acceptance criteria

1. ✅ A tracker runs from `docker run` with no config file.
2. ✅ Every setting is reachable from the environment, and a typo is refused.
3. ✅ The image runs on arm64 and amd64 from one tag.
4. ✅ A self-hoster can follow one document end to end without a checkout.
5. ✅ The project's own tracker is live and answers from the public internet.
6. ✅ A relay announces into its service directory, checked from a third machine.

## Limitations

Owns none. L-81 (release provenance) belongs to [Task 5](Task.5.md) and is untouched by this lane;
the container upgrade path does not use the self-update lane at all.
