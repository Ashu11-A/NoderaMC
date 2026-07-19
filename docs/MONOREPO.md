# MONOREPO.md — Restructuring Nodera into a Polyglot Monorepo

<!-- AI-AGENT-INSTRUCTION: This file is the normative migration instruction set for Task 27
     (docs/Task.27.md). It exists until Task 27 closes; then its durable content is absorbed into
     AGENTS.md + README.md and this file is deleted. Execute the steps in order; the migration
     commit contains ONLY moves + build wiring — never mix code changes in. -->

## Why (read this before touching anything)

Tasks 28/29 introduce **Rust service binaries** (`nodera-tracker`, `nodera-rendezvous`) that must
speak the frozen Nodera wire contract. Dropping cargo crates into the current flat root — where 20
Gradle modules already sit next to `docs/`, `scripts/` and run dirs — would interleave two build
systems in one directory, blur ownership, and give CI no clean routing. The monorepo layout gives:

- **One repo, two toolchains, one gate** — `./gradlew check` + `cargo test` both required green.
- **One wire contract** — shared `fixtures/` golden files prove the Rust codec byte-exact against
  the Java `CanonicalWriter`/`CanonicalReader`; neither side can drift silently.
- **No duplicated protocol definitions** — the tag registry lives in Java (frozen, append-only);
  the Rust mirror is asserted against it in CI.
- **Less root clutter** — Java modules stop competing with docs/tooling for top-level attention.

## Target layout

```
nodera/
├── java/                          # ALL existing Gradle modules, moved verbatim
│   ├── build-logic/  core/  protocol/  simulation/  consensus/  committee/
│   ├── coordinator/  fallback/  shadow-validation/  distribution/  diagnostics/
│   ├── peer-runtime/  storage-api/  storage-eventsourced/  storage-rocksdb/
│   ├── storage-client/  testkit/  transport-api/  transport-socket/
│   ├── transport-neoforge/  neoforge-mod/
├── rust/
│   ├── Cargo.toml                 # [workspace] members = codec, tracker, rendezvous
│   ├── rust-toolchain.toml        # pinned channel (Task 0 §3 discipline)
│   ├── nodera-codec/              # canonical encoding + Ed25519 verify + tag mirror (Task 27)
│   ├── nodera-tracker/            # Task 28
│   └── nodera-rendezvous/         # Task 29
├── fixtures/                      # golden canonical-encoding files (Java ⇄ Rust conformance)
├── docs/                          # unchanged
├── scripts/                       # unchanged + build-all.sh
├── settings.gradle.kts            # stays at root (Gradle entry point unchanged)
├── gradlew / gradlew.bat / gradle/  gradle.properties  build.gradle.kts
└── .github/                       # CI: java job + rust job + release-artifact job
```

**Module names do not change.** `settings.gradle.kts` maps names to the new dirs
(`project(":core").projectDir = file("java/core")` style), so `./gradlew :core:test`, every
`build.gradle.kts`, and every doc snippet that names a module keeps working. Only *paths* move.

## Migration steps (Task 27 executes these, in order)

1. **Freeze**: green `./gradlew check` on main; note the assembled mod-jar content listing
   (`unzip -l`) for the post-move comparison.
2. **Move Java** (one commit, `git mv` only):
   - `git mv build-logic core protocol simulation consensus committee coordinator fallback
     shadow-validation distribution diagnostics peer-runtime storage-api storage-eventsourced
     storage-rocksdb storage-client testkit transport-api transport-socket transport-neoforge
     neoforge-mod java/` (create `java/` first).
   - Run dirs (`run/`, `a/`, logs) are dev residue — leave untracked/ignored, do not move.
3. **Re-wire Gradle** (same commit):
   - `settings.gradle.kts`: `pluginManagement { includeBuild("java/build-logic") }`; for each
     module keep `include(":<name>")` and add the `projectDir` mapping to `java/<name>`.
   - Delete the commented `transport-libp2p` placeholder line while here (superseded by
     `transport-rendezvous`, Task 29 — recorded in `LEGACY.md`).
   - Grep check: no `build.gradle.kts` references a sibling by relative path (they reference by
     project name — expected to be zero edits).
4. **Scaffold Rust** (same or immediate follow-up commit):
   - `rust/Cargo.toml` workspace + `rust-toolchain.toml` (pin current stable; bumps are single
     dedicated commits, same rule as the Java 21 pin).
   - `cargo new --lib rust/nodera-codec`; `cargo new rust/nodera-tracker`;
     `cargo new rust/nodera-rendezvous` (service crates stay placeholders until Tasks 28/29).
   - Workspace-level dependency pinning (`tokio`, `ed25519-dalek`, `thiserror`, `serde`, `toml`)
     — the `libs.versions.toml` discipline mirrored.
5. **Shared fixtures**:
   - Create `fixtures/`; re-point the Java `testkit` `FixtureWriter`/`FixtureReader` base
     directory to it; move existing golden files with `git mv`.
   - `nodera-codec` tests read the same directory (path resolved from the workspace root).
6. **CI**:
   - Existing check workflow: add a `rust` job — `cargo fmt --check`, `cargo clippy --all-targets
     -D warnings`, `cargo test` — required for merge, same standing as the Gradle job.
   - Release job: `cargo build --release` for both service crates; upload artifacts per tag.
   - Path filters optional later; start with both jobs always-on (cheap while crates are small).
7. **Docs + agent memory** (same commit as 2–3):
   - `README.md` project-layout block, `AGENTS.md` build commands (add `cargo test`,
     `scripts/build-all.sh`), `docs/Prompt.base.md` §1.
   - `scripts/build-all.sh`: `./gradlew check && (cd rust && cargo test)`.
8. **Verify** (Task 27 acceptance): `./gradlew check` green; module addressing
   (`:core:test`) green; mod-jar content matches step 1 listing; `cargo test` green;
   `git log --follow java/core/...` shows pre-move history; CI green on both jobs.

## Task-spec rewrite note (binding)

Every implemented task spec (Tasks 1–16, 18–26) contains folder-structure blocks written against
the flat layout (`peer-runtime/src/main/java/...`). After Task 27 lands, those paths acquire a
`java/` prefix. **All affected task files must be rewritten for the monorepo architecture** — a
mechanical path-prefix pass plus, for Tasks 1 (build scaffolding), 4 (transport layout), 10, 20
(already semantically rewritten for Tasks 28/29), a substantive review. Tracked as a checklist
item inside Task 27; the same note lives in `README.md` (Roadmap section) and `docs/Roadmap.md`
§5 so it cannot be lost. Do **not** rewrite specs before the move lands — paths would lie twice.

## Rollback

The migration commit is moves + build wiring only, so `git revert` of that single commit restores
the flat layout. Nothing else may depend on the new paths until the revert window passes (one
green CI cycle + one local `runServer` smoke where available).
