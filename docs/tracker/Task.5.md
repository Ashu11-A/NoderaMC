# Tracker Task 5 — The service directory and the scoring plane

<!-- AI-AGENT-INSTRUCTION: This task makes the tracker answer a SECOND question ("which rendezvous
     points exist and which ones work") without making it authority over the answer. Every row it
     serves is a service's own signed record, and the composite score it publishes is recomputed by
     the peer from the components — so a lying tracker degrades routing exactly as much as it already
     degraded discovery, and no more. Do NOT let the tracker mint, re-word, or arbitrate a service
     record. Keep this header's status accurate. -->

**Status:** 🚧 IN PROGRESS
**Category:** tracker · **Owns:** L-81, L-82 · **Last audit:** 2026-07-27
**Depends on:** [tracker 1](Task.1.md), [tracker 2](Task.2.md), [network 1](../network/Task.1.md)
**Consumed by:** [rendezvous 5](../rendezvous/Task.5.md), [network 13](../network/Task.13.md),
[worker 3](../worker/Task.3.md), [app 2](../app/Task.2.md)

---

## Goal

A peer that knows one tracker address and nothing else can find the network's rendezvous points, tell
the good ones from the bad ones, and keep working when one of them restarts. The tracker gains a
service directory: rendezvous points and trackers **announce themselves** with signed records, peers
**query** the directory and **report what they measured**, and the tracker publishes an aggregate score
built from those measurements — as components, never as a verdict.

## Status detail

Landed and green (`cargo test -p nodera-tracker`, **102 tests**, up from 72).

- `src/services.rs` — the directory: admission (signature, freshness, trust-on-first-use binding),
  bounded state, TTL sweep, and the score aggregation.
- `src/service.rs` — dispatch for tags 67 / 69 / 71, a separate per-IP quota for score reports, and
  `sibling_directory` so an announcer is told about the other services of its kind but not itself.
- `src/config.rs` — `max_services`, `service_directory_page_limit`,
  `service_report_max_age_seconds`, `service_report_max_reporters`, `per_ip_report_quota`, the
  `update_*` keys, `peer_tracker_endpoints`, `advertised_routes`, `identity_file`.
- `src/telemetry.rs` — `tracker.services` (listed / draining counts; no service ids).
- `src/main.rs` — the shared `nodera-service` lifecycle task: identity, self-announce, drain, update.

Remaining: the operator documentation for the new keys, which is
[Task 3](Task.3.md)'s deployment section rather than a second document here.

## Dependencies

- [tracker 1](Task.1.md) — the admission and sweep machinery this reuses shape-for-shape.
- [tracker 2](Task.2.md) — `TrackerClient`, which gains `serviceDirectory` and `reportServiceScores`.
- [network 1](../network/Task.1.md) — the canonical encoding; six new message tags and four type tags.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | Wire family: tags 67–72, type tags 115–118, Java + Rust + golden fixtures in one commit | ✅ |
| 2 | `ServiceDirectory`: signed admission, TOFU binding, bounded state, TTL sweep | ✅ |
| 3 | Score aggregation: capped per-reporter influence, median RTT, freshness decay | ✅ |
| 4 | `SERVICE_DIRECTORY_QUERY` answered best-first, deterministically | ✅ |
| 5 | `SERVICE_SCORE_REPORT` verified and attributed, with its own quota | ✅ |
| 6 | Draining services stay listed with their deadline (a peer must see *why*) | ✅ |
| 7 | The tracker announces *itself*, so trackers are discoverable the same way | ✅ |
| 8 | `tracker.services` telemetry — counts only | ✅ |
| 9 | Self-update from a GitHub release, off by default, drain before install | ✅ |
| 10 | Operator documentation for the new keys ([Task 3](Task.3.md)) | ⬜ |

## Design

**A service signs its own address record, and that is the one new key in the system.** The category
convention was "the service holds no signing keys". It now holds exactly one, and it signs exactly one
kind of value: `ServiceRecord` — where I am, what version I run, how loaded I am, when I am leaving.
Two things need it. A drain notice is an *eviction primitive*; unsigned, it is a cheap way for anyone on
the path to herd a target's traffic onto a relay they run. And a peer that wants to pin the relays that
actually worked for it needs a name that is not an address. The invariant that still holds without
qualification: **nothing a service signs is authority over world state.** No world key, no vote, no
region, no state root.

**The composite is published and recomputed.** `ServiceScore::composite` is byte-identical in Rust and
Java (`ServiceScore.composite`, asserted across the language boundary by the golden fixture). The
tracker fills the field; the peer recomputes it from the components and uses its own number. So the
score is a convenience for dashboards and for peers that do not care, and a lever for nobody. This is
the same boundary that keeps the tracker from lying about worlds, applied to routing.

**Weights 40/30/20/10 over availability, latency, capacity, freshness.** Availability dominating
latency is deliberate: registration and discovery are latency-tolerant
([`../rendezvous/REFERENCE.md`](../rendezvous/REFERENCE.md) §15), so a slow rendezvous that is always up
must outrank a fast one that is usually down. The latency term reads **p95, not the median** — a relay
with a bad tail is bad, and a median hides exactly the stalls that leave a peer sitting on a path it
cannot use. `rtt == 0` is the *unmeasured* sentinel rather than an instant round trip; without that, an
unprobed service would score as the fastest thing in the directory.

**Per-reporter influence is capped, and latency is a median.** `PROBE_CAP_PER_REPORTER = 100` bounds
how far one identity can move an availability aggregate — an influence limit, not a rate limit, because
the reporter might be lying. Taking the median of reporters' RTTs rather than the mean means three
honest reporters cannot be outvoted by one claiming a 30-second round trip. Without both, scoring would
be the cheapest denial-of-service in the system: report every rival relay as dead and take over routing.

**Peers report counters, never verdicts.** A peer that said "this service is bad" would be asking the
tracker to trust its judgement. A peer that says "I probed it 20 times and 3 answered" lets the tracker
aggregate evidence and lets other peers' numbers outvote a liar.

**Freshness decays instead of cliff-edging.** A service's freshness term falls linearly from full at its
last refresh to nothing at its record's expiry, so one that stopped refreshing slides out of selection
*before* the sweep removes it. A hard cliff at the TTL would keep a dead rendezvous top-ranked until the
sweep tick.

**Reports get their own quota.** Score reports are cheap to send and expensive to aggregate; sharing the
announce budget would let a report flood push the world list off the air.

**Updating is off unless configured.** `update_channel` is empty by default. Someone who downloaded a
tracker agreed to run a tracker; they did not agree to let it replace its own executable — the same
consent rule the telemetry lane follows, for the same reason. The comparison is a **digest**, not a
version string, because the rolling `latest` release keeps `VERSION` at one value across hundreds of
builds; comparing the published SHA-256 of our own asset against the digest of the running file answers
"am I running what is published" with no version plumbing.

## Files

- `rust/nodera-tracker/src/services.rs` — the directory and the aggregation
- `rust/nodera-tracker/src/service.rs` — dispatch, quotas, sibling directory
- `rust/nodera-tracker/src/config.rs` — the new keys
- `rust/nodera-service/` — identity, announce client, drain, update, lifecycle (shared with rendezvous)
- `rust/nodera-codec/src/service.rs` — the wire family and the score function
- `java/transport/.../protocol/service/` — the Java mirror of the family
- `java/core/.../crypto/TypeTags.java` — 115–118
- `java/transport/.../protocol/codec/MessageCodec.java` — 67–72
- `java/peer/.../discovery/TrackerClient.java` — `serviceDirectory`, `reportServiceScores`
- `fixtures/wire/service-*.bin` — the six golden frames

## Testing

```bash
cd rust && cargo test -p nodera-tracker      # 102 tests
cd rust && cargo test -p nodera-service      # 38 tests
cd rust && cargo test -p nodera-codec        # 48 + fixtures + tag mirror
./gradlew :transport:test --tests '*ServiceMessageCodecTest*'
./gradlew :peer:test --tests '*ServiceScoreBoardTest*' --tests '*RendezvousDirectoryTest*'
```

Decisive tests:

- `service::tests::a_rendezvous_announces_itself_and_peers_can_then_discover_it` — the whole point of
  the lane, driven through the real `handle_frame` dispatch.
- `service::tests::a_tampered_service_record_is_refused_and_never_listed` — the row a tracker cannot
  fabricate.
- `services::tests::one_reporter_cannot_outvote_many_by_claiming_more_probes` and
  `latency_is_a_median_so_three_honest_reporters_beat_one_liar` — the two anti-abuse properties.
- `services::tests::freshness_decays_between_the_refresh_and_the_expiry` — no cliff at the TTL.
- `service::tests::a_report_flood_cannot_starve_the_announce_budget` — the separate quotas.
- `services::tests::the_published_composite_is_what_a_peer_recomputes` — the non-authority argument.
- `fixtures.rs` — the six frames round-trip byte-exactly **and** the Java-computed composite equals the
  Rust-computed one. A divergence there would silently reorder every peer's failover list.
- `update::tests::a_download_that_does_not_match_its_digest_is_refused_before_anything_is_staged` — the
  decisive property of the update lane.

## Acceptance criteria

1. ✅ A peer configured with only a tracker address discovers a rendezvous it was never told about.
2. ✅ Every directory row is verifiable without trusting the tracker that served it.
3. ✅ A wrong or inflated composite changes no peer's behaviour.
4. ✅ One identity cannot move a score enough to redirect the network.
5. ✅ A draining service is visible, with its deadline, to a peer that asks.
6. ✅ Tracker down ⇒ discovery degrades; a peer keeps the relays it already selected.
7. ✅ Updating is inert unless an operator configures a channel, and never installs an unverified file.
8. ⬜ The new configuration keys are documented for operators ([Task 3](Task.3.md)).

## Limitations

Owns **L-81** (release artifacts carry a digest but no signature — integrity, not provenance) and
**L-82** (the update fetcher shells out to `curl`). Both are in
[`LIMITATIONS.md`](LIMITATIONS.md) with their exit tests.
