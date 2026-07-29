package dev.nodera.mod.debug.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MC-GUI-5, HUD half: the tab list, the boss bars and the zone alerts are drawn from
 * {@code Component}s this module builds directly, so no view-model test can see them. They were all
 * {@code Component.literal("…")} English — text no lang file could reach and no resource pack could
 * change.
 *
 * <p>This test reads those three renderers' own source and fails on any {@code Component.literal}
 * whose argument is prose. Punctuation, separators and measured values stay literal on purpose: they
 * are not words. The point is the next edit, not today's wording.
 */
final class HudHasNoHardcodedEnglishTest {

    private static final List<String> HUD_SOURCES =
            List.of("TabListRenderer.java", "BossBarManager.java", "ActionBarNotifier.java");

    /** {@code Component.literal("…")} with a string constant argument. */
    private static final Pattern LITERAL_CALL =
            Pattern.compile("Component\\.literal\\(\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    /** Any run of three or more letters — the signature of a word rather than a symbol. */
    private static final Pattern WORDY = Pattern.compile("[A-Za-z]{3,}");

    private static Path renderPackage() {
        Path direct = Path.of("src/main/java/dev/nodera/mod/debug/render");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve("java/neoforge-mod/src/main/java/dev/nodera/mod/debug/render");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new AssertionError("cannot locate the debug render package sources");
    }

    @Test
    void noHudSurfaceDrawsHardcodedEnglish() throws IOException {
        Path pkg = renderPackage();
        List<String> violations = new ArrayList<>();
        for (String name : HUD_SOURCES) {
            Path file = pkg.resolve(name);
            assertThat(file).as("HUD source " + name).exists();
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = LITERAL_CALL.matcher(lines.get(i));
                while (m.find()) {
                    if (WORDY.matcher(m.group(1)).find()) {
                        violations.add(name + ":" + (i + 1) + " → \"" + m.group(1) + "\"");
                    }
                }
            }
        }
        assertThat(violations)
                .as("a HUD surface may not hold English; use Component.translatable with a key "
                        + "declared in en_us.json (MC-GUI-5)")
                .isEmpty();
    }

    @Test
    void theScannerActuallyRejectsProse() {
        // Guard the guard, so a broken pattern cannot make the test above pass vacuously.
        Matcher m = LITERAL_CALL.matcher("Component.literal(\"Nodera zone\")");
        assertThat(m.find()).isTrue();
        assertThat(WORDY.matcher(m.group(1)).find()).isTrue();
        assertThat(WORDY.matcher(" · ").find()).isFalse();
    }
}
