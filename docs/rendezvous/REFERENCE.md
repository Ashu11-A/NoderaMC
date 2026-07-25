# Rendezvous and Relay Architecture in Production

> **Supersedes `docs/torrent/rendezvous.md`.** This is the operational rewrite: what rendezvous and
> relay services actually do under load, how they are deployed and metered, what abuse they attract,
> and what fails. There are no code examples — everything is stated as behaviour, contract, or
> operational consequence.
>
> **Section numbering is deliberately unchanged** from the document this replaces. Source files across
> the transport and peer modules, plus `docs/network/REFERENCE.md` and `docs/PROGRESS.md`, cite this material as
> `rendezvous.md §N` (including sub-items such as §12.2); keeping the numbers means those citations
> stay correct and only the path moved. Sections §14 onward are new and cover production concerns the
> earlier study did not address.

> **Reference spec** — implemented for Nodera by **[Task 4](Task.1.md)** (legacy
> [Task 29](Task.1.md)): `rust/nodera-rendezvous` plus the Java rendezvous transport, on the
> [Task 27](../network/Task.1.md) monorepo foundation. Binding protocol decisions live in the task file;
> this document is the background architecture study those decisions draw on.

> **Implementation status against this document.** §4.7's path preference and §12.2's "attempt direct
> connectivity first" are real in the Java rendezvous transport: a peer publishes a **host candidate**
> built from its direct transport's listen route at high priority alongside its relay candidate at low
> priority, and a send dials the peer's best direct candidate before opening a circuit. Until that host
> candidate existed the transport advertised *only* a relay address, so a direct path was never
> available and every byte crossed the relay — the exact inversion §12.2 warns about. Registrations are
> leases (§9.3): a refresh thread renews at half the TTL instead of the record silently expiring.
>
> Still deferred: hole punching as a coordinated upgrade lane (§4.6) — the coordinator exists but no
> lane drives it, so an unreachable-to-unreachable pair stays relayed rather than upgrading; relay
> pooling (§9.2) — a peer reserves against its first configured endpoint only; and the headless
> worker's data plane is socket-only, because its transport is not scoped to one world's namespace the
> way a game client's is. Peer *discovery* over rendezvous is wired for both.

## 1. Overview

A **rendezvous relay architecture** uses one or more publicly reachable nodes to help peers discover
and connect to each other across the Internet. The term bundles two responsibilities that must stay
logically separate:

* **Rendezvous** discovers peers and coordinates connection establishment.
* **Relay** forwards application traffic when peers cannot communicate directly.

One process may implement both, but they are different services with different cost models, different
scaling curves, and different abuse profiles. A rendezvous service does not necessarily relay, and a
relay does not necessarily discover. Conflating them in the *design* — as opposed to co-locating them
in a deployment — is how a cheap coordination service quietly becomes an expensive data path.

The preferred communication path is always direct, peer to peer. The relay exists for connection
establishment and for the cases where no direct path can be made to work.

The architecture is necessary because a large fraction of real peers sit behind network address
translation, carrier-grade NAT, stateful firewalls, routers that drop unsolicited inbound connections,
networks whose addresses change without warning, or browser and mobile environments with restricted
networking. Address discovery of the STUN kind helps an endpoint learn the public address and port its
NAT assigned, but it is a building block rather than a traversal system. The ICE approach combines
candidate discovery, candidate exchange, connectivity checks, and relay candidates to select a usable
path; TURN-style relaying provides the path of last resort.

The single most important operational number in the whole architecture is **the fraction of sessions
that end up relayed**, because that fraction multiplied by session traffic is the relay bill.

---

## 2. Terminology

### 2.1 Peer

A **peer** is an application instance participating in the network. It normally has a persistent or
temporary cryptographic identity, one or more network addresses, a set of supported transports and
application protocols, a declared set of capabilities, and an active set of direct or relayed
connections. A peer may be a desktop client, a mobile client, a browser tab, a server process, or a
headless seeder — and those categories differ enormously in reachability, which is why capability and
candidate information belongs in the peer's record rather than being assumed.

### 2.2 Rendezvous point

A **rendezvous point** is a publicly reachable service through which peers announce presence and
discover others. A registration typically carries the peer's identity, the namespace or room it is
joining, supported protocols, direct addresses, observed public addresses, relay addresses,
capabilities, a registration expiry, and a signature over the whole record.

Peers register into application-specific namespaces and query those namespaces. Because registrations
carry signed records, a recipient can verify that a record was produced by the peer it describes — the
rendezvous point relays claims rather than vouching for them.

The rendezvous point belongs to the **discovery and coordination plane**. It should not be assumed to
be part of the data path, and a design that lets it become one has changed its cost model without
changing its name.

### 2.3 Relay

A **relay** is an intermediary that transports traffic between peers: instead of a direct path, traffic
crosses the relay's interfaces on the way. TURN is the standardised mechanism in the ICE and WebRTC
world; circuit relaying is the comparable mechanism in libp2p. A relay exists specifically for the
cases where NAT behaviour or firewall policy prevents a direct path.

Relay bandwidth is the expensive resource in this architecture, and every byte is counted twice — once
inbound, once outbound. This is why relays are metered, quota-limited, and pooled, while rendezvous
services generally are not.

### 2.4 Signaling

**Signaling** is the exchange of metadata needed to establish a connection: peer identities, transport
candidates, addresses and ports, protocol versions, session credentials, relay addresses, and
hole-punching synchronisation messages. Signaling is not the transport of application data, and keeping
that boundary explicit is what allows the signaling channel to be low-volume and therefore cheap even
when the data path is not.

### 2.5 Candidate

A **candidate** is a potential network endpoint through which a peer might be reachable.

| Candidate        | Description                                                       |
| ---------------- | ----------------------------------------------------------------- |
| Host             | A local interface address, such as a LAN address                  |
| Server-reflexive | The public address and port observed through a STUN-like service  |
| Port-mapped      | An address explicitly mapped through UPnP, NAT-PMP, or PCP        |
| Relay            | An address allocated or reserved on a relay                       |
| Public           | A directly reachable public address                               |

Candidates are gathered and their pairings tested before a path is nominated. Two production notes:
candidates carry priorities, and getting those priorities wrong is how a system ends up relaying
traffic it could have sent directly (§12.2); and host candidates leak local network structure, so
what is published is a privacy decision as well as a connectivity one (§12.9).

### 2.6 Hole punching

**Hole punching** attempts to create a direct path through NAT devices by coordinating simultaneous
outbound attempts from both peers, so each NAT sees outbound traffic and may admit the corresponding
inbound packets.

It does not always succeed. The outcome depends on NAT mapping behaviour, firewall policy, transport,
address predictability, timing, and topology. Treating it as an optimisation with a guaranteed fallback
is the only safe posture; treating it as a transport produces a system that works in testing and fails
for the users behind the most restrictive networks.

### 2.7 Bootstrap node

A **bootstrap node** provides an initial entry point into the network — where to find rendezvous
points, DHT members, relay providers, pub/sub peers, or other bootstrap nodes.

A bootstrap node and a rendezvous point can be the same process, but they answer different questions:
bootstrap answers *how do I enter the network*, rendezvous answers *which peers should I connect to*,
and a relay answers *how can packets reach a peer that is not directly reachable*. A deployment that
only has a bootstrap route has an entry point, not a discovery plane: it learns about whoever that one
route chooses to mention, which is a materially weaker property than querying a namespace.

---

## 3. Logical Components

A complete deployment divides into three planes. Keeping them separate is the architectural core of
this document.

## 3.1 Discovery plane

The discovery plane locates peers. Possible implementations include rendezvous servers, distributed
hash tables, BitTorrent-style trackers, DNS records, multicast DNS for local networks, peer exchange,
pub/sub membership, and static bootstrap lists.

A rendezvous service is especially useful when peers must discover others belonging to a particular
namespace — a game session, a document, a region, a protocol version. Namespacing is what keeps
discovery queries bounded: without it, a peer asks "who is out there" and receives an answer whose size
is the network's, not the session's.

Production deployments should treat discovery as several partial, independently-failing sources whose
results are merged rather than as a lookup with an authoritative answer. A source that omits peers then
dilutes its own influence instead of censoring the network, and a source that invents them costs one
failed dial because identity is verified at the transport handshake (§8.1).

## 3.2 Connectivity-control plane

The connectivity-control plane decides how two discovered peers can actually communicate. It exchanges
candidates, discovers external addresses, tests candidate pairs, coordinates simultaneous connection
attempts, authenticates peer identities, selects direct or relayed transport, and upgrades an existing
relayed connection when a direct path becomes available.

This plane is where most of the complexity of NAT traversal lives, and where most of the observable
behaviour a user perceives as "connection quality" is decided.

## 3.3 Data plane

The data plane carries application traffic over direct TCP or QUIC, WebRTC data channels, WebTransport,
WebSockets, relayed TCP or UDP, or a relay circuit.

It should prefer direct connectivity, which reduces latency, relay bandwidth cost, dependency on
centralised infrastructure, relay congestion, and blast radius when an intermediary fails. Each of
those is an operational property, not an aesthetic preference: a deployment whose data plane defaults
to relaying has centralised a decentralised system and pays for it continuously.

---

## 4. Connection Lifecycle

Take two peers, A initiating and B possibly behind NAT, with R a public rendezvous and relay node.

## 4.1 Peer startup

B starts and creates or loads its cryptographic identity. It then opens local listening transports,
gathers local addresses, determines whether it is publicly reachable, discovers its externally observed
address, finds one or more relays, optionally reserves relay capacity, and registers itself with a
rendezvous point — publishing its namespace, identity, direct candidates, relay candidates,
capabilities, and an expiry.

Registrations should expire automatically, and B refreshes while it remains online (§9.3).

The reachability determination in step three is worth doing properly, because it decides whether B
needs a relay reservation at all. A peer that assumes it is unreachable reserves relay capacity it
will not use and advertises a relay candidate that may get preferred; a peer that assumes it is
reachable when it is not becomes undialable with no fallback.

## 4.2 Relay reservation

When B cannot reliably accept direct inbound connections, it reserves resources on relay R. R answers
with a relay address, an expiry, explicit limits, and a proof of the reservation. B can then advertise
an address meaning *reach B through R*, naming both the relay and the final destination.

Explicit reservations exist because open, unreserved relaying is unbounded in cost: the relay can
impose expiry, duration, and data limits, so its capacity is allocated rather than consumed. The proof
matters for the same reason — it lets the relay validate at bridging time that the limits it is about
to meter against are the limits it actually issued, so a mismatch cannot silently widen the ceiling.

## 4.3 Peer discovery

A queries the rendezvous service for the namespace, and R responds with records for matching peers,
each carrying identity, direct candidates, relay candidates, and capabilities.

R has introduced A to B; it has not created a connection between them, and it has not asserted that
any of B's candidates work. Responses are normally paged, which means a discovering peer must be
prepared to receive a subset and to merge across multiple endpoints (§9.1).

## 4.4 Direct connection attempt

A tries B's direct candidates first. If B has a public address, an explicit port mapping, compatible
NAT mapping behaviour, or an existing reachable listener, this may succeed immediately.

After the transport connection exists, A and B authenticate each other against their expected
identities. **The address obtained from rendezvous is not proof of identity**: the transport handshake
must verify the remote endpoint genuinely holds the private key behind the identity A expected. This is
the property that lets the rendezvous service be untrusted, and it must be enforced at the transport
seam rather than assumed from the record's signature — the record proves what B published, not who
answered at that address.

## 4.5 Initial relay connection

When direct dialling fails, A uses B's relay address: A asks R to connect it to B, R notifies B of an
incoming connection, B accepts, and R bridges the two legs.

A and B must still establish an end-to-end authenticated and encrypted session over the relayed
transport. The relay can observe source and destination identities, connection time, transferred byte
counts, traffic timing, and reservation information; with end-to-end encryption it cannot read or
undetectably modify payloads.

Each bridged circuit is metered against the reservation, and every teardown should carry a stable
reason — remote closed, byte limit, duration limit, idle timeout, or error — because "the connection
dropped" is unactionable while "byte limit reached" is a capacity decision and "idle timeout" is a
keepalive bug.

## 4.6 Direct connection upgrade

Once A and B have a working relayed connection, that connection is a reliable, authenticated signaling
channel for hole punching. They exchange observed addresses through R, synchronise, and make
simultaneous direct attempts. If it works they have a direct path; if not they continue on the relay.

This ordering is the right one because the peers already have a channel, verified identities, a means
of exchanging addresses, and a working fallback — so the upgrade attempt is free of risk. Coordinated
timing matters: both sides must attempt within a narrow window, which is why the coordinating service
stamps a synchronised go-signal with enough lead time for both peers to receive it before dialling.

## 4.7 Path selection

The connection manager evaluates available paths. A typical priority order runs: direct local-network,
direct public QUIC, direct public TCP, hole-punched QUIC, hole-punched TCP, and relayed last.

The exact ordering is application-specific — a real-time application may prefer lowest measured
latency, a bulk transfer may prefer throughput and stability. Two production requirements apply
regardless. Selection must be per-peer and adaptive, demoting a path that fails and re-promoting it
when it works, or a transient failure permanently strands a peer on a worse path. And it must reset
when every path is demoted, because a peer with no available path must still be retried rather than
being written off.

Bulk traffic deserves separate treatment from control traffic: control frames can take any path, while
bulk transfers should avoid the relay whenever any non-relayed path exists, since relay bandwidth is
the scarce resource.

## 4.8 Relay shutdown or retention

Once a direct connection is stable the peers can close the relay connection, retain it briefly as a
fallback, keep a lightweight reservation, periodically verify direct connectivity, or migrate back to
the relay when the network changes.

Mobile peers should be expected to change addresses when switching between wireless and cellular
networks, which makes "retain a cheap reservation" the pragmatic default: dropping the reservation
entirely means a network change costs a full rediscovery, while holding a full circuit open costs the
relay.

---

## 5. Lifecycle walkthrough

The full sequence, in order, as a single narrative.

B connects to R and reserves relay capacity; R returns a relay address, an expiry, and limits. B
registers its identity, namespace, and candidates with R. A queries R for the namespace and receives
B's record, including candidates and capabilities. A attempts B's direct candidates and, in this
scenario, fails. A asks R to connect it to B; R signals B, B accepts, and R bridges the legs, giving A
a relayed stream.

Over that relayed stream A and B authenticate each other end to end, then exchange observed addresses
and synchronise a hole-punching attempt. Both make simultaneous direct attempts. If one succeeds they
migrate to the direct transport and either close the relay leg or retain it as a cheap fallback. If
both fail they continue over R indefinitely, which is a supported outcome rather than a failure.

Every step in that sequence can fail independently, and the useful property of the design is that each
failure degrades to the previous state rather than to nothing: discovery failure leaves cached peers,
direct-dial failure leaves the relay, punch failure leaves the relay, and relay failure leaves
rediscovery.

---

## 6. Protocol surface

A generic rendezvous and relay protocol needs to expose a small, stable set of operations. Stated as
capabilities rather than as an interface definition:

* **Register** a signed record into a namespace, with a self-declared expiry.
* **Refresh** an existing registration before it lapses.
* **Unregister** so a departing peer disappears promptly rather than by timeout.
* **Discover** peers in a namespace, with a cursor and a page limit so responses are bounded.
* **Reserve** relay capacity, returning a route, an expiry, explicit limits, and a proof.
* **Connect** to a target's reserved slot, causing the relay to bridge two legs.
* **Notify** a reserving peer that an inbound circuit is arriving.
* **Exchange observed addresses** and a synchronised punch signal between two peers.
* **Report** a caller's reflexive address back to it.

Whatever the encoding, the specification needs to pin down record and message size limits, expiry
behaviour, stable error codes, rate limits, authentication requirements, candidate filtering rules, and
the permitted connection state transitions. Two rules are load-bearing in production: messages that
only a server may originate must be refused when they arrive from a peer, and every refusal should
carry a stable machine-readable code so a client can distinguish "slow down" from "not permitted" and
back off correctly instead of retrying into a block.

---

## 7. Connection state machine

A peer connection moves through a small number of states: disconnected, discovering, direct-dialling,
direct, relay-dialling, relayed, and hole-punching. The main path is discover, then attempt direct — on
success reaching direct, on failure falling to relay-dialling and then relayed. From relayed, a
hole-punching attempt either promotes the connection to direct or returns it to relayed.

Additional transitions are needed when the direct path stops responding, the relay reservation expires,
the peer changes networks, authentication fails, a better path becomes available, or the rendezvous
record goes stale. Those secondary transitions are where most real bugs live, because the happy path
gets exercised constantly and the transitions out of a degraded state do not. The states worth
instrumenting explicitly are the ones a connection can get *stuck* in: relayed with no upgrade attempts
occurring, and discovering with no records returned.

---

## 8. Security Model

## 8.1 Cryptographic peer identity

Each peer should have an identity derived from or bound to a public key, and all peer records should be
signed. On discovering a record claiming to describe B, A verifies that the record's identity matches
its public key, that the signature is valid, that the record has not expired, that the namespace is
acceptable, and that the declared capabilities are permitted.

The rendezvous service must not be trusted to authenticate application messages on behalf of peers.
Where an identity is *not* derived from the key — a randomly assigned identifier, for instance — a
service cannot check the binding cryptographically at all, and the honest substitute is
trust-on-first-use: the first key to claim an identifier keeps it as long as the service remembers,
which prevents hijacking a live peer's identifier while leaving a freshly restarted service taking the
first claim at face value. That is a directory-level protection, not an authority claim, and it should
be documented as such rather than presented as authentication.

## 8.2 End-to-end transport security

The connection must be encrypted and authenticated between A and B even when the path crosses R.
Encrypting only the individual legs lets R read everything; an end-to-end session on top prevents that.

This is not optional in a design where relays may be operated by third parties, and it is the reason a
relay can be treated as untrusted infrastructure — which in turn is what makes relay pooling and relay
rotation viable.

## 8.3 Registration abuse

A malicious client may try to register on behalf of another peer, publish forged addresses, create
enormous numbers of registrations, register in every namespace, keep stale records alive, or advertise
private or malicious endpoints as candidates.

Mitigations: accept self-registration only, require signed records, enforce a registration TTL,
authorise namespaces where the application allows it, apply per-identity and per-address quotas,
validate advertised addresses, cap record size, and require proof-of-work or payment on genuinely
hostile public networks. Freshness bounds on the record's own timestamp — rejecting both stale and
implausibly future ones — prevent a captured registration from resurrecting a departed peer.

## 8.4 Relay abuse

An unrestricted relay can be used as a bandwidth proxy, a denial-of-service amplifier, an anonymity
layer for attacks, a transport for prohibited content, and a resource-exhaustion target. It is the most
attractive component in the architecture to an abuser, because it is the only one that moves volume.

Relays must enforce reservation limits, connection limits, bandwidth limits, maximum circuit duration,
maximum bytes per circuit, authentication, destination permissions, rate limits, idle timeouts, and
active abuse monitoring. Explicit reservations with limited relaying exist precisely because
unrestricted public relays are expensive and trivially oversubscribed.

Idle timeouts deserve particular emphasis: without them, a cheap attack is to open many circuits and
send nothing, consuming reservation slots and connection state at no cost to the attacker.

## 8.5 Metadata privacy

Even with encrypted payloads, rendezvous and relay operators can learn that two peers are
communicating, when they connected, their apparent addresses, their namespace membership, and
approximate traffic volume.

For sensitive systems: use multiple independent providers, short-lived peer identifiers, minimal
registration metadata, encrypted or hashed namespace identifiers, relay rotation, private discovery
tokens, and application-level access control. It is worth being explicit that these reduce rather than
eliminate exposure — a relay necessarily knows it is relaying between two endpoints, and no amount of
payload encryption changes that.

---

## 9. Reliability and Scaling

## 9.1 Multiple rendezvous points

Clients should not depend on a single rendezvous point. A resilient configuration registers with
several and merges their discovery results, with a further fallback such as a DHT or peer exchange.

Because rendezvous traffic is registration and discovery metadata only, these services are far easier
to scale than relays — a rendezvous point's load is proportional to peer count and refresh rate, not to
application traffic. There is correspondingly little excuse for running exactly one.

Merging across endpoints is what makes redundancy real. Registering with several but querying only the
first converts redundancy into a silent single point of failure, since the fallback endpoints hold
records nobody reads.

## 9.2 Relay pools

Relay bandwidth scales with actual application traffic, so relays should be deployed as a pool across
regions rather than as a single node.

Peers select from the pool by latency, geographic proximity, available capacity, transport
compatibility, trust policy, reservation success, and measured reliability. A pool provides three
distinct benefits that a single relay cannot: capacity beyond one machine's uplink, failure isolation
when one relay dies, and the ability to rotate relays for privacy (§8.5).

A deployment that reserves against only its first configured endpoint has multiple relays configured
and one relay in use — the failure of that one endpoint removes the peer's inbound path entirely even
though healthy alternatives exist. Reservation should therefore try endpoints in order and re-reserve
elsewhere when a reservation lapses or its relay becomes unreachable.

## 9.3 Expiring state

Registrations and reservations should be **leases**, never permanent state: register, receive an
expiry, refresh before it lapses, and let the service drop the state when refreshes stop. Crashed and
disconnected peers then disappear automatically, and the service's state stays bounded without any
explicit cleanup protocol.

Two details matter. The effective expiry should be the *sooner* of the service's TTL and the record's
own self-declared expiry, so a peer can ask to be forgotten early while the service still caps how long
one registration can keep a record alive. And a refresh cadence of roughly half the TTL tolerates a
single lost refresh without the peer silently vanishing from discovery while still believing it is
registered — a failure mode that is invisible from the peer's side and therefore especially worth
designing out.

## 9.4 Keepalives

NAT mappings and stateful firewall entries expire during inactivity, so active connections need
periodic keepalives to keep the bindings alive. Connectivity checks serve this purpose in the
STUN/ICE model.

Keepalive intervals should be configurable, because aggressive intervals consume mobile battery and
bandwidth while lazy ones let mappings lapse mid-session. There is no single correct value: it depends
on the NAT population a deployment actually faces, which is why it is a tunable rather than a constant.

---

## 10. Common Deployment Patterns

### 10.1 Rendezvous only

Peers discover each other through the rendezvous point and connect directly, with no relay deployed.
Appropriate when most peers are publicly reachable or traversal is handled by another protocol. Cheapest
to operate, and leaves unreachable-to-unreachable pairs unable to connect.

### 10.2 Rendezvous with relay fallback

Discovery through the rendezvous point, preferred direct data path, relay as fallback. This is the
most common general-purpose architecture and the right default for a heterogeneous peer population.

### 10.3 Relay-first connection upgrade

Establish the relayed connection immediately, then use it to coordinate hole punching and upgrade to
direct. Useful when a relayed connection is easy to establish and makes a reliable signaling channel.
It trades a little early relay traffic for a much higher eventual direct-connection rate.

### 10.4 Permanently relayed clients

Some clients will never accept direct inbound connections — restricted browser environments, strict
corporate networks, some mobile carriers. Their normal path is through the relay indefinitely. A
deployment should size relay capacity for this population explicitly rather than treating it as an
exception, because it is a stable fraction of real users, not a transient state.

### 10.5 Decentralized rendezvous

Rendezvous need not be one central server: several independent nodes, nodes federated through pub/sub,
DHT-backed registrations, peer exchange, or application-operated regional nodes are all viable, and any
compatible node can act as a rendezvous point. Running several independent daemons rather than one
fragile centralised component is the intended shape.

---

## 11. Comparison with similar components

| Component                 | Discovers peers | Coordinates connection | Relays data |
| ------------------------- | --------------: | ---------------------: | ----------: |
| Bootstrap node            |       Partially |                     No |          No |
| BitTorrent tracker        |             Yes |              Minimally |  Usually no |
| DHT                       |             Yes |                     No |          No |
| STUN server               |              No |        Helps traversal |          No |
| Signaling server          |       Sometimes |                    Yes |          No |
| Rendezvous point          |             Yes |              Sometimes |  Usually no |
| TURN server               |              No |           Supports ICE |         Yes |
| Circuit relay             |              No |    Can support upgrade |         Yes |
| Combined rendezvous relay |             Yes |                    Yes |         Yes |

A BitTorrent tracker is therefore closer to a rendezvous service than to a relay: it tells peers about
one another while piece transfers happen directly between them. The operational corollary is that
tracker experience transfers to running a rendezvous point, and does *not* transfer to running a
relay — the relay is the component whose costs and abuse profile are genuinely different (see
`TRACKERS.md §20`).

---

## 12. Design recommendations

1. **12.1 Separate discovery from transport.** A discovery record must not imply that application traffic
   passes through the discovery service.

2. **12.2 Attempt direct connectivity first, or upgrade as soon as possible.** Relaying stays a fallback
   unless the product intentionally requires mediated communication. A peer that publishes only a relay
   candidate has made relaying mandatory by omission — the most common way this rule is broken in
   practice, and one that shows up as cost rather than as an error.

3. **12.3 Always provide a working relay path before depending on hole punching.** Punching is an
   optimisation, not a transport.

4. **12.4 Authenticate peers end to end.** Never treat an address, a discovery response, or a relay
   connection as proof of identity.

5. **12.5 Use expiring registrations and reservations.** Permanent records go stale and create unbounded
   state.

6. **12.6 Limit relay resources.** Relays need strict quotas, admission control, and observability.

7. **12.7 Support multiple rendezvous and relay nodes.** A single public intermediary is a failure point and
   a censorship point.

8. **12.8 Preserve connections across path changes where possible.** Connection migration or an
   application-level reconnection protocol reduces disruption for mobile peers.

9. **12.9 Do not expose unnecessary private addresses.** Candidate publication leaks local network
   structure; publish what is needed for connectivity and no more.

10. **12.10 Measure path quality continuously.** Latency, loss, throughput, and stability should decide
    whether a path stays preferred, rather than the choice being made once at connection time.

---

## 13. Summary

The architecture solves three distinct problems. Discovery: how does A find B? Traversal: how can A and
B establish a path through NATs and firewalls? Fallback: how do they communicate when no direct path
works? The rendezvous service answers the first; address discovery, candidate exchange, connectivity
checks, and hole punching answer the second; the relay answers the third.

The full lifecycle is register, discover, exchange candidates, attempt direct, establish relay
fallback, coordinate hole punching, upgrade to direct when possible, and fall back to the relay if the
direct path fails.

The most important architectural rule: **a rendezvous point introduces peers, a relay transports
packets.** Combining them in one deployment is convenient; treating them as one logical service is how
a system ends up paying relay prices for coordination work.

---

## 14. Relay capacity planning and cost model

Relay capacity is the only genuinely expensive resource here, and it is straightforward to model.
Required relay bandwidth is the number of concurrent sessions, times the fraction of sessions that are
relayed, times the mean per-session throughput, times two — because every byte is received and sent
again.

The doubling is what surprises operators. The relayed fraction is what they can actually influence.
Everything in §4.4 through §4.7 and recommendation §12.2 exists to push that fraction down, and it is
worth measuring directly rather than inferring from bandwidth: a deployment can be entirely healthy at
five percent relayed and entirely broken at ninety-five percent while looking identical in every other
metric.

Per-circuit ceilings translate the aggregate budget into an admission decision. A byte ceiling bounds
what one circuit can consume, a duration ceiling bounds how long it can hold a slot, and an idle
timeout reclaims slots that are held but unused. Together they make relay capacity allocable: the
service can compute how many concurrent circuits it can honour and refuse reservations beyond that,
rather than accepting everything and degrading every circuit at once.

Rendezvous capacity, by contrast, is cheap and predictable: load is peer count divided by refresh
interval, plus discovery query rate. It scales like a tracker (`TRACKERS.md §29`), and its state is
bounded by namespace count times records per namespace — both of which need explicit ceilings, since
both are attacker-influenced.

---

## 15. Operating a fleet

Rendezvous and relay nodes have different placement logic, which is a further reason to deploy them as
separate services even when the same binary provides both.

**Rendezvous placement** is latency-tolerant: a peer registers and refreshes on a slow cadence, so
proximity barely matters and availability dominates. Run several in different failure domains, publish
them all to clients, and require clients to merge results (§9.1).

**Relay placement** is latency- and throughput-critical, because relayed traffic pays the detour twice.
Relays belong near the peers that use them, which makes a regional pool the natural shape (§9.2). Relay
selection should be measured rather than configured where possible: reservation success and observed
latency are better selectors than a static ordering.

**Rollout** has one asymmetry worth planning for. Restarting a rendezvous node is cheap: its state is
leases, and peers re-register within one refresh interval (§9.3), so the loss is bounded and
self-healing. Restarting a relay is not cheap: every circuit it is bridging breaks, and every peer
holding a reservation there loses its inbound path until it re-reserves. Relays should therefore be
drained — refusing new reservations while letting existing circuits finish — rather than restarted
abruptly, and peers should treat a lapsed reservation as a trigger to re-reserve elsewhere rather than
as a fatal condition.

**Configuration** that materially changes behaviour: registration TTL and refresh interval;
discovery page limit; records per namespace and namespace count ceilings; reservation TTL, byte
ceiling, and duration ceiling; circuit idle timeout; per-address request quotas; maximum message size;
and clock skew tolerance. As with a tracker, these should be validated for coherence at load rather
than discovered as behaviour — a refresh interval longer than the TTL, for instance, guarantees every
peer expires.

---

## 16. Observability

The metrics that diagnose this architecture are mostly about *which path traffic took* and *why a
better path was not used*.

**Relayed versus direct session fraction** is the headline number, for the reasons in §14.

**Path transitions**, counted by direction: direct-to-relay demotions indicate direct paths failing
mid-session; relay-to-direct promotions indicate upgrades working. A deployment with many relayed
sessions and no promotion attempts has a broken or absent upgrade lane, which is invisible in
throughput metrics.

**Direct-dial success rate**, ideally split by candidate kind, tells an operator whether published host
candidates are useful or merely noise.

**Reservation outcomes** — granted, refused for capacity, refused for quota, lapsed without refresh.
Lapses are the signal for the invisible failure of §9.3.

**Circuit teardown reasons**, as a distribution. Byte and duration limits mean ceilings are binding and
may need review; idle timeouts in volume mean a keepalive problem; errors mean transport trouble.

**Registration admission outcomes** by refusal reason — bad signature, stale, quota, namespace full,
too large — for the same localisation benefit a tracker gets from the equivalent breakdown.

**Namespace and record population**, plus discovery page saturation: queries consistently returning a
full page mean peers are seeing a truncated view of their own session.

Useful service objectives: availability of registration and discovery; a high-percentile latency
ceiling on discovery; reservation success rate for peers that need a relay; and — the one that
actually reflects user experience — the fraction of peer pairs that reach *any* working path, direct or
relayed, within a bounded time.

---

## 17. Failure modes and operational response

**A rendezvous node is down.** Peers registered elsewhere are still discoverable; peers that registered
only there vanish from discovery as their leases lapse. Existing connections are unaffected, since the
service is not on the data path. Recovery is automatic as peers re-register — provided clients actually
register with more than one node.

**All rendezvous nodes are down.** New connections cannot be established except through cached peers or
another discovery mechanism. Existing sessions continue. This is the case that justifies keeping a
cache of previously-seen peers and a secondary discovery mechanism.

**A relay is down.** Every circuit it was bridging breaks, and peers that reserved there are
unreachable inbound until they re-reserve. Peers on direct paths are unaffected. Response: peers must
re-reserve against another pool member; if reservation is hard-wired to one endpoint, this becomes an
outage for those peers rather than a failover (§9.2).

**Relay saturated.** Circuits hit byte or duration limits early, or reservations are refused. The
levers are per-circuit ceilings, admission control, and pool capacity. Refusing reservations cleanly is
better than accepting them and degrading every circuit.

**Everything is relayed.** The most expensive failure and the least visible: no errors, just cost. The
usual causes are peers publishing no host candidate, priorities inverted so the relay candidate wins,
or an upgrade lane that exists but is never driven. Diagnosis starts from the path-transition counters
in §16.

**Peers stuck relayed with punching enabled.** Expected for genuinely restrictive NAT combinations, and
a bug when it is universal — commonly a synchronisation window too tight for both peers to receive the
go-signal before dialling.

**A peer believes it is registered but is not.** The lease lapsed and the refresh cadence never caught
it. Symptom is one-directional: the peer sees itself as available while nobody discovers it. Refresh at
half the TTL and count lapses (§9.3).

**Authentication failures on relayed circuits.** Either an identity mismatch — the record's identity is
not who answered, which is exactly what §4.4 requires the handshake to catch — or a relay bridging to
the wrong destination. Either way the correct behaviour is to fail closed and re-dial, never to proceed
with an unverified peer.

---

## 18. Abuse handling in production

A relay is the component that attracts abuse, because it is the component that moves traffic on behalf
of others. An operator should assume attempts to use it as a general-purpose proxy, as an amplifier, as
a way to launder the origin of attack traffic, and as free transport for prohibited content.

The technical controls are those of §8.4, and their effectiveness depends on being enforced at
admission rather than observed after the fact: a reservation that was never granted cannot be abused,
while a circuit that is already bridging can only be torn down after it has consumed capacity.
Destination permissions matter more than they first appear — a relay that will bridge to arbitrary
destinations is an open proxy regardless of how carefully its byte ceilings are set, whereas one that
bridges only to peers holding valid reservations in a known namespace is structurally much harder to
misuse.

The rendezvous side attracts a different abuse: registration flooding and namespace squatting, both
addressed by quotas, signed self-registration, and TTLs (§8.3). Namespace authorisation is the stronger
control where the application can support it, since it turns "anyone may register anywhere" into
"registration requires belonging".

Operational posture: keep refusal reasons stable and attributable so a complaint can be answered
factually; keep the per-circuit metering data needed to investigate an incident but recognise it is
exactly the metadata §8.5 identifies as sensitive; and decide retention deliberately, since relay logs
that record who talked to whom and when are simultaneously the investigative record and the largest
privacy liability the deployment holds.
