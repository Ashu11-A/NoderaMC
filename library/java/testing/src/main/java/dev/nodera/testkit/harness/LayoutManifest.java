package dev.nodera.testkit.harness;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

/**
 * The repository layout table, read from {@code /layout.properties}.
 *
 * <p>Where a module or a crate lives is a property of the tree, not of any one build system, and
 * this repository has five consumers in four languages that need the same answer: Gradle's
 * settings script, this harness, the structural report, the shell suites and the release scripts.
 * They used to each carry their own copy, and two of those copies were wrong — a test had been
 * silently skipping for months against a directory that had moved, and a CI workflow was watching
 * a file that no longer existed. Nothing failed loudly; the copies simply disagreed.
 *
 * <p>So every consumer reads this one file, and {@code LayoutManifestTest} fails the build when the
 * manifest and the projects Gradle actually configured drift apart.
 *
 * <p>This is a <strong>source-checkout tool</strong>, not product API: it resolves paths inside a
 * working copy and means nothing to a peer running from a distribution. Its Rust counterpart is
 * {@code nodera_codec::repo}.
 *
 * <p>Thread-context: immutable once loaded; safe to share.
 */
public final class LayoutManifest {

    private static final String FILE_NAME = "layout.properties";

    private final Path root;
    private final Map<String, String> values;

    private LayoutManifest(Path root, Map<String, String> values) {
        this.root = root;
        this.values = values;
    }

    /**
     * Load the manifest, discovering the repository root from the working directory.
     *
     * @return the loaded manifest.
     */
    public static LayoutManifest load() {
        return load(repositoryRoot());
    }

    /**
     * Load the manifest from a known repository root.
     *
     * @param root the directory holding {@code layout.properties}.
     * @return the loaded manifest.
     */
    public static LayoutManifest load(Path root) {
        Path absolute = root.toAbsolutePath().normalize();
        Path manifest = absolute.resolve(FILE_NAME);
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(manifest)) {
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the layout manifest " + manifest, e);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : new TreeSet<>(properties.stringPropertyNames())) {
            values.put(key, properties.getProperty(key).trim());
        }
        return new LayoutManifest(absolute, Map.copyOf(values));
    }

    /**
     * The same layout table, resolved against a different root.
     *
     * <p>For pointing the harness at a tree that is deliberately empty. A scenario's preflight is
     * only worth testing if the answer does not depend on what happened to be compiled first, so
     * those tests hand it a temporary directory — which has the repository's <em>shape</em> but
     * none of its artefacts. The shape still has to come from the real manifest; a temp directory
     * has no {@code layout.properties} and never will.
     *
     * @param newRoot the directory to resolve every value against.
     * @return a manifest with this one's values and that root.
     */
    public LayoutManifest rebasedOn(Path newRoot) {
        return new LayoutManifest(newRoot.toAbsolutePath().normalize(), values);
    }

    /**
     * Find the repository root by walking up from the working directory.
     *
     * <p>Both markers are required: a stray {@code VERSION} in a subdirectory must not end the walk
     * early. Walking rather than counting {@code ..} segments is the whole point — it is what lets a
     * directory change depth without breaking every caller.
     *
     * @return the repository root.
     * @throws IllegalStateException if neither marker is found on the way up.
     */
    public static Path repositoryRoot() {
        Path start = Path.of("").toAbsolutePath();
        Path candidate = start;
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("VERSION"))
                    && Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "cannot find the repository root (a directory holding both VERSION and "
                        + "settings.gradle.kts) above " + start);
    }

    /** The repository root this manifest was read from. */
    public Path root() {
        return root;
    }

    /**
     * The directory of one Gradle module, by its project name.
     *
     * @param name the Gradle project name without the leading colon, e.g. {@code "neoforge-mod"}.
     * @return the absolute module directory.
     */
    public Path module(String name) {
        return resolve("module." + name);
    }

    /**
     * The directory of one Cargo crate, by its package name.
     *
     * @param name the crate's {@code package.name}, e.g. {@code "nodera-codec"}.
     * @return the absolute crate directory.
     */
    public Path crate(String name) {
        return resolve("crate." + name);
    }

    /**
     * A well-known directory that is neither a module nor a crate.
     *
     * @param name the key suffix, e.g. {@code "cargoTarget"}, {@code "artifacts"}, {@code "run"}.
     * @return the absolute directory.
     */
    public Path dir(String name) {
        return resolve("dir." + name);
    }

    /** Every Gradle module, project name to absolute directory, in key order. */
    public Map<String, Path> modules() {
        return byPrefix("module.");
    }

    /** Every Cargo crate, package name to absolute directory, in key order. */
    public Map<String, Path> crates() {
        return byPrefix("crate.");
    }

    /** The raw value of one key, for the few callers that need the relative form. */
    public String value(String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException(
                    "layout.properties has no key '" + key + "' (keys: " + values.keySet() + ")");
        }
        return value;
    }

    private Path resolve(String key) {
        return root.resolve(value(key)).normalize();
    }

    private Map<String, Path> byPrefix(String prefix) {
        Map<String, Path> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                result.put(key.substring(prefix.length()), root.resolve(value).normalize());
            }
        });
        return Map.copyOf(result);
    }
}
