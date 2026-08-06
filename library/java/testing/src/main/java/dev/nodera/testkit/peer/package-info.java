/**
 * The in-JVM mesh an integration test stands up — one harness, not twenty-five.
 *
 * <p>{@link dev.nodera.testkit.peer.PeerTestHarness} owns a {@code LoopbackNetwork} and the
 * teardown of everything built on it, and hands out three node shapes: a committee member
 * ({@link dev.nodera.testkit.peer.ValidationNode}), an always-on worker behind its real control
 * endpoint ({@link dev.nodera.testkit.peer.WorkerNode}) and a peer carrying one gossip service
 * ({@link dev.nodera.testkit.peer.MeshNode}). {@link dev.nodera.testkit.peer.RegionFixtures} holds
 * the snapshot and action values every committee test starts from, and
 * {@link dev.nodera.testkit.peer.Await} is the one place waiting is written.
 *
 * <p>This is the {@code ControlSocketHarness} / {@code LoopbackMeshHarness} pair that
 * {@code docs/peer/REFACTORING.md} §2 sequences first, plus the committee-lane factory that nine
 * validation ITs had each written out. It is <b>not</b> the live-process harness:
 * {@link dev.nodera.testkit.harness} launches real binaries and drives real Minecraft clients, and
 * this package deliberately reuses its {@link dev.nodera.testkit.harness.ControlClient} rather than
 * adding a second way to speak the control wire.
 *
 * <p>Rule for anyone extending it: where two tests disagree, the difference becomes a named
 * parameter. A default that quietly settles a disagreement leaves both tests green and one of them
 * meaningless.
 */
package dev.nodera.testkit.peer;
