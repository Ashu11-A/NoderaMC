package dev.nodera.simulation;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces the Task 0 §6 determinism ground rules for the {@code simulation} module: the engine
 * must be a pure function, so it is forbidden from touching wall clocks, entropy sources, or
 * filesystem/network/sql state. The one allowed source of randomness is {@link DeterministicRandom}.
 *
 * <p><b>Re-enabled.</b> The repo now compiles to Java 21 bytecode (class file version 65) via
 * {@code --release 21}, so ArchUnit 1.3.0's bundled ASM can once again parse the class files.
 * Previously this was {@code @Disabled} because the JDK-25-compiled class files were version 69,
 * which ArchUnit 1.3's ASM could not parse, causing {@code ClassFileImporter} to silently import
 * zero classes. Determinism is also proven independently by {@link DeterminismPropertyTest}
 * (same inputs produce identical root + delta bytes across runs).
 *
 * <p>Thread-context: single test thread.
 */
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
