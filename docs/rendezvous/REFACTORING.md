# Rendezvous — Refactoring Register

<!-- AI-AGENT-INSTRUCTION: A working register of refactoring debt in the rendezvous modules
     (rendezvous/ + library/java/transport/.../dev/nodera/transport/rendezvous/). Source:
     build/jscpd/jscpd-report.json (2026-07-28) PLUS manual inspection. jscpd flagged ZERO duplicate
     blocks inside these modules, so every row below is a manual candidate and carries "—" under
     % duplicated. Re-run jscpd after each change and move a row out when its plan lands; do not
     delete a row, date-stamp its retirement. Scope excludes target/ and build/. -->

Source: `build/jscpd/jscpd-report.json` (detection 2026-07-28) + manual review of every file in
`rendezvous/src/` and `library/java/transport/src/{main,test}/java/dev/nodera/transport/rendezvous/`.
**jscpd flagged 0 duplicate blocks touching these modules** — the Rust clone percentage is 3.67%
workspace-wide but none of it lands here. The candidates below are therefore structural (god classes,
long methods, mechanical repetition) rather than copy-paste, and show "—" under % duplicated. Line
counts are from `build/loccount.txt`. Rows are sorted by % duplicated (all "—"), then by lines
descending.

| File | Lines | % duplicated | Duplicated-with | Refactor plan |
|---|---:|---|---|---|
| ~~`rendezvous/src/main.rs`, `limits.rs`, `register.rs`, `build.rs`~~ | ~~703~~ | ~~(cross-crate)~~ | ~~`nodera-tracker`, `nodera-telemetry` siblings~~ | **Completed 2026-08-06** (Plan 11 round 2, item 6): jscpd scoped to this crate alone saw none of this, which is why the register did not: the clones were *between* services. `Command`/`parse_args`/dispatch → `nodera_service::cli`; `shutdown_signal` + accept loop → `serve`; `RequestQuota` → `limits::Quota`; `IdentityBindings` + the freshness window → `admission`; `fresh_seed`/`random_node_id` → `identity::load_or_generate`; the byte-identical `build.rs` → `nodera-build`. |
| `rendezvous/src/wire.rs` | 868 | — | (no jscpd clone) | Largest file in the crate. `now_millis`/`read_frame`/`write_frame` and the accept `select!` moved to `nodera_service::{frame, serve}` on 2026-08-06; **the bridge did not and should not** — it is this service's only real concurrency logic. Extract it (+ `limits_of`) into a `CircuitBridge` module so the metered copy loop is testable in isolation, and move the 8 `#[tokio::test]` socket tests (~370 lines, ~42% of the file) into a sibling. `run_reserved` is a deeply nested `select!` over two streams — pull the `ControlEvent::Circuit` arm into its own fn so the in-flight guard handoff reads top-to-bottom. **Fixed 2026-08-06:** `limits_of` applied a constant idle timeout while `circuit_idle_timeout_seconds` was loaded, env-overridable and validated — an operator could set it, see it accepted, and change nothing. |
| `library/java/transport/src/main/java/dev/nodera/transport/rendezvous/RendezvousPeerTransport.java` | 704 | — | (no jscpd clone) | God class: holds identity + endpoints + 5 concurrent maps, and the accept loop, refresh loop, dispatch, circuit registry, drain-notice fanout, and candidate building. Extract three collaborators: `RendezvousRegistration` (`registerSelf` + `refreshLoop` + `selfCandidates`), `RelayAcceptor` (`acceptLoop` + `reserveAnywhere` + the backoff), and a `CircuitRegistry` wrapper around the `circuits`/`knownKeys`/`knownCandidates` maps + `circuitTo`/`startReader`. `dispatch` (line 254) then becomes a pure route-decision. |
| `rendezvous/src/service.rs` | 630 | — | (no jscpd clone) | `on_register` (lines 250-321) is one long branch handling quota → admit → registry outcome → NAT-note → observed-address reply. Pull the registry-outcome handling into a helper so the happy path reads as admit → store → observe. The 9-test `mod tests` (~230 lines) can move to `service/tests.rs` to shrink the production-read path. |
| `rendezvous/src/config.rs` | 470 | — | (no jscpd clone) | **Completed 2026-08-06** (Plan 11 round 2, item 6): `apply_env`'s 23 hand-written `overlay.set("key", &mut self.key)` calls are now a `nodera_service::env_overlay!` table naming the fields, and `ConfigError` + `load` come from `nodera_service::config`. `validate` is still a long sequence of independent bound checks; it is per-service **values**, not per-service logic, and a table-driven loop is a fair next step. |
| `library/java/transport/src/main/java/dev/nodera/transport/rendezvous/EndToEndCipher.java` | 246 | — | (no jscpd clone) | `handshake` (line 74) mixes hello construction, peer-identity check, ECDH, and key derivation; split into `writeHello`/`readAndVerifyPeerHello`/`deriveSession`. The private `compare` (line 174) is a lexicographic byte compare that almost certainly already lives in `core/Bytes` — delegate rather than maintain a second copy. |
| `library/java/transport/src/main/java/dev/nodera/transport/rendezvous/RendezvousClient.java` | 223 | — | (no jscpd clone) | `exchange`, `reserve`, `openConnect`, and `connect` each open a socket, set timeouts, write a frame, and close on failure with the same try/except shape. A `withSocket(endpoint, fn)` helper (open + TcpNoDelay + timeouts + close-on-drop) would collapse the four call sites to their unique payload. |

## Sequencing

Re-ranked 2026-08-06. Item 2 below shrank rather than landed as written, because the cross-crate wave
(Plan 11 round 2 item 6) took the framing helpers with it; what is left of `wire.rs` is the bridge and
its tests.

1. **Split `RendezvousPeerTransport` (704 LOC).** Unchanged, and still first: it is the most entangled
   file in the category and the one every other change risks regressing. Extract
   `RendezvousRegistration` / `RelayAcceptor` / `CircuitRegistry`. The pinning tests
   (`BootstrapAddressHasNoNodeIdTest`, `CallerRouteIsDirectTest`, `RendezvousRelayIT`) hold the
   behaviour while the structure moves.

2. **Extract `wire.rs`'s circuit bridge + relocate its socket tests.** The bridge copy-loop is the one
   piece of real concurrency logic worth isolating, and the test module is ~42% of the file.

3. ~~**Mechanize `config.rs::apply_env`.**~~ Landed 2026-08-06 as `nodera_service::env_overlay!`, and
   for exactly the reason this row gave: the drift surface it removes is the one the
   `every_config_key_has_an_environment_override` test could only catch *after* the fact.

4. **Split `service.rs` tests out + flatten `on_register`.** Unchanged.

5. **`RendezvousClient` socket-lifecycle helper.** Unchanged: smallest payoff, lowest risk, last.

A note on what is **not** here: `RelayCircuitClient.readIncoming` (137 LOC) carries two distinct
concerns (drain-notice verify loop + circuit-accept proof check) but they are genuinely
sequential and well-named; splitting them would add a type without clarifying the code. `punch.rs`'s
`PunchOutcomes` inference is subtle but compact (135 lines) and its comments are load-bearing — leave
it alone.
