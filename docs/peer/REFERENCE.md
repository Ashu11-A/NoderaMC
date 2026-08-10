# Peer control protocol — wire reference

<!-- AI-AGENT-INSTRUCTION: This file is the canonical specification of the loopback control protocol
     between the always-on worker and its local clients (the mod's presence gate, the Tauri app).
     `peer/src/main/java/dev/nodera/peer/control/ControlProtocol.java` is the single source of truth
     for the CONSTANTS (`ControlProtocol.HOST`, `.WORLDID`, …); this file is the single source of
     truth for the PROSE — the wire grammar, the reply shapes and the design rationale behind each
     verb. The mod's `CompanionProtocol` and the app's `control.rs` mirror the Java constants and must
     stay in lockstep with them (a mismatch surfaces as a clear "update the app / update the mod"
     error, never a hang). When a verb's wire form changes, update both this file and the constant's
     one-line Javadoc pointer in the same commit — a moved block with a stale pointer is a regression,
     not a reduction (docs/plans/Plan.11.md phase 2). -->

**Category:** peer · **Covers:** `dev.nodera.peer.control.ControlProtocol` (`peer/src/main/java/dev/nodera/peer/control/ControlProtocol.java`)

## Overview

Line-oriented ASCII handshake over a loopback-only socket (`127.0.0.1`, port 25610) — a local trust
boundary, not a network service. Peers still verify everything the worker serves on the real network
(Task 0 rule 7); requiring the worker locally is a persistence/reachability convenience, never a new
trust anchor.

```
client → worker:  NODERA-PROBE <protocolVersion>
worker → client:  NODERA-OK <protocolVersion> <workerVersion>
```

`ControlServer.dispatch` splits every request line on `\s+`, which collapses runs of whitespace. Any
argument that could contain a space or could legitimately be empty is therefore sent **base64**, not
raw — `SEED`, `PASSWORD`/`REKEY`, `CONFIG`, `DELETE`'s reason, and `TELEMETRY`'s `EVENT` payload all
carry base64 for exactly this reason. Base64's alphabet contains no whitespace, so the encoding makes
mid-line truncation **impossible by construction** rather than merely unlikely.

Most verbs are additive: a worker that predates a verb answers `NODERA-ERR unknown verb`, which every
caller treats as "this lane is unavailable on this worker" and falls back or carries on without it.
That is what let `SEED`, `GRANT`, `REKEY`, `CONFIG`, `WATCH`, `EVENTS`, `LAN`, `PROVE`, `WORLDS`,
`MESH`, `PIECES`, `SEED_REGION`, `FETCH_REGION` and `DELETE` land over months without a coordinated
three-way release across the worker, the mod and the app. `PROTOCOL_VERSION` (currently 2) is bumped
only when a verb's wire form **changes or is removed** — additive verbs never bump it, because doing
so would force every already-installed worker's probe to fail for a change it does not participate
in.

## `NO_VALUE` — the optional-argument placeholder

The sentinel is `"-"`: a single character chosen because it can never be valid base64 and can never
be a world name anyone typed. It exists because the line protocol collapses whitespace runs — an
argument sent as the empty string does not arrive as an empty argument, it **disappears**, and every
argument after it shifts one place left. This is not a theoretical hazard: a `LEAVE` carried no world
name once, the lease value landed in the name slot, and a world called "Teste 1" was silently renamed
to `"0"` the moment its owner disconnected. Every optional positional argument in this protocol uses
`NO_VALUE`, never the empty string.

## Verb catalog

### `PROBE` / `OK` / `ERR` — the handshake

`NODERA-PROBE <protocolVersion>` → `NODERA-OK <protocolVersion> <workerVersion>`, or
`NODERA-ERR <message>` on any other failure. `ControlProtocol.probeLine()` and `.okLine(...)` build
the two success lines.

### `STATE` — dashboard/HUD metrics snapshot

`NODERA-STATE <ver>` → one JSON line: bytes, peers, worlds, maintained pieces, validation counters
and per-peer traffic (docs/peer/Task.2.md deliverable 1).

### `IDENTITY` — worker identity

`NODERA-IDENTITY` → `NODERA-OK <nodeId> <publicKeyBase64>`.

### `HOST` — start hosting a world

`NODERA-HOST <worldId> <nameB64> <optionsJson>`.

### `JOIN` — resolve + join a world

`NODERA-JOIN <worldId> [<nameB64>] [<leaseSeconds>] [<playersInWorld>]`

Sent by a player's game when it connects to somebody else's world, and **repeated on a cadence for as
long as that session lasts**. Both halves matter:

- the world enters this node's sweep set, so a joiner starts supporting the world it is playing in
  rather than only taking from it;
- each call renews a short *connected* lease, which is what lets the companion app say "you are
  playing in this world" without a leave verb it could miss. A game that crashes stops renewing and
  the claim expires on its own — the alternative, a flag set once and cleared by a message, is a flag
  that is wrong forever the first time the message is lost.

The name is optional and additive: without it the world still joins, it just lists under its id until
some other lane learns a better name. `leaseSeconds` is likewise optional — absent means the worker's
own default, and `0` is the clean goodbye a game sends when the player leaves on purpose, so the app
stops saying "playing" immediately instead of at the end of the lease. The world stays supported
either way: leaving a world is not abandoning it.

`playersInWorld` is what the caller's own game can see in that world — the client's online-player
set, the same live set the entity and region lanes plan over. It is sent by every node that is **in**
the world, joiner and host alike, because only such a node can see it. A peer that merely holds the
world's bytes has no game and no opinion; the confident zero it used to publish is the bug this
argument exists to end. Absent or negative means "I cannot tell", carried through as unknown rather
than collapsed to none.

### `STOP` — stop hosting a world

`NODERA-STOP <worldId>`.

### `STATUS` — per-world status

`NODERA-STATUS <worldId>` → one JSON line: players/health/permissions.

### `SEED` — seed a world-archive snapshot (host side of the continuity lane)

`NODERA-SEED <worldId> <archivePathB64>` — the mod packs the save into a canonical archive file and
hands the worker its path (same machine, the loopback trust boundary); the worker splits + seeds it
and advertises the manifest on its next tracker announce. Reply:
`NODERA-OK <manifestRootHex> <version> <pieceCount>`. Additive.

### `ARCHIVE` — fetch a world's newest archive from the network (joiner side)

`NODERA-ARCHIVE <worldId> <destPathB64> <timeoutSeconds>` — the worker resolves seeders through the
tracker, downloads + verifies every piece, and writes the archive blob to the destination path.
Reply: `NODERA-OK <byteCount> <version>`. Additive.

### `PROGRESS` — interim progress on an in-flight `ARCHIVE` or `FETCH_REGION` fetch

`NODERA-PROGRESS <verified> <total> [stagedPathB64]`, written by the worker on the same connection
before the terminal `OK`/`ERR` line.

**Why a fetch has to speak while it works.** The caller's `timeoutSeconds` was being spent twice,
meaning two different things: the worker treats it as a *stall* budget — a transfer that keeps moving
keeps going — while the client had turned it into a socket read timeout, a hard wall clock. A large
archive that was progressing perfectly well therefore kept the worker happy and expired the client
underneath it. Observed live: a player waited 748 seconds and was told the fetch failed, while the
worker was healthy and 212 of 283 pieces into the download being waited on.

So the worker says how far it has got, and each line is liveness — the same reason `WATCH` sends a
keepalive instead of letting the reader guess what silence means. A client that does not understand
this line must ignore it and keep reading, which is what makes the addition safe against an older
peer on either side.

**The third token, and why it rides this line rather than a new verb.** On `FETCH_REGION` the worker
also names a file holding the region's columns that have *verified so far*, encoded exactly like the
finished region (network L-33). The rule the paragraph above already stated — a client that does not
understand a line must ignore it and keep reading — is precisely what makes an extra token safe, so
render-on-arrival cost the control plane no new verb and no compatibility story: a companion built
before this change reads the two counts it knows and is otherwise unaffected, and a worker built
before it simply never sends a third token.

### `SEED_REGION` — seed one committed region snapshot (worker L-41)

`NODERA-SEED-REGION <ver> <worldId> <snapshotPathB64>` — the file holds a canonically encoded
`RegionSnapshot`, written by whichever process actually committed it, and the worker splits,
publishes and pins it exactly as it does an archive.

**Why a file path and not the bytes.** The same reason `SEED` uses one: the control channel is a
line protocol on loopback, and a region snapshot is far too big to belong on a line.

**Why the worker seeds what somebody else committed.** Under field-of-view ownership the seats live
on the players' nodes, so the process holding a region's committed state is usually a game client —
and that is precisely the process that goes away. Pushing the snapshot to the always-on worker is
what lets the region stay fetchable after it does.

Reply: `NODERA-OK <manifestRootHex> <version> <pieceCount>`. Additive.

### `FETCH_REGION` — fetch one region's committed state from the network

`NODERA-FETCH-REGION <ver> <worldId> <dim> <regionX> <regionZ> <destPathB64> [haveIndexRootHex]
[timeoutSeconds]` — the worker downloads the region's pieces, verifies the assembled blob against the
certified region root, and writes the canonical `RegionSnapshot` to `destPath`.

The exact mirror of `SEED_REGION`, and the direction that did not exist. Regions were seeded, split,
hashed and announced, and their manifests travelled — and nothing ever downloaded one, so the only
way to receive somebody else's world was the whole-save archive and the world reload that comes with
it.

`haveIndexRootHex` names what the caller already holds, so the worker can serve the matching version
rather than whatever is newest; omit it for "whatever you have". Reuse is decided below this, by
piece hash, so a caller that already holds most of the region pays for the columns that differ
whether or not it names a root.

**It speaks while it works (network L-33).** Pieces are cut at chunk-column boundaries, so a verified
piece decodes to its own columns without the rest of the blob — a property `PieceSplitter` has always
cut for and nothing spent until 2026-08-10. The worker now writes the columns verified so far to
`<destPath>.partial` — whole, then moved into place, so a reader never sees a half-written file — and
names it on a `NODERA-PROGRESS` line. Each staging is cumulative, so a caller may drop any of them
and still be correct. The terminal reply and `destPath` are byte-for-byte what they always were.

Why a file path and not the bytes: same as `SEED_REGION`.

Reply: `NODERA-OK <byteCount> <regionRootHex>`. Additive.

### `WORLDID` — mint a signed world identity (the worker is the author)

`NODERA-WORLDID <genesisRootB64> <createdAt> <shared> <listed> <encrypted> <manifestRefB64>
[pinnedWorldIdHex]`; reply is `NODERA-OK <worldIdentityBytesB64>`.

`pinnedWorldIdHex` is optional and additive. When present the worker signs that id instead of
deriving one from the genesis root — the fix for a world appearing on the network more than once,
because the derivation is only stable while the genesis root is, and a root re-certified after a
missing genesis file is not the root that minted the original id. Full history: docs/peer/Task.8.md.

### `GRANT` — mint a signed permission grant (issue #36)

`NODERA-GRANT <ver> <worldIdHex> <subjectNodeId> <subjectPubKeyB64> <roleOrdinal> <grantVersion>`;
reply is `NODERA-OK <grantBytesB64>`. The worker signs as the world author. Authority is enforced at
apply-time on every peer; loopback (127.0.0.1) is the local trust boundary. Additive.

### `DELEGATE` — sign a `SessionDelegation`

`NODERA-DELEGATE <ver> <worldIdHex> <sessionPubKeyB64> <ttlSeconds>`; reply is
`NODERA-OK <delegationBytesB64>`. Lets a game session's throwaway transport key speak in the worker's
name (`dev.nodera.core.identity.SessionDelegation`).

**Why this verb has to exist.** Every privilege in a world is anchored to the worker's persistent
key, and the game client generates a fresh keypair per session for its peer transport. So the key a
player proved possession of at join time was a key no world had ever heard of, the permission
evaluator answered `MEMBER`, and a world's own author was de-opped from their own world. The private
key must not move into the game, so the statement moves instead: the worker signs that the session
key speaks for it, in one world, until a stated instant.

The loopback (127.0.0.1) socket is the local trust boundary, the same one `GRANT` and `WORLDID`
already rely on. Additive — a client that gets no delegation announces exactly what it announced
before, which is the pre-existing behaviour rather than a new failure.

### `REKEY` — re-key a world's password (issue #37 / L-51)

`NODERA-REKEY <ver> <worldIdHex> <archivePathB64> <newPasswordB64> <currentWorldIdentityB64>`; reply
is `NODERA-OK <worldIdentityBytesB64>` or `NODERA-ERR <reason>` — never silent success.

The mod hands the worker a freshly-packed archive file path + the new plaintext password + the
current signed `WorldIdentity`; the worker re-encrypts the archive under a fresh Argon2id salt (new
key → new ciphertext → new `manifestRoot` + bumped version), re-signs the identity with the new
`manifestRef`, re-announces, and returns the re-signed identity bytes. The loopback socket is the
local trust boundary; the file-path handoff mirrors `SEED`. Additive.

### `MESH` — join the hosting world's live P2P membership session

`NODERA-MESH <ver> <bootstrapRoute> [worldSeed]` where `bootstrapRoute` is the hosting game's
advertised P2P route (`host:port`) and the optional `worldSeed` binds the worker's validation lane to
that world. Reply `NODERA-OK`, or `NODERA-ERR <reason>` — never silent success.

Lets this always-on worker become a real member of the world's live session rather than a bystander
on its own session of one. The seed is not decoration: it feeds the deterministic RNG the region
engine re-executes with, so a worker validating on the wrong seed computes different roots and votes
against every batch. It is omitted when the caller only wants membership (session health) and has no
world loaded yet.

This is the verb that lets a world be served by peers instead of by whoever happens to be logged in:
once the worker is a member it is counted in session health, it is eligible for committee seats it is
then handed via `RegionAssigned`, and it can win the gateway election when the hosting game exits. An
empty route detaches the worker from the session.

Additive — an older worker's `NODERA-ERR unknown verb` is treated as "this worker cannot be a session
member".

### `PIECES` — the per-world piece picture

`NODERA-PIECES <ver> <worldIdHex>`. Reply is one JSON line, the torrent-style piece map rendered by
both the Minecraft client and the companion app:

```json
{"world_id":"…","manifest_root":"…","version":3,"piece_count":128,
 "held_count":128,"total_bytes":11534336,"held_bitmap":"<base64 of the piece BitSet>",
 "holders":["<nodeId>", …]}
```

`held_bitmap` is little-endian bit-per-piece (`java.util.BitSet#toByteArray()`): bit *i* set means
piece *i* is present locally **and** passed its hash check — never "requested". `holders` is who else
is believed to hold some of this world, i.e. the "peers sharing this world" count.

Additive — a worker predating it answers `NODERA-ERR unknown verb`, treated as "no piece data
available"; callers render an empty map rather than an error.

### `CONFIG` — push or read back the worker's runtime configuration

```
NODERA-CONFIG <ver> <configJsonB64>   → set, reply is the outcome JSON
NODERA-CONFIG <ver>                    → read the effective config, reply is one JSON line
```

The verb that turns the companion app's Settings screen from a file nothing reads into something
that changes what this node actually does.

**Why base64.** `ControlServer.dispatch` splits the request line on `\s+`, so any payload containing
a space would be silently truncated. Base64's alphabet contains no whitespace, so the encoding makes
that corruption impossible by construction rather than merely unlikely — the same reason `SEED`,
`PASSWORD` and `REKEY` already carry base64. (Trailing raw JSON via a `rest()` read would also work,
but only until someone pretty-prints the payload.)

**Why the version token is not bumped.** The verb is purely additive: `dispatch` ignores index 1, and
an older worker answers `NODERA-ERR unknown verb` — which the app renders as "this worker is older
than the app". Bumping `PROTOCOL_VERSION` would force the mod's `ControlProtocol` to move in lockstep
for a change it does not participate in, and would break the probe of every already-installed worker
for no benefit.

**The reply shape is load-bearing.** A set replies with one JSON line (the `STATE`/`PIECES` family —
raw JSON, not an `NODERA-OK` prefix):

```json
{"applied":["network.max_upload_bytes_per_sec","behavior.transfers_paused"],
 "restart_required":["network.port_range"],
 "rejected":{"network.max_connections_per_world":"the transport has no world dimension"}}
```

The app decides its per-setting "enforced" badge **from this reply** and from nothing else, so a key
must appear in `applied` only when it was genuinely applied. A worker that cannot honour a key is
required to name it under `rejected` with a human-readable reason; silently dropping it would let the
UI claim an enforcement that does not exist — exactly the facade this verb was added to remove.

A worker that has the verb but no configuration plane at all replies `NODERA-ERR unsupported` (the
`ControlHandler` default), so a partially-upgraded worker declines loudly rather than pretending.

### `WATCH` — stream the state snapshot instead of asking for it

`NODERA-WATCH <ver> [minMs]`.

Every other verb is one request, one reply, one connection. This one holds the connection open and
the **worker writes**: one `STATE` JSON line immediately, another whenever the state actually
changes, and a repeat of the current line on a keepalive interval so a reader can tell "nothing has
changed" from "the link died". The stream ends when the client disconnects or the worker stops.

**Why this exists.** A poller cannot be both current and cheap: a two-second cadence means a
dashboard is up to two seconds wrong about a world that just came online, and a faster one spends the
difference on connections that mostly re-read the same bytes. Worse, a poll has no way to distinguish
a value that is genuinely zero from one it has not managed to refresh — which is exactly how a
dashboard ends up showing a confident, stale zero. Here the worker announces its own changes, and the
reader always knows when it last heard.

`minMs` bounds how often the worker may write (default 250 ms, floor 50 ms): a node whose counters
move continuously must not turn this into a busy loop.

Additive — an older worker answers `NODERA-ERR unknown verb` on the first line, which a client treats
as "this worker cannot stream" and falls back to polling `STATE`.

### `WORLDS` — the worlds this peer keeps on the network, with their ownership

`NODERA-WORLDS <ver>`. Reply is one JSON line:

```json
{"worlds":[{"world_id":"…","name":"…","role":"shared|supported","owned":true,
            "world_public_key":"<base64>","added_at":…,"updated_at":…}, …]}
```

Distinct from `STATE`'s `connected_worlds`, which describes what this node is doing *right now* and
carries live counters. This verb answers the durable question — what has this peer shared, and what
is it supporting for other people — and is therefore answerable with the game closed, the mesh empty,
and the trackers unreachable.

Additive.

### `PROVE` — prove this peer administers a world

`NODERA-PROVE <ver> <worldIdHex> <challengeB64>`; reply `NODERA-OK <proofB64>` where the payload is a
canonical `WorldAdminProof` signed by the **world's** private key.

The challenge is supplied by whoever is asking, and it is signed together with this node's id, so a
proof is good for exactly one challenge from exactly one claimant. A peer that does not hold the
world's key answers `NODERA-ERR`, which is the honest answer: it cannot produce the signature and
must not be able to pretend otherwise.

Additive.

### `EVENTS` — stream the things that happen on this node

`NODERA-EVENTS <ver> [sinceSeq]`.

The second verb the worker *writes* rather than answers, and the counterpart to `WATCH`: that one
carries what is **true** of the node, this one carries what **happened** to it. A client that learned
about moments by diffing successive state snapshots would have to keep its own previous copy, decide
what counts as a change, and be connected at the time — three chances to miss the one thing it is
watching for.

Each line is one event:

```json
{"seq":7,"event":"lan.opened","at":1785106864612,
 "attributes":{"session":"05f8…","world":"My World","port":"51234"}}
```

**`sinceSeq` is what makes this reliable.** The worker keeps a short history, so a client passing the
last sequence it saw — or `0` on a fresh start — receives what it missed before the live stream
begins. Without it the lane would only work when the app happened to connect before the event, which
for "a player opened a world to LAN and then looked at the app" is a coin toss.

A keepalive line `{"seq":<n>,"event":"keepalive"}` is sent when nothing has happened for a while, so
silence on the socket is never mistaken for a healthy quiet node.

Additive — an older worker's `NODERA-ERR unknown verb` is treated as "this worker cannot announce
events" and falls back to watching state.

### `LAN` — the "Open to LAN" lane

`NODERA-LAN <ver> LIST | SHARE <port> | DECLINE <port> | STOP <port>`.

`LIST` answers one JSON line describing every world this machine can currently see opened to LAN and
what the player decided about each. The other three are the decision, and they exist as separate
verbs rather than as a boolean because "no" and "not yet" are the same answer to the prompt and
different answers to the question — a declined world stays listed so it can still be shared later.

Nothing is announced to the network until `SHARE` arrives. Detection is not consent.

Additive.

### `DIRECTORY` — browse what is joinable

`NODERA-DIRECTORY <ver> [limit]`. Reply is one JSON line of worlds the configured trackers know
about, each with a name, a player count and the id to `CONNECT` to.

Separate from `STATE`'s `connected_worlds`, which is what *this* node is doing. This is what everyone
else is offering.

### `CONNECT` — join a live session

`NODERA-CONNECT <ver> <sessionIdHex>`; reply `NODERA-OK 127.0.0.1:<port>`.

The worker resolves the host through the trackers, opens a tunnel to it, and binds a loopback port on
this machine. The reply is what a player types into Minecraft's **Direct Connect** — which is why
this works with a completely unmodified game: from Minecraft's point of view it is joining a server
on localhost.

No world data crosses this. The tunnel carries the game's own connection and nothing else.

### `DISCONNECT` — leave a session

`NODERA-DISCONNECT <ver> <sessionIdHex>`, leaving a session joined with `CONNECT`.

### `SHARELINK` — mint a shareable invitation

`NODERA-SHARELINK <ver> <worldIdHex>`; reply `NODERA-OK <nodera:?xt=…>`.

The link carries the world id, its name, this node's configured trackers and rendezvous services, and
the world's public key when known. It carries no content and no secret — it is an address and a set
of places to ask, which is exactly what makes it safe to paste.

### `DELETE` — ask the network to forget a world this peer owns

`NODERA-DELETE <ver> <worldIdHex> [reasonB64]`; reply `NODERA-OK <n>` where `n` is how many peers the
request was relayed to, or `NODERA-ERR <reason>`.

**Irreversible.** The worker mints a tombstone signed by the world's own key and by this node's
identity, applies it here, and floods it. Every receiver re-verifies it against the ownership claim
the tombstone carries, so a peer that has never heard of the world can still tell a real deletion from
a request to destroy somebody else's. A node that does not hold the world's private key cannot
produce the request at all — which is why the verb refuses rather than asking anyone's permission:
authority here is possession of the key, not the connection.

The reason is base64 for the same reason `CONFIG`'s payload is: the dispatch splits on whitespace. It
is quoted back to other players, and it is covered by both signatures, so it cannot be edited in
flight.

### `TELEMETRY` — consent and event intake (worker task 5)

```
NODERA-TELEMETRY <ver> GET                     → one JSON line: consent + queue + last error
NODERA-TELEMETRY <ver> SET <granted|denied>    → NODERA-OK, or NODERA-ERR with a reason
NODERA-TELEMETRY <ver> EVENT <eventJsonB64>    → NODERA-OK (accepted into the spool)
```

`EVENT` exists so the mod and the companion app never open a telemetry connection of their own: they
observe, the worker decides and sends. That is what keeps **one** consent check in the system and
stops a closing game from losing the events that describe it.

The event payload is base64 for the same reason `CONFIG`'s is — the dispatch splits request lines on
whitespace, and base64's alphabet contains none.

### `TEST` — the integration-run verb

`NODERA-TEST <ver> ROLE|READY|DRIVE <action…>`.

Only a worker started with `--test-mode` answers it; every other worker replies `ERR unsupported`,
which is the point: a production node must not grow a remote-control surface because a test needed
one. The flag is on the command line rather than in the config so that it cannot be turned on by a
file somebody else can write.

- `ROLE` — which player this node belongs to (`player1`, `player2`, `player3`, `spare`). An
  integration assertion has to be able to name its subject; before this, the harness identified nodes
  by which control port had been started first, and renumbering the port plan silently swapped the
  subjects of every cross-node assertion.
- `READY` — the node has booted far enough to be part of a run.
- `DRIVE <action…>` — publish an action for the attached game to execute on this role's player. The
  worker never touches the game itself: it puts the action on the event stream the mod already
  consumes, so the path under test is the production one.
