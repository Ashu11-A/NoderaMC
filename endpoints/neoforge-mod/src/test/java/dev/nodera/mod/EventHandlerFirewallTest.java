package dev.nodera.mod;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodReference;
import com.tngtech.archunit.core.domain.TryCatchBlock;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NeoForge's {@code EventBus} does <b>not</b> isolate listener exceptions: whatever a handler throws
 * comes back out of {@code post}, into {@code MinecraftServer.runServer} or the client's network
 * thread. This repository shipped that once already (issue #39, a P2P bind collision that crashed
 * the integrated server), fixed it in the handler that caused it, and then wrote eight more
 * handlers without the guard — each one sitting beside a guarded sibling on the same bus
 * (issue #231). {@code ServerBootstrap#onPlayerLoggedIn} was guarded with the comment "must never
 * take the server down with the player mid-login"; its logout twin, which writes region archives to
 * disk <i>and</i> seeds them to the network, was not.
 *
 * <h2>What this asserts</h2>
 *
 * <p>Every method the <b>game</b> bus can call must have all of its calls inside a {@code catch}
 * that would hold a {@link RuntimeException}. Not "has a try somewhere" — every access, so that
 * adding a line after the guarded block is a failure rather than a silent regression. This is a
 * question about bytecode (a try's range lives in the exception table, and no source scanner can
 * tell a guarded statement from one written underneath a guard), which is why it is ArchUnit.
 *
 * <h2>What it deliberately does not assert</h2>
 *
 * <ul>
 *   <li><b>Mod-bus handlers.</b> A listener for an {@code IModBusEvent} runs during mod loading,
 *       where throwing is the documented way to refuse to load — {@code ClientBootstrap
 *       #onClientSetup} aborts startup on purpose when {@code companion.required} and no worker is
 *       there. Catching there would turn an enforced gate into a warning.</li>
 *   <li><b>Lambda bodies.</b> ArchUnit reports an access inside a lambda as an access of the
 *       enclosing method, but the lambda is a separate body that may run anywhere later. They are
 *       skipped; a handler whose only risky work happens inside a lambda it hands to somebody else
 *       is outside this rule's reach.</li>
 *   <li><b>Logging.</b> The calls in a {@code catch} block are, by construction, outside the try
 *       they belong to. Accesses to SLF4J and to {@link Throwable} are what a catch block is made
 *       of, so they do not count against it.</li>
 * </ul>
 *
 * <h2>The allowlist is a ratchet</h2>
 *
 * <p>{@link #UNGUARDED} is asserted to be <b>exactly</b> the set of offenders, not a superset: a
 * handler that gets a guard has to be deleted from the list, and a new unguarded handler fails the
 * build until somebody writes it down. It is debt, not exemption — every entry on it is a handler
 * that should eventually be guarded. The one genuine "must not catch" case, mod-bus loading, is
 * excluded above by type rather than by name.
 *
 * <p>Thread-context: single test thread.
 *
 * @see <a href="https://github.com/nodera/NoderaMC/issues/231">issue #231</a>
 */
final class EventHandlerFirewallTest {

    /**
     * Game-bus handlers that still let a call escape. <b>This list may only shrink.</b>
     *
     * <p>All of them are client-side screen decoration or diagnostics, which is why they are the
     * ones left: a throw here costs the player their client rather than a running world's server.
     * Issue #231 named the server handlers and the two client-lifecycle ones, and those are fixed.
     */
    private static final Set<String> UNGUARDED = new TreeSet<>(Set.of(
            "dev.nodera.mod.client.ClientStallReporter.onTick",
            "dev.nodera.mod.client.create.CreateWorldNoderaAddon.onScreenInit",
            "dev.nodera.mod.client.multiplayer.JoinPasswordScreen.onScreenInit",
            "dev.nodera.mod.client.multiplayer.MultiplayerScreenAddon.onScreenInit",
            "dev.nodera.mod.client.share.PauseScreenShareAddon.onScreenInit",
            "dev.nodera.mod.client.share.SaveScreenSeedOverlay.onScreenRender",
            "dev.nodera.mod.client.title.TitleScreenAddon.onScreenInit",
            "dev.nodera.mod.client.worldlist.SelectWorldScreenAddon.onScreenInit",
            "dev.nodera.mod.client.worldlist.SelectWorldScreenAddon.onScreenRender",
            // The odd one out: its body IS wrapped, but its catch block calls
            // `COUNTDOWN.disarm()` — recovery that is itself outside any try. A real finding, and
            // the smallest one on this list.
            "dev.nodera.mod.debug.SparkProfileBridge.onClientTick"));

    /**
     * The floor on how many handlers this test found.
     *
     * <p>Without it the rule is vacuous in the one way that matters: a discovery step that silently
     * matches nothing — a moved package, an ArchUnit version that stops modelling method references
     * — reports a clean run over an empty set. The number only ever goes up.
     */
    private static final int HANDLERS_AT_LEAST = 33;

    /**
     * Where NeoForge keeps the events its <b>game</b> bus posts.
     *
     * <p>The bus is decided by package rather than by asking whether the type implements
     * {@code IModBusEvent}, because NeoForge is not on this module's test runtime classpath —
     * ArchUnit imports the mod's own bytecode and every {@code net.neoforged} type it mentions is a
     * name with no resolved hierarchy behind it. {@link #everyRegisteredEventTypeIsClassified} is
     * what keeps that from rotting: an event package neither list knows fails the build instead of
     * being quietly treated as one or the other.
     */
    private static final Set<String> GAME_BUS_PACKAGES = Set.of(
            "net.neoforged.neoforge.event",
            "net.neoforged.neoforge.client.event");

    /** Mod-loading events. A listener for one of these is <b>supposed</b> to be able to throw. */
    private static final Set<String> MOD_BUS_PACKAGES = Set.of(
            "net.neoforged.fml.event",
            "net.neoforged.neoforge.network.event",
            "net.neoforged.neoforge.registries");

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        // importPackagesOf derives each package root from the class's own code source, which is the
        // only form that reliably finds the sibling .class files in this layout (see
        // ForbiddenApiTest). One representative per subpackage that registers a listener.
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackagesOf(
                        NoderaMod.class,
                        dev.nodera.mod.server.ServerBootstrap.class,
                        dev.nodera.mod.server.entity.EntityCaptureBridge.class,
                        dev.nodera.mod.server.shadow.BlockCaptureBridge.class,
                        dev.nodera.mod.common.ModNetworking.class,
                        dev.nodera.mod.client.ClientBootstrap.class,
                        dev.nodera.mod.client.multiplayer.MultiplayerScreenAddon.class,
                        dev.nodera.mod.client.share.PauseScreenShareAddon.class,
                        dev.nodera.mod.client.title.TitleScreenAddon.class,
                        dev.nodera.mod.client.create.CreateWorldNoderaAddon.class,
                        dev.nodera.mod.client.worldlist.SelectWorldScreenAddon.class,
                        dev.nodera.mod.debug.SparkProfileBridge.class);
    }

    @Test
    void theImportFoundTheModsClasses() {
        assertThat(classes.size())
                .as("ArchUnit must import the mod's classes; got 0 (classpath/import issue)")
                .isGreaterThan(0);
    }

    @Test
    void everyGameBusHandlerIsFound() {
        assertThat(gameBusHandlers())
                .as("the discovery step must keep finding handlers; a rule that matches nothing "
                        + "passes over an empty set and says nothing at all")
                .hasSizeGreaterThanOrEqualTo(HANDLERS_AT_LEAST);
    }

    @Test
    void everyGameBusHandlerCatchesWhatItThrows() {
        Set<String> offenders = new TreeSet<>();
        List<String> detail = new ArrayList<>();
        for (JavaMethod handler : gameBusHandlers()) {
            for (JavaAccess<?> access : handler.getAccessesFromSelf()) {
                if (access.isDeclaredInLambda() || isCatchBlockMachinery(access)) {
                    continue;
                }
                if (access.getContainingTryBlocks().stream()
                        .noneMatch(EventHandlerFirewallTest::wouldHoldARuntimeException)) {
                    offenders.add(name(handler));
                    detail.add(name(handler) + " → " + access.getTarget().getFullName()
                            + " (line " + access.getLineNumber() + ")");
                }
            }
        }
        assertThat(offenders)
                .as("NeoForge's bus rethrows into the game loop, so a game-bus handler that lets a "
                        + "call escape is a crash (issue #231). Either wrap it, or — if that is "
                        + "genuinely wrong — add it to UNGUARDED and say why. Unwrapped calls:%n%s",
                        String.join("\n", detail))
                .isEqualTo(UNGUARDED);
    }

    /**
     * A guard that spans a whole teardown sequence is not a firewall, it is a skip.
     *
     * <p>Catching keeps the process alive; it does nothing for the state the handler was in the
     * middle of tearing down. When several teardown steps share one {@code try}, the first throw
     * jumps past every later step, and the handler reports a warning while leaving exactly the
     * corruption it was written to prevent — a departed player still in the ownership plan, a
     * client peer runtime still serving a world its player has left. Nothing runs those steps
     * again: {@code PlayerNodeRegistry#forget} has one call site in the whole tree.
     *
     * <p>So each pair below names a step that can plausibly throw and a step after it that has to
     * run anyway, and asserts that <b>no single try block holds both</b>. Try ranges live in the
     * exception table, which is why this is bytecode and not a source scan: two statements written
     * one under the other look identical in source whether they share a guard or not.
     *
     * <p>This is a granularity rule, not a coverage rule — {@link
     * #everyGameBusHandlerCatchesWhatItThrows} already asserts that every one of these calls is
     * guarded by <i>something</i>.
     */
    @Test
    void aFailingTeardownStepDoesNotSkipTheOnesAfterIt() {
        List<String> shared = new ArrayList<>();
        for (String[] pair : TEARDOWN_STEPS_THAT_MUST_NOT_SHARE_A_GUARD) {
            JavaMethod handler = gameBusHandler(pair[0]);
            assertThat(callTargets(handler))
                    .as("the pair for %s names calls that must exist for the rule to mean "
                            + "anything; %s and %s are what it checks", pair[0], pair[1], pair[2])
                    .contains(pair[1], pair[2]);
            for (TryCatchBlock block : handler.getTryCatchBlocks()) {
                Set<String> guarded = new TreeSet<>();
                for (JavaAccess<?> access : block.getAccessesContainedInTryBlock()) {
                    guarded.add(access.getTarget().getFullName());
                }
                if (guarded.contains(pair[1]) && guarded.contains(pair[2])) {
                    shared.add(pair[0] + ": a throw from " + pair[1] + " skips " + pair[2]
                            + " — " + pair[3] + " (shared try at "
                            + block.getSourceCodeLocation() + ")");
                }
            }
        }
        assertThat(shared)
                .as("these steps share a guard, so the first one to throw silently cancels the "
                        + "rest of the teardown. Give each its own try/catch — the order between "
                        + "them is contract and must not change.%n%s", String.join("\n", shared))
                .isEmpty();
    }

    /**
     * {@code {handler, may-throw, must-still-run, what-is-left-broken}}.
     *
     * <p>Fully-qualified target names, matched against {@code JavaAccess.getTarget().getFullName()}
     * so a rename fails the test rather than quietly stopping it from checking anything.
     */
    private static final List<String[]> TEARDOWN_STEPS_THAT_MUST_NOT_SHARE_A_GUARD = List.of(
            new String[] {
                "dev.nodera.mod.server.ServerBootstrap.onPlayerLoggedOut",
                "dev.nodera.mod.common.NoderaHost.refreshWorkerPresence("
                        + "net.minecraft.server.MinecraftServer, net.minecraft.server.level.ServerPlayer)",
                "dev.nodera.endpoint.world.PlayerNodeRegistry.forget(java.util.UUID)",
                "the departed player's node stays in the ownership plan for good"},
            new String[] {
                "dev.nodera.mod.server.ServerBootstrap.onPlayerLoggedOut",
                "dev.nodera.mod.server.shadow.RegionDepartureHandoff.handOff("
                        + "net.minecraft.server.MinecraftServer, net.minecraft.server.level.ServerPlayer)",
                "dev.nodera.endpoint.world.PlayerNodeRegistry.forget(java.util.UUID)",
                "the archive-and-seed step is the most failure-prone thing this handler does, and "
                        + "losing one region's edits must not also cost the forget"},
            new String[] {
                "dev.nodera.mod.server.ServerBootstrap.onPlayerLoggedOut",
                "dev.nodera.mod.server.shadow.RegionDepartureHandoff.handOff("
                        + "net.minecraft.server.MinecraftServer, net.minecraft.server.level.ServerPlayer)",
                "dev.nodera.mod.common.NoderaHost.replanEntityLane("
                        + "net.minecraft.server.MinecraftServer)",
                "the committee keeps seats for a player who has gone"},
            new String[] {
                "dev.nodera.mod.client.ClientBootstrap.onLoggingOut",
                "dev.nodera.mod.client.entity.ClientValidationLane.stop()",
                "dev.nodera.mod.common.NoderaPeerService.stopClient()",
                "the client peer runtime keeps serving and validating a world its player has left"});

    /** The registered game-bus handler with this fully-qualified {@code Owner.method} name. */
    private static JavaMethod gameBusHandler(String qualifiedName) {
        for (JavaMethod handler : gameBusHandlers()) {
            if (name(handler).equals(qualifiedName)) {
                return handler;
            }
        }
        throw new AssertionError("no registered game-bus handler named " + qualifiedName
                + "; the rule below would check nothing");
    }

    /** Every call target of a handler, including the ones inside lambdas. */
    private static Set<String> callTargets(JavaMethod handler) {
        Set<String> targets = new TreeSet<>();
        for (JavaAccess<?> access : handler.getAccessesFromSelf()) {
            targets.add(access.getTarget().getFullName());
        }
        return targets;
    }

    @Test
    void everyRegisteredEventTypeIsClassified() {
        Set<String> unclassified = new TreeSet<>();
        for (JavaMethod listener : referencedListeners()) {
            String eventPackage = eventPackageOf(listener);
            if (!isUnder(eventPackage, GAME_BUS_PACKAGES)
                    && !isUnder(eventPackage, MOD_BUS_PACKAGES)) {
                unclassified.add(eventPackage + " (via " + name(listener) + ")");
            }
        }
        assertThat(unclassified)
                .as("an event package neither list knows would be silently treated as mod-loading "
                        + "and never checked; say which bus posts it")
                .isEmpty();
    }

    @Test
    void theAllowlistNamesOnlyMethodsThatStillExist() {
        Set<String> known = new TreeSet<>();
        for (JavaMethod handler : gameBusHandlers()) {
            known.add(name(handler));
        }
        assertThat(known)
                .as("an allowlist entry for a handler that no longer exists is a line nobody will "
                        + "ever delete; the ratchet only works while every row is real")
                .containsAll(UNGUARDED);
    }

    /**
     * Every method registered as a listener on the NeoForge <b>game</b> bus.
     *
     * <p>Found by method reference rather than by name or by parameter type alone: {@code
     * NeoForge.EVENT_BUS.addListener(Foo::bar)} compiles to a reference to {@code bar}, so this
     * asks the bytecode which methods are actually wired up instead of guessing from a naming
     * convention. A handler that stops being registered stops being checked, which is correct — it
     * is no longer on the bus.
     */
    private static Set<JavaMethod> gameBusHandlers() {
        Set<JavaMethod> handlers = new LinkedHashSet<>();
        for (JavaMethod listener : referencedListeners()) {
            if (isUnder(eventPackageOf(listener), GAME_BUS_PACKAGES)) {
                handlers.add(listener);
            }
        }
        return handlers;
    }

    /** Package equality or containment: {@code …event.tick} is under {@code …event}. */
    private static boolean isUnder(String packageName, Set<String> roots) {
        return roots.stream().anyMatch(root ->
                packageName.equals(root) || packageName.startsWith(root + "."));
    }

    /** Every method reference in the mod whose single parameter is a NeoForge event type. */
    private static Set<JavaMethod> referencedListeners() {
        Set<JavaMethod> listeners = new LinkedHashSet<>();
        for (JavaClass clazz : classes) {
            for (JavaCodeUnit unit : clazz.getCodeUnits()) {
                for (JavaMethodReference reference : unit.getMethodReferencesFromSelf()) {
                    reference.getTarget().resolveMember()
                            .filter(m -> m.getRawParameterTypes().size() == 1)
                            .filter(m -> m.getRawParameterTypes().get(0).getName()
                                    .startsWith("net.neoforged."))
                            .ifPresent(listeners::add);
                }
            }
        }
        return listeners;
    }

    /** The package of a listener's event, with any nested-class suffix removed. */
    private static String eventPackageOf(JavaMethod listener) {
        return listener.getRawParameterTypes().get(0).getPackageName();
    }

    /**
     * A try block whose {@code catch} would hold a {@link RuntimeException}.
     *
     * <p>{@code catch (RuntimeException | LinkageError e)} — this tree's idiom — qualifies on its
     * first alternative. A block that catches only {@code IOException} does not: it is error
     * handling, not a firewall.
     */
    private static boolean wouldHoldARuntimeException(TryCatchBlock block) {
        return block.getCaughtThrowables().stream().anyMatch(caught ->
                caught.getName().equals(RuntimeException.class.getName())
                        || caught.getName().equals(Exception.class.getName())
                        || caught.getName().equals(Throwable.class.getName()));
    }

    /** Logging a caught throwable is what a catch block does; it cannot be inside its own try. */
    private static boolean isCatchBlockMachinery(JavaAccess<?> access) {
        // A class's own `private static final Logger LOG` field: the read is an access to the
        // declaring class, so the package of the target owner does not give it away — the field's
        // TYPE does. Without this, every handler that logs its own failure fails the rule for
        // logging its own failure.
        if (access instanceof JavaFieldAccess field
                && field.getTarget().getRawType().getPackageName().startsWith("org.slf4j")) {
            return true;
        }
        JavaClass owner = access.getTargetOwner();
        return owner.getPackageName().startsWith("org.slf4j")
                || owner.getName().equals(Throwable.class.getName())
                || owner.getAllRawSuperclasses().stream()
                        .anyMatch(c -> c.getName().equals(Throwable.class.getName()));
    }

    private static String name(JavaMethod method) {
        return method.getOwner().getName() + "." + method.getName();
    }
}
