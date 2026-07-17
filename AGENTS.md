# AGENTS.md — NoderaMC

## Build & test (pure-Java Phase 0 modules)
- `./gradlew check`        — compile + all unit tests (the gate)
- `./gradlew build`        — check + assemble jars
- `./gradlew :core:test`   — one module's tests (substitute module name)
- `./gradlew check --rerun-tasks` — force tests to re-run (ignore up-to-date caching)

## Environment notes
- Host JDK is **25**; Task 0 pins Java 21. The pure-Java modules compile against the host JDK and
  use only Java 21-era features (records, sealed interfaces, virtual threads, pattern matching), so
  they remain source-compatible when the 21 toolchain is restored.
- `simulation/ForbiddenApiTest` is `@Disabled`: ArchUnit 1.3's bundled ASM cannot parse JDK 25 class
  files (v69) and silently imports 0 classes. Re-enable when the Java-21 toolchain is pinned.
  Determinism is still enforced by `simulation/DeterminismPropertyTest`.

## Layering (Task 0 §4)
- `core` → JDK only. `simulation`/`protocol`/`consensus`/`transport-api`/`storage-api` → `core`.
- `testkit` → all of the above.
- NeoForge-bound modules (`transport-neoforge`, `neoforge-mod`) and later modules
  (`storage-rocksdb`, `storage-client`, `peer-runtime`, `transport-libp2p`, `integration-tests`)
  are declared as comments in `settings.gradle.kts`; not yet onboarded.

## Frozen contracts (do not change without a version bump)
- Canonical encoding: `core/crypto/CanonicalWriter` + `CanonicalReader` + `Encodable` + `TypeTags`.
  Every `Encodable.encode` starts with `writeU16(typeTag); writeU16(ENCODING_VERSION);`.
- `core/Bytes` is the single byte[] value type (use everywhere, never raw byte[] in records).
- Wire tags: `protocol/codec/MessageCodec` (append-only, never renumber).
- Hash/sign: `core/crypto/HashService` (SHA-256 over canonical encoding) + `SignatureService`
  (Ed25519 verify; signing lives on `core/identity/NodeIdentity`).

---

## ⚠️ Non-negotiable agent disciplines (this file IS the agent memory)

These three rules apply to EVERY session and EVERY commit, no exceptions:

1. **Run tests before committing.** Execute `./gradlew check`. If it is red, you do NOT commit.
   If you cannot fix a failure immediately, open a `bug` issue (`.github/ISSUE_SYSTEM.md`) and stop.
2. **Update `README.md` + `Tested.md` in the same commit** that changes outcomes: recompute the
   progress-bar percentage, the module status table, the roadmap ticks, and the test counts.
   Keep every `<!-- AI-AGENT-INSTRUCTION: ... -->` comment.
3. **Use the commit-message standard** (see README.md → "Commit message standard"):
   ```
   <emoji> [<overall-percentage>%] <change type>: <short description in English>
   ```
   Reference the issue: `refs #N` while working, `fixes #N` / `closes #N` to close.

## GitHub issue workflow (see `.github/ISSUE_SYSTEM.md` for the full rules)
- GitHub issues are the source of truth. Every `docs/Task.N.md` has an issue; every detected
  problem becomes a `bug` issue before a regression reaches `main`.
- One task = one branch (`<type>/<slug>-#<issue>`) = one PR.
- A task is "done" only when: `./gradlew check` green, acceptance criteria evidenced in the PR,
  README/Tested updated, and the issue closed via `Closes #N`.
- The orchestrator reviews every PR; unsatisfactory work stays open and is revised on the same
  branch until it passes.

## The debugger (issue #17)
A standing task (`#17`) owns the **Nodera debugger**: an integration harness that boots real server
instances over `LoopbackTransport`, drives P2P scenarios for every implemented lane (block-break
validation, redstone, entities, …), does real-time execution debugging, counts passing tests, and
emits debug logs + coverage reports — driving toward 100% coverage. Each lane task (5–16) adds
scenarios to it.
