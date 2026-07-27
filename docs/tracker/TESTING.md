# Tracker — Testing

<!-- AI-AGENT-INSTRUCTION: Counts come from `cargo test -p nodera-tracker` and the Java gate, never
     from memory. The decisive test for this category drives the REAL RELEASE BINARY from Java — a
     test that stubs the service proves nothing about the property this category exists for. Always
     include a degradation test when touching the service: tracker down must degrade discovery only.
     Keep counts and Last run current. -->

**Category:** tracker · **Last run:** 2026-07-27 · **102 Rust tests · 0 failing** (plus
`TrackerServiceIT` and the client tests on the Java gate, and 38 in the shared `nodera-service` crate)

```bash
cd rust && cargo test -p nodera-tracker      # the service (102)
cd rust && cargo test -p nodera-service      # identity, announce, drain, update (38)
./gradlew :peer:test --tests '*Tracker*'     # the client + the real-binary IT
./gradlew :peer:test --tests '*ServiceScoreBoardTest*' --tests '*RendezvousDirectoryTest*'
```

---

## 1. The testing strategy

The service is tested at three levels, and the third is the one that matters:

| Level | What it proves |
|---|---|
| Rust unit tests | Each mechanism in isolation: announce lifecycle, TTL sweep, sampling, quotas, health |
| Cross-language conformance | Every announce and query message round-trips byte-exactly against Java-emitted fixtures; the tag mirror fails CI if one side appends alone |
| **Real-binary integration** | `TrackerServiceIT` spawns the actual release binary and drives it from Java peers — the only level that proves the two implementations agree in practice |

## 2. Rust unit coverage

- Announce lifecycle, including re-announce **replacement** (a peer announcing twice must not appear
  twice).
- TTL expiry via the last-seen sweep; `STOPPED` removes immediately.
- Per-world isolation: two worlds announced by the same peers stay separate swarms.
- Sampling bounds and the **seeder floor** — a sample that contained no seeders would leave a joiner
  with peers but no data.
- Quota rejection (per IP, per identity), record-size caps, bounded world and peer counts.
- Invalid-signature rejection: the record never reaches the registry.
- Health and retention-countdown transitions.
- UDP: one datagram per request, the anti-amplification cap, silent drop of undecodable datagrams.
- The service directory (Task 5): signed admission and trust-on-first-use binding, a tampered record
  refused, a draining service still listed with its deadline, a stopped one removed, bounded reporter
  tables, freshness decay between refresh and expiry, and the two anti-abuse properties — a capped
  reporter cannot sink an available service, and three honest reporters beat one liar on the median.
- Separate quotas: a score-report flood cannot starve the announce budget the world list depends on.

## 3. `TrackerServiceIT` — the decisive scenario

Driven from Java against the real binary:

1. Two peers announce two worlds; per-world isolation holds.
2. A JDK-`NodeIdentity`-signed announce is verified inside the service by `ed25519-dalek` — proving
   the Java and Rust crypto agree on the exact signed bytes.
3. A tampered record is refused with `bad-signature` and never reaches the registry.
4. A `STOPPED` announce removes the peer immediately.
5. **The exit scenario:** a world whose every Java seeder has gone silent past the TTL is still listed
   by name, with its countdown and a DEAD verdict.

## 4. Client tests

- `TrackerEndpointTest` — scheme parsing and the bare-host-stays-TCP contract.
- `TrackerClientUdpTest` — the datagram path and the TCP fallback when an answer would exceed the
  bound.
- Merge behaviour: results from several endpoints combine without arbitration; an unreachable endpoint
  backs off without failing the query.

## 5. Conventions

- **Test the degradation path.** Every change to this service should be accompanied by the question
  "what happens when it is down?" — the answer must be *discovery degrades, mesh and state
  unaffected*, and that must be exercised, not assumed.
- **A stubbed tracker proves nothing here.** The category exists because an embedded implementation
  could not satisfy the offline-seeder scenario; only the real binary can demonstrate that it does.
- **Every new message** lands with a Java golden fixture and the Rust mirror in the same commit.

## 6. Live evidence

Live tracker behaviour is exercised by the mod's scripted suites (see
[`../minecraft/TESTING.md`](../minecraft/TESTING.md)); `tracker.log` in a suite run shows announce
acks and signed-record registrations.
