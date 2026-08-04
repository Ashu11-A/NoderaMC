# `nodera-core`

<!-- AI-AGENT-INSTRUCTION: This crate is shared by desktop Tauri and native Android. It must never
     depend on Tauri, React, Compose, a window handle, or Android UI classes. Launch and tunnel
     lifetime belong here, not in either front end. Keep this file current when public commands,
     launch states, or platform seams change. -->

Shared application core for both Nodera front ends. It owns the worker control link, dashboard
model, settings and enforcement, tracker stores, telemetry consent, process supervision primitives,
and desktop launch lane. Shell-specific effects arrive through small traits or explicit parameters.

## Boundaries

- Desktop Tauri command handlers in `app/src/` are thin delegates.
- Android calls the same core through `android/bridge.rs` using one verb-and-JSON JNI surface.
- Network, consensus and world-storage authority remain in the Java worker. This crate coordinates
  the application; it is not a peer.
- Peers status and self-test query the commons namespace for the worker id received in
  `NODERA-STATE`; opening a screen never creates or announces an app-owned second identity.
- `launch/` is absent on Android/iOS because those platforms cannot run Java Minecraft.

## Launch Ownership

`LaunchCoordinator` owns one correlated attempt independently of React component lifetime. Stable
`LaunchTarget.id` values select installations; display names are not identity. World and session ids
are exact 64-character SHA-256 hex values and cross their current protocol alias explicitly.

Direct JVM launches own game-process lifetime and close their tunnel when that process exits.
Prism/MultiMC and official-launcher routes can observe only launcher handoff, so they retain the
tunnel until explicit leave. Cancellation invalidates the attempt before cleanup, preventing a
preparing task from spawning after the player leaves. Explicit leave remains in `closing` until the
worker confirms the tunnel absent; only then does state become `exited`.

Settings mutations are serialized by `SettingsHandle::update`. Android bridge verbs patch only the
fields their screen owns, and scheduled store refreshes replace an entry only when it still matches
the pre-fetch value, preserving concurrent add/remove/manual-refresh decisions.

## Tests

```bash
cargo test -p nodera-core
cargo fmt --check
cargo clippy -p nodera-core --all-targets -- -D warnings
```

The suite has 270 tests, including concurrent launch refusal, leave/spawn interleavings, stale-event
rejection, ambiguous CONNECT cleanup, delegated handoff, worker-presence verification, strict
identifiers and shutdown ownership.
