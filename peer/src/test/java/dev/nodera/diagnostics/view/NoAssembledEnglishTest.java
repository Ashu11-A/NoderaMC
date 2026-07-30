package dev.nodera.diagnostics.view;

import dev.nodera.testkit.harness.LayoutManifest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MC-GUI-5, the future-facing half: the view models in this package are Minecraft-free so they can
 * be unit-tested on the gate, and that is exactly why they used to be where English got assembled —
 * a cell reading {@code "3 region(s) awaiting quorum"} is a sentence no lang file can reach. The
 * refactor moved every phrase to a translation key; this test is what stops the next one coming
 * back.
 *
 * <p>It reads the package's own source and fails on any string literal that reads as prose. Literals
 * that are lang keys, format/unit fragments, or exception messages are allowed — those are not text
 * a player ever sees on a screen.
 */
final class NoAssembledEnglishTest {

    /** Any run of three or more letters — the signature of a word rather than a symbol. */
    private static final Pattern WORDY = Pattern.compile("[A-Za-z]{3,}");

    /** A translation key: what a view model is supposed to emit. */
    private static final Pattern LANG_KEY = Pattern.compile("^nodera(\\.[a-z0-9_]+)+\\.?$");

    /** String literals, with escapes; deliberately simple — this package has no exotic literals. */
    private static final Pattern LITERAL = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");

    /**
     * Fragments that are formatting or units, not prose: they never carry meaning a translator
     * would change, and they are asserted by the formatter tests instead.
     */
    private static final Set<String> ALLOWED = Set.of(
            "KMGTPE", "%.1f %ciB", "%.1f %ciB/s", "KB", "MB", "GB", "TB", "B", " B", " B/s",
            "0 B", "0 B/s", "<1m");

    private static Path viewPackage() {
        Path pkg = LayoutManifest.load()
                .module("peer")
                .resolve("src/main/java/dev/nodera/diagnostics/view");
        if (!Files.isDirectory(pkg)) {
            throw new AssertionError("cannot locate the diagnostics view package sources at " + pkg);
        }
        return pkg;
    }

    /** @return true when the line is an exception message or a comment, not screen text. */
    private static boolean notScreenText(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")
                || line.contains("throw new") || line.contains("Exception(")
                || line.contains("IllegalArgumentException");
    }

    @Test
    void noViewModelSourceContainsAssembledEnglish() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.list(viewPackage())) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (notScreenText(line)) {
                        continue;
                    }
                    Matcher m = LITERAL.matcher(line);
                    while (m.find()) {
                        String literal = m.group(1);
                        if (ALLOWED.contains(literal)
                                || LANG_KEY.matcher(literal).matches()
                                || !WORDY.matcher(literal).find()) {
                            continue;
                        }
                        violations.add(file.getFileName() + ":" + (i + 1) + " → \"" + literal + "\"");
                    }
                }
            }
        }
        assertThat(violations)
                .as("a view model may not hold player-visible English; emit a lang key + arguments "
                        + "and let the mod resolve it (MC-GUI-5)")
                .isEmpty();
    }

    @Test
    void theScannerActuallyRejectsProse() {
        // Guard the guard: if the patterns above ever stop matching, the test above passes vacuously.
        assertThat(WORDY.matcher("3 region(s) awaiting quorum").find()).isTrue();
        assertThat(LANG_KEY.matcher("3 region(s) awaiting quorum").matches()).isFalse();
        assertThat(LANG_KEY.matcher("nodera.diag.value.pending").matches()).isTrue();
        assertThat(WORDY.matcher("%s / %s f").find()).isFalse();
    }

    @Test
    void theScannerReadsARealSourceTree() throws IOException {
        try (Stream<Path> files = Files.list(viewPackage())) {
            assertThat(files.filter(p -> p.toString().endsWith(".java")).count())
                    .as("view model sources scanned")
                    .isGreaterThanOrEqualTo(6);
        }
    }
}
