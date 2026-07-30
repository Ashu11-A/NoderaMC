# BitTorrent-Style Trackers in Production

> **Supersedes `docs/torrent/trackers.md`.** This is the operational rewrite: what a tracker actually
> does when it is carrying real traffic, how it is deployed, what breaks, and what it costs. There are
> no code examples — everything here is stated as behaviour, contract, or operational consequence.
>
> **Section numbering is deliberately unchanged** from the document this replaces. Roughly fifty
> source files and `docs/network/REFERENCE.md` cite this material as `TRACKERS.md §N`; keeping the numbers means
> those citations stay correct and only the path moved. Sections §29 onward are new and cover
> production concerns the earlier study did not address at all.

> **Reference spec** — implemented for Nodera by **[Task 3](Task.1.md)** (legacy
> [Task 28](Task.1.md)): `tracker` plus the Java `TrackerClient`, on the
> [Task 27](../network/Task.1.md) monorepo foundation. Binding protocol decisions live in the task file;
> this document is the background architecture study those decisions draw on.

> **Nodera deviations from the classic model** (kept here so the two are never confused):
>
> * **One encoding, two transports.** Nodera does not use bencode or HTTP. Both the TCP and the UDP
>   surface carry the *same* canonical `nodera-codec` message family (§12–§13 below describe the
>   classic split; Nodera's is transport-only). On TCP a frame is a length prefix followed by a body;
>   on UDP the datagram boundary *is* the frame, so there is no length prefix.
> * **A bare `host:port` route means TCP.** Configuration accepts explicit `tcp://` and `udp://`
>   schemes; an unprefixed route stays TCP, so no existing deployment changes meaning.
> * **UDP is bounded, and silence is a valid answer.** Because a UDP source address is forgeable
>   (§13.2, §26 "reflected UDP"), the service refuses to emit a reply that exceeds its reply-size or
>   amplification ceiling. It does not truncate — a truncated canonical frame is undecodable — it
>   simply does not answer, and the client retries the same endpoint over TCP. A large swarm's peer
>   list therefore always arrives; it just arrives over the surface that proved the requester's
>   address.
> * **No connection-ID handshake.** The classic UDP protocol's connect/announce two-stage cookie
>   (§13.1–§13.2) buys the same anti-reflection property Nodera gets from the amplification cap,
>   without a second round trip. Announces are signed and freshness-bounded regardless of transport,
>   so the cookie is not carrying identity weight either.
> * **Peer records are signed.** The peer identifier here is a real cryptographic identity, so §26's
>   Sybil and identity-takeover notes apply differently: a second key cannot claim a known identity.

A **tracker** is a specialised rendezvous service for a peer-to-peer swarm. It answers exactly one
question: *which peers are currently participating in the same torrent as me?* It does not store the
shared files, transfer pieces, verify content, or relay traffic. It introduces peers to each other and
then gets out of the way — which is precisely why one modest machine can coordinate a swarm whose
aggregate throughput it could never carry.

Everything that makes a tracker hard in production follows from that asymmetry. The service is cheap
per request and enormous in request count; it is trusted for nothing yet depended on for
bootstrapping; and its state is worthless individually but load-bearing in aggregate.

---

# 1. The tracker's fundamental abstraction: a swarm

A tracker groups peers by a **swarm identifier**. In BitTorrent v1 that identifier is the torrent's
20-byte `info_hash`, derived by hashing the `info` dictionary of the torrent metadata. Every peer
sharing the same torrent announces under the same identifier, so the identifier is content-derived and
requires no allocation authority: two peers who have never met agree on the swarm name because they
computed it from the same bytes.

Internally the tracker holds one map from swarm identifier to a set of peer records, each record keyed
by peer identity and carrying the peer's observed address, claimed listening port, reported transfer
counters, and the timestamp of its last announce. BitTorrent v2 and hybrid torrents use newer
identifiers, but the model is unchanged.

Three operational properties fall out of this shape immediately:

* **The keyspace is unbounded and attacker-controlled.** Any client may announce any identifier,
  including one nobody else will ever use. A tracker must therefore cap how many swarms it will hold
  and decide what to shed first, or an attacker mints swarms until memory is exhausted.
* **Swarms are wildly uneven.** Real deployments see a long tail of swarms with one or two peers and a
  head of swarms with tens of thousands. Any per-swarm data structure that is fine at size two and
  quadratic at size fifty thousand will find that out in production, not in testing.
* **The identifier is the natural shard key.** Because every announce and every query for a torrent
  names the same identifier, routing on a hash of it sends all traffic for one swarm to one owner.
  This is what makes horizontal scale tractable (§22, §25).

---

# 2. What happens when a torrent starts

A client reads the announce URL and the swarm identifier from the torrent metadata, then sends an
**announce request** stating: the swarm it is joining, its own peer identifier, the port it claims to
accept incoming connections on, how much it has uploaded and downloaded, how much it still needs, that
this is a `started` event, and roughly how many peers it would like back.

The tracker identifies the swarm, records or refreshes the peer's endpoint, marks it active, selects a
subset of the other active peers, returns their addresses, and tells the client when to come back. The
client then contacts those peers directly and the tracker's involvement ends until the next interval.

Note what the tracker did *not* do: it did not test whether the announced port is reachable, verify
that the reported counters are true, check that the client actually possesses the torrent, or establish
anything between the peers. Each of those omissions is deliberate and each has an operational
consequence discussed later.

---

# 3. The announce lifecycle

A peer announces several times over a session. The lifecycle is the tracker's only view of peer
liveness, so its timing rules are the tracker's load control.

## 3.1 `started`

Sent when the client begins participating. The tracker creates or refreshes the peer's swarm entry.
This is also the announce most likely to arrive in a thundering herd: a popular new torrent produces a
burst of `started` events within seconds of publication, which is the peak a tracker must be sized for
rather than the steady state.

## 3.2 Periodic announce

After the first announce the client returns at the interval the tracker requested. Periodic announces
are the heartbeat: they say *I am still here* and refresh the peer's last-seen timestamp, and they
carry updated counters, port, and observed address.

The interval is the single most powerful capacity lever a tracker operator has. Request rate is swarm
population divided by interval, so doubling the interval halves steady-state load — at the cost of
peers taking longer to notice each other and stale records lingering longer. Operators raise the
interval under pressure and lower it for small swarms that need faster discovery.

## 3.3 `completed`

Sent when a peer finishes downloading everything. The peer transitions from leecher to seeder and the
tracker may increment a completed-download counter. In public deployments this event is
statistics-only and clients can trivially fake it; in private deployments it feeds accounting and
therefore needs the anti-cheat posture of §18.

## 3.4 `stopped`

Sent on a clean stop or shutdown, letting the tracker remove the record immediately.

The critical operational point is that `stopped` **cannot be relied upon**. Clients crash, lose
connectivity, get killed, suspend, or are behind a network that simply stops forwarding. A tracker that
removes peers only on `stopped` accumulates dead records forever and hands out addresses nobody
answers. Expiry by last-seen timestamp (§11) is the actual mechanism; `stopped` is an optimisation that
makes departures prompt when the client is well-behaved.

---

# 4. What is inside an announce request

A traditional HTTP announce is a single URL query string. What matters operationally is what each field
is trusted for.

## Important fields

**`info_hash`** identifies the swarm. In BitTorrent v1 it is 20 raw bytes, percent-encoded for HTTP
transport, not a hexadecimal string. Mis-encoding it is among the most common tracker implementation
bugs, and it fails in a particularly nasty way: the request parses, the tracker creates a swarm nobody
else is in, and the client silently discovers no peers. Any tracker should treat "swarm with exactly
one peer, ever" as a signal worth counting.

**`peer_id`** is a client-generated session identifier, traditionally 20 bytes, often carrying a client
family and version prefix. Its format is not universally enforced. Crucially it is **not** a secure
identity: it is self-asserted and free to mint, which is what makes Sybil attacks cheap (§26) and what
Nodera changes by signing records.

**`port`** is the port the peer claims to accept inbound connections on. The tracker pairs it with the
observed source address and generally does not test reachability, so an unreachable peer is
indistinguishable from a reachable one in the tracker's own state.

**`uploaded`** and **`downloaded`** are session totals reported by the client. Public trackers treat
them as statistics; private trackers use them for ratio accounting and must assume they are lies until
corroborated.

**`left`** is how many bytes the peer still needs. Zero means seeder, non-zero means leecher — this is
the only source of the seeder/leecher split the tracker reports. It says nothing about *which* pieces
the peer holds (§9).

**`event`** is `started`, `completed`, or `stopped`; a periodic announce normally omits it.

**`compact`** requests the compact binary peer representation instead of a list of dictionaries. In
practice compact mode is universal because the bandwidth difference is large (§7).

**`numwant`** is how many peers the client would like. It is a request, not a guarantee: the tracker
enforces its own ceiling, and a client asking for an implausible number is a rate-limiting signal.

**`key`** is a client-generated value used to recognise the same client across requests even when its
address changes — useful for a peer roaming between networks.

**`trackerid`** is an opaque value some trackers issue and expect back on later announces, letting the
tracker correlate a session without depending only on peer identifier and address.

---

# 5. How the tracker determines a peer's address

The peer supplies a port; the tracker takes the IP address from the connection itself. The resulting
endpoint is *observed source address plus announced port*.

Some protocols allow an explicit address parameter, and public trackers commonly ignore or heavily
restrict it, because honouring it turns the tracker into a poisoning tool: a malicious peer claims to
live at a victim's address and the tracker cheerfully directs an entire swarm to connect there. The
observed address is not a strong identity, but it is at minimum an address the announcer could receive
traffic at, which the claimed one is not.

Two production caveats. First, the tracker must know its real client address when deployed behind a
load balancer, reverse proxy, or CDN — a tracker reading the socket peer address behind an unconfigured
proxy records the proxy's address for every peer in every swarm, which quietly destroys discovery for
all of them. Second, IPv4 and IPv6 announces from the same peer are different endpoints and generally
handled as separate address families, with the response splitting them, since handing an IPv6-only peer
a list of IPv4 addresses wastes both sides' time.

---

# 6. Tracker response

The response tells the client when to come back, how large the swarm is, and which peers to try.
Traditionally it is a bencoded dictionary.

**`interval`** is how many seconds to wait before announcing again. Clients that ignore it and poll
aggressively are the most common source of self-inflicted tracker load, and trackers ban for it.

**`min interval`** is an optional floor: the client must not manually re-announce more often than this,
even when the user clicks "update tracker". Without a floor a tracker has no defence against a client
that treats every UI interaction as a reason to announce. Publishing only `interval` and hoping is a
common gap.

**`complete`** and **`incomplete`** are the approximate seeder and leecher counts, derived from whether
each peer reported zero bytes left. They are approximate for structural reasons: peers vanish without
notice, lie about their state, and linger in the table until expiry. Presenting them as exact in a UI
invites support tickets.

**`failure reason`** carries a human-readable refusal — unauthorised torrent, invalid passkey, rate
limit exceeded. Machine-stable refusal codes are worth more than prose here: a client that can
distinguish "you are announcing too fast" from "this torrent is not allowed" can back off correctly
instead of retrying into a ban.

A **warning message** field also exists for conditions that are not fatal, letting the tracker tell a
client something is wrong without failing the announce.

---

# 7. Compact peer format and response economics

Compact mode replaces a per-peer dictionary with a fixed-width record: for IPv4, four address bytes
plus a two-byte big-endian port, six bytes per peer, concatenated with no delimiters. IPv6 peers use
sixteen address bytes plus the port for eighteen bytes each and are normally returned in a separate
field.

This is not a micro-optimisation; it is the difference between a viable and an unviable public tracker.
Fifty peers cost three hundred bytes compact and several kilobytes as dictionaries. Multiply by the
announce rate of a large deployment and the encoding choice decides whether the response fits in a
single datagram, whether it fits in one network packet at all, and what the egress bill is.

Three consequences shape production behaviour. The response size is essentially linear in the number of
peers returned, so the peer-count ceiling is also the response-size ceiling. On UDP the practical peer
count is bounded by the path MTU long before it is bounded by policy, since fragmented datagrams are
lost more often. And because compact entries are pure address material with no length framing, a
malformed or truncated list is undetectable except by total length, which is why clients must validate
that the list length is an exact multiple of the entry size and refuse it otherwise.

---

# 8. What happens after receiving peers

The client attempts direct connections to the returned addresses. On success the peers perform the
BitTorrent handshake, which includes the torrent identifier — so the tracker's list is never blindly
trusted. If the remote answers for a different torrent, the connection is dropped.

That handshake is the security boundary that lets the tracker be untrusted. A tracker can hide peers,
invent peers, or return the addresses of unrelated machines; the worst outcome is wasted connection
attempts, because the peer protocol re-establishes what swarm it is actually in and every piece is
verified against the torrent's hashes before use.

After the handshake the peers exchange capabilities, bitfields, `have` messages, interest and choke
state, piece requests, and piece data. The tracker sees none of it.

Operationally, the fraction of returned addresses that yield a successful handshake is one of the most
valuable health metrics a tracker can obtain — and it can only obtain it from clients, since the tracker
itself never dials. Trackers that accept connection-result feedback can use it to demote unreachable
records; most do not, and hand out dead addresses until expiry.

---

# 9. The tracker does not know which pieces peers have

The tracker knows that a peer is in the swarm, what it claims to still need, and when it was last seen.
It does **not** know which individual pieces any peer holds, or which pieces are rare.

Piece availability is exchanged peer to peer through bitfields and `have` messages, and each client
computes rarity itself to drive rarest-first selection. This keeps the tracker's state small, its
response fixed-width, and its load independent of content size: a tracker for an eight-gigabyte torrent
does exactly as much work as one for an eight-megabyte torrent.

It is worth being explicit about the cost of this design, because systems built on the model sometimes
revisit it. Since the tracker cannot see availability, it cannot report whether a swarm is actually
complete across its members — a swarm with a hundred seeders that collectively lack one piece looks
perfectly healthy — and it cannot make placement or repair decisions. Any system that needs a durability
figure *before* peers connect has to put availability somewhere the directory can see it, and accept a
larger announce and more tracker state in exchange. Nodera makes exactly that trade (see
`docs/network/REFERENCE.md`).

---

# 10. How peers are selected

A tracker does not return the whole swarm. A large torrent may have millions of participants; returning
all of them would be pointless, since a client only maintains a few dozen connections.

Selection is implementation-specific and may weigh random sampling, whether the requester is a seeder
or leecher (seeders benefit from being pointed at leechers and vice versa), address-family
compatibility, subnet diversity, geographic proximity, peer age, recent announce success, account
policy on private trackers, abuse signals, and a hard per-response maximum.

The production requirement is that selection must not touch the whole swarm. Copying and shuffling a
fifty-thousand-peer set on every announce is the classic way to turn a popular torrent into a CPU
incident. Workable approaches include reservoir sampling, indexed random access into a stable array,
segmenting large swarms into pools and serving one pool per request, and caching a response batch per
swarm for a few seconds and serving it to many announcers — the last being extremely effective, since
peers do not need *distinct* peer lists, merely *usable* ones.

Determinism deserves a note. Random selection spreads load well but makes behaviour irreproducible and
tests flaky. Selection derived deterministically from stable inputs is reproducible for a given state,
which matters more for debugging a discovery complaint than statistical purity does.

---

# 11. How tracker state expires

Because `stopped` cannot be relied on (§3.4), every peer record carries a last-seen timestamp and a
cleanup process removes records older than a timeout.

The timeout must exceed the announce interval, with margin: if the interval is thirty minutes, expiry at
forty-five to sixty minutes tolerates a missed announce without evicting a healthy peer. Too tight and
the tracker evicts peers that are merely slow, shrinking swarms it should be growing; too loose and it
advertises the dead for an hour.

A tracker must tolerate delayed announces, packet loss, suspended laptops, mobile network transitions,
process crashes, and NAT address changes. Two implementation details make expiry behave under load.
First, expiry should be applied **on read as well as by the sweep**, so a query never reports a peer the
sweep has not yet reached — otherwise the tracker's answers depend on sweep timing. Second, the sweep
should be incremental rather than a single stop-the-world pass over every swarm, so cleanup never
competes with announces for a global lock (§24).

Clock handling matters more than it appears. A record whose timestamp is in the future — from clock skew
on either side — must not be treated as expired, or a tracker whose own clock jumps backwards evicts its
entire population at once.

---

# 12. HTTP trackers in production

An HTTP tracker uses ordinary HTTP or HTTPS. Its advantages are almost entirely operational: it is
simple to implement, trivially proxied, works with existing web infrastructure and TLS, and slots into
familiar authentication, logging, and rate-limiting tooling.

The costs are also operational. Header overhead dwarfs the payload — a three-hundred-byte peer list
inside a request and response carrying a kilobyte of headers is normal. Connection handling is heavier
than a datagram exchange, and TLS handshakes are heavier still, so connection reuse and session
resumption stop being niceties at scale. Proxies and CDNs interfere when misconfigured, most commonly by
hiding the client address (§5) or by caching a response that must never be cached.

A high-scale HTTP tracker is a pipeline: listener, announce parser, authentication and authorisation,
sharded swarm registry, peer sampler, response encoder. The parser deserves specific attention because
it is the only component reading attacker-controlled bytes, and because percent-decoding binary
identifiers is where correctness bugs concentrate.

---

# 13. UDP trackers in production

UDP trackers use a compact binary protocol. They cut per-announce overhead dramatically — no handshake,
no headers, one datagram each way — at the price of handling spoofing, packet loss, duplication,
reordering, and request/response matching by hand.

The flow has two stages: a connect request, then an announce request.

## 13.1 Connect request

The client sends a fixed protocol identifier, a connect action code, and a randomly generated
transaction identifier. The tracker replies with the action, the same transaction identifier, and a
**connection identifier**. The client verifies the transaction identifier matches before accepting the
reply.

## 13.2 Why use a connection ID?

UDP has no handshake, so an attacker can forge a source address and make the tracker send a large
response to a victim — turning the tracker into a reflection and amplification weapon.

The connection identifier is a short-lived cookie: the tracker issues it only in response to a packet
from that source address, so possessing one is evidence the sender can receive at the address it
claims. It need not be stored per client; a tracker can derive it statelessly by keying a MAC with a
server secret over the client address and a coarse time window, then validate it later by
recomputation. This gives replay resistance bounded by the window without any per-client state.

The property being bought is *address validation*, not identity. A connection identifier says the
sender receives packets at that address; it says nothing about who they are.

## 13.3 UDP announce request

With a connection identifier in hand the client sends a binary announce carrying the connection
identifier, an announce action, a fresh transaction identifier, the swarm identifier, the peer
identifier, the downloaded, left, and uploaded counters, the event, an optional address override, the
client key, the number of peers wanted, and the port. The response carries the action, the transaction
identifier, the interval, the leecher and seeder counts, and the compact peer entries.

## 13.4 Packet layout and sizing

The layout is a fixed sequence of big-endian integer fields at known offsets, followed by the
variable-length compact peer list. Two operational rules follow from fixed offsets. A datagram whose
length does not match the expected header size, or whose trailing peer region is not an exact multiple
of the entry size, must be rejected rather than partially parsed — there is no framing to resynchronise
against. And because the response is a single datagram, the number of peers returned must be chosen so
the total stays inside the path MTU; exceeding it causes IP fragmentation, and fragmented UDP is
disproportionately dropped by middleboxes.

Response sizing is therefore a policy decision, not an incidental one: it bounds amplification ratio,
fragmentation risk, and per-announce egress simultaneously.

---

# 14. UDP retransmission and client robustness

Datagrams are lost, duplicated, and reordered, so clients need retry logic: send, wait for a timeout,
and on no valid reply retry with exponential backoff, giving up after a bounded number of attempts and
falling back to another tracker or another discovery mechanism.

Replies are matched by transaction identifier. A client must discard any reply with the wrong
transaction identifier, the wrong action, an implausible length, an unexpected source address or port,
or malformed peer entries — and a bounded receive buffer must cap what a hostile tracker can make the
client allocate.

Two subtleties bite in production. Retries must not be so aggressive that a slow tracker sees each
client as several; and a client that treats a lost datagram as "this tracker knows nothing" will report
a healthy swarm as empty, which is why a tracker deliberately declining to answer over UDP (as Nodera
does for oversized replies) must be paired with a TCP fallback in the client rather than with a shrug.

---

# 15. Scraping

A **scrape** asks for swarm statistics — seeders, leechers, completed-download count — without
requesting peer addresses. It is useful for displaying torrent statistics and unnecessary for
downloading.

Public trackers commonly restrict scrapes, and for two distinct reasons. A full scrape is expensive: it
touches every swarm rather than one, so it is the cheapest way for a client to make the tracker do the
most work. And scrape enables enumeration: an attacker who can iterate identifiers learns the tracker's
entire catalogue and each swarm's activity level, which is both an intelligence gain and a privacy
problem for participants (§27). Typical posture is to disable full scrape entirely, allow single-swarm
scrape at a lower rate limit than announce, and require authentication where the deployment has
accounts.

---

# 16. Multiple trackers and tracker tiers

Torrent metadata may list many trackers, optionally grouped into tiers that express preference and
fallback. A client uses alternatives when one fails, which improves availability of *discovery* even
though no individual tracker became more reliable.

The structural limitation is that each tracker knows only the peers that announced to *it*. If one peer
announced to tracker A and another to tracker B, they may never learn about each other through trackers
at all. Reunification requires a common tracker, a distributed hash table, peer exchange, or an indirect
introduction by a third peer.

This has a direct operational implication for anyone deploying a tracker: adding a second independent
tracker does not double swarm connectivity, it *partitions* it, unless clients announce to both.
Well-behaved clients do announce to multiple trackers, which is what makes tiers work — but a client
that announces only to the first working tracker in a tier converts redundancy into fragmentation.

---

# 17. Trackers, DHT and peer exchange

Trackers are one discovery mechanism among several.

A **distributed hash table** stores swarm-identifier-to-peers mappings across participating nodes with
no central tracker; a peer queries the DHT to locate participants. It removes the single point of
failure and the single point of censorship, at the cost of higher lookup latency, more complex
bootstrapping, and its own abuse surface.

**Peer exchange** lets already-connected peers tell each other about further peers, so the peer graph
grows after the first few connections without any server. It is extremely cheap and extremely
effective, and it is why a swarm can survive its tracker going down entirely — existing members keep
introducing newcomers as long as *someone* is reachable.

**Local peer discovery** finds peers on the same network segment by multicast, which is both the
fastest and the cheapest path when it applies.

In practice a client merges and deduplicates addresses from the tracker, the DHT, peer exchange, local
discovery, and its own cache of peers from previous sessions. The operational lesson is that discovery
should be treated as a set of partial, untrusted, independently-failing sources whose results are
unioned — not as a lookup with an authoritative answer. A deployment with only one mechanism has a
single point of failure no matter how well that mechanism is run.

---

# 18. Public and private trackers

A **public tracker** generally lets any peer announce for any swarm, requires no account, and exists
purely for peer discovery. Its operational profile is high volume, low value per request, and abuse
resistance as the dominant design concern.

A **private tracker** ties every announce to a user account, usually through a per-user passkey embedded
in the announce URL, and records identity, transferred bytes, seeding time, active torrents, completion
events, ratio, and account restrictions. Private torrents normally set a flag telling compatible clients
to avoid unauthorised discovery mechanisms — public DHT, public peer exchange, local discovery — which
keeps swarm membership under tracker control and is enforced by client cooperation rather than by
cryptography.

The hard problem is that all accounting numbers originate from clients, and clients can lie. Defences
include implausible-speed detection, overlapping-session detection, correlating a peer's claims against
other peers' reports, announce-frequency analysis, tokenised peer identities, statistical anomaly
detection, mandated client versions, and delaying accounting long enough to cross-check. None of them
makes client-reported accounting trustworthy; they make cheating expensive and detectable. An operator
should size their anti-cheat effort accordingly and be honest in the product about what is measured
versus asserted.

A private tracker also acquires obligations a public one does not: it holds personal data, it becomes a
persistence and backup problem (§23), and its passkeys are credentials that leak through torrent files
and support tickets.

---

# 19. Trackers and NAT

A tracker knows a peer announced from some address and claims some port. It does not know that the pair
is *reachable*. A peer behind NAT with no port mapping is unreachable from outside even though its
announce arrived perfectly.

Clients mitigate this themselves with UPnP IGD, NAT-PMP, PCP, manual port forwarding, IPv6, or NAT
traversal extensions. An unreachable peer can still download, because it can open outbound connections
— but two peers that are both unreachable cannot connect at all, and a basic tracker will happily list
each to the other forever. A tracker does not fix this; it merely reports claims.

At scale this produces a measurable population of records that will never yield a connection.
Deployments that care can gather connection-result feedback from clients and demote records that nobody
manages to reach, but the tracker cannot determine reachability on its own without dialling peers —
which would change what a tracker is and how much it costs to run. Systems that must serve
unreachable-to-unreachable pairs need a relay, which is a different service with a different cost model
(§20, and `RENDEZVOUS.md`).

---

# 20. Tracker versus relay

These roles must never be confused. A tracker tells Alice to try Bob and then steps aside; Alice's data
flows directly to Bob. A relay forwards the actual traffic, so every byte between Alice and Bob crosses
the relay's network interface twice.

The cost difference is the entire point. A tracker's bandwidth is small announce requests and peer
lists, essentially independent of how much data the swarm moves; a relay's bandwidth *is* the data the
swarm moves. One tracker can coordinate millions of peers on modest hardware. One relay carrying the
same swarm's traffic would be a content delivery network.

Practical consequences: a tracker is cheap to over-provision and cheap to run redundantly, so there is
little excuse for a single instance; a relay is expensive, must be metered and quota-limited, and is the
component an abuser wants to capture. Any design that quietly lets a discovery service end up on the
data path has converted a cheap component into an expensive one, usually without noticing until the
bill arrives.

---

# 21. The announce decision path

Stated as behaviour rather than code, handling one announce is a fixed sequence of decisions, each of
which can refuse:

1. **Bound the input.** Reject anything larger than the configured maximum before allocating for it.
2. **Parse and validate.** Decode the request; a malformed request is refused without touching swarm
   state.
3. **Rate-limit the source.** Charge the announce against a per-address (and, on private deployments,
   per-account) budget and refuse when the budget is exhausted.
4. **Authenticate and authorise** where the deployment has accounts: is this passkey valid, is this
   torrent permitted, is this account in good standing?
5. **Establish identity trust** to whatever degree the protocol allows — signature verification and
   freshness checks where records are signed, address validation where a connection cookie is in use.
6. **Apply quotas.** Is this a known swarm, or a new one against the swarm ceiling? Is the swarm at its
   peer ceiling, and if so is there an expired slot to reclaim?
7. **Mutate state.** Insert or replace the peer's record — replace, never merge, so a peer that changed
   address or state is never a blend of its past and present — and stamp the last-seen time.
8. **Handle departure.** On `stopped`, remove the record and release any per-identity binding.
9. **Select peers** by the sampling policy, bounded by the response ceiling.
10. **Encode and answer**, including the interval that paces the next request.

The ordering is the security property: every cheap refusal precedes every expensive operation, and
nothing allocates or mutates before the request has been bounded and rate-limited. A pipeline that
parses fully before rate-limiting has handed the attacker the parse cost for free.

---

# 22. Production tracker architecture

A scalable tracker is a funnel. Requests arrive on HTTP and UDP surfaces and are normalised into one
internal announce representation. They pass validation and authentication, then a routing layer maps the
swarm identifier onto a shard. Each shard owns a disjoint set of swarms and holds their peer records in
memory. A sampling stage builds the peer subset, and an encoder writes the response in the requested
format.

Sharding on a hash of the swarm identifier is what makes this work: every announce and query for one
swarm reaches the same shard, so no cross-shard coordination is needed to answer correctly. Shard count
should exceed core count so shards can be moved between processes or machines without re-partitioning
semantics.

Some structural choices consistently pay off. Keeping both wire surfaces in front of a single decision
core means a swarm announced over one is queryable over the other with no duplicated logic — and, more
importantly, means only one implementation of the admission rules exists to be audited. Keeping the
decision core free of I/O and of clock reads, with the current time passed in, makes the entire
admission path — signatures, freshness, quotas, expiry, sampling, health transitions — testable without
sockets or sleeps, which is the difference between a tracker whose limits are verified and one whose
limits are aspirational.

---

# 23. Does a tracker need a database?

For public swarm state, no. Peer records are ephemeral by nature: a peer joins, refreshes, and expires,
and a tracker restart loses nothing that matters because every live peer re-announces within one
interval. In-memory state — process-local, shared-memory, or an in-memory key-value store for
multi-process deployments — is normally sufficient, and persisting every heartbeat to a relational
database is a large expense for no benefit.

Persistence *is* needed for the things that are not ephemeral: private-tracker user statistics,
completed-download counters, audit logs, bans, account state, torrent authorisation, and long-term
analytics. Note that these have completely different durability, backup, and privacy requirements from
swarm state, which is a good reason to keep them in a different store rather than promoting swarm state
into a durable one to reuse the plumbing.

The design consequence to state plainly: a restart being harmless is a *feature* to be defended.
Deploys, crashes, and rolling restarts all become routine, and capacity can be added or removed without
a migration. Any change that makes a tracker restart lossy has given up one of the model's best
operational properties.

---

# 24. Concurrency problems

A tracker receives many simultaneous announces for the same swarm. Naïve implementations produce race
conditions, duplicate peer entries, lost updates, inaccurate seeder counts, lock convoys on a global
structure, and cleanup that starves announces.

The remedies are structural: shard by swarm identifier and synchronise per shard or per swarm rather
than globally; keep counters atomic; make cleanup incremental so it never holds a lock long; and prefer
data structures whose common operation is an insert-or-replace rather than a read-modify-write across
the whole swarm.

Response reproducibility deserves attention here too. Iterating a hash map yields an unspecified order,
so two identical states can produce different answers; sorting before answering, or maintaining a
stable ordering, makes responses reproducible for a given state, which is what makes sampling behaviour
testable at all.

A single-threaded event loop removes in-process data races but does not remove the problem: it moves it
to the process boundary, where distributed replicas still need consistent ownership of each swarm
(§25).

---

# 25. Tracker replication and horizontal scale

Running several instances behind a load balancer creates a partition: a peer announcing to instance one
is invisible to a peer announcing to instance two. Four approaches exist, with different trade-offs.

**Shared state** puts swarm state in a store both instances read and write. Correct and simple to reason
about; the store becomes the bottleneck and the failure domain, and per-announce latency now includes a
network round trip.

**Consistent hashing** routes each swarm identifier to a single owning instance, so ownership is
exclusive and no shared store is needed. This is the approach that scales furthest, and its costs are
the usual ones: rebalancing on membership change, and a hot swarm still landing entirely on one owner.

**Replicated swarm state** has instances gossip peer updates to each other. It tolerates instance loss
gracefully and trades exact consistency for availability, with replication traffic growing with announce
rate.

**Accepting partial views** does nothing at all: each instance answers from its own subset and relies on
peer exchange and the DHT to reunite the subgroups. It is the simplest option, costs nothing, and by its
own admission reduces discovery completeness — which is acceptable precisely because clients treat
discovery as a set of partial sources (§17). For very large public swarms, where any subset is more
peers than a client can use, it is often the right answer.

A deployment should choose deliberately and document the choice, because the failure mode of the fourth
option is invisible: nothing errors, discovery is simply less complete than an operator assumes.

---

# 26. Common attacks and abuse

**Swarm poisoning.** An attacker registers many fake or unreachable addresses so clients waste
connection attempts. Mitigations: rate limits, refusing client-supplied addresses (§5), peer expiry,
connection-result feedback, per-subnet caps on how many peers one source may create, and proof-of-work
in genuinely hostile environments.

**Sybil attacks.** One participant mints thousands of peer identities. Because traditional peer
identifiers are free to create, the identifier alone is no defence; the cost must come from elsewhere —
address diversity requirements, accounts, or cryptographic identities bound to keys.

**Scrape harvesting.** An attacker enumerates torrents and swarm sizes. Mitigations: disable full
scrape, require authentication, rate-limit scrape below announce, and avoid any interface that permits
identifier enumeration.

**Denial of service.** Attackers flood announces or create enormous numbers of swarms. Mitigations:
request-size limits, per-address and per-account limits, a bounded swarm count with a defined shedding
policy, expiry, stateless UDP cookies, and load shedding that refuses cheaply rather than degrading
everything.

**Reflected UDP traffic.** An attacker forges a victim's address so the tracker replies to the victim.
The connection-identifier mechanism (§13.2) reduces this by proving the requester receives at its
claimed address; capping reply size and the reply-to-request amplification ratio removes the leverage
directly, and is effective even without a cookie exchange.

**Fake statistics.** Clients lie about uploaded, downloaded, left, and completed. Public trackers mostly
tolerate it because the fields are not security-critical; private trackers need the anti-cheat posture
of §18.

The unifying principle is that a tracker must be *safe while trusted for nothing*. Every mitigation
above either bounds resource consumption or refuses cheaply. None of them attempts to establish truth,
because the peer protocol and content hashing already handle correctness, and a tracker that tries to
become an authority acquires all the costs of one without any of the leverage.

---

# 27. Privacy implications

A tracker necessarily observes each peer's public address, which torrent it announced for, when it
joined, when it last announced, its reported transfer volumes, whether it claims to be a seeder, its
client identifier and version, and its listening port. HTTPS protects that from observers between client
and tracker; it does not protect it from the tracker.

The tracker then *distributes* each peer's address to other swarm members, which is the entire purpose.
Participating in a tracker-based swarm therefore inherently reveals a network endpoint to other
participants, and any privacy story that ignores this is incomplete.

Operationally this makes tracker logs sensitive by default. Announce logs are a record of who was
sharing what and when; retaining them longer than needed for abuse handling creates risk with no
benefit. Aggregating and truncating addresses in analytics, keeping raw logs short-lived, and being
explicit in operator documentation about what is retained are the baseline expectations.

---

# 28. Complete sequence

A single session, end to end. A client opens a torrent and reads or computes its swarm identifier. It
sends a `started` announce carrying the identifier, its peer identifier, its claimed listening port, and
its transfer counters. The tracker observes the source address, rate-limits and validates the request,
adds the peer to the swarm, selects a subset of active peers, and answers with the interval, the seeder
and leecher counts, and the compact peer list.

The client dials the returned addresses directly and verifies the torrent identifier in the peer
handshake, discarding any peer that answers for something else. It exchanges bitfields, learns which
pieces each neighbour holds, and downloads pieces directly, verifying each against the torrent's hashes.
It announces again at the interval, announces `completed` when it finishes, and announces `stopped` when
it exits. If `stopped` never arrives, expiry removes it.

The essential division of labour: the tracker says *here are peers you may try*, the peer protocol says
*here are the pieces I have*, and the direct connection carries the actual bytes. A tracker is a
lightweight, swarm-scoped discovery coordinator — not a server through which content passes.

---

# 29. Capacity planning and load characteristics

Tracker load is dominated by one number: announces per second, which is total peer population divided by
the announce interval, plus a burst factor for swarm launches and for clients that reconnect in waves
after a network event. A hundred thousand peers on a thirty-minute interval is a steady fifty-five
announces per second — trivial — but the same population re-announcing after a tracker restart arrives as
a spike bounded only by client backoff behaviour.

That asymmetry is the central planning fact: **steady state is easy and recovery is hard.** A tracker
must be sized for the reconnection storm that follows its own downtime, not for its average. Client
backoff and jitter are the only things that flatten the spike, and they are outside the operator's
control, which argues for shedding load cheaply and answering quickly rather than queueing.

Memory is the other dimension, and it is straightforward to bound: cost is peers times record size, plus
per-swarm overhead times swarm count. Because both multipliers are attacker-influenced, both need
explicit ceilings and a defined shedding order — typically evicting the least recently active swarm that
holds no live peers before refusing a new one, so a burst of junk swarms does not deny service to real
ones.

CPU is normally spent in three places: parsing requests, selecting peers, and cryptography where records
are signed. Signature verification is the expensive one where it applies, and it belongs *after* rate
limiting for exactly that reason. Egress is dominated by peer lists and is controlled by the response
ceiling; ingress is dominated by request count.

---

# 30. Deployment topology and network placement

A tracker is latency-tolerant — a client waits the interval regardless — so geographic proximity matters
far less than for a relay. Availability and address correctness matter more.

Placement decisions that matter in practice: terminate as close to the client as possible only if the
real client address survives the hop, since an address-mangling proxy is worse than a distant tracker
(§5). Prefer many small instances over a few large ones, because the model tolerates instance loss and
restart cheaply (§23). Publish multiple independent endpoints rather than one load-balanced name where
clients support tiers, so a failure of the balancer or its DNS is not a total discovery outage — while
remembering that independent endpoints partition swarms unless clients announce to all of them (§16).

Anycast and DNS-based distribution both work, with a caveat specific to UDP: a datagram from one client
may reach a different instance than its connect exchange did, which breaks any cookie derived from
per-instance state. Deriving the connection identifier from a *shared* secret plus the client address
and time window keeps it valid across instances, which is a good reason to prefer the stateless
construction (§13.2).

Address families should both be served, on the same identifier space, with responses split per family.
Serving only one family silently halves discovery for dual-stack swarms.

---

# 31. Observability

The metrics that actually diagnose a tracker are not the obvious ones. Request rate and latency are
table stakes; the following carry the real signal.

**Refusals by reason.** Every refusal path counted separately — malformed, too large, rate-limited,
unauthorised, swarm-limit, swarm-full, stale, bad signature. A climbing single reason localises an
incident immediately, and the aggregate "errors" metric that most deployments start with localises
nothing.

**Swarm and peer population**, plus swarm size distribution. The distribution, not the mean, is what
predicts whether a sampling change will hurt.

**Expiry volume and age at expiry.** Records expiring in bulk indicates a client population that
stopped announcing — usually a network event or a client bug, both worth knowing about.

**Response peer counts and response sizes**, as distributions. This is where a fragmentation or
amplification problem shows up before an abuse report does.

**Single-peer swarm creation rate.** A strong signal for both identifier-encoding bugs (§4) and swarm
flooding (§26).

**UDP replies suppressed** by size or amplification policy, since a deployment that suppresses many
replies is silently pushing load to its TCP surface and depends on clients falling back correctly.

Useful service objectives are availability of the announce path, a latency ceiling at a high percentile,
and a correctness objective that is easy to overlook: the fraction of returned addresses that clients
successfully handshake with, obtainable only from client feedback (§8) and the closest thing to a
measure of whether the tracker is doing its actual job.

Logs should be sampled rather than complete at high volume, and given the privacy profile of §27,
retained briefly and with addresses truncated in anything long-lived.

---

# 32. Failure modes and operational response

**The tracker is down.** Existing swarms continue: peers already connected keep transferring, and peer
exchange and the DHT keep introducing newcomers. New peers with no other discovery mechanism cannot
join. This is the model's best property — the tracker is a bootstrap aid, not a dependency of the running
swarm — and it is why extended downtime is survivable while a *reconnection storm* on recovery is the
real hazard (§29).

**The tracker is up but returns empty peer lists.** Usually a keying problem, not a load problem:
identifier mis-encoding, an address-mangling proxy putting every peer behind one address, or a routing
change sending announces and queries for one swarm to different shards. Diagnosis starts with
single-peer swarm creation rate and with comparing what one client announced against what another is
told.

**Announce rate collapses.** Either clients cannot reach the tracker or something raised the interval.
Interval changes should be treated as production changes with a rollout, because they take effect only
as slowly as clients come back — and are therefore hard to roll back quickly.

**Memory grows without bound.** A swarm-count or peer-count ceiling is missing or too high, or expiry
has stalled. The shedding policy should be verified under load rather than assumed, since an untested
eviction path is usually a broken one.

**Amplification abuse report.** Reply-size and amplification ceilings are the immediate lever; disabling
the UDP surface entirely is the emergency stop, and is survivable exactly because well-built clients
fall back to TCP (§14).

**A hot swarm saturates one shard.** Consistent hashing cannot spread a single identifier. The available
responses are response caching per swarm (§10), splitting the swarm into served pools, or accepting
partial views (§25).

The generalisable lesson is that a tracker's incidents are rarely about throughput. They are about
address correctness, keying, and eviction policy — and each of those is verifiable in advance if the
admission and expiry logic is testable without a network (§22).

---

# 33. Configuration and change management

The parameters that materially change tracker behaviour are few and worth calling out as a set, because
several take effect only after a delay and one is effectively irreversible in the short term:

* **Announce interval** and **minimum interval** — the primary load control, applied by clients, and
  therefore slow in both directions.
* **Peer expiry timeout** — must remain comfortably larger than the interval; changing one without the
  other is the classic way to start evicting healthy peers.
* **Response peer ceiling** — governs response size, egress, and fragmentation risk simultaneously.
* **Swarm count and per-swarm peer ceilings**, with the shedding policy that applies when they are
  reached.
* **Per-address and per-account rate budgets.**
* **UDP reply-size and amplification ceilings**, and whether the UDP surface is enabled at all.
* **Maximum request size.**
* **Clock skew tolerance** where announces carry timestamps.

Two disciplines make these safe to operate. First, validate the configuration at load and refuse to
start on an incoherent combination — an expiry shorter than the announce interval, or a UDP request
ceiling above the frame ceiling — rather than discovering it as behaviour under load. Second, treat
interval changes as staged rollouts and remember that the old value persists in every client until it
next announces, so the effect of a change is spread across a full interval and cannot be reverted faster
than that.

---

# 34. Abuse handling and operator posture

A public tracker will receive complaints and requests concerning the content its swarms carry, and the
tracker's own architecture is the honest answer to most of them: it holds no content, transfers no
content, and cannot inspect what a swarm's identifier refers to, since the identifier is an opaque hash.
What an operator can do is refuse to serve specific identifiers, and that capability — an identifier
blocklist consulted during admission — should exist before it is needed rather than be improvised during
an incident.

The corresponding operational requirements: refusals should be attributable and logged with a stable
reason so a request can be answered factually; blocklist entries should be reviewable and reversible;
and the retention posture of §27 should be decided in advance, because announce logs are simultaneously
the evidence for abuse handling and the largest privacy liability the service holds. Those two pull in
opposite directions, and resolving that tension deliberately — short retention, truncated addresses,
aggregate statistics kept longer than raw records — is part of running the service rather than an
afterthought.

Private deployments additionally hold account data and credentials, which converts the tracker from a
stateless coordinator into a system with backup, breach, and data-subject obligations. That is a
significant step up in operational responsibility and is worth taking knowingly.
