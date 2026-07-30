<!-- AI-AGENT-INSTRUCTION: This is a PROGRAMME PLAN — a historical record of how a multi-task effort
     was scoped and executed. It is NOT the current specification of anything. Paths and task numbers
     inside it predate the 2026-07-25 documentation reorganization: the current specs are
     docs/<category>/Task.<n>.md, indexed by docs/ROADMAP.md. Do not "fix" the historical references
     below; read them through the current structure. Do not treat this file as a status source. -->

> **Historical programme plan.** Current specifications live in `docs/<category>/Task.<n>.md`;
> the index is [`../ROADMAP.md`](../ROADMAP.md) and the documentation format is
> [`../README.md`](../README.md). Task numbers and paths below are from before the 2026-07-25
> reorganization and are preserved as written.

# Plan 4 — Signature-based creator OP + encryption/permission flaw fixes (F1–F6)

Tracks GitHub issue **#36**. World creators hold **cryptographic proof** (their Ed25519 Peer
identity — never a UUID or username) that grants server-style **OP** powers in worlds they
authored. Authors and the operators they designate grant/revoke other players with `/op`-`/deop`
commands that issue **signed permission grants bound to the recipient's Peer key**.

Most primitives exist from Task 33 (signed `WorldIdentity`, `WorldRole`, `WorldPermissionGrant`,
`WorldPermissions`), but the evaluator is wired only to mesh join admission — there is no bridge to
Minecraft ops and, critically, **no proof of key possession anywhere in the announce path**. A
security review found seven flaws (F1–F6 + F2b), several of them privilege-escalation holes.

## Threat model

Identity is an Ed25519 keypair. `NodeId` is a random UUID **unrelated to the key** — so a NodeId is
a spoofable label; only a signature over a fresh challenge proves key possession. Trust roots at the
**world author's public key** (embedded in the signed `WorldIdentity`). Everything downstream must
chain a signature to that key.

Attacker capabilities assumed: can craft arbitrary announce/grant bytes, replay observed messages,
claim any NodeId, and forge any field not covered by a signature verified against a key they do not
hold. Attacker cannot forge Ed25519 signatures or recover private keys.

| ID | Sev | Flaw | Fix phase |
|----|-----|------|-----------|
| F1 | Critical | Announce spoofing — server registers claimed NodeId+key with no proof of private-key possession | Phase 2 |
| F2 | Critical | Grants bind subject **NodeId** only; NodeId is a random UUID → announce the subject's NodeId with your own key, inherit OPERATOR | Phase 1 |
| F2b | Critical | Granter key unchecked — `apply()` verifies the signature against the key **carried inside the grant**; forge `granter=authorNodeId, granterPublicKey=attackerKey`, self-sign → accepted | Phase 1 |
| F3 | High | `grantHostOperator()` ops **every** online player at share time | Phase 4 |
| F4 | High | `WorldRole` never bridged to MC ops — role gates mesh admission only | Phase 4 |
| F5 | Medium | Grants in-memory only — lost on restart, no persistence | Phase 3 |
| F6 | Medium | Password rotation broken twice — `NODERA-PASSWORD` silent no-op, and the verb carries a password **hash** which cannot re-derive the Argon2id content key | Phase 6 |

### Noted, not fixed here (Phase 6 adds `docs/LIMITATIONS.md` entries)

- Live Minecraft join path has **no password gate** — `openGameServer → publishServer(null, …)`;
  world password protects only archived content, not who connects to the live game.
- Mesh `JoinAdmission` stays NodeId-only until the transport `ServerHello`/`ChallengeResponse`
  challenge is wired into the accept path.
- Grants are not gossiped to non-hosting co-host peers yet.
- Convergent AES-256-GCM content encryption leaks plaintext equality (by design, in-code note).

## Phases (order 1→6, each green independently)

### Phase 1 — Key-bound grants (F2, F2b) — storage only
- `WorldPermissionGrant`: add `Bytes subjectPublicKey`. Grant-local wire **v2**; v1 stays decodable
  (empty `subjectPublicKey`); `signedPortion()` re-encodes in the grant's **original** version so
  legacy signatures stay valid. `create(...)` overload carrying the subject key.
- `WorldPermissions`: constructor takes `authorPublicKey`. `apply()` requires author-signed grants
  to carry the **real** author key, operator-signed grants to chain to a key-matching operator
  grant; **v1 grants can never carry OPERATOR+**. Key-checked `roleOf(NodeId, Bytes)` /
  `isOperator(NodeId, Bytes)`; `snapshot()`. NodeId-only `canJoin` kept for the mesh gate.
- Files: `library/java/storage/.../{WorldPermissionGrant,WorldPermissions}.java`.

### Phase 2 — Challenge–response announce (F1) — neoforge-mod
- `AnnounceChallenges`: per-login 32-byte `SecureRandom` nonce, single-use, expiring, keyed by MC
  UUID; issued in `ServerBootstrap.onPlayerLoggedIn`, carried in `NoderaSessionPayload`.
- `NodeAnnounceProof` (pure): canonical bytes = `challenge ‖ nodeId ‖ publicKey ‖ MC-UUID`. Client
  signs with `clientIdentity()`; `NoderaNodeAnnouncePayload` gains `signatureB64`.
- `handleNodeAnnounce`: consume nonce + `SignatureService.verify` **before** `announce`. Bump
  `ModNetworking.PROTOCOL_VERSION` `"1"→"2"`.

### Phase 3 — Grant persistence (F5) — storage + mod
- `TypeTags.WORLD_PERMISSION_SET = 107` (**not 106 — 106 is already `MOVE_PLAYER_ACTION`**), bump
  `NEXT` to 107. `WorldPermissionStore` writes `nodera-permissions.dat` beside `nodera-world.dat`
  (AtomicFileWriter; corrupt → empty + warn).
- `NoderaHost.activate()` reloads via `permissions::apply` (re-verifies every signature); writes on
  every accepted grant.

### Phase 4 — MC op bridge (F3, F4) — neoforge-mod
- `OperatorBridge`: tracks players **it** opped (never touches server-configured ops);
  `syncPlayer` maps verified registry node → key-checked `roleOf` → `playerList.op()/deop()`,
  BANNED ⇒ disconnect. Pure `decide()` for MC-free tests. Deop-on-logout; re-sync on every verified
  announce.
- Replace `grantHostOperator`: op only the integrated-server owner and only when
  `localWorkerIsAuthor(server)`. Dedicated server: nobody automatic.

### Phase 5 — `/nodera op|deop` + `NODERA-GRANT` control verb (the feature)
- Additive control verb (SEED/ARCHIVE precedent, no protocol bump):
  `NODERA-GRANT <ver> <worldIdHex> <subjectNodeId> <subjectPubKeyB64> <roleOrdinal> <grantVersion>`
  → signed grant bytes (B64). Mirror in `CompanionProtocol`/`CompanionClient.grantRole`.
- `NoderaCommand`: `/nodera op <player>`, `/nodera deop <player>`; `.requires(hasPermission(2))` is
  UI gate only — real check is the executor's verified key-checked OWNER/OPERATOR (or integrated
  owner with `localWorkerIsAuthor`). `grantVersion = existing + 1`.

### Phase 6 — Honest password rotation (F6)
- `NODERA-PASSWORD <ver> <worldId> <newPasswordB64>` — plaintext over the loopback-only (127.0.0.1)
  control socket (the hash cannot re-key; the same machine holds every key). Zero live callers.
- `WorkerControlHandler.password()` override: verify worker is author, re-key (`WorldKeyMaterial`
  → re-encrypt archive → new manifest version → `WorldIdentity.resign` → refresh announce). If the
  re-key pipeline exceeds budget: land the contract fix + **honest error surfacing** (never silent
  success) + `docs/LIMITATIONS.md` entry + follow-up issue.
- Add `docs/LIMITATIONS.md` entries for the three "noted, not fixed" items.

## Back-compat

- Grant **v2** with tolerant v1 decode + version-faithful `signedPortion`; v1 restricted to
  non-operator roles.
- `ModNetworking.PROTOCOL_VERSION` `"1"→"2"` — intentional hard break between mismatched mod builds.
- `NODERA-GRANT` additive; `NODERA-PASSWORD` arg change is breaking-but-dead.
- `TypeTags` **107** appended (106 already taken), `NEXT` bumped. `nodera-permissions.dat` new file
  (absent = empty; old saves unaffected).

## Verification

- Unit: grant v1/v2 round-trip + golden v1 bytes; spoof matrices (subject-key, granter-key,
  replayed nonce, wrong UUID); `OpSyncDecision` matrix; `WorldPermissionStore` corrupt-file;
  `NODERA-GRANT` round-trip.
- Build: `./gradlew build`.
- Live: two-client run — author auto-op on entering own world; `/nodera op` a second player; confirm
  a vanilla op-gated command; `/nodera deop`; restart host, confirm grants persist.
