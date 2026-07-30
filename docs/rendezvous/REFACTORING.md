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
| `rendezvous/src/wire.rs` | 879 | — | (no jscpd clone) | Largest file in the crate. Extract the byte-copy `bridge` (+ `limits_of`) into a `CircuitBridge` module so the metered copy loop is reusable and testable in isolation, and move the 8 `#[tokio::test]` socket tests (~370 lines, ~42% of the file) into a `tests/` sibling or `wire/tests.rs`. `run_reserved` (lines 242-358) is a deeply nested `select!` over two streams — pull the `ControlEvent::Circuit` arm into its own fn so the in-flight guard handoff reads top-to-bottom. |
| `library/java/transport/src/main/java/dev/nodera/transport/rendezvous/RendezvousPeerTransport.java` | 704 | — | (no jscpd clone) | God class: holds identity + endpoints + 5 concurrent maps, and the accept loop, refresh loop, dispatch, circuit registry, drain-notice fanout, and candidate building. Extract three collaborators: `RendezvousRegistration` (`registerSelf` + `refreshLoop` + `selfCandidates`), `RelayAcceptor` (`acceptLoop` + `reserveAnywhere` + the backoff), and a `CircuitRegistry` wrapper around the `circuits`/`knownKeys`/`knownCandidates` maps + `circuitTo`/`startReader`. `dispatch` (line 254) then becomes a pure route-decision. |
| `rendezvous/src/service.rs` | 630 | — | (no jscpd clone) | `on_register` (lines 250-321) is one long branch handling quota → admit → registry outcome → NAT-note → observed-address reply. Pull the registry-outcome handling into a helper so the happy path reads as admit → store → observe. The 9-test `mod tests` (~230 lines) can move to `service/tests.rs` to shrink the production-read path. |
| `rendezvous/src/config.rs` | 538 | — | (no jscpd clone) | `apply_env` (lines 168-233) is ~25 hand-written `overlay.set("key", &mut self.key)` calls — one per field, pure mechanical duplication and the exact place a new config key forgets its env twin (the `every_config_key_has_an_environment_override` test exists to catch this, but a macro/helper that takes `(name, field)` pairs would remove the failure mode). `validate` (lines 258-340) is a long sequence of independent bound checks that could be a table-driven loop. |
| `library/java/transport/src/main/java/dev/nodera/transport/rendezvous/EndToEndCipher.java` | 246 | — | (no jscpd clone) | `handshake` (line 74) mixes hello construction, peer-identity check, ECDH, and key derivation; split into `writeHello`/`readAndVerifyPeerHello`/`deriveSession`. The private `compare` (line 174) is a lexicographic byte compare that almost certainly already lives in `core/Bytes` — delegate rather than maintain a second copy. |
| `library/java/transport/src/main/java/dev/nodera/transport/rendezvous/RendezvousClient.java` | 223 | — | (no jscpd clone) | `exchange`, `reserve`, `openConnect`, and `connect` each open a socket, set timeouts, write a frame, and close on failure with the same try/except shape. A `withSocket(endpoint, fn)` helper (open + TcpNoDelay + timeouts + close-on-drop) would collapse the four call sites to their unique payload. |

## Sequencing

The top five, in the order they should land, with the reason each comes before the next:

1. **Split `RendezvousPeerTransport` (704 LOC).** It is the most entangled file in the category and the
   one every other change risks regressing: accept loop, refresh loop, dispatch, and the circuit map
   all share state. Extracting `RendezvousRegistration` / `RelayAcceptor` / `CircuitRegistry` first
   gives the later refactors smaller, testable surfaces to work against. The pinning tests
   (`BootstrapAddressHasNoNodeIdTest`, `CallerRouteIsDirectTest`, `RendezvousRelayIT`) hold the
   behaviour while the structure moves.

2. **Extract `wire.rs` circuit bridge + relocate its socket tests (879 LOC).** The bridge copy-loop is
   the one piece of real concurrency logic worth reusing and isolating, and the test module is ~42% of
   the file's length — moving it is the cheapest single readability win in the crate. Do this after (1)
   because the Java accept path is the bridge's only caller and should already be cleaner.

3. **Mechanize `config.rs::apply_env`.** This is low-risk and high-value: the failure mode it removes
   (a new key with no env twin) is exactly what the `every_config_key_has_an_environment_override`
   test exists to catch after the fact. A `(name, field)` table-driven setter removes the drift
   surface entirely. Land it early because every later config addition benefits.

4. **Split `service.rs` tests out + flatten `on_register`.** After the wire/transport splits, the
   service file is the remaining 600+ LOC unit. Moving its 9 inline tests to a sibling and pulling
   the registry-outcome branch out of `on_register` are independent and both purely local.

5. **`RendezvousClient` socket-lifecycle helper.** Smallest payoff and lowest risk; do it last, once
   the transport split has stabilised the caller. Removes the four-way open/write/close repetition
   without changing any wire byte.

A note on what is **not** here: `RelayCircuitClient.readIncoming` (137 LOC) carries two distinct
concerns (drain-notice verify loop + circuit-accept proof check) but they are genuinely
sequential and well-named; splitting them would add a type without clarifying the code. `punch.rs`'s
`PunchOutcomes` inference is subtle but compact (135 lines) and its comments are load-bearing — leave
it alone.
