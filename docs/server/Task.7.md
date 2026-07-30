# Server Task 7 — Modded Clients on an Endpoint

<!-- AI-AGENT-INSTRUCTION: A modded client on an endpoint is NOT a tenant — it is its own node, with
     its own key, forwarding to the region primary exactly as it does today. The endpoint↔mod
     handshake rides PLUGIN MESSAGING (`nodera:v1`), never a NeoForge custom payload: those do not
     exist on a Paper server. Admission is registryFingerprint equality — a cryptographic answer to a
     mod-compatibility question, using something the codebase already has. §Design records an
     ASSUMPTION about the source requirement; re-confirm it before starting. Keep this header
     accurate. -->

**Status:** ⬜ NOT STARTED
**Category:** server · **Owns:** L-70 · **Last audit:** 2026-07-28
**Depends on:** [server 6](Task.6.md), [minecraft 4](../minecraft/Task.4.md), [engine 2](../engine/Task.2.md)
**Consumed by:** [server 9](Task.9.md)

---

## Goal

A NeoForge client running the Nodera mod joins an endpoint over the ordinary Minecraft protocol,
discovers that this server speaks Nodera, and participates **as its own node** — its own key, its own
committee seats, its own forwarded actions — in the same world an unmodified player is standing in.
One world, two client classes, one lane.

## Status detail

Not started. The admission rule rests on an assumption about the source requirement that is stated in
§Design and recorded as [L-70](LIMITATIONS.md).

## Dependencies

- [server 6](Task.6.md) — the identity and session model modded clients slot into beside tenants.
- [minecraft 4](../minecraft/Task.4.md) — the client half: the multiplayer screen that lists an
  endpoint like any other Nodera world, and the join flow.
- [engine 2](../engine/Task.2.md) — `GenesisManifest.registryFingerprint`, which becomes the
  admission test.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `nodera:v1` plugin-message channel — the cross-platform endpoint↔mod handshake | ⬜ |
| 2 | Mod-side: NeoForge payloads declared **optional** so a modded client can join a plain Paper server | ⬜ |
| 3 | Node announce over `nodera:v1`, carrying the existing challenge/proof (issue #36 F1) | ⬜ |
| 4 | Admission by `registryFingerprint` equality, with a human-readable mod-set diff on refusal | ⬜ |
| 5 | Required/allowed mod-set publication + `vanillaFriendly` on the tracker listing | ⬜ |
| 6 | Lane-plan broadcast over `nodera:v1` (the `NoderaLanePlanPayload` equivalent) | ⬜ |
| 7 | Mixed-session ownership: tenants and modded nodes ranked by the same geometry | ⬜ |

## Design

### The handshake rides plugin messaging

NeoForge custom payloads do not exist on a Paper server: the registrar is a NeoForge networking
concept and a Paper server will never negotiate it. The one channel both platforms speak is Bukkit
plugin messaging, so the endpoint↔mod conversation moves to **`nodera:v1`**:

```
client → server   HELLO   protocolVersion, nodeId, publicKey, registryFingerprint
server → client   WELCOME worldId, worldIdentity, challenge, sessionRoute, requiredMods
client → server   ANNOUNCE signature over (challenge ‖ nodeId ‖ publicKey ‖ playerUUID)
server → client   LANEPLAN worldSeed, rulesVersion, registryFingerprint, genesisRoot, members
                  REFUSE  reason, offendingMods                (the unhappy path, always explicit)
```

The payloads are the existing ones (`NodeAnnounceProof`, `NoderaLanePlanPayload`) re-framed onto a
different carrier; the challenge/proof discipline from issue #36 F1 is unchanged, because a spoofable
`NodeId` is spoofable on any transport.

**The mod's payloads must become optional.** Today a NeoForge client with a required payload
registration cannot join a server that does not answer it. Making them optional is a small mod-side
change with a large consequence: a Nodera-modded player can join a plain Paper server with no plugin
at all, and simply play vanilla there. A mod that narrows where a player can connect is a mod players
uninstall.

### Admission is registry-fingerprint equality

The source rule — *NeoForge players may join these servers, except where the host requires specific
mods* — resolves onto something the project already built.

`GenesisManifest.registryFingerprint` is what makes two peers agree on the block and item palette. A
client whose fingerprint differs would compute different `StateRoot`s from the same inputs and vote
against every batch. So:

> **A client is admitted iff its registry fingerprint matches the world's.**

That is a cryptographic answer to a mod-compatibility question, and it is exact where a mod-name list
is approximate: it catches a content mod that changes the palette, and it ignores a client-side
rendering mod that does not.

Alongside it, for humans:

- the endpoint publishes a **required mod set** (mods a client must have, because the world's palette
  depends on them) and an **allowed set**;
- a refusal names the offending mods — *"this world requires `examplemod` (palette fingerprint
  mismatch)"* — rather than a hash difference nobody can act on;
- the tracker listing carries **`vanillaFriendly`**: an endpoint declaring a non-empty required mod
  set cannot serve unmodified clients and says so *before* anyone tries to join.

### The assumption, stated

> The source rule is ambiguous. It is read here as **mod-set gating**: the endpoint declares what a
> client must have and must not have, and the fingerprint enforces it.
>
> The alternative reading — the host must accept whatever the client brings — would require relaxing
> the fingerprint test to a palette-superset check, which is a much larger piece of work with real
> determinism consequences (two peers with different palettes cannot re-execute the same region).
>
> **Re-confirm this reading before starting the task.** [L-70](LIMITATIONS.md) tracks it.

### A modded client is a node, not a tenant

This is the line that keeps A0 intact for everyone who runs the mod:

| | Tenant | Modded client |
|---|---|---|
| Key | none | its own Ed25519 identity |
| Actor on an envelope | tenant id | its own `NodeId` |
| Who signs | the endpoint | itself |
| Membership | never | yes, with a verified key |
| Committee seats | never | yes, by geometry |
| Can verify anything | no | yes — it re-executes |

Ownership does not care which class a player is: `ViewOwnershipPlanner` ranks a tenant's disc (held by
the endpoint) and a modded player's disc (held by their own node) **identically, by distance**. A
modded player standing closer than any tenant primaries the region; the endpoint validates it. That is
[C4](../plans/Plan.5.md) working, and it is what stops a big endpoint from centralising a world simply
by being big.

### Joining through the network, not only through the address bar

An endpoint's world is announced on the tracker like any other Nodera world, so it appears in the
mod's multiplayer screen with player counts, health, and a piece map, and quick-play connects to its
`mc/host:port` route. The two join paths — the Nodera multiplayer screen and a plain server-list entry
— land in the same world, which is exactly what `e2e-endpoint.sh` asserts.

## Files

- `endpoints/paper-plugin/src/main/java/dev/nodera/endpoint/net/{NoderaPluginChannel,EndpointHandshake,ModSetPolicy}.java`
- `endpoints/neoforge-mod/src/main/java/dev/nodera/mod/common/EndpointClientLink.java` (mod side)
- `endpoints/neoforge-mod/src/main/java/dev/nodera/mod/common/ModNetworking.java` (payloads → optional)

## Testing

- `EndpointHandshakeTest` — the four-message flow; a replayed challenge is refused; a wrong signature
  is refused; a refusal always names a reason.
- `ModSetPolicyTest` — fingerprint equality admits; a mismatch refuses and names the offending mods;
  an empty required set implies `vanillaFriendly`.
- `OptionalPayloadTest` (mod side) — a Nodera-modded client's payload registration does not prevent
  joining a server that never answers it.
- Live (`e2e-endpoint.sh` P2/P4): a modded client joins **through the Nodera network** while an
  unmodified client joins **directly at the endpoint**, both land in the same world, and the modded
  client appears in membership with its own verified key while the vanilla player does not.
- Live (`e2e-endpoint.sh` P8): a client with a mismatched fingerprint is refused with a message naming
  the mod. **This is L-70's exit.**

## Acceptance criteria

1. ⬜ A modded client joins an endpoint and is a session member under its **own** key.
2. ⬜ A modded client can still join a plain Paper server with no Nodera plugin installed.
3. ⬜ Modded nodes and tenants are ranked identically by distance in the ownership plan.
4. ⬜ A fingerprint mismatch is refused with a message naming the offending mod.
5. ⬜ `vanillaFriendly` is advertised and is accurate.
6. ⬜ An endpoint's world appears in the mod's multiplayer screen and quick-play joins it.

## Limitations

- **L-70** — mod-set gating is an assumption about the source requirement, and the fingerprint test
  admits or refuses whole clients with no partial-compatibility path.
