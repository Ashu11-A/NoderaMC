package dev.nodera.testkit.suite;

/**
 * Why a scenario did not run — the distinction that decides whether a skip may be tolerated.
 *
 * <p>The category charter states the rule this enum enforces: <i>a skip must be circumstantial (this
 * machine lacks a display) and never structural (nothing builds the jar the scenario looks for) — a
 * structural skip is a scenario that never runs</i>. Until this existed, the two rendered
 * identically in the report and exited identically from the tool, and three server-category suites
 * reported SKIP on every machine including CI for weeks without anybody's build going red.
 */
public enum SkipKind {

    /**
     * The machine, or the build under test, genuinely cannot host this run: no display, not enough
     * memory, no phone attached, a feature compiled out.
     *
     * <p>Reporting this is the honest outcome, and {@code --allow-skips} exists for the operator who
     * has decided to accept it on a particular box.
     */
    CIRCUMSTANTIAL,

    /**
     * Nothing about the machine explains it: an artefact the harness itself is supposed to produce
     * is missing, or a path is wrong, so the scenario could never run anywhere.
     *
     * <p>Never tolerated, with or without {@code --allow-skips}. A run that reports this is a run
     * whose coverage does not exist.
     */
    STRUCTURAL
}
