package dev.nodera.peer.archival;

/**
 * How many full copies of a world the network must keep, and therefore how much of the network's
 * data each peer has to store.
 *
 * <h2>The question this answers</h2>
 *
 * <p>A world is safe when at least one peer holding it is reachable. Peers here are people's home
 * machines: they are switched off, they close the game, they go on holiday. So "how many copies"
 * is not a taste question, it is arithmetic over how often a peer is up.
 *
 * <p>Model every peer as independently available with probability {@code p}. Copies are whole —
 * this network replicates whole archives, never fragments, so a peer either has a world or does not
 * — which means a world with {@code R} copies is lost at a given moment only when all {@code R} are
 * down at once:
 *
 * <pre>{@code   P(lost) = (1 - p)^R }</pre>
 *
 * <p>Requiring {@code P(lost) ≤ 1 - D} for a durability target {@code D} and solving for R:
 *
 * <pre>{@code   R  ≥  ln(1 - D) / ln(1 - p) }</pre>
 *
 * <p>At the defaults below ({@code p = 0.35}, {@code D = 0.9999}) that is
 * {@code ln(0.0001) / ln(0.65) ≈ 21.4}, so {@code R = 22}. Read it the other way and it is the
 * useful sentence: <b>a swarm of home machines that are online a third of the time needs about
 * twenty-two copies of a world before losing one becomes unlikely.</b> Ten copies of such a world
 * is a 1.3% chance of being unreachable at any moment; three copies is 27%.
 *
 * <h2>Why a small network stores everything</h2>
 *
 * <p>{@link #replicasFor(int)} is capped by the number of peers that exist, so a network smaller
 * than the target has <b>every peer holding every world</b> — a 100% share, not because that is
 * generous but because it is the only configuration a network that small has. Fewer copies than
 * peers, at that size, is choosing to be less durable than you could be for free.
 *
 * <p>The cap is what makes this safe to state as a rule. Without it a two-peer network would be
 * told it needs twenty-two copies and could only ever fail to reach it.
 *
 * <h2>What each peer stores</h2>
 *
 * <p>{@link #shareOfNetworkPermille(int)} is {@code R / N}: with whole-copy replication a peer that
 * is selected for a world stores 100% <em>of that world</em>, and across the whole corpus each peer
 * ends up holding {@code R/N} of the network's data. Some numbers, at the defaults:
 *
 * <table border="1">
 *   <caption>Copies and per-peer share by network size</caption>
 *   <tr><th>Peers besides the host</th><th>Copies</th><th>Share of all world data per peer</th></tr>
 *   <tr><td>1</td><td>1</td><td>100%</td></tr>
 *   <tr><td>5</td><td>5</td><td>100%</td></tr>
 *   <tr><td>22</td><td>22</td><td>100%</td></tr>
 *   <tr><td>50</td><td>22</td><td>44%</td></tr>
 *   <tr><td>200</td><td>22</td><td>11%</td></tr>
 *   <tr><td>1000</td><td>22</td><td>2.2%</td></tr>
 * </table>
 *
 * <p>So the cost per peer falls as the network grows, and the durability does not: that is the
 * whole point of replicating to a target rather than to a fraction.
 *
 * <h2>What this deliberately does not model</h2>
 *
 * <p>Correlated failure. Peers on one ISP, in one country, or all running the same auto-update go
 * down together, and independence is exactly the assumption that breaks first. The defaults are
 * pessimistic about {@code p} partly to buy margin against that, but the honest statement is that
 * {@code R} here is a floor for independent loss, not a guarantee against a shared cause.
 *
 * <p>Erasure coding would reach the same durability for a fraction of the bytes, and this network
 * does not do it: pieces are whole and hash-checked against a manifest a joiner re-derives, which
 * is what lets any peer serve any piece to any other with no coordination. That simplicity is paid
 * for here, in copies.
 *
 * @param peerAvailabilityPermille how often a peer is reachable, in permille (1..999). A hobby
 *                                 network's machines are not servers; this is the number to change
 *                                 if that assumption stops holding.
 * @param durabilityPermille       the chance a world is reachable at any moment, in permille of a
 *                                 permille — see {@link #DEFAULT_DURABILITY_PPM}.
 * @Thread-context immutable record, safe for any thread.
 */
public record ReplicationTarget(int peerAvailabilityPermille, int durabilityPermille) {

    /**
     * Assumed peer availability: 35%.
     *
     * <p>A player's machine, not a server. Roughly eight hours a day with the app running, which is
     * generous for a game client and stingy for the always-on worker this project also ships — the
     * pessimistic reading is the correct one to size backups against.
     */
    public static final int DEFAULT_AVAILABILITY_PERMILLE = 350;

    /**
     * Target durability: 99.99% — a world is unreachable about one minute in ten thousand.
     *
     * <p>Expressed in permille like every other ratio on this wire, so 9999 means 99.99%.
     */
    public static final int DEFAULT_DURABILITY_PPM = 9999;

    /** The defaults above. */
    public static final ReplicationTarget DEFAULT =
            new ReplicationTarget(DEFAULT_AVAILABILITY_PERMILLE, DEFAULT_DURABILITY_PPM);

    /**
     * An upper bound on copies, so a pessimistic availability cannot ask a large network to store
     * everything on every node.
     *
     * <p>Not a durability statement — it is the point past which extra copies buy so little that
     * the disk is better spent on another world. Reaching it means the swarm's peers are too
     * unreliable for replication alone to fix.
     */
    public static final int MAX_REPLICAS = 32;

    /** @throws IllegalArgumentException if either parameter is outside its range. */
    public ReplicationTarget {
        if (peerAvailabilityPermille < 1 || peerAvailabilityPermille > 999) {
            throw new IllegalArgumentException(
                    "peerAvailabilityPermille must be in 1..999, got " + peerAvailabilityPermille);
        }
        if (durabilityPermille < 1 || durabilityPermille > 9999) {
            throw new IllegalArgumentException(
                    "durabilityPermille must be in 1..9999, got " + durabilityPermille);
        }
    }

    /** @return the defaults. */
    public static ReplicationTarget standard() {
        return DEFAULT;
    }

    /**
     * Copies needed for the durability target, before the network's own size is considered.
     *
     * <p>{@code ceil(ln(1 - D) / ln(1 - p))}, clamped to {@link #MAX_REPLICAS}. Independent of how
     * many peers exist — this is the demand; {@link #replicasFor(int)} is what the supply allows.
     *
     * @return the ideal copy count, at least 1.
     */
    public int idealReplicas() {
        double unavailable = 1.0 - peerAvailabilityPermille / 1000.0;
        double allowedLoss = 1.0 - durabilityPermille / 10_000.0;
        // Both logs are negative, so the quotient is positive. `unavailable` cannot be 0 or 1: the
        // constructor bounds availability to 1..999 permille.
        double exact = Math.log(allowedLoss) / Math.log(unavailable);
        // The epsilon is load-bearing. p = 0.9, D = 0.9999 is exactly 4 copies, and `Math.log`
        // returns 4.000000000000001 for it — a bare ceil rounds that to 5 and every peer in the
        // network stores an extra full copy of every world because of one floating-point ulp.
        int ideal = (int) Math.ceil(exact - 1e-9);
        return Math.max(1, Math.min(MAX_REPLICAS, ideal));
    }

    /**
     * Copies to actually place, given how many peers could hold the world.
     *
     * <p>The cap is the "few peers ⇒ everybody stores everything" rule: a network with fewer peers
     * than the target cannot reach it, and holding back copies it could have made would leave a
     * world less safe than the network is able to make it, for no saving anybody asked for.
     *
     * @param eligiblePeers peers that could hold the world, excluding its host.
     * @return copies to place; {@code 0} only when there is nobody to place them on.
     */
    public int replicasFor(int eligiblePeers) {
        if (eligiblePeers <= 0) {
            return 0;
        }
        return Math.min(idealReplicas(), eligiblePeers);
    }

    /**
     * The fraction of <b>all</b> the network's world data one peer should expect to store, in
     * permille.
     *
     * <p>{@code R / N}. With whole-copy replication a peer selected for a world holds 100% of that
     * world; averaged over every world in the network, it holds this share of the total. It is the
     * number to put in front of a person deciding how much disk to give this: at the defaults it is
     * 100% until the network passes {@link #idealReplicas()} peers, and falls from there.
     *
     * @param eligiblePeers peers that could hold a world, excluding its host.
     * @return 0..1000; {@code 1000} (all of it) for a network at or below the target size.
     */
    public int shareOfNetworkPermille(int eligiblePeers) {
        if (eligiblePeers <= 0) {
            return 0;
        }
        return (int) Math.min(1000L,
                Math.round(1000.0 * replicasFor(eligiblePeers) / eligiblePeers));
    }

    /**
     * The chance a world with {@code replicas} copies is unreachable at any moment, in permille.
     *
     * <p>{@code (1 - p)^R}. Exposed because it is what makes the copy count arguable rather than
     * asserted: a screen can say "3 copies — 27% chance nobody has it right now" instead of asking
     * somebody to trust a constant.
     *
     * @param replicas copies in existence, including the host if it is holding one.
     * @return 0..1000; {@code 1000} when there are no copies at all.
     */
    public int lossRiskPermille(int replicas) {
        if (replicas <= 0) {
            return 1000;
        }
        double unavailable = 1.0 - peerAvailabilityPermille / 1000.0;
        return (int) Math.round(1000.0 * Math.pow(unavailable, replicas));
    }
}
