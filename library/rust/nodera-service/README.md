# `rust/nodera-service`

<!-- AI-AGENT-INSTRUCTION: This crate holds NO game, consensus, or storage logic (Task 0 §4 rule 7).
     The one signing key it manages signs exactly one kind of value — the service's own address record —
     and nothing it signs is authority over world state. The drain ORDER in `lifecycle.rs` is a
     contract, not an implementation detail: refuse new work, tell the peers already committed, tell the
     trackers, wait for in-flight work, then stop. Do not reorder it and do not add a second drain path.
     Update this file when a module is added. -->

**The four things `nodera-tracker` and `nodera-rendezvous` both need, written once.** Both were growing
their own versions with small differences that mattered — two definitions of "draining" being the worst of
them.

- **Depends on:** `nodera-codec` (the service-directory wire family and the score function),
  `ed25519-dalek`, `sha2`, `tokio`.
- **Depended on by:** `nodera-tracker`, `nodera-rendezvous`.
- **Docs:** [`docs/tracker/Task.5.md`](../../docs/tracker/Task.5.md) ·
  [`docs/rendezvous/Task.5.md`](../../docs/rendezvous/Task.5.md)

---

## Architecture

| Module | What it owns |
|---|---|
| `identity` | The service's stable `NodeId` + Ed25519 key, persisted so a restart does not present the host to the network as a brand-new, unmeasured service. Publishes the key in Java's X.509 form, because that is the only form Java verifies. |
| `directory` | Announcing a signed `ServiceRecord` to trackers, and merging the sibling lists the acks return — a union with a best-evidence tie-break, never an arbitration. |
| `drain` | `DrainState`: the atomic "am I still open for business" flag every request path reads, the in-flight counter a shutdown waits on, and the `WorkGuard` that releases it on every exit path including a panic. |
| `lifecycle` | The task that ties them together: the announce cadence, the drain sequence, and the update check. `ServiceHost` is what a concrete service implements — its kind, its capacity numbers, and how it reaches the peers holding its control channels. |
| `update` | Notice that the published binary is not the one running, verify the replacement against the release's digest manifest, stage it beside the executable, and swap it atomically. |

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
cd rust && cargo test -p nodera-service      # 38 tests
```

The ones that matter most:

- `drain::tests::draining_refuses_new_work_immediately` — the first obligation, which has to come first.
- `lifecycle::tests::a_drain_refuses_work_before_it_tells_anybody` — the ordering, asserted directly.
- `lifecycle::tests::in_flight_work_delays_the_drain_and_the_grace_period_bounds_it`.
- `update::tests::a_download_that_does_not_match_its_digest_is_refused_before_anything_is_staged` — a
  corrupted or substituted download never becomes the executable, and no drain is spent on it.
- `identity::tests::an_identity_survives_a_restart_because_the_score_should_too`.
- `directory::tests::an_inflated_composite_cannot_reorder_the_list`.
