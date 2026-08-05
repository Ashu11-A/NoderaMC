# Rendezvous and relay reference

<!-- AI-AGENT-INSTRUCTION: This is the REFERENCE for `nodera-rendezvous`, not a study of NAT
     traversal in general. Every row must be true of the `rendezvous` crate — settings against
     `src/config.rs`, refusal codes against `src/register.rs`, teardown reasons against
     `src/circuit.rs`, verbs against `src/service.rs`, metrics against `src/telemetry.rs`. Cite
     sections by TITLE, never by number. The operator walkthrough is SELF-HOSTING.md; the two must
     not duplicate each other. -->

**Category:** rendezvous · **Applies to:** the `nodera-rendezvous` binary and the Java rendezvous
transport.

One binary, two jobs that must stay logically separate:

* **Rendezvous** — peers register a signed record into a namespace and discover each other's
  records. Cheap metadata; scales like a tracker.
* **Relay** — when no direct path can be made to work, the service bridges two legs of a circuit.
  This is the expensive half: every byte is received and sent again, so relay bandwidth is the only
  genuinely scarce resource here and everything about it is metered.

It holds **no authority and cannot read what it carries.** Records are self-signed and re-verified
by the discovering peer; the transport handshake re-proves identity on connect; circuit payloads are
end-to-end encrypted. A lying relay can refuse to forward or hide records. It cannot forge one, and
it cannot read a byte of what passes through it — it sees ciphertext, framing sizes, and the fact
that two identities are talking.

For installing and running one, see [`SELF-HOSTING.md`](SELF-HOSTING.md). This page is the contract.

## Namespaces

A namespace is `(networkId, genesisHash)` — one world is one namespace. The Java side derives
`networkId` from the world id, so a peer registering for a world is not visible to a query for any
other world. Namespacing is what keeps a discovery query bounded: without it a peer asks "who is out
there" and gets an answer sized by the network rather than by the session.

## Verbs

Kinds are the frozen wire kinds of [`../network/REFERENCE.md`](../network/REFERENCE.md).

| In | Out | What it does |
|---|---|---|
| `RendezvousRegister` 35 | `ObservedAddress` 43 | Register or refresh a signed record in a namespace. The reply reports the caller's reflexive address, so a peer learns the public route its NAT presents and can publish it as a candidate |
| `RendezvousDiscover` 36 | `RendezvousPeers` 37 | One bounded page of *signed* records, ordered by node id so a cursor is stable across queries. A namespace nobody is in answers an empty page, never silence |
| `RelayReserve` 38 | `RelayReservation` 39 | Reserve inbound relay capacity: a relay route, an expiry, a byte ceiling, a duration ceiling, and an HMAC proof over exactly those limits |
| `RelayConnect` 40 | `RelayIncoming` 41 | Ask the service to bridge to a reserved peer; the target is notified over its control channel and the two legs are spliced |
| `PunchSync` 42 | forwarded | Coordinate a simultaneous open (see *Hole punching*) |

**Server-originated kinds are refused inbound.** A peer that sends `RendezvousPeers`,
`RelayReservation`, `RelayIncoming` or `ObservedAddress` is dropped with a reason, which keeps the
state machine one-directional.

## Registration admission

1. **Frame size** — anything above `max_frame_bytes` is dropped as `too-large` before decoding.
2. **Per-IP quota** — `per_ip_request_quota` register and reserve requests per refresh interval.
3. **Signature** — Ed25519 over the record's own signed byte range, as received.
4. **Freshness** — the record's `issuedAt` must be within `clock_skew_seconds`, both directions.
5. **Identity binding** — trust-on-first-use, per service, exactly as the tracker does. A graceful
   departure releases the binding.
6. **Quotas** — `max_records_per_namespace`, then `max_namespaces`.

| Refusal code | Meaning |
|---|---|
| `bad-signature` | Signature did not verify, or the key was malformed |
| `stale-record` | `issuedAt` outside the skew window |
| `identity-mismatch` | This `NodeId` is already bound to a different key here |
| `quota` | The source address exceeded its request quota |
| `too-large` | The frame exceeds `max_frame_bytes` |
| `namespace-limit` | At `max_namespaces`, and this namespace is not already tracked |
| `namespace-full` | At `max_records_per_namespace` |
| `draining` | The service is shutting down and will not take new work |

**Registrations are leases, not state.** The effective expiry is the *sooner* of
`registration_ttl_seconds` and the record's own self-declared `expiresAt`, so a peer can ask to be
forgotten early while the service still caps how long one registration keeps a record alive.
Crashed peers disappear on their own; there is no cleanup protocol. Peers refresh at about half the
advertised `refresh_interval_seconds`, which tolerates one lost refresh — the failure it prevents is
invisible from the peer's side, because a peer whose lease lapsed still believes it is registered
while nobody can discover it.

## Relay reservations and circuits

A peer that expects inbound relayed connections reserves before it advertises a relay candidate.
The reservation carries an expiry and the two ceilings, plus an **HMAC proof over exactly those
limits**, computed under a key that never leaves the process. The service re-validates the proof
immediately before it bridges, in constant time, so a mismatched-limits bug cannot silently widen a
ceiling and a peer cannot forge one. No reservation, no circuit — which is what closes the
open-relay hole. An open relay is an open proxy however carefully its byte ceilings are set.

A bridged circuit is metered per direction against the reservation and always torn down with a
stable reason:

| Reason | Meaning |
|---|---|
| `remote-closed` | One side closed its half — the normal end of a session |
| `byte-limit` | `reservation_max_bytes` reached, both directions summed |
| `duration-limit` | `reservation_max_duration_seconds` reached |
| `idle-timeout` | No bytes either way for `circuit_idle_timeout_seconds` |
| `error` | A transport error broke the circuit |

The idle timeout is not a nicety: without it, opening many circuits and sending nothing consumes
reservation slots and connection state at no cost to the attacker. "The connection dropped" is
unactionable; `byte-limit` is a capacity decision and `idle-timeout` is a keepalive bug.

## Hole punching

Over an established circuit the two peers exchange observed addresses and agree a shared T-minus.
The service only relays that exchange and stamps **one** go-signal, `500 ms` out, shared by both
directions — the first `PunchSync` with an unset signal fixes the value for that pair, and every
later one for the same pair is stamped identically, so A and B receive the same instant whichever
asked first. The pair key is unordered, because a punch is symmetric.

The service never dials. Failure is not an error: staying relayed is a legal steady state, so
nothing in this lane can break a working circuit. A synchronisation window too tight for both peers
to receive the signal before dialling is the usual cause of a punch that never succeeds anywhere.

## Draining

`SIGTERM` starts a drain rather than a cut: the service stops accepting new work, tells the peers
holding its control channels, tells its trackers, and waits up to `drain_grace_seconds` for live
circuits to finish.

* A `RelayReserve` during a drain is answered with `accepted = false` and the reason `draining` —
  **a refusal with a reason, not a closed socket**. A peer that can read the reason re-reserves
  elsewhere immediately; a silent close reads as "retry here", which is the difference between a
  migration and a stall.
* A `RelayConnect` during a drain is refused for the same reason: a new circuit through a relay that
  is about to stop is a connection that breaks inside the grace period.
* The grace is bounded on purpose — an unbounded wait lets one stuck circuit hang a restart forever.
  A drain that times out still cuts, and making that cost a re-dial instead of the whole transfer
  needs resumable transfers, which do not exist yet.

`update_channel` therefore **requires** `tracker_endpoints`, and the service refuses to start
without them: a relay that updates itself has to be able to tell peers where to go while it drains,
and it learns about replacement relays from its trackers.

## Settings

Every setting is a key in `nodera-rendezvous.toml` **and** an environment variable:
`NODERA_RENDEZVOUS_` plus the key, uppercased. Precedence is **defaults → config file → environment
→ command line**. An unrecognised `NODERA_RENDEZVOUS_*` variable refuses the start. Three names in
that prefix belong to the Java peer rather than to this service — `NODERA_RENDEZVOUS_ENDPOINTS`,
`_FANOUT`, `_SWEEP_SECONDS` — and are ignored rather than refused, so one shell can start both.

| Key | Default | Unit / meaning |
|---|---|---|
| `bind_addr` | `0.0.0.0:25601` | Listen address, TCP only |
| `advertised_routes` | empty | Public routes this service publishes. Empty falls back to `bind_addr`, which is wrong behind NAT |
| `tracker_endpoints` | empty | Trackers this relay announces itself to. **Empty means only peers that already have the address can find it** |
| `identity_file` | `nodera-rendezvous-identity.bin` | Service signing key. Preserve it: a regenerated one is a brand-new unmeasured relay to every peer |
| `registration_ttl_seconds` | `300` | How long a registration survives without a refresh. Must be ≥ the refresh interval |
| `refresh_interval_seconds` | `120` | The refresh cadence advertised to peers; peers refresh at about half of it |
| `clock_skew_seconds` | `300` | Accepted deviation of a record's `issuedAt`, both directions |
| `discover_page_limit` | `50` | Records in one discovery page — no full enumeration |
| `max_records_per_namespace` | `5000` | Records retained per namespace |
| `max_namespaces` | `10000` | Namespaces tracked at once |
| `reservation_ttl_seconds` | `300` | How long a reservation stays valid |
| `reservation_max_bytes` | `67108864` | Byte ceiling for one circuit. This is the bandwidth bill |
| `reservation_max_duration_seconds` | `600` | Wall-clock ceiling for one circuit |
| `circuit_idle_timeout_seconds` | `60` | No bytes either way for this long tears a circuit down |
| `reservation_hmac_key_hex` | empty | Seed for reservation proofs. Empty mints an ephemeral key at boot — correct, but every outstanding reservation dies with the process |
| `per_ip_request_quota` | `120` | Register and reserve requests per source IP per refresh interval |
| `max_frame_bytes` | `262144` | Largest accepted control frame; may not exceed the protocol cap |
| `max_concurrent_circuits` | `0` (unstated) | **Advertised headroom, not an enforced cap** — the enforcement is the reservation limits. Publishing it lets a peer prefer a relay with room |
| `drain_grace_seconds` | `30` | How long a drain waits for live circuits |
| `telemetry_endpoint` | empty — **off** | Where to report this service's own counters |
| `telemetry_interval_seconds` | `300` | Reporting window; floored at 30 |
| `update_channel` | empty — **off** | Release channel for self-update. Requires `tracker_endpoints` |
| `update_feed_base_url` | the project's releases | Must be `https://` when a channel is set |
| `update_check_interval_seconds` | `3600` | Must be positive when a channel is set |
| `update_release_public_key` | this build's key | The Ed25519 key a release manifest must be signed with, 64 hex characters |

**Refused at load:** a zero `refresh_interval_seconds`; a `registration_ttl_seconds` below it, which
guarantees every peer expires between its own refreshes; a zero `discover_page_limit`,
`max_records_per_namespace`, `max_namespaces` or `max_frame_bytes`; a `max_frame_bytes` above the
protocol cap; a zero reservation TTL, byte ceiling, duration ceiling or idle timeout; a
`reservation_hmac_key_hex` that is not even-length hex; a zero `drain_grace_seconds`, because that
makes every restart the hard cut this whole lane exists to remove; and, with `update_channel` set, a
zero check interval, a non-`https://` feed, or no `tracker_endpoints`.

## Command line

```
nodera-rendezvous [--config <file>] [--bind <addr>] [--healthcheck <addr>] [--print-env] [--version]
```

`--print-env` lists every environment variable this build understands. `--healthcheck` speaks the
real protocol against a running instance rather than merely opening a socket.

## Metrics

Off unless `telemetry_endpoint` is set. Windowed counters and coarse classes only — never an
address, a node id, a namespace, or a pair identity.

| Event | Numbers |
|---|---|
| `service.start` | once at boot, labelled with OS and architecture only |
| `rendezvous.window` | `registrations`, `discoveries`, `reservations`, `circuits`, `rejected`, `namespaces`, `window_seconds` |
| `rendezvous.punch` | `attempts`, `successes`, labelled by `nat_pair` — one of four coarse classes |
| `rendezvous.relay` | granted and `denied` reservations over the window |

The NAT class is derived from one boolean per peer: does the address it advertises match the address
its packets came from? Four classes and no finer — anything more specific would begin to
characterise individual networks rather than the population. The number it exists to answer is "of
attempts between two hard NATs, what fraction worked", which is what decides whether the relay lane
can ever be retired.

## Failure modes

| Symptom | Usual cause | Lever |
|---|---|---|
| A working relay is never used | It announces to no tracker | Set `tracker_endpoints`. This is the single most common way a healthy relay ends up idle |
| Everything is relayed | Peers publish no host candidate, or priorities put the relay candidate first | The expensive failure and the least visible: no errors, only cost. Start from the direct-versus-relayed session fraction |
| A peer believes it is registered and nobody discovers it | Its lease lapsed and the refresh never caught it | Refresh at half the TTL and watch the registration count |
| Reservations refused | The service is draining, or the source is over quota | The reason code distinguishes them; `draining` means re-reserve elsewhere now |
| Circuits end early in volume | `reservation_max_bytes` or `reservation_max_duration_seconds` is binding | Both are the bandwidth budget; raise knowingly |
| Idle timeouts in volume | A keepalive problem on the peers, not here | — |
| A restart invalidates every outstanding reservation | `reservation_hmac_key_hex` was empty, so the key was ephemeral | Set it, `openssl rand -hex 32` |
| Peers stuck relayed with punching enabled | Expected for genuinely restrictive NAT pairs; a bug when it is universal | A synchronisation window too tight for both peers to receive the go-signal |

## What this service deliberately does not do

* **No relay pooling.** A peer reserves against its *first* configured endpoint only. Several
  rendezvous endpoints are used for discovery, but inbound relay capacity depends on one service, so
  if that endpoint is the unreachable one the peer has no relay path even with healthy alternatives
  configured. Tracked in [`LIMITATIONS.md`](LIMITATIONS.md).
* **No decentralised rendezvous.** Endpoints are configured or learned from a tracker's service
  directory; there is no DHT, no peer exchange and no local multicast discovery.
* **No authority over identity.** `NodeId` is random rather than derived from the key, so no service
  can check the binding cryptographically. Trust-on-first-use is a directory-level protection, not
  authentication — the transport handshake is what proves key possession, per connection.
* **No visibility into payloads, and no hiding of metadata.** Payload encryption is end to end, and
  no amount of it changes the fact that a relay knows it is relaying between two endpoints. Relay
  logs are simultaneously the investigative record and the largest privacy liability the deployment
  holds; short retention is the only thing that resolves that tension.
