# Worker Task 2 — Control Protocol v2 + Live Telemetry

<!-- AI-AGENT-INSTRUCTION: Verbs are ADDITIVE. Adding one does not bump the protocol version; changing
     or removing one does, and then all three mirrors (ControlProtocol, CompanionProtocol,
     control.rs) change in the SAME commit. `STATE` fields are additive too — consumers use defaults
     for unknown fields, so never repurpose an existing field name. PASSWORD/REKEY must verify
     self == author BEFORE any re-key. Keep this header's status accurate. -->

**Status:** ✅ COMPLETED
**Category:** worker · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [worker 1](Task.1.md), [network 11](../network/Task.11.md), [network 3](../network/Task.3.md)
**Consumed by:** [minecraft 4](../minecraft/Task.4.md), [minecraft 6](../minecraft/Task.6.md), [app 2](../app/Task.2.md)

---

## Goal

Turn the presence endpoint into a real control plane: live telemetry the dashboard and HUD can render,
world lifecycle delegation, the signed world-identity mint, author-only password authority, and the
configuration surface — all as additive verbs over the same loopback channel.

## Status detail

Complete and verified live. The worker answers real `STATE`, `IDENTITY`, `HOST`, `JOIN`, `STOP`,
`PASSWORD`, `STATUS`, and `WORLDID`, with `SEED`, `ARCHIVE`, `GRANT`, `REKEY`, `PIECES`, and `CONFIG`
added additively as later lanes landed. `STATE` reports real bytes, peers, worlds, maintained pieces,
validation counters, and per-peer traffic — the dashboard and the multiplayer tabs stopped showing
placeholder zeros.

The worker is the **world author**: it holds the signing key, mints signed `WorldIdentity` records via
`WORLDID`, and enforces author-only re-key.

## Dependencies

- [worker 1](Task.1.md) — the endpoint.
- [network 11](../network/Task.11.md) — the meters and snapshots `STATE` serialises.
- [network 3](../network/Task.3.md) — the world-identity and permission types.

## Deliverables

| # | Verb | Purpose | State |
|---|---|---|---|
| 1 | `STATE` | Dashboard and HUD metrics: bytes, peers, worlds, maintained pieces, validation counters | ✅ |
| 2 | `IDENTITY` | The worker's node id and public key — the author identity | ✅ |
| 3 | `HOST` / `JOIN` / `STOP` | World lifecycle delegation | ✅ |
| 4 | `PASSWORD` / `REKEY` | Author-only re-key | ✅ |
| 5 | `STATUS` | Per-world players, health, permissions | ✅ |
| 6 | `WORLDID` | Mint and sign a `WorldIdentity` | ✅ |
| 7 | `SEED` / `ARCHIVE` | Seed a world archive; fetch one from the swarm | ✅ |
| 8 | `GRANT` | Issue a permission grant and gossip it | ✅ |
| 9 | `PIECES` | The piece bitmap the GUI's piece map renders | ✅ |
| 10 | `CONFIG` | Live configuration push with honest applied/rejected/restart-required results | ✅ |

## Design

**Additive verbs, versioned protocol.** Adding a verb does not break an older mirror, which is what
allowed `SEED`, `GRANT`, `REKEY`, and `CONFIG` to land over months without a coordinated three-way
release. Changing or removing one *does* bump the version — and the mod's gate then classifies the
skew as "update the app" or "update the mod" rather than failing with a parse error.

**`STATE` fields are additive too.** Consumers parse with defaults for unknown fields, so a worker
newer than its dashboard degrades to fewer panels rather than to an error. The corollary is a hard
rule: never repurpose an existing field name.

**Author authority lives here because the key does.** The worker holds the signing key, so it is the
only component that *can* mint a `WorldIdentity` or authorise a re-key. `PASSWORD` verifies
self == author before any re-key, which is what makes "only the world's creator can change its
password" a cryptographic statement rather than a UI convention.

**Honest configuration results.** `CONFIG` returns `applied`, `rejected` **with a reason**, or
`restart_required` per key. Reporting a false success for a setting the node cannot honour is worse
than refusing it, because the user then believes something is enforced that is not. Two settings are
permanently reported as unsupportable with the worker's own reason rather than being deleted from the
UI — see [`app/LIMITATIONS.md`](../app/LIMITATIONS.md) L-56.

**No secrets cross the boundary in the clear.** Passwords are hashed or derived before they reach a
verb; nothing password-shaped is logged or serialized.

## Files

- `peer/src/main/java/dev/nodera/headless/WorkerControlHandler.java` (verb table + `STATE` snapshot)
- `peer/src/main/java/dev/nodera/peer/control/ControlProtocol.java`
- Mirrors: `endpoints/neoforge-mod/.../common/CompanionProtocol.java`, `app/src/control.rs`

## Testing

- `ControlServerTest` — v2 verb dispatch.
- `ConfigVerbIT` — a pushed setting actually changes worker behaviour over the **real** control
  socket, and the cross-language key contract is pinned against the app's golden string.
- `RekeyVerbIT` — the re-key crypto and identity round trip: the blob decrypts under the **new**
  password only; a wrong-author identity is rejected; a second re-key supersedes the old ciphertext.
- Manual live verification: `printf 'NODERA-STATE 1\n' | nc 127.0.0.1 25610` returns the real JSON
  snapshot with non-zero values.

## Acceptance criteria

1. ✅ Every verb answers with real runtime data, not placeholders.
2. ✅ Verbs and `STATE` fields are additive; version bumps are coordinated across all three mirrors.
3. ✅ Only the author can re-key a world, enforced by signature.
4. ✅ Configuration results are honest per key.
5. ✅ No secret material is logged or serialized.

## Streaming

`NODERA-WATCH` (app task 6) is the one verb that inverts this protocol: the worker holds the
connection and writes a `STATE` line whenever its own state changes, plus a keepalive every 10 s so
a reader can tell a quiet node from a dead socket. Everything else here stays one request, one reply,
one connection. See [`../app/Task.6.md`](../app/Task.6.md) for why the dashboard needed it.

## Limitations

None owned. **L-56** (two connection settings that cannot be honoured as specified) is owned by
[`app/LIMITATIONS.md`](../app/LIMITATIONS.md), where the UI that exposes them lives.
