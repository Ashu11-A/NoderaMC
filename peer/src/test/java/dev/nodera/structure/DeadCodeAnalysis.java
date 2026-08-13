package dev.nodera.structure;

import dev.nodera.structure.CodeGraph.ClassInfo;
import dev.nodera.structure.CodeGraph.MethodId;
import dev.nodera.structure.CodeGraph.MethodInfo;
import dev.nodera.structure.CodeGraph.Origin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which of the code we compiled is actually part of the running system.
 *
 * <h2>Three verdicts, three confidence levels</h2>
 *
 * <ol>
 *   <li><b>never referenced</b> — nothing in the tree names it. Highest confidence: delete it, or
 *       explain why it is here.</li>
 *   <li><b>test-only</b> — production code that only tests and benchmarks call. This is the
 *       repository's most common defect shape: implemented, covered, and wired to nothing. A green
 *       suite actively hides it, which is why it gets its own verdict instead of being folded into
 *       "referenced".</li>
 *   <li><b>unreachable</b> — referenced, but only by other unreachable code: a dead cluster that
 *       keeps itself looking alive. Found by walking OUT from the real entry points rather than by
 *       counting references inwards.</li>
 * </ol>
 *
 * <h2>What is deliberately never called dead</h2>
 *
 * <p>A report that cries wolf gets ignored, so anything that cannot be proven dead is not claimed
 * to be: methods that satisfy an external interface (the JDK, NeoForge, JUnit — their callers are
 * not in our bytecode), compiler-generated members (record accessors, {@code values()},
 * {@code equals}/{@code hashCode}/{@code toString}), abstract declarations, and every member of a
 * class whose supertypes are not on this classpath (the NeoForge module, when Minecraft is not
 * resolvable). Those are counted separately as <i>unanalysable</i> so the report never pretends its
 * coverage is bigger than it is.
 *
 * <p>Thread-context: computed once in {@link #of}; the result is immutable.
 */
record DeadCodeAnalysis(
        List<MethodId> neverReferenced,
        List<MethodId> testOnly,
        List<MethodId> unreachable,
        List<ClassInfo> deadClasses,
        List<ClassInfo> testOnlyClasses,
        int analysedMethods,
        int unanalysableMethods,
        Set<MethodId> reachable) {

    /** Methods the compiler generates and the runtime calls; never our dead code. */
    private static final Set<String> GENERATED = Set.of(
            "<clinit>", "values", "valueOf", "$values", "equals", "hashCode", "toString",
            "compareTo", "clone", "finalize", "readResolve", "writeReplace", "readObject",
            "writeObject");

    /** Annotations that mean "something outside this tree calls me". */
    private static final Set<String> ENTRY_ANNOTATIONS = Set.of(
            "org.junit.jupiter.api.Test",
            "org.junit.jupiter.api.RepeatedTest",
            "org.junit.jupiter.api.TestFactory",
            "org.junit.jupiter.api.BeforeEach",
            "org.junit.jupiter.api.BeforeAll",
            "org.junit.jupiter.api.AfterEach",
            "org.junit.jupiter.api.AfterAll",
            "org.junit.jupiter.params.ParameterizedTest",
            "net.jqwik.api.Property",
            "net.jqwik.api.Example",
            "com.tngtech.archunit.junit.ArchTest",
            "org.openjdk.jmh.annotations.Benchmark",
            "org.openjdk.jmh.annotations.Setup",
            "org.openjdk.jmh.annotations.TearDown",
            // NeoForge dispatches these by reflection from its own event bus.
            "net.neoforged.bus.api.SubscribeEvent",
            "net.neoforged.fml.common.Mod",
            "net.neoforged.fml.common.EventBusSubscriber",
            // Bukkit dispatches a Listener's handlers reflectively out of registerEvents, and
            // WorldEdit's own event bus does the same for @Subscribe. That reflective call is the
            // ONLY caller such a method will ever have, so without these the endpoint plugin's
            // event handlers read as methods nothing in the tree references — which is true, and
            // is not the same statement as "nothing calls them".
            "org.bukkit.event.EventHandler",
            "com.sk89q.worldedit.util.eventbus.Subscribe",
            // Mixins are wired by a JSON config and applied by a bytecode transformer, so nothing
            // in our tree ever names them.
            "org.spongepowered.asm.mixin.Mixin",
            "org.spongepowered.asm.mixin.injection.Inject",
            "org.spongepowered.asm.mixin.injection.Redirect",
            "org.spongepowered.asm.mixin.injection.ModifyArg",
            "org.spongepowered.asm.mixin.injection.ModifyVariable",
            "org.spongepowered.asm.mixin.Overwrite");

    static DeadCodeAnalysis of(CodeGraph graph) {
        List<MethodId> never = new ArrayList<>();
        List<MethodId> testOnly = new ArrayList<>();
        int analysed = 0;
        int unanalysable = 0;

        Set<MethodId> reachable = reachableFromEntryPoints(graph);

        for (ClassInfo info : graph.productionClasses()) {
            boolean resolvable = graph.hierarchyIsResolvable(info);
            for (MethodInfo method : info.methods().values()) {
                if (!analysable(graph, info, method)) {
                    unanalysable++;
                    continue;
                }
                if (!resolvable) {
                    // Its supertypes are off-classpath (the NeoForge module without Minecraft), so
                    // we cannot know whether a framework calls it. Counted, never accused.
                    unanalysable++;
                    continue;
                }
                analysed++;
                Set<Origin> origins = graph.referencesTo(method.id());
                if (origins.isEmpty()) {
                    never.add(method.id());
                } else if (!origins.contains(Origin.PRODUCTION)) {
                    testOnly.add(method.id());
                }
            }
        }

        List<MethodId> unreachable = new ArrayList<>();
        for (ClassInfo info : graph.productionClasses()) {
            if (!graph.hierarchyIsResolvable(info)) {
                continue;
            }
            for (MethodInfo method : info.methods().values()) {
                if (!analysable(graph, info, method) || reachable.contains(method.id())) {
                    continue;
                }
                Set<Origin> origins = graph.referencesTo(method.id());
                if (origins.isEmpty() || !origins.contains(Origin.PRODUCTION)) {
                    // Already reported with a stronger verdict.
                    continue;
                }
                unreachable.add(method.id());
            }
        }

        List<ClassInfo> deadClasses = new ArrayList<>();
        List<ClassInfo> testOnlyClasses = new ArrayList<>();
        for (ClassInfo info : graph.productionClasses()) {
            if (info.isSynthetic() || info.name().contains("$$") || isPackageInfo(info)) {
                continue;
            }
            if (!graph.hierarchyIsResolvable(info) || hasEntryAnnotation(info.annotations())) {
                continue;
            }
            if (declaresMain(info) || info.constantHolder()) {
                // Constants are inlined into their callers, so a constant holder has no incoming
                // reference however widely it is used.
                continue;
            }
            Set<Origin> origins = graph.referencesTo(info.name());
            if (origins.isEmpty()) {
                deadClasses.add(info);
            } else if (!origins.contains(Origin.PRODUCTION)) {
                testOnlyClasses.add(info);
            }
        }

        never.sort(Comparator.naturalOrder());
        testOnly.sort(Comparator.naturalOrder());
        unreachable.sort(Comparator.naturalOrder());
        deadClasses.sort(Comparator.comparing(ClassInfo::name));
        testOnlyClasses.sort(Comparator.comparing(ClassInfo::name));

        return new DeadCodeAnalysis(List.copyOf(never), List.copyOf(testOnly),
                List.copyOf(unreachable), List.copyOf(deadClasses), List.copyOf(testOnlyClasses),
                analysed, unanalysable, reachable);
    }

    /**
     * Walk out from every real entry point over production call edges.
     *
     * <p>Virtual dispatch is expanded conservatively in both directions: a call to
     * {@code Interface.handle()} keeps every implementation's {@code handle()} alive, and a call to
     * {@code Subclass.handle()} keeps an inherited {@code Superclass.handle()} alive. Being
     * conservative here means the "unreachable" list under-reports rather than accuses working code
     * — the only useful failure direction for a gate somebody has to trust.
     */
    private static Set<MethodId> reachableFromEntryPoints(CodeGraph graph) {
        Set<MethodId> reachable = new LinkedHashSet<>();
        Deque<MethodId> queue = new ArrayDeque<>();

        for (ClassInfo info : graph.productionClasses()) {
            boolean unresolvable = !graph.hierarchyIsResolvable(info);
            // A class initialiser runs when the class is first used, so anything it constructs is
            // live the moment anything in production touches the class. Missing that reported every
            // singleton, every `static final` holder, and every constant built in a `<clinit>` as
            // unreachable — the running worker disagreed, which is how it was found.
            boolean classIsUsed = graph.referencesTo(info.name()).contains(Origin.PRODUCTION)
                    || declaresMain(info);
            for (MethodInfo method : info.methods().values()) {
                boolean root = isMain(method)
                        || hasEntryAnnotation(method.annotations())
                        || hasEntryAnnotation(info.annotations())
                        || graph.overridesExternal(info, method)
                        || (method.id().name().equals("<clinit>") && classIsUsed)
                        || unresolvable;
                if (root && reachable.add(method.id())) {
                    queue.add(method.id());
                }
            }
        }

        while (!queue.isEmpty()) {
            MethodId current = queue.poll();
            for (MethodId target : graph.callsFrom(current)) {
                for (MethodId resolved : dispatchTargets(graph, target)) {
                    if (reachable.add(resolved)) {
                        queue.add(resolved);
                    }
                }
            }
        }
        return reachable;
    }

    /** The call target itself, every override of it below, and the declaration above it. */
    private static List<MethodId> dispatchTargets(CodeGraph graph, MethodId target) {
        List<MethodId> out = new ArrayList<>();
        out.add(target);
        Deque<String> down = new ArrayDeque<>(graph.subtypesOf(target.owner()));
        Set<String> seen = new LinkedHashSet<>();
        while (!down.isEmpty()) {
            String subtype = down.poll();
            if (!seen.add(subtype)) {
                continue;
            }
            out.add(new MethodId(subtype, target.name(), target.desc()));
            down.addAll(graph.subtypesOf(subtype));
        }
        ClassInfo owner = graph.classInfo(target.owner());
        while (owner != null && owner.superName() != null) {
            out.add(new MethodId(owner.superName(), target.name(), target.desc()));
            owner = graph.classInfo(owner.superName());
        }
        return out;
    }

    /**
     * Is this method something we can make a claim about at all?
     *
     * <p>False for compiler-generated members, synthetic/bridge methods, abstract declarations,
     * record components, lambda bodies (whose reference lives in an {@code invokedynamic} we
     * already followed), and anything satisfying an external supertype.
     */
    private static boolean analysable(CodeGraph graph, ClassInfo owner, MethodInfo method) {
        String name = method.id().name();
        if (method.isSynthetic() || method.isAbstract() || GENERATED.contains(name)) {
            return false;
        }
        if (name.startsWith("lambda$") || name.startsWith("access$") || name.contains("$")) {
            return false;
        }
        if (name.equals("<init>") && method.id().desc().equals("()V")
                && (method.access() & org.objectweb.asm.Opcodes.ACC_PRIVATE) != 0) {
            // The `private Foo() {}` idiom that exists purely to forbid instantiation.
            return false;
        }
        if (owner.constantHolder()) {
            return false;
        }
        if (owner.isInterface() || owner.isEnum()) {
            // Interface members are call targets by definition; enum constants are constructed by
            // the class initialiser the runtime runs for us.
            return false;
        }
        if (owner.isRecord() && method.id().desc().startsWith("()")
                && owner.methods().containsKey(method.id())) {
            // Record accessors are generated from the component list.
            return false;
        }
        if (hasEntryAnnotation(method.annotations()) || hasEntryAnnotation(owner.annotations())) {
            return false;
        }
        if (isMain(method)) {
            return false;
        }
        return !graph.overridesExternal(owner, method);
    }

    private static boolean isMain(MethodInfo method) {
        return method.isStatic() && method.id().name().equals("main")
                && method.id().desc().equals("([Ljava/lang/String;)V");
    }

    private static boolean declaresMain(ClassInfo info) {
        return info.methods().values().stream().anyMatch(DeadCodeAnalysis::isMain);
    }

    private static boolean isPackageInfo(ClassInfo info) {
        return info.name().endsWith("/package-info");
    }

    private static boolean hasEntryAnnotation(Set<String> annotations) {
        for (String annotation : annotations) {
            if (ENTRY_ANNOTATIONS.contains(annotation)) {
                return true;
            }
        }
        return false;
    }
}
