# Tracker reference

<!-- AI-AGENT-INSTRUCTION: This is the REFERENCE for `nodera-tracker`, not a study of trackers in
     general. Every row here must be true of the code in the `tracker` crate — settings against
     `src/config.rs`, refusal codes against `src/announce.rs`, verbs against `src/service.rs`,
     metrics against `src/telemetry.rs`. If a value cannot be pointed at in that crate, it does not
     belong here. Cite sections by TITLE, never by number: the numbers moved once already and took
     fourteen source-comment citations with them. The operator walkthrough is SELF-HOSTING.md; do
     not duplicate its prose here, and do not duplicate this table there. -->

**Category:** tracker · **Applies to:** the `nodera-tracker` binary and the Java `TrackerClient`.

A tracker answers three questions and nothing else: *which worlds exist*, *who is announcing one*,
and *where can I dial them*. It never stores a world, transfers a piece, verifies content, or
relays traffic. It holds **no authority** — a lying tracker can hide peers or list unreachable ones;
it cannot forge a world (hash-verified), an identity (Ed25519-signed against the bytes it received),
or a vote. Losing every tracker degrades discovery and never correctness.

For installing and running one, see [`SELF-HOSTING.md`](SELF-HOSTING.md). This page is the contract.

## Surfaces

| Surface | Framing | Default |
|---|---|---|
| TCP | `u32` big-endian length prefix, then one NDR2 frame; one task per connection | `0.0.0.0:25600` |
| UDP | the datagram **is** the frame — no length prefix, one request and one reply per datagram | same address, `udp_enabled = true` |

Both surfaces decode into the same handler, so a world announced over one is queryable over the
other. There is no HTTP surface and no bencode: one frozen canonical encoding for the whole network
is what keeps the Java↔Rust conformance tests meaningful.

Endpoint routes parse as `host:port`, `tcp://host:port` or `udp://host:port`. A bare route is TCP,
so UDP is always an explicit opt-in.

**UDP is bounded three ways**, because a UDP source address is forgeable and an unbounded answer
would make the service a reflection amplifier: a request-size cap, a reply-size cap, and a
reply-to-request amplification ratio. An answer that exceeds a cap is **not sent and not
truncated** — a truncated canonical frame is undecodable, so silence is the honest outcome and the
client retries the same endpoint over TCP, where the handshake already proved the source address.
This replaces the classic connect/announce cookie exchange: the cookie proves an address, and a
signed announce proves the identity, which is the stronger of the two.

## Verbs

Kinds are the frozen wire kinds of [`../network/REFERENCE.md`](../network/REFERENCE.md). Anything
else is answered as unsupported.

| In | Out | What it does |
|---|---|---|
| `TrackerAnnounce` 33 | `TrackerAnnounceAck` 34 | Register, refresh or remove this peer's record for one world. The ack carries `accepted`, `next_announce_after_seconds`, and a refusal code when refused |
| `TrackerQuery` 27 | `TrackerResponse` 28 | One world: a sampled peer list, per-manifest seeders, live player count, distinct stored pieces, mean reliability, health, retention deadline |
| `TrackerCatalogQuery` 44 | `TrackerCatalogResponse` 45 | Every tracked world, sorted by name then hash. Page defaults to 64 and is capped at 256 whatever the query asks |
| `TrackerRoutesQuery` 49 | `TrackerRoutesResponse` 50 | Every live peer's full claimed route list, verbatim — this is where a joiner finds the `mc/host:port` game endpoint |
| `WorldDeletionGossip` 66 | — | Remember an owner-signed deletion (see *Deleted worlds*) |
| `WorldRevivalGossip` 76 | — | Undo one, on the same evidence rules |
| `ServiceAnnounce` 67 | `ServiceAnnounceAck` 68 | A tracker or relay lists itself. The ack carries the announcer's siblings, so a draining service gets its replacement list in the round trip it is least able to make twice |
| `ServiceDirectoryQuery` 69 | `ServiceDirectoryResponse` 70 | The service directory, filtered by kind and network |
| `ServiceScoreReport` 71 | — | A peer's measurement of a service it used |

`InventoryAdvertisement` is refused deliberately: holdings arrive only inside a signed announce, and
accepting a third party's claim about somebody else's content is a way to lie about durability.
`ServiceDrainNotice` 72 is likewise refused inbound — a tracker holds no service control channels,
so receiving one means somebody is talking to the wrong port.

## Announce admission

Every cheap refusal happens before every expensive operation. In order:

1. **Tombstone.** A world its owner deleted is not re-listed, whoever announces it. The announcer
   gets the owner's signed record back so it can verify the refusal itself.
2. **Per-IP quota.** A fixed window of `per_ip_announce_quota` announces per announce interval.
   Before signature verification, so flooding cannot buy CPU.
3. **Signature.** Ed25519 over the signed byte range *as it arrived*, never over a re-encoding —
   verifying a re-encoding checks this implementation against itself.
4. **Freshness.** The announce's own timestamp must be within `announce_clock_skew_seconds` of the
   tracker's clock, in **both** directions, so a captured announce cannot resurrect a departed peer.
5. **Identity binding.** Trust-on-first-use: the first public key to claim a `NodeId` keeps it for
   as long as this tracker remembers it. A graceful `STOPPED` releases the binding, so a peer that
   rotates its key after leaving is not locked out of its own id.
6. **Quotas.** World ceiling, then per-world peer ceiling.

| Refusal code | Meaning |
|---|---|
| `bad-signature` | Signature did not verify, or the key was malformed |
| `stale-announce` | Timestamp outside the skew window — stale or replayed |
| `identity-mismatch` | This `NodeId` is already bound to a different key here |
| `quota` | The source address exceeded its announce quota |
| `too-large` | The record is larger than `max_frame_bytes` |
| `world-limit` | At `max_worlds`, and this world is not already tracked |
| `world-full` | At `max_peers_per_world` for this world |
| `undecodable-announce` | An announce body this build cannot read |

The last one is a refusal rather than a hang-up on purpose. The kind is legible even when the body
is not, so the tracker can always say *I did not register you* — the one thing the announcer cannot
work out for itself. Every other undecodable frame is dropped.

**Display metadata is honoured only from the world's `FULL_ARCHIVE` host.** Taking a name from any
announcer would let a passer-by rename someone else's world in every player's server list.

## Records, sampling and expiry

One record per `(world, peer identity)` — keyed by identity, never by address. A re-announce
**replaces** the previous record rather than merging it, so a peer that changed address or state is
never a blend of its past and present.

| Field | Note |
|---|---|
| `routes` | The peer's own claimed dial routes, in preference order |
| `observed_route` | Where the announce came from, appended **last** and never substituted for a claim. A signed route list may name a forwarded port the tracker cannot observe |
| `capabilities` | Declared roles; a seeding role is what makes the peer a seeder |
| `holdings` | Which pieces of which manifests this peer holds for this world |
| `reliability_bps` | Self-reported, in basis points |
| `world_player_count` | Players this peer can see, or `-1` for "I cannot see". Per peer, because that is how it arrives and how it expires |
| `last_seen_millis` | Tracker clock at the last accepted announce |

**Expiry** is `last_seen_millis + peer_ttl_seconds`, applied lazily on read *and* by a sweep, so an
answer never depends on sweep timing. `STOPPED` is an optimisation, not the mechanism: clients
crash, suspend and lose connectivity, so `peer_ttl_seconds` must stay comfortably above
`announce_interval_seconds` — the configuration refuses to start otherwise.

**Sampling.** A query returns at most `sample_size` peers, with `seeder_floor` seeders taken first.
The `world_player_count` in the response is computed from the **whole** swarm rather than the page,
so a busy world is not under-reported by its own page size.

**Shedding.** At `max_worlds`, the tracker drops the least recently active world that holds no live
peers before it refuses a new one. If no such world exists the announce is refused with
`world-limit`.

**State is ephemeral by design.** A restart loses nothing that matters — every live peer
re-announces within one interval. Only world display names and the deleted-world cache are
persisted, and only when `persist_dir` is set.

## World health

| Verdict | Condition |
|---|---|
| `HEALTHY` | seeders ≥ `healthy_seeder_floor` |
| `DEAD` | zero seeders **and** a retention deadline that has passed |
| `DEGRADED` | everything else, including zero seeders inside the window and zero seeders with no deadline at all |

A world with no seeders is not instantly dead — that would paint a host's world gray the moment they
reboot. The tracker only *surfaces* the retention countdown; the peers' own retention policy owns
the actual drop, which is what keeps the tracker a directory rather than a decision-maker. A seeder
returning cancels the verdict even past the deadline.

Note that `healthy_seeder_floor` defaults to 5, so a freshly shared world with one or two seeders
reads `DEGRADED` in the directory. That is the classifier working, not a fault.

## Deleted worlds

A deletion is remembered for **120 days**, keyed by world hash. Removing the world from the registry
is not enough on its own: peers that were offline when the owner deleted it keep announcing, and a
tracker with no memory would re-list the world from the first such announce. Keeping the record
turns that around — an announce for a deleted world is answered with the owner's signed tombstone,
which the announcer verifies for itself exactly as the tracker did.

The window is a judgement, not a proof: a peer reappearing after it, with the bytes still on disk,
will find nobody left to contradict it. Without `persist_dir` the cache is memory-only and the
window really means "until this process restarts".

## Service directory

A tracker also lists the network's infrastructure. Services announce themselves with a signed
record, peers query the directory and report back what they measured, and a service that is draining
says so, so peers migrate before its circuits break.

* `max_services` defaults to **256** — deliberately far below `max_worlds`. A network has a handful
  of infrastructure hosts and thousands of worlds, so a directory grown to world scale is evidence
  of abuse rather than of success.
* A score is the median of at most `service_report_max_reporters` distinct reporters, each
  contribution counting for `service_report_max_age_seconds`. That number is the width of the
  evidence, and therefore how many identities an attacker needs before the median moves.
* Score reports carry their own per-IP quota, `per_ip_report_quota`, separate from announces.

A `ServiceAnnounce` is admitted on rules close to a peer announce's, but **not in the same order**,
and the order is part of the contract: shape (`malformed-record`), then freshness (`stale-record`),
then the signature over the bytes as they arrived (`bad-signature`), then trust-on-first-use identity
binding (`identity-mismatch`), then the directory ceiling (`directory-full`). The per-IP announce
quota (`quota`) is charged before any of it. Note the consequence, because it differs from the peer
path, which verifies the signature first: a service record is answered `malformed-record` or
`stale-record` **before** its signature is checked, so an unauthenticated sender can probe
`announce_clock_skew_seconds` with junk in the signature field. That is a deliberate
cheapest-check-first ordering, not an oversight, and it is stated here so it cannot be changed by
accident.

Three of these codes also answer a `ServiceScoreReport` (tag 71) rather than an announce: `quota`
against `per_ip_report_quota`, plus `stale-record` and `bad-signature`. The codes are stable and
machine-readable rather than prose:

| Ack code | `accepted` | Meaning |
|---|---|---|
| `quota` | `false` | The source address exceeded `per_ip_announce_quota` |
| `bad-signature` | `false` | The signature did not verify against the record's own key |
| `stale-record` | `false` | The record's issue time is outside `announce_clock_skew_seconds` |
| `identity-mismatch` | `false` | This service `NodeId` is already bound to a different key here |
| `directory-full` | `false` | At `max_services`, with no expired row to reuse |
| `malformed-record` | `false` | `Draining` with no drain deadline — unplannable, and the shape a truncated record has |
| `not-listed` | `false` | A `Stopped` record for a service this tracker was not listing |
| *(empty)* | `true` | Registered, refreshed, or delisted |

`not-listed` is the only one that is not a refusal to do something. The tracker did exactly what was
asked — there was nothing to remove — and says so because it is the one fact the announcer cannot
work out for itself: it means this service's listing had been expiring between announces, so peers
querying *this* tracker were being answered without it. The draining service logs the code and the
endpoint; a successful-looking round trip would have hidden a discovery outage.

## Settings

Every setting is a key in `nodera-tracker.toml` **and** an environment variable: `NODERA_TRACKER_`
plus the key, uppercased. Precedence is **defaults → config file → environment → command line**.
An unrecognised `NODERA_TRACKER_*` variable refuses the start rather than leaving an operator
believing a bound is in force. `NODERA_TRACKER_ENDPOINTS` is the exception: it belongs to the Java
peer, and is ignored here so one shell can start both.

| Key | Default | Unit / meaning |
|---|---|---|
| `bind_addr` | `0.0.0.0:25600` | Listen address for both surfaces |
| `advertised_routes` | empty | Public routes this tracker publishes about itself. Empty falls back to `bind_addr`, which is wrong behind NAT |
| `identity_file` | `nodera-tracker-identity.bin` | Service signing key, created on first start. Preserve it: a regenerated one is a brand-new unmeasured host to every peer |
| `persist_dir` | unset | World display names and the deleted-world cache. Peer state is never persisted |
| `announce_interval_seconds` | `120` | The cadence handed back in every ack — the tracker paces announce traffic, not the peer |
| `peer_ttl_seconds` | `300` | How long a record survives without a refresh. Must be ≥ the interval |
| `announce_clock_skew_seconds` | `300` | Accepted deviation of an announce's own timestamp, both directions |
| `max_worlds` | `10000` | Worlds tracked at once; the idlest peerless world is shed beyond it |
| `max_peers_per_world` | `5000` | Records retained per world |
| `sample_size` | `50` | Peers returned in one query response |
| `seeder_floor` | `10` | Seeders taken before the rest of a sample is filled |
| `healthy_seeder_floor` | `5` | Seeders a world needs to read `HEALTHY` |
| `per_ip_announce_quota` | `60` | Announces accepted per source IP per interval |
| `max_frame_bytes` | `262144` | Largest accepted frame; may not exceed the protocol cap |
| `udp_enabled` | `true` | Serve the same requests over UDP on `bind_addr` |
| `udp_max_request_bytes` | `8192` | Largest accepted request datagram; may not exceed `max_frame_bytes` |
| `udp_max_reply_bytes` | `32768` | Largest reply emitted; a bigger answer is dropped, not truncated |
| `udp_max_amplification` | `4` | Reply-to-request size ratio ceiling |
| `max_services` | `256` | Size of the service directory |
| `service_directory_page_limit` | `32` | Rows in one directory answer |
| `service_report_max_age_seconds` | `900` | How long one peer's measurement still counts |
| `service_report_max_reporters` | `32` | Distinct reporters retained per service |
| `per_ip_report_quota` | `30` | Score reports accepted per source IP per interval |
| `peer_tracker_endpoints` | empty | Other trackers to announce *this* one to. Empty is normal for a single-tracker deployment |
| `telemetry_endpoint` | empty — **off** | Where to report this service's own counters |
| `telemetry_interval_seconds` | `300` | Reporting window; floored at 30 |
| `update_channel` | empty — **off** | Release channel for self-update |
| `update_feed_base_url` | the project's releases | Must be `https://` when a channel is set |
| `update_check_interval_seconds` | `3600` | Must be positive when a channel is set |
| `update_release_public_key` | this build's key | The Ed25519 key a release manifest must be signed with, 64 hex characters |
| `drain_grace_seconds` | `30` | How long in-flight work may hold up a drain |

**Refused at load**, rather than discovered under traffic: a zero announce interval; a
`peer_ttl_seconds` below it (which would expire every peer between its own announces, so the world
list flickers empty however healthy the swarm is); a zero `max_worlds`, `max_peers_per_world`,
`sample_size`, `healthy_seeder_floor` or `max_frame_bytes`; a `max_frame_bytes` above the protocol
cap; a zero UDP bound while UDP is enabled; a `udp_max_request_bytes` above `max_frame_bytes`; a
zero service-directory bound; and, when `update_channel` is set, a zero check interval or a
non-`https://` feed.

## Command line

```
nodera-tracker [--config <file>] [--bind <addr>] [--healthcheck <addr>] [--print-env] [--version]
```

`--print-env` lists every environment variable this build understands — ask the binary rather than
trusting a document. `--healthcheck` speaks the real protocol against a running instance and exits
non-zero if it does not answer; a port scan proves a socket is open, which is a different claim.

## Metrics

Off unless `telemetry_endpoint` is set. What leaves is windowed counters and `'static` labels; a
value derived from a request is not representable in the event type, so a peer, a world hash or an
address cannot be reported even by accident.

| Event | Numbers |
|---|---|
| `service.start` | once at boot, labelled with OS and architecture only |
| `tracker.window` | `announces`, `announces_rejected`, `queries`, `worlds`, `window_seconds` |
| `tracker.world_health` | `healthy`, `degraded`, `dead` |
| `tracker.services` | `listed`, `draining_rendezvous`, `draining_trackers` |

The first three are window deltas over cumulative counters, because a dashboard wants rates.

## Failure modes

| Symptom | Usual cause | Lever |
|---|---|---|
| The tracker is down | — | Existing swarms keep transferring; only *new* peers with no other route in are blocked. The hazard is the reconnection storm on recovery, not the outage |
| The world list flickers empty | `peer_ttl_seconds` too close to `announce_interval_seconds` | Keep the TTL at roughly twice the interval |
| A busy world answers "0 peers" over UDP | The reply exceeded `udp_max_reply_bytes` or the amplification ratio | Nothing, on this side: it is the designed behaviour and the client must fall back to TCP. A third-party client that skips the fallback reports busy worlds as empty |
| Every peer in every world shares one address | The service is behind a proxy that does not preserve the client address | Fix the proxy; the observed route is a hint, but a hint identical for everyone is noise |
| Memory grows without bound | `max_worlds` or `max_peers_per_world` too high for the host | Both ceilings are attacker-influenced; lower them and confirm the shed path under load |
| Announce rate collapses after a config change | An interval change only takes effect as each client comes back | Treat interval changes as staged rollouts; they cannot be reverted faster than one interval |
| A world reads `DEGRADED` that an operator expects healthy | Fewer seeders than `healthy_seeder_floor` | Expected for a freshly shared world |

## What this service deliberately does not do

* **No scrape and no full enumeration by identifier.** The catalog verb covers the product need — a
  player browses worlds — and is bounded by a page cap.
* **No `min interval`.** The ack carries only `next_announce_after_seconds`; there is no floor a
  misbehaving client can be held to. Callers clamp their own cadence, which a third-party peer is
  under no obligation to copy.
* **No replication between trackers.** Instances are independent and ephemeral. Peers query several
  and **merge** the answers, so a tracker that omits peers dilutes its own influence rather than
  censoring the world. Adding a second tracker without clients querying both *partitions*
  discovery rather than doubling it.
* **No public/private distinction.** A world's `listed` flag lives in its identity record, not in
  anything a tracker enforces; any peer may announce any world to any tracker. "Unlisted" is not an
  access control.
* **No reachability testing.** The tracker never dials. A peer behind a NAT with no mapping looks
  exactly like a reachable one here; that is what the relay in
  [`../rendezvous/REFERENCE.md`](../rendezvous/REFERENCE.md) is for.
* **No content on the data path.** The tracker's bandwidth is announces and peer lists, independent
  of how much data a swarm moves. A design that quietly puts a discovery service on the data path
  has converted a cheap component into an expensive one.
