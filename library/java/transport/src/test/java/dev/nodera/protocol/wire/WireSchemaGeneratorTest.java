package dev.nodera.protocol.wire;

import dev.nodera.protocol.codec.MessageCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Holds the generated wire artefacts to their source (Task 14 phase 1).
 *
 * <p>{@link WireRegistry} is the schema; the Rust kind table and the fixture manifest are rendered
 * from it. This test re-renders and compares, so a kind appended in Java without regenerating fails
 * here rather than reaching the network as a number one side has never heard of. Pass
 * {@code -Dnodera.wire.regenerate=true} to write the rendered output — a generated file is still a
 * reviewed file, so the default is to fail and show the difference.
 *
 * <p>It also pins the two directions the schema has to agree with: the frozen {@code TAG_*}
 * constants that the rest of the codebase compiles against, and the registry's own invariants.
 *
 * <p>Thread-context: JUnit test; single-threaded.
 */
class WireSchemaGeneratorTest {

    private static final String REGENERATE_PROPERTY = "nodera.wire.regenerate";

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("fixtures"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("cannot locate the repository root from "
                    + Path.of("").toAbsolutePath());
        }
        return dir;
    }

    private static void checkGenerated(String relativePath, String rendered) {
        Path path = repoRoot().resolve(relativePath);
        try {
            if (Boolean.getBoolean(REGENERATE_PROPERTY)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, rendered, StandardCharsets.UTF_8);
                return;
            }
            assertThat(Files.exists(path))
                    .withFailMessage("%s is missing; re-run with -D%s=true", relativePath,
                            REGENERATE_PROPERTY)
                    .isTrue();
            assertThat(Files.readString(path, StandardCharsets.UTF_8))
                    .withFailMessage("%s is stale — it no longer matches WireRegistry. Re-run with "
                            + "-D%s=true and review the diff; the schema is the source, this file "
                            + "is its shadow.", relativePath, REGENERATE_PROPERTY)
                    .isEqualTo(rendered);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("the Rust kind registry is a faithful rendering of the schema")
    void rustKindRegistryMatchesTheSchema() {
        checkGenerated(WireSchemaGenerator.RUST_KINDS_PATH, WireSchemaGenerator.renderRustKinds());
    }

    @Test
    @DisplayName("the fixture manifest is a faithful rendering of the schema")
    void fixtureManifestMatchesTheSchema() {
        checkGenerated(WireSchemaGenerator.FIXTURE_MANIFEST_PATH,
                WireSchemaGenerator.renderFixtureManifest());
    }

    /**
     * The {@code TAG_*} constants are what the rest of the codebase compiles against, so they are
     * part of the contract even though the schema is the source. A constant that disagrees with its
     * row would encode under one number and dispatch under another.
     */
    @Test
    @DisplayName("every frozen TAG_ constant equals the kind its schema row declares")
    void frozenTagConstantsAgreeWithTheSchema() {
        Map<Integer, String> constants = new TreeMap<>();
        for (Field f : MessageCodec.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || !f.getName().startsWith("TAG_")
                    || f.getType() != int.class) {
                continue;
            }
            try {
                constants.put(f.getInt(null), f.getName());
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }

        Map<Integer, String> expected = new TreeMap<>();
        for (WireKind k : WireRegistry.kinds()) {
            expected.put(k.kind(), "TAG_" + k.rustConstantName());
        }

        assertThat(constants)
                .withFailMessage("the TAG_ constants and the schema disagree.%nconstants: %s%nschema:    %s",
                        constants, expected)
                .containsExactlyInAnyOrderEntriesOf(expected);
        assertThat(constants).hasSameSizeAs(WireRegistry.kinds());
    }

    @Test
    @DisplayName("the schema is contiguous, uniquely typed, and covers every known tag")
    void schemaIsWellFormed() {
        Map<Integer, String> byKind = new LinkedHashMap<>();
        int expected = 1;
        for (WireKind k : WireRegistry.kinds()) {
            assertThat(k.kind()).isEqualTo(expected++);
            assertThat(byKind.put(k.kind(), k.name())).isNull();
        }
        assertThat(WireRegistry.NEXT_KIND).isEqualTo(WireRegistry.kinds().size());
        assertThat(MessageCodec.KNOWN_TAGS).isEqualTo(byKind.keySet().stream().toList());
        assertThat(MessageCodec.NEXT_TAG).isEqualTo(WireRegistry.NEXT_KIND);
    }

    @Test
    @DisplayName("both planes are populated, and the split is the one Plan.7 D1 describes")
    void bothPlanesArePopulated() {
        // Not an arbitrary count: the whole point of the split is that the messages a peer needs in
        // order to REACH other peers are tolerant, and the ones it needs to AGREE with them are
        // strict. If a future change moves a kind across, this number moves with it deliberately.
        assertThat(WireRegistry.onPlane(MessagePlane.INFRASTRUCTURE)).hasSize(50);
        assertThat(WireRegistry.onPlane(MessagePlane.CONSENSUS)).hasSize(26);
        assertThat(WireRegistry.onPlane(MessagePlane.INFRASTRUCTURE).size()
                + WireRegistry.onPlane(MessagePlane.CONSENSUS).size())
                .isEqualTo(WireRegistry.kinds().size());
    }

    @Test
    @DisplayName("an unknown kind is reported as absent, not as a crash")
    void unknownKindIsAbsentRatherThanFatal() {
        assertThat(WireRegistry.find(WireRegistry.NEXT_KIND + 1)).isEmpty();
        assertThat(WireRegistry.find(0)).isEmpty();
        assertThatThrownBy(() -> WireRegistry.require(WireRegistry.NEXT_KIND + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown message kind");
    }

    @Test
    @DisplayName("fixture and Rust-constant names are derived from the record name")
    void derivedNamesFollowTheRecordName() {
        WireKind proposal = WireRegistry.require(MessageCodec.TAG_REGION_PROPOSAL);
        assertThat(proposal.fixtureName()).isEqualTo("region-proposal.bin");
        assertThat(proposal.rustConstantName()).isEqualTo("REGION_PROPOSAL");
    }
}
