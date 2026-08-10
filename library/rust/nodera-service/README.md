# `library/rust/nodera-service`

<!-- AI-AGENT-INSTRUCTION: This crate holds NO game, consensus, or storage logic (Task 0 §4 rule 7).
     The one signing key it manages signs exactly one kind of value — the service's own address record —
     and nothing it signs is authority over world state. The drain ORDER in `lifecycle.rs` is a
     contract, not an implementation detail: refuse new work, tell the peers already committed, tell the
     trackers, wait for in-flight work, then stop. Do not reorder it and do not add a second drain path.
     Update this file when a module is added. The Architecture table below must list every `pub mod`
     in `src/lib.rs` — it drifted from five to thirteen once already, which is how a shared crate
     acquires a second copy of something it already has. -->

**Everything `nodera-tracker`, `nodera-rendezvous` and `nodera-telemetry` all need, written once.** They
were each growing their own versions with small differences that mattered — two definitions of "draining"
being the worst of them, and three copies of the frame reader of which only one had ever had its
size bound tested being the most dangerous.

- **Depends on:** `nodera-codec` (the service-directory wire family and the score function),
  `ed25519-dalek`, `sha2`, `tokio`, `serde` + `toml` (the operator's configuration file),
  `thiserror`, and `ureq` (the update lane's HTTPS fetch, `rustls` only — see `update::Fetcher`).
- **Depended on by:** `nodera-tracker`, `nodera-rendezvous`, `nodera-telemetry`. Telemetry takes the
  plumbing and none of the peer half; `telemetry/Cargo.toml` says which modules and why.
- **Build-time half:** [`build-version/`](build-version/) is a separate package, `nodera-build`, and it
  is deliberately not a module here. A `build.rs` may only use `[build-dependencies]`, so depending on
  `nodera-service` from one would compile tokio, ureq and ed25519-dalek **for the host** on every
  cross-compiled release build. It has no dependencies at all and all three service build scripts are
  two lines that call `stamp_version()`.
- **Docs:** [`docs/tracker/Task.5.md`](../../../docs/tracker/Task.5.md) ·
  [`docs/rendezvous/Task.5.md`](../../../docs/rendezvous/Task.5.md)

---

## Architecture

Thirteen modules, in two groups. The first five are what makes a process a **service in the
network** — it has an identity, it announces itself, it drains, it updates — and only the tracker and
the rendezvous take them. The rest is **plumbing every one of the three binaries needs** and none of
it carries authority, which is what lets telemetry depend on this crate without becoming a peer.

| Module | What it owns |
|---|---|
| `identity` | The service's stable `NodeId` + Ed25519 key, persisted so a restart does not present the host to the network as a brand-new, unmeasured service. Publishes the key in Java's X.509 form, because that is the only form Java verifies. |
| `directory` | Announcing a signed `ServiceRecord` to trackers, and merging the sibling lists the acks return — a union with a best-evidence tie-break, never an arbitration. |
| `drain` | `DrainState`: the atomic "am I still open for business" flag every request path reads, the in-flight counter a shutdown waits on, and the `WorkGuard` that releases it on every exit path including a panic. |
| `lifecycle` | The task that ties them together: the announce cadence, the drain sequence, and the update check. `ServiceHost` is what a concrete service implements — its kind, its capacity numbers, and how it reaches the peers holding its control channels. |
| `update` | Notice that the published binary is not the one running, verify the replacement against the release's digest manifest, stage it beside the executable, and swap it atomically. |
| `admission` | The two checks an unauthenticated service runs before it records anything: is the record's own timestamp close enough to ours to be neither stale nor replayed (symmetric — a timestamp too far in the *future* is as suspect as one too far in the past), and has this `NodeId` already been seen. Pure, so every rejection path is unit-testable with no network. |
| `cli` | The five flags every service takes — `--config`, `--bind`, `--healthcheck`, `--print-env`, `--version`/`-h` — the `Command` enum, the usage string and the dispatch. Measured similarity across the three hand-written copies was 0.96–1.00. A service with a flag of its own declares it in `Service::extra`; telemetry's `--print-schema` is the only one. |
| `config` | `ConfigError`, `load_toml`, `configure`, `bind`, and the `service_config!` macro: the six steps every `serve` opens with (read the file, overlay the environment, print what it applied, take the flag, validate, bind). Each service keeps its own `Config` **struct** — the fields genuinely differ — and `deny_unknown_fields` stays on, because an operator's typo must be loud rather than leave them believing a limit is in force. |
| `env` | The environment overlay and `env_overlay!`: defaults, then the file, then the environment, because the environment is the layer an orchestrator controls and the file is the layer a human wrote. Unknown variables are refused for the same reason unknown TOML keys are. |
| `endpoint` | `socket_target`: taking the scheme off `tcp://host:port` before a connect. It exists because it did not — a rendezvous configured with the *documented* endpoint form logged one resolver failure at boot and then announced to nobody, for ever. |
| `frame` | The `u32`-length + body framing all three services serve over TCP, generic over the stream so the bound that matters — *a frame larger than the limit is refused before the buffer is allocated* — is asserted once for all of them instead of in one of three copies. Also the UDP amplification cap, which is a security property rather than a tuning knob and previously lived inline in the tracker's datagram loop. |
| `limits` | `PairQuota`: per-source fixed-window quotas, the only thing between an unauthenticated socket and the registry. Fixed windows rather than token buckets, because an operator reasons in the units the interval is configured in. **A limit of `0` disables that limit**; the opposite reading turns an unset value into a total outage. |
| `serve` | `shutdown_signal` (SIGTERM as well as Ctrl-C — SIGTERM is what an orchestrator, a systemd unit and `docker stop` send) and `accept_loop`, the `select!` between it and `listener.accept()`. What a service does *with* an accepted connection stays in its own `wire` module. |

## Two decisions worth knowing before you change anything here

**A service signs its own address record.** The category convention used to be "the service holds no
signing keys". It holds exactly one now, and it signs exactly one kind of value: `ServiceRecord` — where
I am, what version I run, how loaded I am, when I am leaving. A drain notice is an *eviction primitive*;
unsigned, it is a cheap way for anyone on the path to herd a target's traffic onto a relay they run. The
invariant that still holds without qualification: **nothing a service signs is authority over world
state.**

**The update comparison is a digest, not a version.** The project publishes a rolling `latest`
prerelease on every push, so `VERSION` stays `0.1.0` across hundreds of distinct builds and a version
comparison would never see a change. Comparing the published SHA-256 of our own asset against the digest
of the file we are executing answers the question that actually matters — *am I running what is
published?* — with no version plumbing and no ordering rule to get wrong.

## Consent

`update.channel` is empty by default, and that means the whole update lane is inert. Someone who
downloaded a tracker agreed to run a tracker; they did not agree to let it replace its own executable.
The project's own deployments set it. Same rule as the telemetry lane, same reason.

## Tests

```bash
cargo test -p nodera-service
```

The count is **not** written here. It is held in README's module-status table by
`scripts/test-counts.sh --check`, which enumerates the suite with `cargo test -- --list` — a number
copied into a second file is a number that drifts, and this file has already carried a stale one.

The ones that matter most:

- `drain::tests::draining_refuses_new_work_immediately` — the first obligation, which has to come first.
- `lifecycle::tests::a_drain_refuses_work_before_it_tells_anybody` — the ordering, asserted directly.
- `lifecycle::tests::in_flight_work_delays_the_drain_and_the_grace_period_bounds_it`.
- `update::tests::a_download_that_does_not_match_its_digest_is_refused_before_anything_is_staged` — a
  corrupted or substituted download never becomes the executable, and no drain is spent on it.
- `identity::tests::an_identity_survives_a_restart_because_the_score_should_too`.
- `directory::tests::an_inflated_composite_cannot_reorder_the_list`.
