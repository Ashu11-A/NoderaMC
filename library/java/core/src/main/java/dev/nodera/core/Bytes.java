package dev.nodera.core;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * Immutable, value-semantic byte buffer. This is the ONE representation used for every byte-array
 * field across Nodera (state roots, signatures, encoded payloads, content hashes) — per Task 2's
 * "pick one and use it everywhere" rule. Records holding byte data use {@code Bytes} so they get
 * correct {@link #equals(Object)}, {@link #hashCode()} and a hex {@link #toString()} for free.
 *
 * <p>{@code Bytes} is immutable: every constructor defensive-copies its input, and the backing
 * array is never exposed mutably.
 *
 * <p>Thread-context: immutable, safe for any thread.
 */
public final class Bytes {

    private final byte[] data;
    /** Cached only after first request; safe under benign data races (idempotent computation). */
    private int hash;

    private Bytes(byte[] data, boolean copy) {
        this.data = copy ? data.clone() : data;
    }

    /** Wrap an existing array (defensive copy). */
    public Bytes(byte[] data) {
        this(data, true);
    }

    /** Wrap a range of an array (defensive copy). */
    public Bytes(byte[] data, int offset, int length) {
        byte[] copy = new byte[length];
        System.arraycopy(data, offset, copy, 0, length);
        this.data = copy;
    }

    /** Construct from a hex string (no {@code 0x} prefix). */
    public static Bytes fromHex(String hex) {
        return new Bytes(HexFormat.of().parseHex(hex), false);
    }

    /** Empty byte sequence. */
    public static Bytes empty() {
        return new Bytes(new byte[0], false);
    }

    /** Adopts the given array WITHOUT copying — caller must not retain/mutate it. */
    public static Bytes unsafeWrap(byte[] data) {
        return new Bytes(data, false);
    }

    /** Length in bytes. */
    public int length() {
        return data.length;
    }

    public boolean isEmpty() {
        return data.length == 0;
    }

    /** Return a fresh defensive copy. Callers may mutate freely. */
    public byte[] toArray() {
        return data.clone();
    }

    /** Copy these bytes into {@code dest} at {@code offset}. */
    public void copyInto(byte[] dest, int offset) {
        System.arraycopy(data, 0, dest, offset, data.length);
    }

    /** Hex string (lowercase, no separator). */
    public String toHex() {
        return HexFormat.of().formatHex(data);
    }

    /** Truncated hex for log lines, e.g. {@code 1a2b3c4d…}. */
    public String toShortHex(int prefixBytes) {
        int n = Math.min(prefixBytes, data.length);
        String full = HexFormat.of().formatHex(data, 0, n);
        return full + (data.length > n ? "…" : "");
    }

    @Override
    public boolean equals(Object o) {
        return (this == o) || (o instanceof Bytes b && Arrays.equals(data, b.data));
    }

    @Override
    public int hashCode() {
        int h = hash;
        if (h == 0) {
            h = Arrays.hashCode(data);
            hash = h;
        }
        return h;
    }

    @Override
    public String toString() {
        return "Bytes[" + toHex() + "]";
    }
}
