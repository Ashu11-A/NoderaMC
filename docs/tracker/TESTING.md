# Tracker — Testing

<!-- AI-AGENT-INSTRUCTION: Counts come from `cargo test -p nodera-tracker` and the Java gate, never
     from memory. The decisive test for this category drives the REAL RELEASE BINARY from Java — a
     test that stubs the service proves nothing about the property this category exists for. Always
     include a degradation test when touching the service: tracker down must degrade discovery only.
     Keep counts and Last run current. -->

**Category:** tracker · **Last run:** 2026-07-28 · **109 Rust `#[test]`s · 0 failing** (grep-verified)
in `tracker`, plus **89 `@Test` methods** in `peer/.../dev/nodera/peer/discovery/`
(including `TrackerServiceIT` and the client tests on the Java gate, and 38 in the shared
`nodera-service` crate).

```bash
cd rust && cargo test -p nodera-tracker      # the service (109 #[test]s)
cd rust && cargo test -p nodera-service      # identity, announce, drain, update (38; incl. L-81's exit test)
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

## 2. Rust unit coverage (109 `#[test]`s across the crate)

Counts by file (grep `#\[test\]`/`#\[tokio::test\]`):

| File | `#[test]`s | Focus |
|---|---|---|
| `service.rs` | 26 | dispatch, the announce path, the service directory through real `handle_frame`, deletion handling |
| `services.rs` | 20 | directory admission, TOFU binding, score aggregation, anti-abuse, freshness decay |
| `query.rs` | 11 | sampling + seeder floor, catalog, routes, population semantics |
| `registry.rs` | 9 | announce replacement, TTL expiry, per-world isolation, world/peer limits |
| `config.rs` | 8 | defaults, validation, env-overlay precedence, the drift guard |
| `announce.rs` | 6 | signature/freshness/identity-binding admission |
| `wire.rs` | 7 | TCP + UDP over real sockets, amplification cap, silent drop |
| `health.rs` | 6 | health + countdown transitions |
| `deletion.rs` | 5 | verified-deletion cache, persistence, restart, edited-file rejection |
| `limits.rs` | 5 | per-IP quota windows |
| `main.rs` | 4 | arg parsing (`--healthcheck`/`--version`/`--config`/`--bind`) |
| `telemetry.rs` | 2 | off-without-endpoint; platform-label enums |

Coverage spans: announce lifecycle, including re-announce **replacement**; TTL expiry via the
last-seen sweep; `STOPPED` removes immediately; per-world isolation; sampling bounds and the
**seeder floor**; quota rejection (per IP, per identity) and the separate report quota; record-size
caps and bounded world/peer counts; invalid-signature rejection; health and retention-countdown
transitions; UDP (one datagram per request, the anti-amplification cap, silent drop of undecodable
datagrams); the world-deletion lane (verified unlisting, re-list refusal with proof, persistence,
edited-file rejection); and the service directory (signed admission, TOFU binding, tampered-record
refusal, draining-with-deadline, bounded reporter tables, freshness decay, the two anti-abuse
properties, the non-authority composite).

## 3. `TrackerServiceIT` — the decisive scenario

Driven from Java against the real binary:

1. Two peers announce two worlds; per-world isolation holds.
2. A JDK-`NodeIdentity`-signed announce is verified inside the service by `ed25519-dalek` — proving
   the Java and Rust crypto agree on the exact signed bytes.
3. A tampered record is refused with `bad-signature` and never reaches the registry.
4. A `STOPPED` announce removes the peer immediately.
5. **The exit scenario:** a world whose every Java seeder has gone silent past the TTL is still listed
   by name, with its countdown and a DEAD verdict.

## 4. Client tests (`peer/.../discovery/`)

- `TrackerServiceIT` (7 `@Test`s) — the client against the real binary, including the deletion-notice
  hand-back and the service-directory + score-report round trips.
- `TrackerEndpointTest` — scheme parsing and the bare-host-stays-TCP contract.
- `TrackerClientUdpTest` — the datagram path and the TCP fallback when an answer would exceed the
  bound.
- `ServiceScoreBoardTest` (18) — peer-local scoring, percentile windows, selection ordering.
- `RendezvousDirectoryTest` (11) — sweep/probe/select, drain-notice migration, seeds-preferred.
- `PersistentIdentityStoreTest`, `PinnedTrackerEndpointsTest`, `CommonsPresenceTest` — the identity
  and endpoint-configuration surfaces.

> **The Java-side bootstrap and directory caches are gone (2026-08-06, issue #210).** This list used
> to name `CachedPeerStoreTest`, `InvitationCodecTest`, `BootstrapClientTest`, `MultiBootstrapIT`,
> `PeerDirectoryTest` and `ArchiveInventoryTest`. Commit `0b02aa5` deleted all six with the classes
> they covered: `BootstrapClient` was a second discovery resolver — cached-peer redial plus a pasted
> invitation blob — superseded by `PeerDiscoveryService` + `TrackerClient`, and `ArchiveInventory`
> was a tracker cache that only the deleted repair lane consulted. The seeder index those tests
> described now lives in the **Rust** tracker (`tracker/src/registry.rs`), fed by the
> `ManifestHolding` list on each `TrackerAnnounce` and read back as `ManifestSeeders`; its coverage
> is `cargo test -p nodera-tracker`, not the Java gate.
>
> One property left with them and has not been rewritten: `MultiBootstrapIT` proved a new client
> still joins when its original bootstrap is **offline**, by each of three independent routes.
> Nothing in the tree asserts that today. Recorded in
> [`../network/TESTING.md`](../network/TESTING.md) §2.1 and against L-34.
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
