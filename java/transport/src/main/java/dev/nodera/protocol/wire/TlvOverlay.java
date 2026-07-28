package dev.nodera.protocol.wire;

import java.util.List;
import java.util.Set;

/**
 * The difference between the field set a frame arrived with and the one this build would write
 * (Task 14 phase 3).
 *
 * <p>Forward compatibility runs in two directions, and both of them have to survive a re-encode:
 *
 * <ul>
 *   <li>A <b>newer</b> peer sends a field this build has never heard of. It is skipped on decode —
 *       and kept in {@link #preserved}, because a peer that drops it becomes a lossy relay between
 *       two peers that understand each other perfectly well.</li>
 *   <li>An <b>older</b> peer omits a field this build does write. Its value takes the documented
 *       default — and the omission is recorded in {@link #absent}, because re-emitting the field
 *       would put words in that peer's mouth, and would mean one value had two spellings: present
 *       with the default, and absent.</li>
 * </ul>
 *
 * <p>Together they say "reproduce the field set you were given". An overlay is only meaningful
 * alongside the message it was decoded with; a caller that <em>changes</em> the message re-encodes
 * without one.
 *
 * @param preserved fields no accessor read, plus any field whose nested body carried something
 *                  unreadable — kept verbatim, and taking precedence over this build's own
 *                  rendering of the same id.
 * @param absent    ids this build writes that the received frame did not carry.
 * @Thread-context immutable; any thread.
 */
public record TlvOverlay(List<TlvField> preserved, Set<Integer> absent) {

    /** No difference — what a freshly constructed message re-encodes with. */
    public static final TlvOverlay NONE = new TlvOverlay(List.of(), Set.of());

    public TlvOverlay {
        preserved = List.copyOf(preserved);
        absent = Set.copyOf(absent);
    }

    /** @return {@code true} when the frame's field set is exactly what this build writes. */
    public boolean isEmpty() {
        return preserved.isEmpty() && absent.isEmpty();
    }

    /**
     * Apply the overlay to an encoded body.
     *
     * @param encoded the body this build produced for the message.
     * @return the body as the sender spelled it.
     * @Thread-context any thread.
     */
    public byte[] applyTo(byte[] encoded) {
        if (isEmpty()) {
            return encoded;
        }
        java.util.TreeMap<Integer, TlvField> all = new java.util.TreeMap<>();
        for (TlvField f : new TlvReader(encoded).unknown()) {
            all.put(f.id(), f);
        }
        for (int id : absent) {
            all.remove(id);
        }
        // Preserved fields win: they are the bytes that actually arrived, and the only reason a
        // field is preserved is that this build could not fully reconstruct it.
        for (TlvField f : preserved) {
            all.put(f.id(), f);
        }
        TlvWriter w = new TlvWriter();
        for (TlvField f : all.values()) {
            w.raw(f);
        }
        return w.toByteArray();
    }
}
