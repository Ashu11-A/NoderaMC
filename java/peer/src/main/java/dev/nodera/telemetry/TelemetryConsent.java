package dev.nodera.telemetry;

/**
 * The consent gate (network task 12; {@code docs/plans/Plan.6.md} D1).
 *
 * <p>Three values, not two, because "has not been asked" and "said no" are different facts even
 * though they have the same effect: the app must know whether to show its first-run modal, and a
 * worker must be able to say which state it is in. Both non-{@link #GRANTED} values collect nothing.
 *
 * <p><b>Consent gates collection, not transmission.</b> Every recording call site asks
 * {@link #collects()} first and returns without building an event when the answer is no. A design
 * that collected into a buffer and discarded it later would be one flag away from shipping data
 * nobody agreed to, and would spend the CPU cost of measurement on people who declined.
 *
 * @Thread-context immutable enum, any thread.
 */
public enum TelemetryConsent {

    /** Never asked. Collects nothing — the safe state for a worker started by hand or by a script. */
    UNANSWERED,
    /** Asked and declined. Collects nothing, and the question is not asked again. */
    DENIED,
    /** Asked and accepted. The only value under which anything is measured. */
    GRANTED;

    /** Whether anything may be measured at all. */
    public boolean collects() {
        return this == GRANTED;
    }

    /** The wire token used by the control verb and the batch envelope. */
    public String token() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Parse a token, defaulting to {@link #UNANSWERED}.
     *
     * <p>An unrecognised value is <b>not</b> an error and is <b>never</b> read as consent: a
     * corrupt file, a truncated write, or a value written by a future version must all fail closed.
     */
    public static TelemetryConsent parse(String token) {
        if (token == null) {
            return UNANSWERED;
        }
        String trimmed = token.trim().toLowerCase(java.util.Locale.ROOT);
        for (TelemetryConsent value : values()) {
            if (value.token().equals(trimmed)) {
                return value;
            }
        }
        return UNANSWERED;
    }
}
