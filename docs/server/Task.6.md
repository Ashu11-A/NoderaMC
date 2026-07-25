# Server Task 6 — The Vanilla Endpoint: Tenants

<!-- AI-AGENT-INSTRUCTION: A0′ lands here. A tenant is NOT a node: it never appears in membership, it
     never holds a committee seat, and it never gets a vote. N tenants grow an endpoint's FOOTPRINT,
     never its WEIGHT — minting a synthetic node per player would be a Sybil attack the operator would
     not even have to intend. The tenant lane is the EXISTING path (LiveEntityLaneRuntime.submit
     already signs another party's action with the host key) made permanent and auditable, not a new
     mechanism. Passwords never reach a log, a toString, or the wire. Keep this header accurate. -->

**Status:** ⬜ NOT STARTED
**Category:** server · **Owns:** L-68 · **Last audit:** 2026-07-25
**Depends on:** [server 5](Task.5.md), [minecraft 6](../minecraft/Task.6.md), [storage via network 3](../network/Task.3.md)
**Consumed by:** [server 7](Task.7.md), [server 9](Task.9.md)

---

## Goal

A player who has installed nothing types the endpoint's address into their vanilla server list, joins,
and plays in a Nodera world. Their actions enter the one validated lane as `ActionEnvelope`s signed by
the endpoint's node on their behalf; their identity on the network is a stable, auditable **tenant
id**; and what they can see of Nodera rides the surfaces a vanilla client already renders.

## Status detail

Not started.

## Dependencies

- [server 5](Task.5.md) — the capture lane the tenant's actions enter.
- [minecraft 6](../minecraft/Task.6.md) — `WorldIdentity`, `WorldPermissions`, the grant model, and
  the operator-bridge shape this task re-expresses for Bukkit.
- [network 3](../network/Task.3.md) — the store the tenant roster persists into.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `TenantId` — `NodeId` derived deterministically from `(endpointNodeId, playerUUID)` | ⬜ |
| 2 | `TenantRoster` — the endpoint's signed, published tenant↔player mapping | ⬜ |
| 3 | Tenant action submission through `EndpointEntityLaneRuntime` (endpoint signs, tenant is actor) | ⬜ |
| 4 | Tenant views feed [server 3](Task.3.md)'s multi-view ownership plan | ⬜ |
| 5 | Limbo password gate: `/nodera join <password>` before world placement | ⬜ |
| 6 | Permission/op bridge over `WorldPermissions` (the `OperatorBridge` shape, Bukkit side) | ⬜ |
| 7 | Tenant-visible surfaces: tab list, boss bar, action bar, chat, scoreboard | ⬜ |

## Design

### The tenant id

```
tenantId = NodeId( UUIDv5( namespace = endpointNodeId , name = playerUUID ) )
```

Deterministic (the same player at the same endpoint is the same tenant across sessions and across
restarts), unique across endpoints (the namespace is the endpoint's node id), and **never present in
membership** — so it can never be mistaken for a node, never be handed a committee seat, and never
counted toward quorum.

The signature on a tenant's `ActionEnvelope` is the **endpoint's**. That is exactly right: the
signature says *who is accountable*, the actor says *who did it*. An endpoint that submits a bad
action is a node that submitted a bad action, and the existing equivocation and spot-check machinery
already knows what to do with one.

### This is not a new mechanism

`LiveEntityLaneRuntime.submit` already does this today:

```java
NodeId actor = new NodeId(player.getUUID());
validation.registerActor(actor, authority.publicKeyBytes());
ActionEnvelope signed = new ActionEnvelope(actor, …, authority.sign(unsigned.signedPortion()));
```

— the transitional path for *"players whose clients have not announced a node yet"*. This task makes
that path **permanent, explicit, and auditable** rather than implicit: the mapping stops being a local
convention and becomes a signed `TenantRoster` the endpoint publishes, so any peer can say *which
player, at which endpoint, is behind this actor* without asking the endpoint to be honest about it
after the fact.

An `onBehalfOf` field on `ActionEnvelope` would make the delegation explicit on the wire. It is
deliberately **not** in this task: `ActionEnvelope` is a frozen contract, so it costs an encoding
version bump, a golden fixture, and a Rust mirror ([`docs/README.md`](../README.md) §4.4), and the
roster delivers the auditability without touching it. It is recorded as the hardening path.

### N tenants grow the footprint, never the weight

One endpoint contributes **one node, one key, one seat per region** no matter how many players it
serves. Their discs grow the *area* it owns; they do not grow its *vote*.

The alternative — a synthetic node per tenant — would let an endpoint with 40 players claim 40
committee seats and quietly own every quorum in its world. That is a Sybil attack an honest operator
would trip over by accident, so the model makes it unrepresentable rather than forbidden.

### The password, for a client that cannot answer a handshake

L-52's live-join gate runs in the configuration phase over a mod payload; a vanilla client has no idea
it exists. So on an endpoint the world password becomes the **endpoint's** gate:

1. the tenant connects and is placed in a **limbo**: spawn-locked, no world interaction, adventure
   mode, a title telling them what to type;
2. `/nodera join <password>` verifies against the world's derived key material;
3. on success they are released into the world; on failure the attempt is throttled
   (`JoinAttemptThrottle`, which already exists) and, past the limit, they are disconnected.

The plaintext is read from the command buffer, used, and cleared: never logged, never in a
`toString`, never on the wire. `e2e-endpoint.sh` greps every log for it, which is
[L-68](LIMITATIONS.md)'s exit condition alongside the refusal itself.

**Why not just use the server whitelist?** Because an operator should not have to choose between the
world's password and the server's access control. Both apply, independently, and the limbo is what
makes the world's password meaningful to a client that cannot negotiate one.

### Permissions and ops

`WorldPermissions` is the source of truth, unchanged: an op is a **key-checked role**, not a
username. On an endpoint the subject of a grant is usually a tenant id rather than a node id, so
`roleOf` is consulted with the tenant id and the *endpoint's* key. The mod's `OperatorBridge`
discipline carries over exactly — the bridge tracks only the players **it** opped, never touching
server-configured ops, and de-ops on logout so nothing lingers past a session.

### What a tenant can see

A vanilla client renders no custom payloads, so Nodera-facing UI is limited to what the protocol
already carries: **tab list, boss bar, action bar, chat, titles, scoreboard**. That is an envelope
constraint ([A-9](LIMITATIONS.md)), and the mitigation is already built — `TabListRenderer`,
`BossBarManager`, `ActionBarNotifier`, and `ComponentRenderer` exist in the mod and render the same
`DiagnosticsView` view models this task feeds. A tenant gets region ownership, session health, and
piece progress on surfaces they already have.

## Files

- `java/paper-plugin/src/main/java/dev/nodera/endpoint/tenant/{TenantId,TenantRoster,TenantSession,TenantLimbo}.java`
- `java/paper-plugin/src/main/java/dev/nodera/endpoint/permission/{EndpointOperatorBridge,EndpointJoinGate}.java`
- `java/paper-plugin/src/main/java/dev/nodera/endpoint/ui/EndpointSurfaces.java`

## Testing

- `TenantIdTest` — determinism across restarts; distinctness across endpoints; a tenant id never
  collides with a real `NodeId` drawn from the identity store.
- `TenantRosterTest` — round-trip, signature verification, and refusal of a roster signed by anyone
  but the endpoint.
- `TenantLimboTest` — no world interaction is possible before release; a wrong password throttles and
  eventually disconnects; the right one releases exactly once.
- `EndpointOperatorBridgeTest` — the `OpSyncDecision` matrix, re-run against tenant subjects.
- Live (`e2e-endpoint.sh` P3/P4/P5): a vanilla client joins directly, appears as a tenant with a
  stable id, plays, and its dropped item is credited to a modded player exactly once.
- Live (`e2e-endpoint.sh` P7): a wrong password never reaches the world and never appears in any log.
  **This is L-68's exit.**

## Acceptance criteria

1. ⬜ An unmodified 1.21.1 client joins an endpoint and plays with nothing installed.
2. ⬜ Its actions are committed by the region committee, attributed to a stable tenant id.
3. ⬜ The endpoint contributes every tenant's view to the ownership plan under one node id, and holds
   one seat per region regardless of tenant count.
4. ⬜ The tenant roster is signed, published, and verifiable by another peer.
5. ⬜ A password-protected world refuses a wrong password before world placement, throttles retries,
   and leaks no plaintext.
6. ⬜ A tenant sees region ownership and session health on vanilla surfaces.

## Limitations

- **L-68** — the tenant join gate is enforced by the endpoint's limbo, not cryptographically by the
  network.
