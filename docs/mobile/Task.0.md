# Mobile Task 0 — Charter

<!-- AI-AGENT-INSTRUCTION: The charter states what the mobile build IS and what it must never claim.
     Rule 1 is load-bearing: if the phone ever stops running the real worker, this file must say so
     before any other document is updated. -->

**Status:** ✅ COMPLETED · **Category:** mobile · **Last audit:** 2026-07-26

---

## What the mobile build is

The same node as the desktop, on a phone. The Android app **runs the real Java peer worker**
in-process — the same `dev.nodera.headless.HeadlessPeerMain`, with the same identity, transport,
validation lane and control endpoint — loaded from the APK's assets by `NoderaWorker.kt`. The Rust
app talks to it over `127.0.0.1:25610` with the same code the desktop uses.

## Rules

1. **No substitute peer.** If the worker cannot run, the app reports the node as offline. It does
   not quietly fall back to something smaller and keep calling itself a peer. (An earlier design did
   exactly that — a Rust discovery-plane peer — and it was removed when the worker was made to run.)
2. **Nothing invented on screen.** Every number comes from the worker's own state. Unknown renders
   as `—`.
3. **The OS decides, and is quoted.** Battery restrictions, storage permissions and folder access are
   Android's to grant. The app detects, explains, and links to the setting; it never asserts an
   outcome it did not verify with a real write or a real read.
4. **One layout question is about the window, another is about the binary.** `useIsCompact` decides
   density; `useIsMobileBuild` decides which features exist. Conflating them would let a narrow
   desktop window lose features it genuinely has.
5. **Material You means generated.** Every colour is derived from a source colour through HCT tonal
   palettes. No hand-picked palette that merely resembles one.

## Tasks

| Task | Title | Status |
|---|---|---|
| [1](Task.1.md) | The Android build, and the worker inside it | ✅ COMPLETED |
| [2](Task.2.md) | The interface: Material You, and what a phone may be asked | ✅ COMPLETED |
| [3](Task.3.md) | The phone in the mesh: proving it receives from the Linux peers | ✅ COMPLETED |

Operational guide — building, Wi-Fi debugging, the scripts, the test procedures:
[`TESTING.md`](TESTING.md).

## Boundaries

* The worker's lifetime is the app's lifetime: Android does not let an app spawn a VM, so the worker
  is a thread in this process.
* The APK targets one ABI at a time (`aarch64` by default).
* The LAN tunnel lane is present in the worker but untested on Android; a phone is not where an
  unmodified Minecraft runs.
