package dev.nodera.distribution;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import dev.nodera.core.state.SnapshotVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fence around {@link SnapshotVersion}.
 *
 * <h2>The rule</h2>
 *
 * <p>A snapshot version is a <b>per-region chain height</b> — how many times one committee has
 * committed. It may be compared only between peers seated on the same region's committee at the same
 * epoch, where it means something. It may never decide which of two <i>independently produced</i>
 * copies of a region is newer, never appear in a filename, and never be fed into a clock.
 *
 * <p>That rule is not new, and stating it in prose is what failed. Two peers that have been apart
 * counted their heights on their own machines, so comparing them was meaningless — and the system
 * compared them everywhere: to decide which archive to open, which manifest superseded which, which
 * column won a merge, and, worst, as the counter component of a hybrid logical clock. Each of those
 * discarded somebody's work, and each looked reasonable at its own call site.
 *
 * <p>So the line is held here instead. The distribution package is the freshness plane: it decides
 * what to fetch, what to keep and what to merge, and none of those questions has a version-shaped
 * answer. Content roots say whether two copies differ; {@code Hlc} stamps say which of two differing
 * columns is more recent.
 *
 * <h2>What is allowed through, and why</h2>
 *
 * <p>Carrying a version is fine — {@code PieceManifest} records the chain height its region was at,
 * and {@code RegionMerger} continues this peer's own chain. What is banned is <b>comparing</b> one:
 * {@code compareTo} and {@code value()} are how a comparison is spelled, and neither has an honest
 * use in this package.
 */
final class SnapshotVersionScopeTest {

    private static JavaClasses distribution;

    @BeforeAll
    static void importClasses() {
        // importPackagesOf rather than importPackages: the latter relies on
        // ClassLoader.getResources for the package path, which returns nothing in this Gradle
        // layout and would silently import zero classes — a rule that checks nothing passes.
        distribution = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackagesOf(PieceManifest.class, RegionMerger.class);
    }

    @Test
    void theImportFoundTheDistributionClasses() {
        assertThat(distribution.size())
                .as("a rule that imported nothing passes without checking anything")
                .isGreaterThan(0);
    }

    @Test
    void theFreshnessPlaneDoesNotCompareChainHeights() {
        ArchRule rule = noClasses()
                .should().callMethod(SnapshotVersion.class, "compareTo", SnapshotVersion.class)
                .because("a chain height counted on one machine says nothing about a copy produced "
                        + "on another; content roots decide whether two copies differ, and Hlc "
                        + "stamps decide which of two differing columns is more recent");
        rule.check(distribution);
    }

    @Test
    void theFreshnessPlaneDoesNotUnwrapAChainHeightToCompareIt() {
        // value() is the other way a comparison is spelled, and it is how every one of the sites
        // this fence exists to prevent was actually written: `offered.version().value() <= held...`.
        ArchRule rule = noClasses()
                // A version in a log line is a fact about this node's own chain, not a comparison,
                // and reading it there is how a support question gets answered. Named explicitly
                // rather than left to a broad exemption: the point of the fence is that every way
                // through it is deliberate.
                .that(new com.tngtech.archunit.base.DescribedPredicate<>("outside toString") {
                    @Override
                    public boolean test(com.tngtech.archunit.core.domain.JavaClass ignored) {
                        return true;
                    }
                })
                .should(new com.tngtech.archunit.lang.ArchCondition<>(
                        "call SnapshotVersion.value() outside toString()") {
                    @Override
                    public void check(com.tngtech.archunit.core.domain.JavaClass item,
                                      com.tngtech.archunit.lang.ConditionEvents events) {
                        for (var call : item.getMethodCallsFromSelf()) {
                            if (!call.getTarget().getName().equals("value")
                                    || !call.getTargetOwner().isEquivalentTo(SnapshotVersion.class)
                                    || call.getOrigin().getName().equals("toString")) {
                                continue;
                            }
                            events.add(com.tngtech.archunit.lang.SimpleConditionEvent.violated(
                                    item, call.getDescription()));
                        }
                    }
                })
                .because("unwrapping a chain height in the freshness plane is a comparison in "
                        + "disguise — the very shape that discarded a whole copy of somebody's work");
        rule.check(distribution);
    }
}
