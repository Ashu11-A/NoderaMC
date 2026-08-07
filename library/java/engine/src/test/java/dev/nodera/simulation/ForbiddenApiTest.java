package dev.nodera.simulation;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import dev.nodera.core.state.FixedVec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.UUID;
import java.util.stream.Stream;

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
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackagesOf(
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

    /**
     * The one-line fences: six bans that are the same claim over a different forbidden API.
     *
     * <p>They were six methods whose bodies differed by one call and one sentence, and a seventh
     * would have been a seventh method. As a parameter list the rule and its justification sit on
     * one row, and JUnit still reports one test per row — so a ban that starts failing names
     * itself.
     *
     * @return one row per banned API: the name JUnit reports, and the rule.
     */
    static Stream<Arguments> determinismFences() {
        return Stream.of(
                Arguments.of("System.currentTimeMillis", noClasses()
                        .should().callMethod(System.class, "currentTimeMillis")
                        .because("wall-clock reads break cross-replica determinism (Task 0 §6)")),
                Arguments.of("System.nanoTime", noClasses()
                        .should().callMethod(System.class, "nanoTime")
                        .because("wall-clock reads break cross-replica determinism (Task 0 §6)")),
                Arguments.of("Math.random", noClasses()
                        .should().callMethod(Math.class, "random")
                        .because("Math.random uses an unseeded entropy source (Task 0 §6)")),
                Arguments.of("ThreadLocalRandom", noClasses()
                        .should().accessClassesThat()
                        .haveFullyQualifiedName("java.util.concurrent.ThreadLocalRandom")
                        .because("ThreadLocalRandom is unseeded per-thread entropy; use "
                                + "DeterministicRandom (Task 0 §6)")),
                Arguments.of("UUID.randomUUID", noClasses()
                        .should().callMethod(UUID.class, "randomUUID")
                        .because("UUID.randomUUID uses SecureRandom entropy; use "
                                + "DeterministicRandom (Task 0 §6)")),
                Arguments.of("java.io / java.net / java.sql", noClasses()
                        .should().accessClassesThat()
                        .resideInAnyPackage("java.io..", "java.net..", "java.sql..")
                        .because("IO/network/sql access breaks purity; execute must be a pure "
                                + "function (Task 0 §6)")));
    }

    @ParameterizedTest(name = "the simulation module never touches {0}")
    @MethodSource("determinismFences")
    void theEngineTouchesNoNonDeterministicApi(String api, ArchRule rule) {
        rule.check(classes);
    }

    @Test
    void noDoubleRoundTrips() {
        // Every continuous quantity in hashed state is Q32.32 fixed-point. Round-tripping through
        // a JVM double (Math.floor/ceil/round of a double) is NOT bit-identical across JVMs/hardware
        // for arbitrary fractions, so it can silently diverge replica roots. Read coordinates via
        // the pure-integer FixedVec3.blockX/Y/Z() shift instead. (int-typed Math.floorDiv/floorMod
        // are unaffected — these signatures target the double overloads.)
        noClasses().should().callMethod(Math.class, "floor", double.class)
                .because("Math.floor(double) is a non-portable double round-trip; use the integer block shift")
                .check(classes);
        noClasses().should().callMethod(Math.class, "ceil", double.class)
                .because("Math.ceil(double) is a non-portable double round-trip; use fixed-point math")
                .check(classes);
        noClasses().should().callMethod(Math.class, "round", double.class)
                .because("Math.round(double) is a non-portable double round-trip; use fixed-point math")
                .check(classes);
    }

    @Test
    void noFixedVec3DoubleAdapters() {
        // FixedVec3.fromExternal/toExternal are the ONLY double seams into the entity frame — they
        // exist for Minecraft capture, never for simulation math. A call from the simulation module
        // is a determinism hazard: build positions with pure integer fixed-point math instead.
        noClasses().should().callMethod(
                        FixedVec3.class, "fromExternal", double.class, double.class, double.class)
                .because("fromExternal routes a JVM double into hashed state; build FixedVec3 with integer math")
                .check(classes);
        noClasses().should().callMethod(FixedVec3.class, "toExternal", long.class)
                .because("toExternal produces a JVM double from hashed state; read the integer block shift instead")
                .check(classes);
    }
}
