# Task 33 — Live Worker Data, World Identity/Authorship, P2P Operator-Permissions + Chunk Validation (Phase 6, worker + mod + storage)

**Phase:** 6 (makes the Task 32 companion fully functional) · **Depends on:** Tasks 32 (peer worker +
control endpoint), 30/31 (share flow + GUI), 23 (encryption), 20/28 (tracker), 7/11 (committee +
interference validation), 9 (event-sourced certified state) · **Modules:** `peer-runtime` (control
protocol + verbs), `nodera-headless` (worker handler + identity mint), `storage-api`
(`WorldIdentity`/`WorldPermissionGrant`/`WorldPermissions`/`WorldRole`), `neoforge-mod` (world store,
host delegation, password authority, op-granting, multiplayer feed), `rust/nodera-app` (live metrics),
`core` (`WorldRole`, tags 92/93).

## Implementation status (2026-07-21 — increments landed, gate green)

`./gradlew check` + `cargo test` green. Landed this pass:

- **Multiplayer-tab bug fixed (was: "No trackers/rendezvous configured").** `MultiplayerStatusFeed`
  reads `CLIENT_TRACKER_ENDPOINTS`/`CLIENT_RENDEZVOUS_ENDPOINTS`, TCP-probes each on a daemon cadence,
  and feeds the previously-uncalled `NoderaMultiplayerScreen.setTrackerSupplier`/`setRendezvousSupplier`
  (wired in `ClientBootstrap.onClientSetup`). The tabs now list the configured endpoints with live
  reachability instead of always-empty.
- **Worker telemetry → real dashboard data.** Control protocol bumped to v2 with a `ControlHandler`
  seam + dispatching `ControlServer`; new verbs `STATE`/`IDENTITY`/`HOST`/`JOIN`/`STOP`/`PASSWORD`/
  `STATUS`/`WORLDID`. `WorkerControlHandler` answers `STATE` from the live `TrafficMeter` + session
  view + hosted worlds (real bytes/peers/worlds JSON). Rust `control.rs` `fetch_state` parses it and
  `main.rs` pumps it to the React dashboard each second (`daemon_up` from the probe). Proven live:
  the worker answers `NODERA-STATE`/`NODERA-IDENTITY`/`NODERA-HOST`/`NODERA-WORLDID`.
- **Per-world identity + authorship + persistence.** `WorldIdentity` (tag 92): author-signed record
  `{ worldId, authorNodeId, authorPublicKey, createdAtEpoch, shared, listedOnTracker, encrypted,
  manifestRef, signature }`; `worldId = SHA-256(seed ‖ authorPublicKey ‖ createdAt)` — unique,
  rename-proof. The **worker is the author** (it holds the signing key): the mod calls the `WORLDID`
  verb to mint + sign, then persists the record into the save folder as `nodera-world.dat`
  (`NoderaWorldStore`, atomic write). On world load `ServerBootstrap` auto-re-shares a world whose
  record is `shared` — the host always restores its opened-to-Nodera status.
- **Password authority (only the original author).** `NoderaHost.localWorkerIsAuthor` compares the
  local worker identity (`NODERA-IDENTITY`) to the persisted `authorNodeId`; `ShareWorldScreen`
  makes the password field read-only with an explanatory hint when this install is not the author.
- **P2P operator-permission model.** `WorldRole` (`OWNER`/`OPERATOR`/`MEMBER`/`BANNED`, core) +
  `WorldPermissionGrant` (tag 93, author/operator-signed, versioned/supersedable) + `WorldPermissions`
  (authenticated evaluator: author is implicit OWNER, verifies every grant's signature + granter
  authority, newer version supersedes, author cannot be demoted). The sharing player is granted MC
  operator on share (`NoderaHost.grantHostOperator`).
- **Host delegation.** `NoderaHost.activate` asks the worker to `HOST` the world (keyed by the real
  `worldId`) when a worker is linked, so hosting rides the always-on node.

Tests added (headless): `ControlServerTest` (STATE/IDENTITY/HOST dispatch), `WorldIdentityTest`,
`WorldPermissionGrantTest`, `WorldPermissionsTest`, `NoderaWorldStoreTest`; `TypeTagsTest` + Rust
tag-mirror updated for tags 92/93.

## Still the live lane (documented, next increments)

- **Per-row singleplayer world-list display "like a server" (mixin).** The chosen approach is the
  repo's first mixin (`WorldSelectionListEntryMixin`) rendering the public badge + live connected-peer
  count on each shared world's row, keyed off `nodera-world.dat` + the worker `STATUS`/`STATE` player
  count. The headless view model (`PublicWorldBadgeView`) + the data (worker `STATE`) exist; the mixin
  itself needs the GUI env to verify (L-45/L-46). Interim: `SelectWorldScreenAddon` draws a
  screen-level summary.
- **Live chunk/world validation + revalidation.** Wire the already-tested headless machinery over the
  worker's `PeerRuntime`: join-time verify the `WorldIdentity` signature + the certified event chain
  (`storage-eventsourced.EventReplayer`) + per-piece hash (`distribution.PieceReassembler`); live
  region validation via `coordinator` (`RegionAllocator`/`RegionPipeline`/`LeaseManager`) + `committee`
  (`CommitteeSession` 2-of-3 quorum re-execution) — the `CommitteeMvpIT` pipeline, now live;
  revalidation via `committee.SpotCheckAuditor` + `coordinator.DelegabilityMonitor`; equivocation →
  `consensus.EquivocationDetector` slash. This is T7/T11-scale live wiring over the worker mesh.
- **Grant distribution + enforcement over the network.** Announce `WorldPermissionGrant`s to the
  tracker + gossip over the `PeerRuntime`; enforce `BANNED` at `JOIN`; grant MC op to remote joiners
  whose role is `OWNER`/`OPERATOR`.
- **Password network propagation.** The authority check + local re-key/re-sign is done; replacing the
  old ciphertext with the new across all seeders rides the live Task 23 encryption path.
- **Worker content seeding.** The `HOST` verb records the world; extracting region pieces
  (`RegionSnapshotSplitter`) + seeding them from the worker is the data-plane increment.

## The permission / validation design (how it works with P2P + trackers + rendezvous)

1. **Identity root.** Each world has one author = the worker `NodeIdentity` that first opened it to
   Nodera. `WorldIdentity` is self-authenticating (author signature over the record); `worldId` is
   derived and unique. This is the single trust root per world (L-20: still single-signer, now the
   host player's worker; T16 owns multi-party re-cert).
2. **Roles.** Author = OWNER (intrinsic). Everyone else's role comes from a signed
   `WorldPermissionGrant` chained to the author (or an operator). No trusted server: every peer
   verifies signatures locally. Newer `grantVersion` supersedes, so revocation = a newer BANNED grant.
3. **Distribution.** Grants + the `WorldIdentity` are content-addressed and travel over the same P2P
   fabric as world data — announced to the **tracker** (discovery) and gossiped over the
   **rendezvous**-reachable `PeerRuntime` mesh. Reachability (rendezvous) and discovery (tracker) are
   untrusted transports; authority lives entirely in the signatures.
4. **Enforcement.** `JOIN` refuses BANNED peers; in-game operator powers follow `WorldRole.isOperator`.
   The password is author-only (enforced at the worker + greyed in the UI).
5. **Validation.** World content is validated on join (identity signature + certified chain + piece
   hashes) and continuously by committees re-executing regions (2-of-3 quorum), with spot-check
   revalidation and equivocation slashing — the existing headless consensus stack, wired live.

## Acceptance criteria

1. Multiplayer Trackers/Rendezvous tabs list configured endpoints with live reachability (bug fixed).
2. The companion dashboard shows real worker metrics (bytes/peers/worlds), not zeros.
3. A shared world has a signed `nodera-world.dat` (unique id + author); re-opening auto-re-shares;
   only the author can change the password.
4. The sharing player is in-game operator; the signed permission model authenticates roles; BANNED
   cannot join (enforcement rides the live JOIN path).
5. `./gradlew check` + `cargo test` green; README/Tested/LIMITATIONS/Roadmap updated; the live lane
   (mixin, committee validation, grant gossip, password propagation, worker seeding) is documented.
