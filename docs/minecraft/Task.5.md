# Minecraft Task 5 — Decentralized Host Lane

<!-- AI-AGENT-INSTRUCTION: There is NO dedicated-server requirement anywhere in this lane; an optional
     dedicated server is just a well-provisioned peer with one non-authoritative vote. A password
     change is a FULL RE-MANIFEST (new key ⇒ new ciphertext ⇒ new manifest root) — the UI must warn,
     and the re-key must not silently re-seed a plaintext archive. Keep this header's status
     accurate. -->

**Status:** 🚧 IN PROGRESS (genesis, continuity, re-key, and the live-join password gate all landed; live rendezvous composition + per-piece encryption at share remain)
**Category:** minecraft · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [network 3](../network/Task.3.md), [network 8](../network/Task.8.md), [rendezvous 2](../rendezvous/Task.2.md), [worker 3](../worker/Task.3.md)
**Consumed by:** players, [worker 3](../worker/Task.3.md)

---

## Goal

Any player can put a world on the network from the pause menu. The central dedicated server is gone:
a player's integrated server runs the same host lane, shares from a **"Open to Nodera"** button (the
analogue of "Open to LAN", with an optional password), produces a certified genesis from the existing
world, and delegates hosting to the always-on worker so the world outlives the game.

## Status detail

**The dedicated-server requirement is gone.** The dist gate was removed, the host service is
role-driven rather than dist-driven, the share screen runs one code path for both create-time and
share-later, and the development script runs infrastructure only — no server install.

**Genesis from an existing world landed.** A host-signed certified genesis is extracted from live
region digests, persisted, and reused across restarts (proven live), and the world's identifier
derives from the certified root, with pre-existing identities keeping their id through derivation
matching.

**Continuity landed.** A share packs the save into the canonical archive and streams it continuously
with a bounded final flush and a freshness marker; a client that loses its host swaps the vanilla
disconnect screen for a recovery flow — fetch, unpack, re-open, auto-re-share — so the joiner becomes
the world's next host **with the same world id**.

**The password re-key pipeline landed and is green live.** A re-key packs a fresh archive,
re-encrypts it under a new salt, re-signs the identity, re-announces, and evicts the superseded
ciphertext — and `e2e-rekey.sh` drives it end to end in CI (**L-51** retired; see
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md)). The live **join password gate** landed too: a
configuration-phase challenge-response refuses a joiner at the game server before a player is
created (**L-52** retired, `e2e-password.sh` green in CI). Along the way a plaintext-publishing hole
closed (**L-59** retired): the continuous streaming lane now routes a password-protected world through
the re-encrypting path with no plaintext fallback.

**Remaining:** live rendezvous composition and per-piece encryption **at share time** (deliverable 10)
— the re-key path proves the crypto round trip, but a first share of a password-protected world still
seeds through the ordinary share activation. That is the last open half of this lane.

## Dependencies

- [network 8](../network/Task.8.md) — encryption and key material.
- [network 3](../network/Task.3.md) — certified genesis and identity types.
- [worker 3](../worker/Task.3.md) — hosting delegation and seeding.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | Dist-agnostic, role-driven host service | ✅ |
| 2 | Pause-menu share screen; one code path for create-time and share-later | ✅ |
| 3 | Infrastructure-only development script | ✅ |
| 4 | Certified genesis from an existing world, persisted and reused | ✅ |
| 5 | World id derived from the certified root, with continuity for older worlds | ✅ |
| 6 | Continuous archive streaming + bounded final flush + freshness marker | ✅ |
| 7 | Disconnect-recovery: fetch, unpack, re-open, auto-re-share | ✅ |
| 8 | Password re-key pipeline (headless) | ✅ |
| 9 | Re-key live GUI exit | ✅ (**L-51** retired — `e2e-rekey.sh` green in CI) |
| 10 | Live rendezvous composition + per-piece encryption at share | 🚧 |
| 11 | Password gate on the live join path | ✅ (**L-52** retired — `e2e-password.sh` green in CI) |

## Design

**The host role is a capability, not a dist.** Once hosting is role-driven, "player-hosted" and
"dedicated" stop being different code paths — a dedicated server is simply a peer that happens to be
well provisioned and always on. That is what removes the central server without a rewrite.

**One share path.** Create-time and share-later flow through the same code, so a world shared a month
after creation is indistinguishable from one shared at creation. Two paths would drift, and the
difference would surface as "worlds created before X behave differently".

**A password change is a full re-manifest, and the UI says so.** Convergent encryption means a new key
produces new ciphertext, a new manifest root, and a new version. There is no way to re-key without
republishing, so the interface warns rather than pretending it is a settings toggle. The re-key
deliberately does **not** re-run the ordinary share activation, because that would seed a *plaintext*
archive as the newest version and let joiners bypass the password entirely.

**Failure is surfaced, never silently swallowed.** If a re-key fails, the world keeps its old password
and the player is told. A silent success on a security operation is the worst outcome available.

**Recovery keeps the world's identity.** A joiner that re-hosts after losing its host keeps the same
world id, so the world does not fork into two histories that both claim to be it.

**The private key never travels.** The archive carries the signed world identity and the certified
genesis; the host's signing key and runtime state do not.

## Files

- `java/neoforge-mod/src/main/java/dev/nodera/mod/common/{NoderaPeerService,NoderaHost,NoderaWorldStore}.java`
- `java/neoforge-mod/src/main/java/dev/nodera/mod/client/share/`
- `java/neoforge-mod/src/main/java/dev/nodera/mod/common/{WorldArchiver,NoderaContinuity}.java`

## Testing

- `ShareOptionsTest` — the value type: password implies encryption, defaults, immutable copy-with,
  positive replication, and the password never appearing in `toString`.
- `WorldArchiverStreamingTest`, `WorldArchiverPackToSpoolTest`, `CompanionClientRekeyTest`.
- `RekeyVerbIT` (network/worker) — the crypto and identity round trip.
- Live: `e2e-continuity.sh` — bake, share, join, archive on the network, host killed, joiner recovers
  and re-hosts.

## Acceptance criteria

1. ✅ A world can be shared from the pause menu with no dedicated server anywhere.
2. ✅ A certified genesis is produced from an existing world and reused across restarts.
3. ✅ A shared world survives its host closing the game, and the joiner re-hosts it with the same id.
4. ✅ An author changes the password from the share screen and joiners must supply the new one
   (**L-51** retired).
5. ✅ A password-protected world refuses a joiner at the live game server without the password
   (**L-52** retired).

## Limitations

None open. **L-51**, **L-52**, and **L-59** are RETIRED — see
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md). The remaining work (live rendezvous composition +
per-piece encryption at share) is tracked as deliverable 10 above, not as a limitation row.
