/**
 * Discovery: the tracker role, the peer directory, the archive inventory, and multi-bootstrap
 * (Task 20) — the <b>control plane</b> above Task 19's data plane.
 *
 * <h2>The tracker is a role, not a server</h2>
 *
 * <p>Any {@code FULL_ARCHIVE}/{@code BOOTSTRAP}-capable peer can run
 * {@link dev.nodera.peer.discovery.TrackerService}. The dedicated server is the <i>preferred</i>
 * tracker, never the only one — the same "preferred but not only" discipline the bootstrap peer
 * already follows. This works because a tracker's answers are advisory: it can lie about who is
 * online, but not about state, because every manifest and checkpoint verifies by hash. The cost of
 * a lying tracker is a wasted round trip.
 *
 * <h2>How a node gets in</h2>
 *
 * <p>{@link dev.nodera.peer.discovery.PeerDiscoveryService} over
 * {@link dev.nodera.peer.discovery.TrackerClient} and
 * {@link dev.nodera.peer.discovery.RendezvousDirectory}: configured routes are asked for the
 * world's peers, candidates are dialled direct-first, and the service directory supplies a
 * replacement when a relay drains. A second resolver — a cached-peer redial plus a signed
 * invitation blob a friend pastes — was written for Task 20 and never reached from a shipping
 * entry point; it was deleted on 2026-08-06 (Plan 11 round 2, issue #210) rather than left to look
 * like a path a node might take.
 *
 * <h2>Identity has to persist for any of this to mean anything</h2>
 *
 * <p>{@link dev.nodera.peer.discovery.PersistentIdentityStore} keeps a peer's
 * {@code NodeId} across restarts (retiring LIMITATIONS L-28). Without it, a returning seeder is a
 * stranger: the directory forgets it, its reliability score resets, and its committee memberships
 * and archive assignments (Task 21) silently move elsewhere every reboot.
 *
 * <h2>Bounded, always</h2>
 *
 * <p>{@link dev.nodera.peer.discovery.PeerDirectory} is LRU-bounded. Everything it holds
 * arrives from remote peers, so an unbounded index is a memory-exhaustion vector reachable by any
 * peer that gossips enthusiastically enough (Plan §3.13).
 *
 * <p>Thread-context: the services are thread-safe; see per-class Javadoc.
 */
package dev.nodera.peer.discovery;
