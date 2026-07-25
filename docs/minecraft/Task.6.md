# Minecraft Task 6 — World Identity + Permissions (Mod Half)

<!-- AI-AGENT-INSTRUCTION: Operator authority is CRYPTOGRAPHIC — an Ed25519 peer key, never a UUID or
     a username. A grant is a signed statement re-verified by every receiver against the world's
     author key; never treat "the server said so" as authority. The world-list mixin is the repo's
     first and must stay minimal, with a COMPATIBILITY.md note. Keep this header's status accurate. -->

**Status:** 🚧 IN PROGRESS
**Category:** minecraft · **Owns:** L-49 · **Last audit:** 2026-07-25
**Depends on:** [worker 2](../worker/Task.2.md), [worker 3](../worker/Task.3.md), [task 1](Task.1.md)
**Consumed by:** players, world authors, and operators

---

## Goal

A world has an **author**, and authorship is a cryptographic fact. The mod persists the signed
per-world identity, re-shares automatically on load, restricts the password to the author, grants and
revokes operator powers as **signed permission grants bound to a recipient's peer key**, and enforces
bans at join.

## Status detail

**Landed.** The signed world identity is minted by the worker and persisted per world with an atomic
write; a shared world auto-re-shares on load; only the author can change the password; the sharing
player becomes operator; and operator powers are granted and revoked through signed grants evaluated
by a permission model whose types live in the storage layer. `/nodera op` and `/nodera deop` issue
grants, and a bridge checks operator authority by key rather than by name.

Ban enforcement is in: join admission gates both direct joins and gossip ingest, and a hosted world
installs its permission check at share time — so a banned peer is refused at join **and** cannot slip
in through gossip.

**Remaining:** the per-row public badge and live player count on the single-player world list, which
needs the repository's **first mixin**; remote-joiner operator grants; and password propagation over
the network, which rides the encryption lane's live half. That set is **L-49**.

## Dependencies

- [worker 2](../worker/Task.2.md) — the identity mint and per-world status.
- [worker 3](../worker/Task.3.md) — grant gossip on the mesh.
- [task 1](Task.1.md) — the GUI environment to verify the mixin.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | Signed world identity persisted per world (atomic write) | ✅ |
| 2 | Auto-re-share on world load | ✅ |
| 3 | Author-only password authority in the UI | ✅ |
| 4 | The sharing player becomes operator | ✅ |
| 5 | Signed permission grants + role evaluation | ✅ |
| 6 | `/nodera op` and `/nodera deop`; key-checked operator bridge | ✅ |
| 7 | Ban enforced at join **and** on gossip ingest | ✅ |
| 8 | World-list entry mixin: per-row public badge + live player count | ⏳ (**L-49**) |
| 9 | Remote-joiner operator grants | 🚧 |
| 10 | Password propagation over the network | ⏳ |

## Design

**Authorship is a key, not a name.** A username or a UUID can be reused, spoofed, or reassigned by a
service. Binding authorship and operator powers to an Ed25519 peer key makes "only the creator can
change the password" a statement the network can check without trusting anyone's account system.

**A grant is a signed statement, re-verified everywhere.** The mod issues grants; the mesh relays
them; every receiver re-verifies both the signature and the granter's *authority* against the world's
author key. The transport carries the decision without ever being trusted with it — which is why a
correctly-signed grant from a non-author is refused by every receiver, and is not even relayed by the
attacker's own node.

**Bans must be enforced on both doors.** Gating the join path alone leaves gossip as an open side
entrance: a banned peer could still be admitted by any peer that had not been told. Admission
therefore gates join *and* gossip ingest, and grants gossip so every peer knows.

**The first mixin, and it is deliberately small.** Everything so far has used events; the per-row world
list badge cannot, because vanilla's row geometry is not accessible from outside its package. That
makes it the repository's first mixin — minimal, documented, and recorded in the compatibility
contract, because mixins are what version churn breaks.

**Author-only password, enforced where the key is.** The UI restricts it, but the *enforcement* is in
the worker, which holds the signing key and verifies self == author before any re-key. A UI-only
restriction is a suggestion.

## Files

- `java/neoforge-mod/src/main/java/dev/nodera/mod/common/{NoderaWorldStore,OperatorBridge}.java`
- `java/neoforge-mod/src/main/java/dev/nodera/mod/debug/command/` (`op` / `deop`)
- `java/neoforge-mod/src/main/java/dev/nodera/mod/mixin/` (the world-list entry mixin)
- Types: `java/storage/.../{WorldIdentity,WorldPermissionGrant,WorldPermissions,WorldRole}.java`

## Testing

- `NoderaWorldStoreTest` — the per-world identity file, written atomically.
- `BannedPeerAdmissionTest` — refusal at join **and** via gossip.
- `GrantGossipIT` (6, in the peer module) — grants converge across co-hosts; a non-author's signed
  grant is refused everywhere.
- Live: `e2e-commands.sh` covers the operator command surface per player.

## Acceptance criteria

1. ✅ A world carries a signed identity that survives restarts and re-shares.
2. ✅ Only the author can change the password.
3. ✅ Operator powers are granted and revoked by signed, key-bound grants.
4. ✅ A banned peer is refused at join and cannot enter through gossip.
5. ⏳ A shared world shows a live player count on its row in the single-player world list
   (**L-49** exit).
6. ⏳ A re-keyed world's new ciphertext replaces the old across seeders, and joiners must supply the
   new password.

## Limitations

- **L-49** — the live halves: the world-list mixin, live committee validation over the worker mesh,
  and password propagation. See [`LIMITATIONS.md`](LIMITATIONS.md).
