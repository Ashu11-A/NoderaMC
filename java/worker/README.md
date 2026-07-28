# `java/worker`

<!-- AI-AGENT-INSTRUCTION: This module is the runnable always-on node, not a peer library and not
     part of the NeoForge mod jar. Keep launcher name `nodera-headless`, control protocol mirrors,
     durable-state security, and test count current. Do not move game or NeoForge types here. -->

**Always-on Java peer process supervised by the companion app.** Minecraft may close; this process
keeps identity, membership, validation, hosted-world announcements and seeded content alive.

- **Depends on:** `core`, `engine`, `transport`, `storage`, `peer`.
- **Depended on by:** distribution packaging and `rust/nodera-app`; no Java module depends on it.
- **Docs:** [`docs/worker/`](../../docs/worker/Task.0.md)

## Architecture

```text
dev.nodera.headless
├── HeadlessPeerMain           process composition root; opens durable local state first
├── WorkerControlHandler       loopback control verbs and NODERA-STATE
├── WorldHostingService        persisted host/seed claims and tracker/rendezvous announces
├── WorldArchiveService        archive and committed-region piece seeding/fetch
├── WorldRegistryStore         worlds.dat
├── WorldKeyStore              per-world administrator private keys
├── WorldTombstoneStore        durable owner-authorized deletion records
└── LanSessionService          unmodified Open-to-LAN discovery and tunnel control

dev.nodera.peer.validation.WorkerValidationService
                               out-of-game committee participation composed by the worker
```

`HeadlessPeerMain.openLocalState` is the production startup seam for node identity, world registry
and world-key directory. `main` consumes the returned state before transport/runtime composition.

## Durable State

`LocalFiles` and peer's `PersistentIdentityStore` both delegate to
`storage.io.AtomicFileWriter.writeOwnerOnly`. On a POSIX `FileStore`, temporary files are created as
`0600` before secret bytes are written; a provider advertising POSIX but rejecting that attribute
fails closed. A non-POSIX store omits the inapplicable attribute. Failed writes or moves attempt to
delete the temporary file, with cleanup errors suppressed on the primary failure.

## Tests

173 Gradle test cases. Landmark coverage includes `WorldContinuityIT`, `WorkerQuorumValidationIT`,
`CompanionCrashSurvivalIT`, `SeedRegionVerbIT`, `WorldHostingPersistenceTest`, and
`HeadlessPeerMainStateTest`.

```bash
./gradlew :worker:test
```
