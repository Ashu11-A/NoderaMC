package dev.nodera.testkit.suite;

/**
 * Thrown by a scenario that cannot run here — never by one that ran and disagreed.
 *
 * <p>Keeping the two apart is what makes the report readable: a skipped scenario says what the
 * machine lacked, a failed one says what the product did. A harness that reports "no display" as a
 * failure trains people to ignore red, which costs more than the coverage it pretends to have.
 */
public final class SkipSignal extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient SkipKind kind;

    public SkipSignal(String reason) {
        this(SkipKind.CIRCUMSTANTIAL, reason);
    }

    public SkipSignal(SkipKind kind, String reason) {
        super(reason);
        this.kind = kind;
    }

    /** Whether this skip may be tolerated at all; see {@link SkipKind}. */
    public SkipKind kind() {
        return kind == null ? SkipKind.CIRCUMSTANTIAL : kind;
    }
}
