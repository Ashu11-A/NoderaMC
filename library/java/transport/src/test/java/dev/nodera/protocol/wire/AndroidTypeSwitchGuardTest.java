package dev.nodera.protocol.wire;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Android worker shares this module's classes. A Java 21 type-pattern switch compiles to an
 * {@code invokedynamic} on {@code java.lang.runtime.SwitchBootstraps.typeSwitch}, which ART does not
 * implement and D8 can neither run nor desugar — so any such switch is a latent crash that hits on
 * Android only, the moment the path first executes. The shell guard
 * {@code scripts/check-android-bytecode.sh} catches it in the dex, but only where Android build-tools
 * exist; this test catches the same regression in {@code ./gradlew check} by scanning the module's
 * compiled main classes — the module whose {@code MessageRouter.answerFor} was the regression.
 *
 * <p>See {@code docs/mobile/LIMITATIONS.md} M-5 and {@code docs/mobile/LIMITATIONS.fixed.md} M-8.
 *
 * <p>Thread-context: JUnit test; single-threaded, read-only over the class files.
 */
class AndroidTypeSwitchGuardTest {

    @Test
    @DisplayName("no class in the transport module compiles to a SwitchBootstraps type-switch")
    void noTypePatternSwitchInTheTransportModule() throws IOException {
        URL codeSource = MessageRouter.class.getProtectionDomain().getCodeSource().getLocation();
        assertThat(codeSource)
                .withFailMessage("cannot resolve the module's compiled-classes root").isNotNull();
        Path classesRoot = Paths.get(codeSource.getPath());
        assertThat(Files.isDirectory(classesRoot))
                .withFailMessage("expected a compiled-classes directory, got %s", classesRoot)
                .isTrue();

        try (Stream<Path> walk = Files.walk(classesRoot)) {
            walk.filter(p -> p.toString().endsWith(".class")).forEach(clazz -> {
                byte[] bytes;
                try {
                    bytes = Files.readAllBytes(clazz);
                } catch (IOException e) {
                    throw new AssertionError("cannot read " + clazz, e);
                }
                // Class-file Utf8 constants are modified-UTF-8, but the ASCII substrings we look
                // for appear verbatim in the byte stream, so a Latin-1 projection is exact enough.
                String probe = new String(bytes, StandardCharsets.ISO_8859_1);
                assertThat(probe)
                        .withFailMessage("%s compiles to a SwitchBootstraps type-switch — rewrite it "
                                + "as an instanceof chain (see docs/mobile/LIMITATIONS.md M-5)",
                                classesRoot.relativize(clazz))
                        .doesNotContain("SwitchBootstraps")
                        .doesNotContain("typeSwitch");
            });
        }
    }
}
