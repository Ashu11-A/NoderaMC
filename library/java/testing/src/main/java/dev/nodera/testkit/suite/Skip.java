package dev.nodera.testkit.suite;

/**
 * A scenario that did not run, and why.
 *
 * @param kind   whether the machine could not host it, or the harness never gave it a chance.
 * @param reason the sentence a reader gets, naming what was missing.
 */
public record Skip(SkipKind kind, String reason) {
}
