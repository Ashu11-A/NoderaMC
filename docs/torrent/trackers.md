# BitTorrent-Style Trackers

> **Reference spec** — implemented for Nodera by **[Task 28](../Task.28.md)**
> (`rust/nodera-tracker` + Java `TrackerClient`, on the [Task 27](../Task.27.md) monorepo
> foundation). Binding protocol and architecture decisions live in the task file; this document
> is the background architecture study it draws on.

A **tracker** is a specialized rendezvous service for a peer-to-peer swarm. Its job is to answer one question:

> “Which peers are currently participating in the same torrent as me?”

It does **not** normally store the shared files, transfer pieces, verify file contents, or relay traffic between peers.

```text
                  announce / peer discovery
        ┌──────────────────────────────────────┐
        │                                      │
        ▼                                      │
     Tracker ◄─────────────── Peer B            │
        ▲                       ▲               │
        │                       │ direct data   │
        │                       │ transfer      │
        └──────── Peer A ◄──────┘               │
                    │                           │
                    └───────────────────────────┘
```

The tracker introduces Peer A to Peer B. After that, A and B communicate directly.

---

# 1. The tracker’s fundamental abstraction: a swarm

A tracker groups peers by a **swarm identifier**.

In traditional BitTorrent v1, this identifier is the torrent’s 20-byte `info_hash`, calculated from the bencoded `info` dictionary inside the `.torrent` file.

Conceptually:

```text
torrent metadata
    |
    v
hash(info dictionary)
    |
    v
info_hash
    |
    v
swarm identifier
```

Every peer sharing the same torrent announces itself using the same `info_hash`.

The tracker internally maintains something resembling:

```ts
type InfoHash = string;
type PeerId = string;

interface PeerRecord {
  peerId: PeerId;
  publicIp: string;
  listeningPort: number;

  uploaded: bigint;
  downloaded: bigint;
  left: bigint;

  lastSeenAt: number;
  completed: boolean;
}

type Swarm = Map<PeerId, PeerRecord>;

const swarms = new Map<InfoHash, Swarm>();
```

For example:

```text
Swarm: 7A42E8...

Peer A: 203.0.113.10:51413
Peer B: 198.51.100.25:6881
Peer C: 192.0.2.44:45521
Peer D: 203.0.113.73:49160
```

When a new peer asks for participants in `7A42E8...`, the tracker returns some of those addresses.

BitTorrent v2 and hybrid torrents use newer identifiers and extensions, but the fundamental model remains the same: a swarm is indexed by a content-derived identifier.

---

# 2. What happens when a torrent starts

Suppose Alice opens a torrent.

Her client reads:

```text
announce: https://tracker.example.com/announce
info_hash: 7A42E8...
total size: 8 GiB
```

The client then sends an **announce request** to the tracker.

Conceptually:

```text
Alice -> Tracker:

I am participating in swarm 7A42E8...
My peer ID is Alice123...
I am listening on port 51413
I have downloaded 0 bytes
I have uploaded 0 bytes
I still need 8 GiB
My event is "started"
Please give me some peers
```

The tracker:

1. identifies the swarm using `info_hash`;
2. records or updates Alice’s endpoint;
3. marks Alice as active;
4. selects a subset of other active peers;
5. returns their IP addresses and ports;
6. tells Alice when to announce again.

Alice then contacts those peers directly.

---

# 3. The announce lifecycle

A peer usually announces several times during a torrent session.

## 3.1 `started`

Sent when the client begins participating.

```text
event=started
```

The tracker creates or refreshes the peer’s swarm entry.

## 3.2 Periodic announce

After the initial announce, the client announces again at the interval requested by the tracker.

For example:

```text
Tracker response:
interval = 1800 seconds
```

The client should announce again approximately 30 minutes later.

Periodic announces serve as heartbeats:

```text
Peer -> Tracker: I am still here
```

The request also updates statistics such as:

* bytes uploaded;
* bytes downloaded;
* bytes remaining;
* current listening port;
* current public address.

## 3.3 `completed`

Sent when the peer finishes downloading all content:

```text
event=completed
left=0
```

The peer transitions from a **leecher** to a **seeder**.

The tracker may increment the torrent’s completed-download counter.

## 3.4 `stopped`

Sent when the torrent is stopped or the client shuts down cleanly:

```text
event=stopped
```

The tracker can immediately remove the peer.

However, clients can crash, lose Internet access, or be forcibly terminated. Therefore, trackers cannot rely on receiving `stopped`.

They also expire inactive peers based on `lastSeenAt`.

---

# 4. What is inside an announce request?

A traditional HTTP tracker request looks conceptually like this:

```http
GET /announce
  ?info_hash=<binary-url-encoded-hash>
  &peer_id=<20-byte-peer-id>
  &port=51413
  &uploaded=0
  &downloaded=1048576
  &left=8588886016
  &compact=1
  &event=started
  &numwant=50
```

In practice, this is one URL query string.

## Important fields

### `info_hash`

Identifies the torrent swarm.

For a traditional BitTorrent v1 torrent, it is 20 raw bytes, URL-encoded for an HTTP request.

It is not normally sent as a human-readable hexadecimal string.

For example, a byte such as:

```text
0xA7
```

is encoded as:

```text
%A7
```

Incorrectly encoding `info_hash` is one of the most common tracker implementation bugs.

---

### `peer_id`

A client-generated identifier, traditionally 20 bytes.

Some clients embed client-family and version information in the peer ID:

```text
-UT2210-...
-qB4600-...
```

The exact format is not universally required.

The peer ID identifies the client session, but trackers should not treat it as a secure cryptographic identity.

---

### `port`

The TCP or UDP port where the peer claims to accept incoming BitTorrent connections.

For example:

```text
port=51413
```

The tracker combines this with the peer’s observed public IP address:

```text
203.0.113.10:51413
```

The tracker generally trusts that the port is correct. It does not necessarily test reachability.

---

### `uploaded`

Total bytes uploaded during the torrent session.

```text
uploaded=734003200
```

This is often used by private trackers for ratio accounting.

Public trackers usually use it mostly for statistics, if they use it at all.

---

### `downloaded`

Total bytes downloaded during the torrent session.

```text
downloaded=104857600
```

This usually represents payload accounting reported by the client, not something independently measured by the tracker.

---

### `left`

The number of bytes the peer still needs to obtain.

```text
left=0
```

means the peer has completed the torrent and is a seeder.

```text
left>0
```

means it is still downloading and is categorized as a leecher.

This field does not tell the tracker which individual pieces the peer owns.

---

### `event`

Usually one of:

```text
started
completed
stopped
```

A periodic announce normally omits the event.

---

### `compact`

Requests a compact binary peer representation:

```text
compact=1
```

Without compact mode, peers may be returned as a larger bencoded list of dictionaries.

Compact mode is much more bandwidth-efficient.

---

### `numwant`

The approximate number of peers requested:

```text
numwant=50
```

The tracker may return fewer peers or enforce its own maximum.

It is a request, not a guarantee.

---

### `key`

A client-generated value used to distinguish or validate the announcing client across requests.

Its exact use varies between tracker implementations.

---

### `trackerid`

Some trackers return an opaque tracker identifier. The client sends it back in later announces.

This allows a tracker to correlate the session without relying only on `peer_id` and network address.

---

# 5. How the tracker determines a peer’s address

The peer sends a listening port, but the tracker normally learns the IP address from the network connection itself.

For example:

```text
HTTP request source address:
203.0.113.10

Announced port:
51413

Resulting endpoint:
203.0.113.10:51413
```

A client can sometimes provide an explicit `ip` parameter, but public trackers commonly ignore or restrict it because accepting arbitrary addresses would allow poisoning.

Otherwise, a malicious peer could say:

```text
I am located at the victim's IP address
```

and cause other peers to connect to that victim.

Therefore:

```text
Observed source IP + announced listening port
```

is generally safer than:

```text
Untrusted user-supplied IP + announced listening port
```

---

# 6. Tracker response

An HTTP tracker response is traditionally a bencoded dictionary.

Conceptually:

```ts
interface TrackerResponse {
  interval: number;
  minInterval?: number;

  complete?: number;
  incomplete?: number;

  peers: Uint8Array | PeerDescription[];
  peers6?: Uint8Array;

  trackerId?: string;
  warningMessage?: string;
  failureReason?: string;
}
```

A conceptual decoded response might be:

```json
{
  "interval": 1800,
  "complete": 342,
  "incomplete": 86,
  "peers": [
    {
      "ip": "203.0.113.50",
      "port": 6881
    },
    {
      "ip": "198.51.100.22",
      "port": 51413
    }
  ]
}
```

## `interval`

How many seconds the client should wait before announcing again.

```text
interval = 1800
```

The client should not continuously request new peers. Ignoring the interval can overload the tracker and may result in a ban.

## `min interval`

An optional lower bound.

For example:

```text
interval = 1800
min interval = 900
```

The client should not manually reannounce more often than every 900 seconds.

## `complete`

Approximate number of seeders:

```text
left = 0
```

## `incomplete`

Approximate number of leechers:

```text
left > 0
```

These numbers are approximate because peers disappear unexpectedly, lie, fail to send stop messages, or remain in the table until expiration.

## `failure reason`

When the announce fails, a tracker can return:

```text
failure reason: torrent not authorized
```

or:

```text
failure reason: invalid passkey
```

---

# 7. Compact peer format

Compact responses avoid returning a dictionary for each peer.

For IPv4, every peer occupies exactly six bytes:

```text
4 bytes: IPv4 address
2 bytes: port, unsigned big-endian
```

For example:

```text
CB 00 71 32 C8 D5
```

could represent:

```text
CB 00 71 32 = 203.0.113.50
C8 D5       = 51413
```

So the entire peer list is:

```text
[IP][PORT][IP][PORT][IP][PORT]...
```

TypeScript decoder:

```ts
interface IPv4Peer {
  readonly host: string;
  readonly port: number;
}

function decodeCompactIPv4Peers(data: Uint8Array): IPv4Peer[] {
  if (data.byteLength % 6 !== 0) {
    throw new Error(
      `Invalid compact peer list length: ${data.byteLength}`,
    );
  }

  const peers: IPv4Peer[] = [];

  for (let offset = 0; offset < data.byteLength; offset += 6) {
    const host = [
      data[offset],
      data[offset + 1],
      data[offset + 2],
      data[offset + 3],
    ].join(".");

    const port =
      (data[offset + 4] << 8) |
      data[offset + 5];

    peers.push({ host, port });
  }

  return peers;
}
```

For IPv6, each peer occupies 18 bytes:

```text
16 bytes: IPv6 address
2 bytes: port
```

IPv6 peers are commonly returned in a separate `peers6` field.

---

# 8. What happens after receiving peers?

Suppose the tracker returns:

```text
Peer B: 198.51.100.22:6881
Peer C: 203.0.113.17:51413
Peer D: 192.0.2.90:49000
```

Alice’s client attempts direct connections:

```text
Alice -> Peer B
Alice -> Peer C
Alice -> Peer D
```

When a connection succeeds, the peers perform the BitTorrent handshake.

That handshake includes the torrent identifier. This prevents the tracker’s peer list from being blindly trusted.

Conceptually:

```text
Alice -> Bob:
I want to communicate about swarm 7A42E8...

Bob -> Alice:
I am also participating in swarm 7A42E8...
```

If Bob responds with a different torrent identifier, the connection is rejected.

After the handshake, the peers exchange:

* protocol capabilities;
* bitfields;
* `have` messages;
* interested/not-interested state;
* choking/unchoking state;
* piece requests;
* piece blocks.

The tracker is no longer involved in those messages.

---

# 9. The tracker does not know which pieces peers have

This is an important distinction.

The tracker knows:

```text
Peer A is in the swarm
Peer A says it has 0 bytes left
Peer B says it has 3 GiB left
Peer C was last seen five minutes ago
```

The tracker usually does not know:

```text
Peer A has pieces 0, 1, 2, 7, 10 and 15
Peer B is missing piece 200
Piece 314 is rare
```

Piece availability is exchanged directly between peers using messages such as:

```text
bitfield
have
have-all
have-none
```

The BitTorrent clients themselves calculate piece rarity and decide which pieces to request.

This keeps trackers simple and reduces tracker load dramatically.

---

# 10. How peers are selected

A tracker usually does not return the entire swarm.

A large torrent might have millions of participants. Returning all of them would be inefficient and unnecessary.

Instead, the tracker chooses a subset:

```text
requested peers: 50
returned peers: 50
```

The selection algorithm is implementation-specific.

A tracker might consider:

* random sampling;
* whether the requester is a seeder or leecher;
* IPv4 versus IPv6 compatibility;
* subnet diversity;
* geographic proximity;
* peer age;
* recent successful announces;
* private-tracker account policy;
* abuse or reputation signals;
* maximum peers per announce.

A simple implementation could use random sampling:

```ts
function samplePeers<T>(
  peers: readonly T[],
  count: number,
): T[] {
  const copy = [...peers];

  for (let index = copy.length - 1; index > 0; index--) {
    const randomIndex = Math.floor(Math.random() * (index + 1));

    [copy[index], copy[randomIndex]] = [
      copy[randomIndex],
      copy[index],
    ];
  }

  return copy.slice(0, count);
}
```

A production tracker should avoid copying and shuffling an entire large swarm. It might instead use reservoir sampling, indexed random access, segmented peer pools, or cached response batches.

---

# 11. How tracker state expires

Peers do not always announce `stopped`.

Therefore, every peer record needs a timeout:

```ts
interface PeerRecord {
  readonly peerId: string;
  readonly ip: string;
  readonly port: number;
  readonly lastSeenAt: number;
}
```

A cleanup process periodically removes stale entries:

```ts
function removeExpiredPeers(
  swarm: Map<string, PeerRecord>,
  now: number,
  timeoutMs: number,
): void {
  for (const [peerId, peer] of swarm) {
    if (now - peer.lastSeenAt > timeoutMs) {
      swarm.delete(peerId);
    }
  }
}
```

If the announce interval is 30 minutes, the tracker might expire records after a longer period, such as 45–60 minutes.

The exact value is implementation-specific.

A tracker must tolerate:

* delayed announces;
* temporary packet loss;
* suspended laptops;
* mobile-network transitions;
* process crashes;
* NAT address changes.

---

# 12. HTTP trackers

An HTTP tracker uses ordinary HTTP or HTTPS requests.

Example:

```text
https://tracker.example.com/announce?info_hash=...&peer_id=...
```

Advantages:

* simple to implement;
* easy to proxy;
* works with ordinary web infrastructure;
* HTTPS can encrypt the client-to-tracker exchange;
* straightforward authentication and logging.

Disadvantages:

* HTTP headers add overhead;
* large numbers of announces create more CPU and bandwidth overhead;
* connection handling can be heavier than a compact UDP protocol;
* proxies and CDNs can interfere if misconfigured.

A high-scale HTTP tracker usually needs:

```text
HTTP listener
    |
    v
announce parser
    |
    v
authentication / authorization
    |
    v
sharded swarm registry
    |
    v
peer sampler
    |
    v
bencoded response
```

---

# 13. UDP trackers

UDP trackers use a compact binary protocol.

They reduce overhead compared with HTTP, but require more careful handling of spoofing, packet loss, retries, and transaction matching.

The UDP flow has two main stages:

```text
1. Connect request
2. Announce request
```

## 13.1 Connect request

The client sends:

```text
protocol ID
action = connect
transaction ID
```

The protocol ID commonly used by the UDP tracker protocol is:

```text
0x41727101980
```

The transaction ID is randomly generated by the client.

The tracker responds with:

```text
action = connect
same transaction ID
connection ID
```

The client verifies that the transaction ID matches.

## 13.2 Why use a connection ID?

UDP has no real connection handshake. An attacker can spoof a victim’s source IP address.

Without protection, an attacker could send:

```text
Tracker, send a large response to this victim
```

The tracker could then become a reflection/amplification tool.

The connection ID acts like a short-lived cookie. The tracker generates it in a way that indicates the sender previously communicated from the source address.

It is not necessarily a permanent stored session.

A tracker can generate a stateless connection ID using something conceptually similar to:

```text
HMAC(
  server-secret,
  client-IP || time-window
)
```

Then it can validate the ID without retaining per-client state.

## 13.3 UDP announce request

After receiving a connection ID, the client sends a binary announce packet containing fields such as:

```text
connection ID
action = announce
transaction ID
info hash
peer ID
downloaded
left
uploaded
event
IP address
key
number wanted
port
```

The response contains:

```text
action
transaction ID
interval
number of leechers
number of seeders
compact peers
```

## 13.4 Packet layout

A simplified UDP connect request:

```text
Offset  Size  Field
0       8     Protocol ID
8       4     Action = 0
12      4     Transaction ID
```

Connect response:

```text
Offset  Size  Field
0       4     Action = 0
4       4     Transaction ID
8       8     Connection ID
```

Announce request:

```text
Offset  Size  Field
0       8     Connection ID
8       4     Action = 1
12      4     Transaction ID
16      20    Info hash
36      20    Peer ID
56      8     Downloaded
64      8     Left
72      8     Uploaded
80      4     Event
84      4     IP address override
88      4     Key
92      4     Number wanted
96      2     Port
```

Announce response:

```text
Offset  Size  Field
0       4     Action = 1
4       4     Transaction ID
8       4     Interval
12      4     Leechers
16      4     Seeders
20      ...   Compact peer entries
```

Multi-byte integer fields use network byte order.

---

# 14. UDP retransmission

UDP packets may be lost, duplicated, or reordered.

Clients therefore need retry logic.

Conceptually:

```text
send request
wait timeout
if no valid response:
    retry with backoff
```

Responses are matched using:

```text
transaction ID
```

A client must ignore responses with:

* the wrong transaction ID;
* the wrong action;
* an invalid packet length;
* an unexpected source;
* malformed peer entries.

A robust implementation also limits the maximum packet size and the number of accepted peers.

---

# 15. Scraping

A **scrape** request asks for swarm statistics without requesting peer addresses.

It may return:

```text
complete: 342
incomplete: 86
downloaded: 12761
```

Where:

* `complete` means seeders;
* `incomplete` means leechers;
* `downloaded` is an approximate count of completed downloads.

Scrapes are useful for displaying torrent statistics.

They are not required for downloading.

Public trackers frequently restrict scrape requests because they can be expensive and can expose information about swarm activity.

---

# 16. Multiple trackers and tracker tiers

A `.torrent` file can contain:

```text
announce
announce-list
```

The `announce-list` may be organized into tiers.

Conceptually:

```text
Tier 1:
  tracker-a.example
  tracker-b.example

Tier 2:
  tracker-c.example
  tracker-d.example
```

Clients can use alternative trackers when one fails.

The exact client behavior varies, but tiers generally represent preference and fallback grouping.

Multiple trackers improve availability:

```text
Tracker A unavailable
    |
    v
Try Tracker B
```

However, each tracker may know only the peers that announced to it.

Suppose:

```text
Alice announced to Tracker A
Bob announced to Tracker B
```

Alice and Bob may not discover one another through trackers unless:

* they both announce to a common tracker;
* they use DHT;
* they use peer exchange;
* another peer introduces them indirectly.

---

# 17. Trackers, DHT and peer exchange

Trackers are only one peer-discovery mechanism.

Modern BitTorrent clients can also use:

## DHT

The distributed hash table stores mappings conceptually resembling:

```text
info_hash -> peers
```

There is no single central tracker.

A peer queries DHT nodes to locate participants in the swarm.

## Peer Exchange

Connected peers share knowledge of other peers.

```text
Alice -> Bob:
I also know Carol and Dave
```

This allows the peer graph to expand after the first few connections.

## Local Peer Discovery

Peers on the same LAN can announce themselves using local multicast mechanisms.

## Combined discovery

A client may use all of these:

```text
Tracker
DHT
Peer exchange
Local peer discovery
Previously cached peers
```

The resulting peer addresses are merged and deduplicated.

---

# 18. Public and private trackers

## Public tracker

A public tracker usually permits any peer to announce for any swarm.

It may not require an account.

Its primary purpose is peer discovery.

## Private tracker

A private tracker usually associates announces with a user account through a unique URL or passkey:

```text
https://tracker.example.com/<user-passkey>/announce
```

The tracker records:

* user identity;
* uploaded bytes;
* downloaded bytes;
* seeding time;
* active torrents;
* completion events;
* ratio;
* account restrictions.

A private torrent normally has the `private` flag enabled. Compatible clients then avoid unauthorized discovery mechanisms such as:

* public DHT;
* public peer exchange;
* local peer discovery.

This keeps swarm membership under tracker control.

However, reported upload and download values originate from clients. A private tracker therefore needs anti-cheat mechanisms because clients can lie.

Possible defenses include:

* implausible speed detection;
* overlapping-session detection;
* peer correlation;
* announce-frequency analysis;
* tokenized peer identities;
* statistical anomaly detection;
* required client versions;
* delayed accounting;
* cross-checking reports from other peers.

No purely client-reported accounting system is perfectly trustworthy.

---

# 19. Trackers and NAT

A tracker knows that a peer announced from:

```text
203.0.113.10
```

and claims to listen on:

```text
51413
```

But that does not prove that:

```text
203.0.113.10:51413
```

is reachable.

The peer may be behind NAT:

```text
Peer:
192.168.1.20:51413

Router public address:
203.0.113.10

No port mapping exists
```

In this case, other peers cannot necessarily initiate a connection.

The tracker does not solve that by itself.

The client might use:

* UPnP IGD;
* NAT-PMP;
* PCP;
* manual port forwarding;
* IPv6;
* NAT traversal extensions;
* simultaneous outbound connection techniques.

Even an unreachable peer can often download because it can initiate outbound connections. However, two peers that are both unreachable may be unable to connect directly.

A basic tracker does not relay their data.

---

# 20. Tracker versus relay

These roles must not be confused.

## Tracker

```text
Alice -> Tracker:
Who has this torrent?

Tracker -> Alice:
Try Bob at 198.51.100.20:6881

Alice -> Bob:
Direct connection
```

## Relay

```text
Alice -> Relay -> Bob
```

A relay forwards the actual traffic.

A traditional BitTorrent tracker does not do that.

Therefore, a tracker is much cheaper to operate than a relay:

```text
Tracker bandwidth:
small announce requests and peer lists

Relay bandwidth:
all transferred torrent data
```

A tracker can coordinate millions of peers while transferring relatively little data compared with the total swarm traffic.

---

# 21. A simplified tracker algorithm

Here is a more realistic TypeScript model.

```ts
type InfoHash = string;
type PeerId = string;

interface AnnounceInput {
  readonly infoHash: InfoHash;
  readonly peerId: PeerId;

  readonly observedIp: string;
  readonly port: number;

  readonly uploaded: bigint;
  readonly downloaded: bigint;
  readonly left: bigint;

  readonly event?:
    | "started"
    | "completed"
    | "stopped";

  readonly numWant: number;
}

interface PeerRecord {
  readonly peerId: PeerId;
  readonly ip: string;
  readonly port: number;

  readonly uploaded: bigint;
  readonly downloaded: bigint;
  readonly left: bigint;

  readonly lastSeenAt: number;
}

interface PeerEndpoint {
  readonly ip: string;
  readonly port: number;
}

interface AnnounceOutput {
  readonly intervalSeconds: number;
  readonly complete: number;
  readonly incomplete: number;
  readonly peers: readonly PeerEndpoint[];
}

class Tracker {
  private readonly swarms =
    new Map<InfoHash, Map<PeerId, PeerRecord>>();

  public announce(
    input: AnnounceInput,
    now = Date.now(),
  ): AnnounceOutput {
    const swarm =
      this.swarms.get(input.infoHash) ??
      new Map<PeerId, PeerRecord>();

    this.swarms.set(input.infoHash, swarm);

    if (input.event === "stopped") {
      swarm.delete(input.peerId);
    } else {
      swarm.set(input.peerId, {
        peerId: input.peerId,
        ip: input.observedIp,
        port: input.port,
        uploaded: input.uploaded,
        downloaded: input.downloaded,
        left: input.left,
        lastSeenAt: now,
      });
    }

    let complete = 0;
    let incomplete = 0;

    const candidates: PeerRecord[] = [];

    for (const peer of swarm.values()) {
      if (peer.left === 0n) {
        complete++;
      } else {
        incomplete++;
      }

      if (peer.peerId !== input.peerId) {
        candidates.push(peer);
      }
    }

    const selected = this.randomSample(
      candidates,
      Math.min(input.numWant, 50),
    );

    return {
      intervalSeconds: 1800,
      complete,
      incomplete,
      peers: selected.map(({ ip, port }) => ({
        ip,
        port,
      })),
    };
  }

  private randomSample<T>(
    values: readonly T[],
    count: number,
  ): T[] {
    const output: T[] = [];
    const selectedIndices = new Set<number>();

    while (
      output.length < count &&
      output.length < values.length
    ) {
      const index = Math.floor(
        Math.random() * values.length,
      );

      if (!selectedIndices.has(index)) {
        selectedIndices.add(index);
        output.push(values[index]);
      }
    }

    return output;
  }
}
```

This demonstrates the principle, but it is not yet production-ready.

---

# 22. Production tracker architecture

A scalable tracker may look like:

```text
                    ┌─────────────────┐
HTTP announce ─────►│                 │
                    │ Request gateway │
UDP announce ──────►│                 │
                    └────────┬────────┘
                             │
                             ▼
                    ┌─────────────────┐
                    │ Validation and  │
                    │ authentication  │
                    └────────┬────────┘
                             │
                             ▼
                    ┌─────────────────┐
                    │ Swarm sharding  │
                    │     layer       │
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
         Swarm shard 1  Swarm shard 2  Swarm shard 3
              │              │              │
              └──────────────┼──────────────┘
                             ▼
                    ┌─────────────────┐
                    │ Peer sampling   │
                    └────────┬────────┘
                             ▼
                    Compact response
```

The swarm can be sharded using:

```text
shard = hash(info_hash) mod number_of_shards
```

This ensures all announces for a specific torrent reach the same logical shard.

---

# 23. Does a tracker need a database?

Not necessarily.

For a public tracker, peer state is ephemeral:

```text
peer joins
peer refreshes
peer expires
```

An in-memory data store is often sufficient.

Possible storage strategies include:

* process-local memory;
* shared-memory structures;
* Redis;
* distributed key-value stores;
* custom partitioned swarm servers.

Persistent storage may be needed for:

* private-tracker user statistics;
* download counts;
* audit logs;
* bans;
* account state;
* torrent authorization;
* long-term analytics.

Persisting every public peer heartbeat to a relational database would often be unnecessarily expensive.

---

# 24. Concurrency problems

A tracker can receive many simultaneous announces for the same swarm.

A naïve implementation can encounter:

* race conditions;
* duplicate peers;
* lost updates;
* inaccurate seeder counts;
* expensive global locks;
* cleanup competing with announces.

Avoid locking the entire tracker for each request.

Better approaches include:

```text
Shard by info hash
Use per-swarm or per-shard synchronization
Store counters atomically
Perform cleanup incrementally
Use append/update-friendly structures
```

In Node.js or Bun, a single event loop avoids some in-process data races, but distributed replicas still require consistent ownership or coordination.

---

# 25. Tracker replication

Running multiple tracker instances behind a load balancer introduces a problem:

```text
Alice announces to instance 1
Bob announces to instance 2
```

If the instances do not share state, they may not discover each other.

Solutions include:

## Shared state

```text
Instance 1 ─┐
            ├── Shared Redis or datastore
Instance 2 ─┘
```

## Consistent hashing

Route every `info_hash` to the same tracker shard:

```text
hash(info_hash) -> shard owner
```

## Replicated swarm state

Instances exchange peer updates.

## Accept partial swarm views

For very large public swarms, each tracker instance can return peers from its own subset. Peer exchange and DHT can connect the resulting subgroups.

This is simpler but reduces discovery completeness.

---

# 26. Common attacks

## Swarm poisoning

An attacker registers many fake or unreachable peer addresses.

Mitigations:

* rate limits;
* source-IP validation;
* peer expiry;
* connection-result feedback;
* maximum peers per source subnet;
* proof-of-work in hostile environments.

## Sybil attack

One participant creates thousands of fake peer identities.

Because traditional peer IDs are cheap to create, `peer_id` alone does not prevent this.

## Scrape harvesting

An attacker enumerates torrents and swarm sizes.

Mitigations:

* disable full scrape;
* require authentication;
* rate-limit scrape requests;
* prevent arbitrary torrent enumeration.

## Denial of service

Attackers flood announce requests or create enormous numbers of swarms.

Mitigations:

* request-size limits;
* per-IP limits;
* per-passkey limits;
* bounded swarm counts;
* expiration;
* stateless UDP cookies;
* overload shedding.

## Reflected UDP traffic

An attacker spoofs a victim’s IP so the tracker responds to the victim.

The UDP connection-ID mechanism helps reduce this risk.

## Fake statistics

Clients lie about:

```text
uploaded
downloaded
left
completed
```

Public trackers often tolerate this because the fields are not security-critical. Private trackers require additional anti-cheat logic.

---

# 27. Privacy implications

A tracker can observe:

* the peer’s public IP address;
* which torrent it announced for;
* when it joined;
* when it last announced;
* its reported upload and download amounts;
* whether it claims to be a seeder;
* its client peer ID;
* its listening port.

HTTPS protects this information from passive observers between the client and tracker, but the tracker itself still sees it.

The tracker also distributes the peer’s address to other swarm members.

Therefore, participating in a tracker-based torrent swarm inherently reveals a peer’s network endpoint to other participants.

---

# 28. Complete sequence

```text
1. Alice opens a torrent.

2. Alice calculates or reads its info hash.

3. Alice sends:
   started announce
   info hash
   peer ID
   public-facing listening port
   transfer statistics

4. Tracker observes Alice's public IP.

5. Tracker adds Alice to the swarm.

6. Tracker selects active peers.

7. Tracker returns:
   interval
   seeder count
   leecher count
   compact peer endpoints

8. Alice connects directly to Bob and Carol.

9. Alice verifies the torrent identifier during
   the BitTorrent peer handshake.

10. Alice exchanges bitfields with Bob and Carol.

11. Alice downloads pieces directly from them.

12. Alice periodically announces again.

13. When Alice finishes, she announces completed.

14. When Alice stops, she announces stopped.

15. If stopped is never received, the tracker
    eventually removes Alice through expiration.
```

The essential model is:

```text
Tracker:
"Here are peers you may try."

Peer protocol:
"Here are the pieces I have."

Direct connection:
"Here are the actual file bytes."
```

A tracker is therefore a lightweight, swarm-specific discovery coordinator—not a server through which the torrent’s content normally passes.
