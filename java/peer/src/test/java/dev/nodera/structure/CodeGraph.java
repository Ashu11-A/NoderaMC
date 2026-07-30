package dev.nodera.structure;

import dev.nodera.testkit.harness.LayoutManifest;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The compiled tree, read as a graph: who calls whom, who is never called, and which methods carry
 * work that costs more than it looks like it does.
 *
 * <h2>Why bytecode and not source</h2>
 *
 * <p>Three things this report needs are only true after compilation. A method reference
 * ({@code this::handle}) is a source-level identifier but a bytecode-level {@code invokedynamic}
 * bootstrap argument — read the source and every lambda target looks dead. A loop is a source
 * construct with five spellings ({@code for}, enhanced-{@code for}, {@code while},
 * {@code do/while}, {@code Stream.forEach}) but exactly one bytecode signature: a jump to an
 * earlier instruction. And "too big for the JIT to compile" is a property of the emitted code, not
 * of how many lines somebody wrote.
 *
 * <p>So this reads {@code java/&lt;module&gt;/build/classes/java/&lt;sourceSet&gt;} with ASM. It
 * never loads a class: analysing the NeoForge module would otherwise need Minecraft on the
 * classpath, and analysing anything would run its static initialisers.
 *
 * <h2>Production origin vs harness origin</h2>
 *
 * <p>Every reference records where it came from. A call from {@code src/test/java} or from the JMH
 * benchmark source set does NOT make production code alive — that distinction is the entire point
 * of the report, because this repository's dominant defect is code that is implemented, tested, and
 * never called from a production path.
 *
 * <p>Thread-context: build once on one thread ({@link #scan}), then read-only.
 */
final class CodeGraph {

    /** Package prefix that makes a class ours. Everything else is an external boundary. */
    static final String OURS = "dev/nodera/";

    /** Estimated bytecode size above which HotSpot refuses to JIT-compile at all. */
    static final int HUGE_METHOD_BYTES = 8_000;

    /** Estimated bytecode size above which HotSpot will never inline the method. */
    static final int LARGE_METHOD_BYTES = 2_000;

    /** Where a reference came from. Only {@link Origin#PRODUCTION} keeps code alive. */
    enum Origin {
        /** {@code src/main/java} — the shipped code. */
        PRODUCTION,
        /** {@code src/test/java} — proves behaviour, does not constitute a caller. */
        TEST,
        /** {@code src/jmh/java} — measures behaviour, does not constitute a caller either. */
        HARNESS
    }

    /** One method, addressed the way the constant pool addresses it. */
    record MethodId(String owner, String name, String desc) implements Comparable<MethodId> {

        String simpleOwner() {
            return owner.substring(owner.lastIndexOf('/') + 1).replace('$', '.');
        }

        String packageName() {
            int slash = owner.lastIndexOf('/');
            return slash < 0 ? "" : owner.substring(0, slash).replace('/', '.');
        }

        String display() {
            return packageName() + "." + simpleOwner() + "#" + name + shortDesc();
        }

        String shortDesc() {
            StringBuilder out = new StringBuilder("(");
            Type[] args = Type.getArgumentTypes(desc);
            for (int i = 0; i < args.length; i++) {
                if (i > 0) {
                    out.append(", ");
                }
                String name = args[i].getClassName();
                out.append(name.substring(name.lastIndexOf('.') + 1));
            }
            return out.append(')').toString();
        }

        @Override
        public int compareTo(MethodId other) {
            int byOwner = owner.compareTo(other.owner);
            if (byOwner != 0) {
                return byOwner;
            }
            int byName = name.compareTo(other.name);
            return byName != 0 ? byName : desc.compareTo(other.desc);
        }
    }

    /** One method's structural facts. */
    record MethodInfo(MethodId id, int access, Set<String> annotations) {

        boolean isStatic() {
            return (access & Opcodes.ACC_STATIC) != 0;
        }

        boolean isAbstract() {
            return (access & Opcodes.ACC_ABSTRACT) != 0;
        }

        boolean isSynthetic() {
            return (access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0;
        }
    }

    /** One class's structural facts. */
    record ClassInfo(String name, String superName, List<String> interfaces, int access,
                     String module, Origin origin, Set<String> annotations,
                     Map<MethodId, MethodInfo> methods, boolean constantHolder) {

        boolean isInterface() {
            return (access & Opcodes.ACC_INTERFACE) != 0;
        }

        boolean isEnum() {
            return (access & Opcodes.ACC_ENUM) != 0;
        }

        boolean isRecord() {
            return (access & Opcodes.ACC_RECORD) != 0;
        }

        boolean isSynthetic() {
            return (access & Opcodes.ACC_SYNTHETIC) != 0;
        }

        String display() {
            return name.replace('/', '.').replace('$', '.');
        }
    }

    /** One thing worth fixing, found in the bytecode of one method. */
    record Finding(MethodId method, String module, String kind, String detail, int count,
                   int severity) {
    }

    private final Map<String, ClassInfo> classes = new LinkedHashMap<>();
    private final Map<MethodId, Set<Origin>> methodReferences = new HashMap<>();
    private final Map<String, Set<Origin>> classReferences = new HashMap<>();
    private final Map<MethodId, Set<MethodId>> callsFrom = new HashMap<>();
    private final List<Finding> findings = new ArrayList<>();
    private final Map<String, Set<String>> subtypes = new HashMap<>();

    private CodeGraph() {
    }

    /**
     * Read every compiled class under the given module output roots.
     *
     * @param roots {@code (module, sourceSet origin, directory)} triples produced by
     *              {@link #outputRoots(Path)}.
     * @return the graph.
     */
    static CodeGraph scan(List<ScanRoot> roots) {
        CodeGraph graph = new CodeGraph();
        for (ScanRoot root : roots) {
            if (!Files.isDirectory(root.directory())) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root.directory())) {
                files.filter(p -> p.toString().endsWith(".class"))
                        .sorted()
                        .forEach(p -> graph.readClass(p, root));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot walk " + root.directory(), e);
            }
        }
        graph.indexHierarchy();
        return graph;
    }

    /** One compiled source set of one module. */
    record ScanRoot(String module, Origin origin, Path directory) {
    }

    /**
     * The compiled output roots of every Gradle module in the tree.
     *
     * <p>Read from {@code layout.properties} rather than a hand-kept list, so a module added
     * tomorrow is analysed the day it is added. Listing a directory (what this did before) looked
     * equivalent but was not: it also picked up {@code build-logic}, which is an included build with
     * no {@code build/classes/java} at all, and it silently stopped finding anything the moment a
     * module lived outside one parent directory.
     */
    static List<ScanRoot> outputRoots(Path repoRoot) {
        List<ScanRoot> roots = new ArrayList<>();
        LayoutManifest.load(repoRoot).modules().forEach((name, directory) -> {
            Path classes = directory.resolve("build/classes/java");
            // `:testing` is a library FOR tests (LoopbackTransport, FakeRegion, fixture IO).
            // Counting its main source set as production would let a test harness keep
            // production code looking alive, which is the exact illusion this report exists to
            // remove.
            Origin mainOrigin = name.equals("testing") ? Origin.TEST : Origin.PRODUCTION;
            roots.add(new ScanRoot(name, mainOrigin, classes.resolve("main")));
            roots.add(new ScanRoot(name, Origin.TEST, classes.resolve("test")));
            roots.add(new ScanRoot(name, Origin.HARNESS, classes.resolve("jmh")));
        });
        return roots;
    }

    private void readClass(Path file, ScanRoot root) {
        ClassNode node = new ClassNode();
        try (InputStream in = Files.newInputStream(file)) {
            new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }

        Map<MethodId, MethodInfo> methods = new LinkedHashMap<>();
        for (MethodNode method : node.methods) {
            MethodId id = new MethodId(node.name, method.name, method.desc);
            methods.put(id, new MethodInfo(id, method.access,
                    annotationsOf(method.visibleAnnotations, method.invisibleAnnotations)));
            if (root.origin() == Origin.PRODUCTION) {
                inspectForCost(id, root.module(), method, loopMask(method));
            }
            collectReferences(id, node, method, root.origin());
        }

        ClassInfo info = new ClassInfo(
                node.name,
                node.superName,
                node.interfaces == null ? List.of() : List.copyOf(node.interfaces),
                node.access,
                root.module(),
                root.origin(),
                annotationsOf(node.visibleAnnotations, node.invisibleAnnotations),
                methods,
                isConstantHolder(node));
        // First writer wins: a module's own output is authoritative, and a duplicate on a second
        // root would only ever be a stale copy.
        classes.putIfAbsent(node.name, info);

        // Structural references that are not instructions: supertypes, field types, signatures.
        referenceClass(node.name, node.superName, root.origin());
        if (node.interfaces != null) {
            node.interfaces.forEach(i -> referenceClass(node.name, i, root.origin()));
        }
        if (node.fields != null) {
            for (FieldNode field : node.fields) {
                referenceType(node.name, Type.getType(field.desc), root.origin());
            }
        }
    }

    private void collectReferences(MethodId from, ClassNode owner, MethodNode method, Origin origin) {
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            referenceType(from.owner(), argument, origin);
        }
        referenceType(from.owner(), Type.getReturnType(method.desc), origin);

        InsnList instructions = method.instructions;
        if (instructions == null) {
            return;
        }
        for (AbstractInsnNode insn : instructions) {
            switch (insn) {
                case MethodInsnNode call -> {
                    referenceClass(from.owner(), call.owner, origin);
                    referenceMethod(from, new MethodId(call.owner, call.name, call.desc), origin);
                }
                case FieldInsnNode field -> {
                    referenceClass(from.owner(), field.owner, origin);
                    referenceType(from.owner(), Type.getType(field.desc), origin);
                }
                case TypeInsnNode type -> referenceClass(from.owner(), type.desc, origin);
                case MultiANewArrayInsnNode array ->
                        referenceType(from.owner(), Type.getType(array.desc), origin);
                case LdcInsnNode ldc -> {
                    if (ldc.cst instanceof Type type) {
                        referenceType(from.owner(), type, origin);
                    } else if (ldc.cst instanceof Handle handle) {
                        referenceHandle(from, handle, origin);
                    }
                }
                case InvokeDynamicInsnNode indy -> {
                    // Lambdas and method references live ONLY here. Missing them would report every
                    // `this::handle` target and every lambda body as dead code.
                    referenceHandle(from, indy.bsm, origin);
                    for (Object argument : indy.bsmArgs) {
                        if (argument instanceof Handle handle) {
                            referenceHandle(from, handle, origin);
                        } else if (argument instanceof Type type) {
                            referenceType(from.owner(), type, origin);
                        }
                    }
                }
                default -> { }
            }
        }
        // Annotations on the owning class can name types too (e.g. a listener registration).
        if (owner.visibleAnnotations != null) {
            for (AnnotationNode annotation : owner.visibleAnnotations) {
                referenceType(from.owner(), Type.getType(annotation.desc), origin);
            }
        }
    }

    private void referenceHandle(MethodId from, Handle handle, Origin origin) {
        referenceClass(from.owner(), handle.getOwner(), origin);
        if (handle.getTag() == Opcodes.H_GETFIELD || handle.getTag() == Opcodes.H_PUTFIELD
                || handle.getTag() == Opcodes.H_GETSTATIC || handle.getTag() == Opcodes.H_PUTSTATIC) {
            return;
        }
        referenceMethod(from, new MethodId(handle.getOwner(), handle.getName(), handle.getDesc()),
                origin);
    }

    private void referenceMethod(MethodId from, MethodId target, Origin origin) {
        if (!target.owner().startsWith(OURS) || target.equals(from)) {
            // Direct recursion is not a caller: a method that only calls itself is still dead.
            return;
        }
        methodReferences.computeIfAbsent(target, k -> originSet()).add(origin);
        if (origin == Origin.PRODUCTION) {
            callsFrom.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(target);
        }
    }

    private static Set<Origin> originSet() {
        return new LinkedHashSet<>();
    }

    private void referenceClass(String from, String internalName, Origin origin) {
        if (internalName == null) {
            return;
        }
        String name = internalName;
        if (name.startsWith("[")) {
            referenceType(from, Type.getType(name), origin);
            return;
        }
        if (!name.startsWith(OURS) || name.equals(from)) {
            // A class referring to itself is not a user of itself.
            return;
        }
        classReferences.computeIfAbsent(name, k -> originSet()).add(origin);
    }

    private void referenceType(String from, Type type, Origin origin) {
        Type element = type;
        while (element.getSort() == Type.ARRAY) {
            element = element.getElementType();
        }
        if (element.getSort() == Type.OBJECT) {
            referenceClass(from, element.getInternalName(), origin);
        }
    }

    /**
     * Is this class nothing but compile-time constants?
     *
     * <p>{@code static final} primitives and strings are INLINED into every caller by javac, so a
     * constant holder like {@code TypeTags} has no incoming bytecode reference no matter how many
     * classes depend on it. Reporting one as dead would be reporting a fact about the language
     * rather than about the code.
     */
    private static boolean isConstantHolder(ClassNode node) {
        if (node.fields == null || node.fields.isEmpty()) {
            return false;
        }
        for (FieldNode field : node.fields) {
            boolean constant = (field.access & Opcodes.ACC_STATIC) != 0
                    && (field.access & Opcodes.ACC_FINAL) != 0
                    && (field.desc.length() == 1 || field.desc.equals("Ljava/lang/String;"));
            if (!constant) {
                return false;
            }
        }
        for (MethodNode method : node.methods) {
            boolean plumbing = method.name.equals("<init>") || method.name.equals("<clinit>");
            if (!plumbing && (method.access & Opcodes.ACC_STATIC) == 0) {
                return false;
            }
        }
        return true;
    }

    private void indexHierarchy() {
        for (ClassInfo info : classes.values()) {
            if (info.superName() != null) {
                subtypes.computeIfAbsent(info.superName(), k -> new LinkedHashSet<>()).add(info.name());
            }
            for (String parent : info.interfaces()) {
                subtypes.computeIfAbsent(parent, k -> new LinkedHashSet<>()).add(info.name());
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Cost inspection
    // ------------------------------------------------------------------------------------------

    /**
     * Look for work that costs more than the source suggests.
     *
     * <p>Every check here is a fact about the emitted code, not a style opinion: an allocation
     * inside a loop IS an allocation per iteration, and a method over
     * {@value #HUGE_METHOD_BYTES} bytes IS one HotSpot declines to compile. The report ranks them,
     * it does not "fix" them — several are perfectly correct in cold code, which is why the runtime
     * profile (which methods actually execute, and how often) is what turns a finding into a
     * priority.
     */
    private void inspectForCost(MethodId id, String module, MethodNode method, boolean[] inLoop) {
        int estimated = estimateBytes(method);
        if (estimated > HUGE_METHOD_BYTES) {
            findings.add(new Finding(id, module, "huge-method",
                    "~" + estimated + " bytes of bytecode: over HotSpot's DontCompileHugeMethods "
                            + "limit (" + HUGE_METHOD_BYTES + "), so it stays interpreted forever",
                    1, 3));
        } else if (estimated > LARGE_METHOD_BYTES) {
            findings.add(new Finding(id, module, "large-method",
                    "~" + estimated + " bytes of bytecode: past the inlining limit, so every call "
                            + "site pays a real call", 1, 1));
        }

        if (method.instructions == null || inLoop.length == 0) {
            return;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> examples = new LinkedHashMap<>();
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            boolean hot = index < inLoop.length && inLoop[index];
            index++;
            if (!hot) {
                continue;
            }
            switch (insn) {
                case TypeInsnNode type when insn.getOpcode() == Opcodes.NEW
                        || insn.getOpcode() == Opcodes.ANEWARRAY -> {
                    String kind = type.desc.startsWith("java/lang/StringBuilder")
                            ? "string-concat-in-loop" : "alloc-in-loop";
                    counts.merge(kind, 1, Integer::sum);
                    examples.putIfAbsent(kind, "new " + simple(type.desc));
                }
                case MethodInsnNode call -> {
                    String kind = costlyCall(call);
                    if (kind != null) {
                        counts.merge(kind, 1, Integer::sum);
                        examples.putIfAbsent(kind, simple(call.owner) + "." + call.name + "()");
                    }
                }
                case InvokeDynamicInsnNode indy when indy.name.startsWith("makeConcat") -> {
                    counts.merge("string-concat-in-loop", 1, Integer::sum);
                    examples.putIfAbsent("string-concat-in-loop", "string concatenation");
                }
                case AbstractInsnNode any when any.getOpcode() == Opcodes.MONITORENTER -> {
                    counts.merge("lock-in-loop", 1, Integer::sum);
                    examples.putIfAbsent("lock-in-loop", "synchronized block");
                }
                default -> { }
            }
        }
        counts.forEach((kind, count) -> findings.add(new Finding(
                id, module, kind, describe(kind, examples.get(kind)), count, severityOf(kind))));
    }

    private static String costlyCall(MethodInsnNode call) {
        String owner = call.owner;
        String name = call.name;
        if (owner.equals("java/util/List") || owner.equals("java/util/ArrayList")
                || owner.equals("java/util/LinkedList") || owner.equals("java/util/Collection")) {
            if (name.equals("contains") || name.equals("indexOf") || name.equals("remove")) {
                return "linear-search-in-loop";
            }
        }
        if (owner.equals("java/util/regex/Pattern") && name.equals("compile")) {
            return "regex-compile-in-loop";
        }
        if (owner.equals("java/lang/String") && (name.equals("format") || name.equals("split"))) {
            return "format-in-loop";
        }
        if (name.equals("valueOf") && (owner.equals("java/lang/Integer") || owner.equals("java/lang/Long")
                || owner.equals("java/lang/Double") || owner.equals("java/lang/Float")
                || owner.equals("java/lang/Short") || owner.equals("java/lang/Character"))) {
            return "boxing-in-loop";
        }
        if ((owner.equals("java/util/Collection") || owner.equals("java/util/List")
                || owner.equals("java/util/Set") || owner.equals("java/util/Map"))
                && (name.equals("stream") || name.equals("parallelStream"))) {
            return "stream-in-loop";
        }
        if (owner.startsWith("java/security/MessageDigest") && name.equals("getInstance")) {
            return "digest-alloc-in-loop";
        }
        return null;
    }

    private static String describe(String kind, String example) {
        return switch (kind) {
            case "alloc-in-loop" -> "allocates per iteration (" + example + ")";
            case "string-concat-in-loop" -> "builds strings per iteration (" + example + ")";
            case "boxing-in-loop" -> "boxes a primitive per iteration (" + example + ")";
            case "linear-search-in-loop" -> "linear scan inside a loop (" + example
                    + ") — quadratic in the collection size";
            case "regex-compile-in-loop" -> "compiles a regex per iteration (" + example + ")";
            case "format-in-loop" -> "formats/splits a string per iteration (" + example + ")";
            case "stream-in-loop" -> "opens a stream pipeline per iteration (" + example + ")";
            case "lock-in-loop" -> "acquires a monitor per iteration (" + example + ")";
            case "digest-alloc-in-loop" -> "allocates a MessageDigest per iteration (" + example + ")";
            default -> example == null ? kind : example;
        };
    }

    private static int severityOf(String kind) {
        return switch (kind) {
            case "linear-search-in-loop", "regex-compile-in-loop" -> 3;
            case "string-concat-in-loop", "format-in-loop", "lock-in-loop", "digest-alloc-in-loop" -> 2;
            default -> 1;
        };
    }

    private static String simple(String internalName) {
        String name = internalName.startsWith("L") && internalName.endsWith(";")
                ? internalName.substring(1, internalName.length() - 1) : internalName;
        return name.substring(name.lastIndexOf('/') + 1);
    }

    /**
     * Mark every instruction that sits inside a loop.
     *
     * <p>A loop in bytecode is a jump to an earlier instruction — that is the whole definition, and
     * it covers all five ways to spell one in Java. Everything between the target and the jump is
     * the body. Nested loops merge, which is intentional: an allocation in an inner loop is worse
     * than one in an outer loop, never better.
     */
    private static boolean[] loopMask(MethodNode method) {
        InsnList instructions = method.instructions;
        if (instructions == null || instructions.size() == 0) {
            return new boolean[0];
        }
        Map<LabelNode, Integer> labelIndexes = new HashMap<>();
        int index = 0;
        for (AbstractInsnNode insn : instructions) {
            if (insn instanceof LabelNode label) {
                labelIndexes.put(label, index);
            }
            index++;
        }
        boolean[] inLoop = new boolean[instructions.size()];
        index = 0;
        for (AbstractInsnNode insn : instructions) {
            List<LabelNode> targets = switch (insn) {
                case JumpInsnNode jump -> List.of(jump.label);
                case TableSwitchInsnNode table -> table.labels == null ? List.of() : table.labels;
                case LookupSwitchInsnNode lookup -> lookup.labels == null ? List.of() : lookup.labels;
                default -> List.of();
            };
            for (LabelNode target : targets) {
                Integer at = labelIndexes.get(target);
                if (at != null && at < index) {
                    for (int i = at; i <= index; i++) {
                        inLoop[i] = true;
                    }
                }
            }
            index++;
        }
        return inLoop;
    }

    /**
     * Estimate the emitted bytecode size.
     *
     * <p>ASM's tree API models instructions, not their encoding, so the exact {@code code_length}
     * is not available without re-serialising the class. Summing each opcode's operand width gets
     * within a few percent, which is all the two thresholds this feeds need: the interesting
     * methods are nowhere near {@value #HUGE_METHOD_BYTES}, or hopelessly past it.
     */
    private static int estimateBytes(MethodNode method) {
        if (method.instructions == null) {
            return 0;
        }
        int bytes = 0;
        for (AbstractInsnNode insn : method.instructions) {
            bytes += switch (insn) {
                case LabelNode ignored -> 0;
                case IntInsnNode ignored -> insn.getOpcode() == Opcodes.SIPUSH ? 3 : 2;
                case VarInsnNode ignored -> 2;
                case IincInsnNode ignored -> 3;
                case LdcInsnNode ignored -> 3;
                case FieldInsnNode ignored -> 3;
                case TypeInsnNode ignored -> 3;
                case JumpInsnNode ignored -> 3;
                case MultiANewArrayInsnNode ignored -> 4;
                case InvokeDynamicInsnNode ignored -> 5;
                case MethodInsnNode call ->
                        call.getOpcode() == Opcodes.INVOKEINTERFACE ? 5 : 3;
                case TableSwitchInsnNode table ->
                        16 + 4 * (table.labels == null ? 0 : table.labels.size());
                case LookupSwitchInsnNode lookup ->
                        12 + 8 * (lookup.labels == null ? 0 : lookup.labels.size());
                default -> insn.getOpcode() >= 0 ? 1 : 0;
            };
        }
        return bytes;
    }

    private static Set<String> annotationsOf(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        Set<String> out = new LinkedHashSet<>();
        for (List<AnnotationNode> list : List.of(
                visible == null ? List.<AnnotationNode>of() : visible,
                invisible == null ? List.<AnnotationNode>of() : invisible)) {
            for (AnnotationNode annotation : list) {
                out.add(Type.getType(annotation.desc).getClassName());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Read side
    // ------------------------------------------------------------------------------------------

    ClassInfo classInfo(String internalName) {
        return classes.get(internalName);
    }

    /**
     * Where a method is referenced from, <b>including through its supertypes</b>.
     *
     * <p>A call goes to the type the caller names, not to the type that implements it: nothing in
     * the bytecode ever mentions the anonymous class implementing {@code MutableWorldView}, only
     * the interface. Resolving up the hierarchy is what keeps every override in the tree out of the
     * dead-code list.
     */
    Set<Origin> referencesTo(MethodId method) {
        Set<Origin> origins = new LinkedHashSet<>(methodReferences.getOrDefault(method, Set.of()));
        for (String supertype : supertypesOf(method.owner())) {
            origins.addAll(methodReferences.getOrDefault(
                    new MethodId(supertype, method.name(), method.desc()), Set.of()));
        }
        return origins;
    }

    /** Every supertype of {@code internalName} that we scanned, transitively. */
    Set<String> supertypesOf(String internalName) {
        Set<String> out = new LinkedHashSet<>();
        collectSupertypes(internalName, out);
        return out;
    }

    private void collectSupertypes(String internalName, Set<String> out) {
        ClassInfo info = classes.get(internalName);
        if (info == null) {
            return;
        }
        if (info.superName() != null && out.add(info.superName())) {
            collectSupertypes(info.superName(), out);
        }
        for (String parent : info.interfaces()) {
            if (out.add(parent)) {
                collectSupertypes(parent, out);
            }
        }
    }

    Set<Origin> referencesTo(String internalName) {
        return classReferences.getOrDefault(internalName, Set.of());
    }

    Set<MethodId> callsFrom(MethodId method) {
        return callsFrom.getOrDefault(method, Set.of());
    }

    Set<String> subtypesOf(String internalName) {
        return subtypes.getOrDefault(internalName, Set.of());
    }

    List<Finding> findings() {
        List<Finding> sorted = new ArrayList<>(findings);
        sorted.sort(Comparator.comparingInt(Finding::severity).reversed()
                .thenComparing(f -> f.method().owner())
                .thenComparing(f -> f.method().name()));
        return sorted;
    }

    /** Every production class we scanned, in a stable order. */
    List<ClassInfo> productionClasses() {
        return classes.values().stream()
                .filter(c -> c.origin() == Origin.PRODUCTION)
                .sorted(Comparator.comparing(ClassInfo::name))
                .toList();
    }

    /** The modules whose production output was found on disk. */
    Set<String> productionModules() {
        Set<String> modules = new LinkedHashSet<>();
        for (ClassInfo info : classes.values()) {
            if (info.origin() == Origin.PRODUCTION) {
                modules.add(info.module());
            }
        }
        return modules;
    }

    /** True when every supertype of {@code info} is either scanned or loadable on this classpath. */
    boolean hierarchyIsResolvable(ClassInfo info) {
        Set<String> seen = new HashSet<>();
        return resolvable(info.name(), seen);
    }

    private boolean resolvable(String internalName, Set<String> seen) {
        if (internalName == null || !seen.add(internalName)) {
            return true;
        }
        ClassInfo info = classes.get(internalName);
        if (info == null) {
            // Not ours: it has to be loadable for us to know what it declares.
            try {
                Class.forName(internalName.replace('/', '.'), false,
                        CodeGraph.class.getClassLoader());
                return true;
            } catch (Throwable notOnClasspath) {
                return false;
            }
        }
        if (!resolvable(info.superName(), seen)) {
            return false;
        }
        for (String parent : info.interfaces()) {
            if (!resolvable(parent, seen)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Does this method implement or override something declared outside our tree?
     *
     * <p>{@code run()}, {@code close()}, {@code accept(Object)} — a method satisfying a JDK or
     * framework interface has a caller nobody can see in our bytecode, so it can never be reported
     * as dead. External supertypes are resolved reflectively; ours are resolved from the scan.
     */
    boolean overridesExternal(ClassInfo owner, MethodInfo method) {
        if (method.isStatic() || method.id().name().startsWith("<")) {
            return false;
        }
        return overridesExternal(owner.name(), method.id().name(), method.id().desc(), new HashSet<>());
    }

    private boolean overridesExternal(String internalName, String name, String desc, Set<String> seen) {
        ClassInfo info = classes.get(internalName);
        List<String> parents = new ArrayList<>();
        if (info != null) {
            if (info.superName() != null) {
                parents.add(info.superName());
            }
            parents.addAll(info.interfaces());
        }
        for (String parent : parents) {
            if (!seen.add(parent)) {
                continue;
            }
            ClassInfo parentInfo = classes.get(parent);
            if (parentInfo == null) {
                if (declaresExternally(parent, name, desc)) {
                    return true;
                }
                continue;
            }
            if (parentInfo.methods().containsKey(new MethodId(parent, name, desc))) {
                // Declared by one of ours: the reference analysis already covers it.
                return false;
            }
            if (overridesExternal(parent, name, desc, seen)) {
                return true;
            }
        }
        return false;
    }

    private static boolean declaresExternally(String internalName, String name, String desc) {
        try {
            Class<?> type = Class.forName(internalName.replace('/', '.'), false,
                    CodeGraph.class.getClassLoader());
            for (java.lang.reflect.Method candidate : type.getMethods()) {
                if (candidate.getName().equals(name) && Type.getMethodDescriptor(candidate).equals(desc)) {
                    return true;
                }
            }
            for (java.lang.reflect.Method candidate : type.getDeclaredMethods()) {
                if (candidate.getName().equals(name) && Type.getMethodDescriptor(candidate).equals(desc)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable notOnClasspath) {
            // Cannot see it, so cannot rule it out. Treated as "overrides something", which keeps
            // the report free of claims it cannot support.
            return true;
        }
    }

    @Override
    public String toString() {
        return "CodeGraph[" + classes.size() + " classes]";
    }
}
