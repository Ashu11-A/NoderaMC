# `java/testing`

<!-- AI-AGENT-INSTRUCTION: This module is a TEST LIBRARY consumed by other modules' test source sets.
     Nothing here may leak into production code, and nothing here may be Minecraft-aware. Fixture
     files under fixtures/wire/ are GENERATED, never hand-edited — regenerate them through the writer
     rather than adjusting bytes. Update this file when a helper is added or its contract changes. -->

**The shared test library.** Small, deliberately: the project's testing strategy puts almost all of
its weight on real objects, real sockets, and real processes, so the helpers here exist only where a
substitute genuinely buys something.

- **Depends on:** `core`, `engine`, `transport`.
- **Depended on by:** the test source sets of the other Java modules.
- **Docs:** [`docs/engine/TESTING.md`](../../docs/engine/TESTING.md) ·
  [`docs/network/TESTING.md`](../../docs/network/TESTING.md)

---

## Architecture

```
dev.nodera.testkit
├── LoopbackTransport      an in-JVM PeerTransport for multi-peer scenarios without sockets
├── FakeRegion             region/snapshot builders for engine and consensus tests
├── FixtureWriter/Reader   golden canonical frames — emit from Java, compare byte-exactly
├── harness/               the live stack: processes, ports, readiness, log evidence
├── suite/ + report/       the scenario SPI, its outcomes, and the one run report
└── scenario/             one class per acceptance scenario, composing the harness
```

## The harness owns capabilities; a scenario owns policy

Everything a scenario needs from the MACHINE lives in `harness/`, and a scenario support class may
only compose it. That boundary is not tidiness. Five capabilities lived one layer up under
`// HARNESS-GAP` markers, so one behaviour was described in two places and the error-audit rule was
written out three times — a benign cause added to one copy fixed a third of the suites and left the
rest.

- `LiveStack` starts every process and **proves each one is answering before it hands it back**:
  services by socket probe, workers by their control socket, the dedicated server by its game port
  *and* an RCON round trip, a client by the existence of a game JVM carrying that run's
  `<run>RunProgramArgs` token. Every failure names the process, its exit status and its log tail.
  It also stages a client's `options.txt` and amends a world's `nodera-server.toml`.
- `ManagedProcess` addresses processes it did NOT fork, by a token on their command line, reading
  `/proc` directly because `ProcessHandle.info().commandLine()` truncates at 4096 bytes and the
  token sits past it.
- `LogWatcher` is the one assertion primitive: waits (with a mark, a guard, a blocked-screen watch,
  and a host-lag explanation for an expiry) and window reads after a mark.
- `Requirements` classifies why a scenario did not run. A missing display is the machine; a missing
  artefact the runner builds is the harness. The tool exits non-zero on either, and only the first
  can be accepted with `--allow-skips`.

## Why it is shaped this way

**`LoopbackTransport` implements the real seam.** It is not a mock: it is a `PeerTransport` like any
other, so a multi-peer scenario written against it exercises the same call paths as a socket run.
Scenarios that need real network behaviour use real TCP instead — several deliberately do, because
handshakes, bind failures, and teardown reasons only exist on a real socket.

**Fixtures are generated, never authored.** `FixtureWriter` emits golden frames from the Java
canonical encoder; `rust/nodera-codec` re-encodes them byte-exactly. Hand-editing a fixture would
break the only mechanism that keeps the two implementations honest, so a fixture change is always a
regeneration.

**Deliberately thin.** The project's crash, adversarial, and consensus proofs use forcibly killed
JVMs, genuinely adversarial peers on the wire, and the real service binaries. Every helper added here
is a small step away from that, so the bar for adding one is high.

## Rules

- Nothing here may be referenced from production code.
- No Minecraft or NeoForge types.
- A helper that would let a test pass without exercising the real seam does not belong here.

## Tests

74 tests covering the helpers themselves — a broken test library produces false confidence
everywhere else, and this module is the one that decides what "the run passed" means.

```bash
./gradlew :testing:test
```
