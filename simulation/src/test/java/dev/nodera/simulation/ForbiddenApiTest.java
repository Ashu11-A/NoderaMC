package dev.nodera.simulation;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces the Task 0 §6 determinism ground rules for the {@code simulation} module: the engine
 * must be a pure function, so it is forbidden from touching wall clocks, entropy sources, or
 * filesystem/network/sql state. The one allowed source of randomness is {@link DeterministicRandom}.
 *
 * <p><b>Currently {@link Disabled}.</b> ArchUnit 1.3.0's bundled ASM cannot parse JDK 25 class
 * files (version 69), so {@code ClassFileImporter} silently imports zero classes and every rule
 * trips {@code failOnEmptyShould}. The rules are correct and re-enable unchanged once the Task 0
 * Java-21 toolchain pin is restored (the codebase uses only Java 21-era features). Until then the
 * determinism ground rules are enforced by:
 * <ul>
 *   <li>{@link DeterminismPropertyTest} — proves {@code execute} IS a pure function (same inputs
 *       ⇒ identical root + delta bytes across runs);</li>
 *   <li>code review — the engine's only entropy is {@link DeterministicRandom} (JDK-named
 *       {@code "L64X128MixRandom"} seeded from {@link dev.nodera.core.crypto.StableHash}); it never
 *       calls {@code System.currentTimeMillis/nanoTime}, {@code Math.random},
 *       {@code ThreadLocalRandom}, or {@code UUID.randomUUID}.</li>
 * </ul>
 *
 * <p>Thread-context: single test thread.
 */
@Disabled("ArchUnit 1.3 ASM cannot parse JDK 25 class files (v69); re-enable when the Task 0 Java-21 toolchain is restored. Determinism is covered by DeterminismPropertyTest meanwhile.")
final class ForbiddenApiTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        // importPackages(String...) relies on ClassLoader.getResources("dev/nodera/simulation")
        // which returns 0 entries in this JDK25/Gradle layout. importPackagesOf(Class...) derives
        // each package root from the class's own code source and reliably finds the sibling
        // .class files. One representative class per subpackage covers the whole module.
        classes = new ClassFileImporter().importPackagesOf(
                RegionEngine.class,
                dev.nodera.simulation.rules.FlatWorldRules.class,
                dev.nodera.simulation.border.BorderClassifier.class,
                dev.nodera.simulation.engine.FlatWorldRegionEngine.class);
    }

    @Test
    void importFoundSimulationClasses() {
        assertThat(classes.size())
                .as("ArchUnit must import the simulation classes; got 0 (classpath/import issue)")
                .isGreaterThan(0);
    }

    @Test
    void noSystemCurrentTimeMillis() {
        ArchRule rule = noClasses()
                .should().callMethod(System.class, "currentTimeMillis")
                .because("wall-clock reads break cross-replica determinism (Task 0 §6)");
        rule.check(classes);
    }

    @Test
    void noSystemNanoTime() {
        ArchRule rule = noClasses()
                .should().callMethod(System.class, "nanoTime")
                .because("wall-clock reads break cross-replica determinism (Task 0 §6)");
        rule.check(classes);
    }

    @Test
    void noMathRandom() {
        ArchRule rule = noClasses()
                .should().callMethod(Math.class, "random")
                .because("Math.random uses an unseeded entropy source (Task 0 §6)");
        rule.check(classes);
    }

    @Test
    void noThreadLocalRandom() {
        ArchRule rule = noClasses()
                .should().accessClassesThat()
                .haveFullyQualifiedName("java.util.concurrent.ThreadLocalRandom")
                .because("ThreadLocalRandom is unseeded per-thread entropy; use DeterministicRandom (Task 0 §6)");
        rule.check(classes);
    }

    @Test
    void noUuidRandomUuid() {
        ArchRule rule = noClasses()
                .should().callMethod(UUID.class, "randomUUID")
                .because("UUID.randomUUID uses SecureRandom entropy; use DeterministicRandom (Task 0 §6)");
        rule.check(classes);
    }

    @Test
    void noIoNetSqlPackages() {
        ArchRule rule = noClasses()
                .should().accessClassesThat()
                .resideInAnyPackage("java.io..", "java.net..", "java.sql..")
                .because("IO/network/sql access breaks purity; execute must be a pure function (Task 0 §6)");
        rule.check(classes);
    }
}
