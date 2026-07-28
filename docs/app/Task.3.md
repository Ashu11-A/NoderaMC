# App Task 3 — Per-OS Packaging + CI

<!-- AI-AGENT-INSTRUCTION: Until this task lands, CI does NOT compile the app at all — never present
     the green workspace Rust gate as covering it. The bundle-vs-locate JVM decision must be
     documented and kept reversible; it has real size consequences for every player. Keep this
     header's status accurate. -->

**Status:** 🚧 IN PROGRESS
**Category:** app · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [app 1](Task.1.md)
**Consumed by:** players (the install target), [app 4](Task.4.md)

---

## Goal

Turn the app from something that builds on a developer's machine into something a player installs:
per-OS installers with icons, verified autostart on each platform, and a CI job that actually compiles
it.

## Status detail

Partially landed. A companion CI job exists that builds the app end to end — the UI is built and the
Rust release is compiled, with the worker distribution staged and bundled — and it surfaced and fixed
four real packaging gaps in the process: distribution staging, a gitignored UI build, gitignored
bundle icons, and a gitignored tray icon.

Remaining: per-OS installer bundles for each target platform, application icons across all sizes,
autostart acceptance verified on Windows, macOS, and Linux, and the documented bundle-versus-locate
decision for the Java runtime the worker needs.

## Dependencies

- [app 1](Task.1.md) — the application being packaged.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | CI job that builds the app (UI + Rust release + staged worker) | ✅ |
| 2 | Bundle icon and tray icon assets committed (not gitignored) | ✅ |
| 3 | Per-OS installer bundles | 🚧 |
| 4 | Application icons at every required size | 🚧 |
| 5 | Autostart acceptance on Windows, macOS, and Linux | ⬜ |
| 6 | Documented bundle-vs-locate decision for the Java 21 runtime | ⬜ |

## Design

**A workspace-excluded crate that CI never compiles is a crate that silently rots.** The exclusion is
justified — Tauri's native webkit dependencies would burden every unrelated Rust change — but the cost
must be paid somewhere, and the dedicated build job is where. The four gaps this job found were all of
the same shape: files that worked locally because they existed on disk, and failed in CI because they
were gitignored. That class of bug is invisible without a clean-checkout build.

**Bundling a JVM is a size decision with a support decision hiding inside it.** Bundling makes the
install self-contained and large; locating an existing Java 21 makes it small and makes "it does not
start" a support conversation about the user's system. The choice must be **documented and
reversible**, because the right answer may differ per platform and will certainly be revisited.

**Autostart needs per-platform acceptance, with no shortcut.** Login-item registration works
differently on each OS and fails quietly on all of them. This is the same discipline the project
applies to platform-dependent behaviour elsewhere: verify per platform, do not extrapolate from one.

**Icons are part of the product.** A tray application with a missing or placeholder icon is
indistinguishable from malware to a cautious user, and this app asks to run at login.

## Files

- `rust/nodera-app/tauri.conf.json`, `rust/nodera-app/icons/`
- `.github/workflows/` (the companion build job)

## Testing

- The CI job builds from a **clean checkout** — the only way to catch gitignored build inputs.
- Planned: installer smoke on each target OS; autostart registration verified after a real reboot or
  session restart per platform.
- Once the crate is in CI, its unit tests run there rather than only locally.

## Acceptance criteria

1. ✅ CI compiles the app from a clean checkout with the worker staged.
2. 🚧 Installers are produced for the target OSes with correct icons.
3. ⬜ Autostart is verified on Windows, macOS, and Linux.
4. ⬜ The bundle-vs-locate Java decision is documented and reversible.

## Limitations

None owned as a register row. The workspace-exclusion consequence is recorded in
[`LIMITATIONS.md`](LIMITATIONS.md) §C, with this task as its owner.
