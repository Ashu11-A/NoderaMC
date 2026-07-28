package dev.nodera.protocol.wire;

import dev.nodera.core.Bytes;

/**
 * One decoded TLV field, kept in its raw form (Task 14 phase 3).
 *
 * <p>This exists for the fields a build does <b>not</b> know. Discarding them would make a relayed
 * or gossiped message lossy in exactly the situation the tolerant plane is for: a peer in the middle
 * of a version spread would quietly strip the fields the newer peers on either side of it are
 * relying on. Keeping the raw value lets that peer forward what it cannot read, and lets
 * decode-then-encode reproduce the bytes it was given.
 *
 * @param id    the field id, as it appeared on the wire.
 * @param type  the declared wire type.
 * @param value the raw value bytes, without the header.
 * @Thread-context immutable; any thread.
 */
public record TlvField(int id, WireType type, Bytes value) implements Comparable<TlvField> {

    public TlvField {
        if (id < 0 || id > 0xFFFF) {
            throw new IllegalArgumentException("field id must be a u16, got " + id);
        }
        if (type == null) {
            throw new IllegalArgumentException("field " + id + " must declare a wire type");
        }
        if (value == null) {
            throw new IllegalArgumentException("field " + id + " must have a value");
        }
        if (type.fixedLength() >= 0 && value.length() != type.fixedLength()) {
            throw new IllegalArgumentException("field " + id + " is " + type + " so its value must "
                    + "be " + type.fixedLength() + " byte(s), got " + value.length());
        }
    }

    @Override
    public int compareTo(TlvField other) {
        return Integer.compare(id, other.id);
    }
}
