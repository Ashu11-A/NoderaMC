package dev.nodera.protocol.session;

import dev.nodera.protocol.NoderaMessage;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The answer to a {@link Hello}: admitted or not, with what, and why (Task 14 phase 4).
 *
 * <p>The interesting field is {@link #role}. The tempting design is to refuse a peer whose rules
 * version differs, and it is wrong: content distribution, discovery, relay and tunnelling are all
 * version-independent, so refusing costs the network a seeder and buys no safety at all. What a
 * version difference actually prevents is <em>agreeing on a state root</em> — you cannot co-validate
 * with a peer whose engine computes it differently. So the boundary is drawn exactly there and
 * nowhere earlier: an {@link SessionRole#OBSERVER} meshes, seeds, relays, tunnels and receives
 * commits, and sits on no committee.
 *
 * <p>Today that same peer is admitted with no check at all and then throws from inside the region
 * engine, which is the same outcome reached by the least informative route available.
 *
 * @param wireEpoch        the responder's frame generation.
 * @param selectedFeatures the <b>intersection</b> of the two feature sets — the session's emit
 *                         profile from this point on.
 * @param role             what the peer may do; see {@link SessionRole}.
 * @param networkId        the responder's network, so a peer that dialled the wrong world learns it
 *                         here rather than by receiving traffic that makes no sense.
 * @param reject           why the peer is not fully admitted, or {@link RejectCode#NONE}.
 * @param detail           a short human-readable note for logs; never parsed.
 * @Thread-context immutable; any thread.
 */
public record HelloAck(int wireEpoch,
                       Set<Integer> selectedFeatures,
                       int roleCode,
                       UUID networkId,
                       int rejectCode,
                       String detail) implements NoderaMessage {

    public HelloAck {
        if (networkId == null) {
            throw new IllegalArgumentException("HelloAck requires a network");
        }
        if (roleCode < 0 || roleCode > 0xFFFF || rejectCode < 0 || rejectCode > 0xFFFF) {
            throw new IllegalArgumentException("role and reject codes are u16");
        }
        if (detail == null) {
            throw new IllegalArgumentException("detail must not be null; use \"\"");
        }
        // A SORTED immutable view, not `Set.copyOf`: the latter is unordered, so the encoder would
        // emit whatever iteration order the hash produced and one value would have many spellings.
        selectedFeatures = selectedFeatures == null ? Set.of()
                : Collections.unmodifiableSet(new TreeSet<>(selectedFeatures));
        for (int code : selectedFeatures) {
            // Feature codes share the u16 space every other code here uses. The bound is not
            // decoration: the set is sorted as signed ints and written as unsigned ones, so a code
            // above Integer.MAX_VALUE would encode in an order the decoder refuses to read back.
            if (code <= 0 || code > 0xFFFF) {
                throw new IllegalArgumentException("feature codes are positive u16; got " + code);
            }
        }
    }

    /** Build an ack from a role and reason this build knows. */
    public HelloAck(int wireEpoch, Set<Integer> selectedFeatures, SessionRole role, UUID networkId,
                    RejectCode reject, String detail) {
        this(wireEpoch, selectedFeatures, role.code(), networkId, reject.code(), detail);
    }

    /**
     * The role, resolved.
     *
     * @return the constant, or {@link SessionRole#REFUSED} for a code this build does not know. An
     *         unreadable role is treated as the most restrictive one: a peer must never grant itself
     *         membership on the strength of a word it cannot read.
     */
    public SessionRole role() {
        return SessionRole.fromCode(roleCode);
    }

    /** The refusal reason, resolved; the raw {@link #rejectCode()} keeps an unknown one faithful. */
    public RejectCode reject() {
        return RejectCode.fromCode(rejectCode);
    }

    /** @return {@code true} if the session may hold committee seats. */
    public boolean consensusCompatible() {
        return role() == SessionRole.ADMITTED;
    }

    /** @return {@code true} if the peer was turned away entirely. */
    public boolean refused() {
        return role() == SessionRole.REFUSED;
    }
}
