package dev.nodera.testkit.harness;

/**
 * A live-harness failure with a stated cause.
 *
 * <p>Every message thrown from the harness names what was being waited for and where the evidence
 * is, because the shell suites this replaces failed as "S5: timeout" and left the reader to guess
 * which of five processes had gone quiet. A stage that cannot say what it wanted is a stage nobody
 * can fix.
 *
 * <p>Thread-context: ordinary exception.
 */
public class HarnessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HarnessException(String message) {
        super(message);
    }

    public HarnessException(String message, Throwable cause) {
        super(message, cause);
    }
}
