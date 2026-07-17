# AGENTS.md — NoderaMC

## Build & test (pure-Java Phase 0 modules)
- `./gradlew check`        — compile + all unit tests (the gate)
- `./gradlew build`        — check + assemble jars
- `./gradlew :core:test`   — one module's tests (substitute module name)
- `./gradlew check --rerun-tasks` — force tests to re-run (ignore up-to-date caching)

## Environment notes
- Host JDK is **25**; Task 0 pins Java 21. All modules compile with `--release 21` (Java 21
  bytecode, v65) so the `org.gradle.jvm.version` attribute is consistent across module
  boundaries — the NeoForge modules are forced to a Java 21 toolchain by ModDevGradle, so a
  25/21 mismatch breaks project dependency resolution. The test JVM is still the host JDK 25.
- `simulation/ForbiddenApiTest` is `@Disabled`: it was originally disabled because ArchUnit 1.3's
  bundled ASM could not parse JDK 25 class files (v69). The repo now emits v65 bytecode, so the
  parseability blocker is gone — re-enabling is tracked as a follow-up (still needs a dedicated
  verification pass because tests run on the JDK 25 JVM). Determinism is meanwhile enforced by
  `simulation/DeterminismPropertyTest`.

## Layering (Task 0 §4)
- `core` → JDK only. `simulation`/`protocol`/`consensus`/`transport-api`/`storage-api` → `core`.
- `testkit` → all of the above.
- NeoForge-bound modules (`transport-neoforge`, `neoforge-mod`) are onboarded via the
  `nodera.neoforge-mod` convention (ModDevGradle → NeoForge 21.1.77, Java 21 toolchain). They
  compile and assemble a jar; `runServer`/`runClient` acceptance is deferred to a GUI env.
  Later modules (`storage-rocksdb`, `storage-client`, `peer-runtime`, `transport-libp2p`,
  `integration-tests`) are still declared as comments in `settings.gradle.kts`.

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

## Base orientation prompt
[`docs/Prompt.base.md`](docs/Prompt.base.md) is a paste-in base prompt: the ordered list of files
to read first, the project pattern, where progress lives, and how the issue workflow operates. Point
new contributors/agents at it.
