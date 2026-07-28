package dev.nodera.protocol.wire;

import dev.nodera.protocol.NoderaMessage;

/**
 * One row of the wire schema: everything the protocol knows about a single message kind
 * (Task 14, {@code Plan.7} decision D6 — the schema is the source, the tables are generated).
 *
 * <p>Before this record existed, a message's identity was spread across five hand-maintained lists
 * in two languages — a tag constant, a {@code KNOWN_TAGS} entry, a {@code typeName} switch arm, an
 * {@code instanceof} chain, and a mirrored Rust constant. Each was appended by hand, so each could
 * be forgotten independently; the conformance harness found exactly that (a tag live on the wire and
 * absent from the core snapshot). A single row is the fix: appending a kind is one edit, and
 * everything else is derived from it.
 *
 * @param kind  the permanent wire number. <b>Assigning a kind is permanent</b>; never renumber or
 *              reuse one. A breaking change allocates a new kind rather than bumping a version
 *              ({@code Plan.7} D3).
 * @param name  the stable display name — the simple name of the message record, used by
 *              diagnostics, by the fixture file names, and by the Rust mirror.
 * @param plane which encoding contract governs the body; see {@link MessagePlane}.
 * @param type  the message record this kind decodes to.
 * @Thread-context immutable; any thread.
 */
public record WireKind(int kind, String name, MessagePlane plane,
                       Class<? extends NoderaMessage> type) {

    public WireKind {
        if (kind <= 0 || kind > 0xFFFF) {
            throw new IllegalArgumentException("kind must be a positive u16, got " + kind);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("kind " + kind + " must have a name");
        }
        if (plane == null) {
            throw new IllegalArgumentException(name + " must declare a plane");
        }
        if (type == null) {
            throw new IllegalArgumentException(name + " must declare a message type");
        }
        if (!name.equals(type.getSimpleName())) {
            throw new IllegalArgumentException(
                    "kind " + kind + " is named " + name + " but maps to " + type.getSimpleName()
                            + "; the name is the record's simple name so fixtures and diagnostics "
                            + "cannot drift from the type");
        }
    }

    /**
     * The fixture file name for this kind — {@code RegionProposal} becomes
     * {@code region-proposal.bin}. Derived rather than declared so a fixture can never be filed
     * under a name no kind claims.
     *
     * @return the kebab-cased file name, including the {@code .bin} suffix.
     * @Thread-context any thread.
     */
    public String fixtureName() {
        StringBuilder out = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    out.append('-');
                }
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.append(".bin").toString();
    }

    /** The Rust constant name for this kind — {@code RegionProposal} becomes {@code REGION_PROPOSAL}. */
    public String rustConstantName() {
        StringBuilder out = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                out.append('_');
            }
            out.append(Character.toUpperCase(c));
        }
        return out.toString();
    }
}
