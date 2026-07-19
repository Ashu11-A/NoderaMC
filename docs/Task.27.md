# Task 27 — Monorepo Restructure + Rust Workspace Foundation (cross-cutting, urgent)

**Phase:** 0–8 (build architecture; executes [`MONOREPO.md`](./MONOREPO.md)) ·
**Depends on:** nothing (do first — Tasks 28/29 are blocked on it) ·
**Modules:** every Gradle module (path move only), `rust/` cargo workspace (new),
`rust/nodera-codec` (new), `fixtures/` (new shared dir), `.github/` CI, `build-logic`

## Goal

Reorganize the repository into a **polyglot monorepo** so the Java mod/peer code and the new Rust
network services (Tasks 28/29) live side by side with one CI gate, one fixture set, and no
duplicated protocol definitions. Deliver the **`nodera-codec`** Rust crate — a bit-exact port of
the frozen canonical encoding (`CanonicalWriter`/`CanonicalReader`/`Bytes`/type tags) proven
against the *same golden fixture files* the Java side uses — so Tasks 28/29 build services on a
verified wire foundation instead of re-inventing serialization.

The normative step-by-step migration instructions live in [`MONOREPO.md`](./MONOREPO.md); this
task executes them. `MONOREPO.md` is deleted (absorbed into `AGENTS.md`/`README.md`) when this
task closes.

## Context

- Today all 20 Gradle modules sit at the repo root next to `docs/`, `scripts/`, and run dirs.
  Adding cargo crates at the root would interleave two build systems in one directory and make
  ownership/CI routing unreadable.
- Tasks 28 (Rust tracker) and 29 (Rust rendezvous relay) introduce long-running **standalone
  binaries** that must speak the frozen Nodera wire contract. The encoding is a consensus
  contract (Task 0 §4, Plan §3.7): two implementations are acceptable only if conformance is
  mechanically proven — hence the shared `fixtures/` golden files and the cross-language
  round-trip harness delivered here.
- The layering rules (Task 0 §4) gain one clause: **Rust crates never contain game or consensus
  logic** — they are infrastructure (discovery, relay). All authority stays with the Java peers
  and their committees; services are introducers/forwarders that peers verify, never trust.

## Target layout (summary — full detail in `MONOREPO.md`)

```
nodera/
├── java/                    # ← every existing Gradle module moves here unchanged
│   ├── build-logic/ core/ protocol/ simulation/ consensus/ committee/ coordinator/
│   ├── fallback/ shadow-validation/ distribution/ diagnostics/ peer-runtime/
│   ├── storage-api/ storage-eventsourced/ storage-rocksdb/ storage-client/
│   ├── testkit/ transport-api/ transport-socket/ transport-neoforge/ neoforge-mod/
├── rust/                    # cargo workspace (workspace Cargo.toml + rust-toolchain.toml)
│   ├── nodera-codec/        # canonical encoding port + Ed25519 verify + tag registry mirror
│   ├── nodera-tracker/      # Task 28 (empty placeholder crate after this task)
│   └── nodera-rendezvous/   # Task 29 (empty placeholder crate after this task)
├── fixtures/                # golden canonical-encoding files, shared Java ⇄ Rust
├── docs/                    # unchanged
├── scripts/                 # unchanged + new `scripts/build-all.sh`
├── settings.gradle.kts      # stays at root; include paths become ":java:<module>" style
└── .github/                 # CI runs BOTH: ./gradlew check AND cargo fmt/clippy/test
```

## Implementation details — migration (see `MONOREPO.md` for the ordered checklist)

- **`git mv` everything** (history preserved), one dedicated `chore` commit, no code edits mixed
  in. `settings.gradle.kts` keeps module *names* stable (`:core`, `:peer-runtime`, …) via
  `project(":core").projectDir = file("java/core")`-style mapping so existing Gradle invocations
  (`./gradlew :core:test`) and `build.gradle.kts` files keep working unmodified.
- `gradlew`, `gradle.properties`, `settings.gradle.kts` stay at the root (the root remains the
  Gradle entry point); `build-logic` moves with its siblings and is re-pointed via
  `pluginManagement { includeBuild("java/build-logic") }`.
- **Toolchain pins:** `rust-toolchain.toml` pins the Rust channel the same way Task 0 §3 pins
  Java 21; version bumps are single dedicated commits. Workspace-level `Cargo.toml` pins
  dependency versions (`tokio`, `ed25519-dalek`, `thiserror`, …) — the version-catalog discipline
  from `gradle/libs.versions.toml`, mirrored.
- **CI:** the existing check workflow gains a `rust` job (`cargo fmt --check`, `cargo clippy -D
  warnings`, `cargo test`) plus a release-artifact job (`cargo build --release` for
  `nodera-tracker`/`nodera-rendezvous`, uploaded per-tag). A red cargo job blocks merge exactly
  like a red `./gradlew check`.

## Implementation details — `nodera-codec` (the load-bearing half)

- Ports, bit-exactly: `CanonicalWriter`/`CanonicalReader` primitives (fixed integer widths,
  field order, sorted collections), `Bytes`, the `writeU16(typeTag); writeU16(ENCODING_VERSION)`
  frame prefix, and a **read-only mirror of the tag registry** (`TypeTags` + `MessageCodec` wire
  tags). The registry mirror is generated or asserted against the Java source in CI — appending a
  tag on one side without the other fails the build (append-only discipline, never renumber).
- Ed25519 **verification** (via `ed25519-dalek`) for signed records; the services never hold
  signing keys — signing remains a Java-peer (`NodeIdentity`) capability.
- **Golden-file conformance:** the Java `testkit` `FixtureWriter` emits fixtures for an initial
  message set (at minimum `TrackerQuery`/`TrackerResponse`/`InventoryAdvertisement`, tags 27–29,
  plus core primitives) into `fixtures/`; `nodera-codec` tests decode + re-encode each fixture and
  assert byte identity. Java-side golden tests re-point at the same moved directory.
- Length-prefixed TCP framing helper matching `transport-socket`'s framing, so Tasks 28/29 speak
  to existing Java transport code without a new framing layer.

## Potential limitations

- None staged in `LIMITATIONS.md` — this is build architecture, not player-visible capability.
  (Task 28 owns L-44; Task 29 owns the re-pointed L-23/L-27.)
- Until Task 28/29 land, the `rust/` service crates are placeholders; the codec crate is the only
  one with substance after this task.

## Acceptance criteria

1. `./gradlew check` green from the new layout, including `./gradlew :core:test` module
   addressing; the assembled mod jar is byte-comparable in content to a pre-move build.
2. `cargo test` green: `nodera-codec` decodes + re-encodes every file in `fixtures/` byte-exactly;
   tag-registry mirror assertion passes.
3. CI runs both build systems; a deliberately broken fixture fails the Rust job (proven once,
   reverted).
4. `git log --follow` shows preserved history for a sampled moved file (e.g. `core`'s
   `CanonicalWriter`).
5. Docs updated in the same commit: `README.md` project layout, `AGENTS.md` build commands,
   `docs/Prompt.base.md`; `MONOREPO.md` deleted; task-spec path references handled per the
   rewrite note in `README.md`/`docs/Roadmap.md`.
6. No Java behavior change: zero edits under any `src/` directory in the migration commit.
