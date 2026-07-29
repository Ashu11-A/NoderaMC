package dev.nodera.bench;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.content.ContentChunk;
import dev.nodera.protocol.health.Heartbeat;
import dev.nodera.protocol.health.WorkerLoad;
import dev.nodera.protocol.membership.MembershipUpdate;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.protocol.membership.RegionProgress;
import dev.nodera.protocol.membership.SessionKeepAlive;
import dev.nodera.core.region.RegionEpoch;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Lane 3 — <b>the wire</b>: canonical encode/decode of the messages the discovery and chunk-sync
 * lanes send.
 *
 * <h2>Why the codec gets its own lane</h2>
 *
 * <p>Canonical encoding is not an implementation detail here: the bytes ARE the hash input, so
 * every message is encoded at least once more than it is sent (to hash it, to sign it, to verify
 * it). The codec is therefore multiplied by the message rate of every other lane, and it is the one
 * piece of code with a second implementation ({@code rust/nodera-codec}) held byte-exact against
 * it — which makes it the last place where a "harmless" allocation should be allowed to creep in.
 *
 * <p>The four messages measured are the ones that dominate a live session: membership gossip
 * (O(peers) per round), keep-alives (every peer, every second, carrying per-region progress),
 * content chunks (the bulk data plane), and heartbeats (the smallest frame, so its cost is almost
 * entirely fixed overhead — the number to watch if framing changes).
 *
 * <p>Thread-context: JMH state per benchmark; the codec is stateless.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@State(Scope.Benchmark)
public class WireBenchmark {

    private static final int MEMBERS = 64;
    private static final int REGIONS = 32;
    private static final int CHUNK_BYTES = 24 * 1024;

    private final HashService hashes = new HashService();

    private MembershipUpdate membership;
    private SessionKeepAlive keepAlive;
    private ContentChunk contentChunk;
    private Heartbeat heartbeat;

    private byte[] membershipFrame;
    private byte[] keepAliveFrame;
    private byte[] contentChunkFrame;
    private byte[] heartbeatFrame;

    @Setup(Level.Trial)
    public void setUp() {
        List<PeerEntry> members = BenchFixtures.peers(MEMBERS);
        membership = new MembershipUpdate(9L, BenchFixtures.node(1), members);

        List<RegionProgress> progress = new ArrayList<>(REGIONS);
        for (int i = 0; i < REGIONS; i++) {
            progress.add(new RegionProgress(
                    BenchFixtures.region(i % 8, i / 8),
                    new RegionEpoch(3L),
                    BenchFixtures.node(1 + (i % 4)),
                    10_000L + i));
        }
        keepAlive = new SessionKeepAlive(BenchFixtures.node(1), 512L, progress);

        byte[] payload = new byte[CHUNK_BYTES];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31);
        }
        contentChunk = new ContentChunk(BenchFixtures.digest(11), 17, Bytes.unsafeWrap(payload));

        heartbeat = new Heartbeat(1_000L, new WorkerLoad(4, 512L * 1024 * 1024, 2_500_000L));

        membershipFrame = MessageCodec.encode(membership);
        keepAliveFrame = MessageCodec.encode(keepAlive);
        contentChunkFrame = MessageCodec.encode(contentChunk);
        heartbeatFrame = MessageCodec.encode(heartbeat);
    }

    /** Membership gossip: O(peers) per round, sent to every member on every change. */
    @Benchmark
    public byte[] encodeMembership() {
        return MessageCodec.encode(membership);
    }

    @Benchmark
    public NoderaMessage decodeMembership() {
        return MessageCodec.decode(membershipFrame);
    }

    /** Keep-alives with per-region progress — the steady-state cost of simply staying connected. */
    @Benchmark
    public byte[] encodeKeepAlive() {
        return MessageCodec.encode(keepAlive);
    }

    @Benchmark
    public NoderaMessage decodeKeepAlive() {
        return MessageCodec.decode(keepAliveFrame);
    }

    /** One piece on the wire: the bulk plane, so per-byte cost dominates per-message cost. */
    @Benchmark
    public byte[] encodeContentChunk() {
        return MessageCodec.encode(contentChunk);
    }

    @Benchmark
    public NoderaMessage decodeContentChunk() {
        return MessageCodec.decode(contentChunkFrame);
    }

    /** The smallest frame there is: almost pure framing overhead. */
    @Benchmark
    public byte[] encodeHeartbeat() {
        return MessageCodec.encode(heartbeat);
    }

    @Benchmark
    public NoderaMessage decodeHeartbeat() {
        return MessageCodec.decode(heartbeatFrame);
    }

    /**
     * The other half of every frame's real cost: SHA-256 over the canonical bytes. Nothing is
     * signed, committed, or content-addressed without it.
     */
    @Benchmark
    public Bytes hashContentChunkFrame() {
        return hashes.sha256(contentChunkFrame);
    }
}
