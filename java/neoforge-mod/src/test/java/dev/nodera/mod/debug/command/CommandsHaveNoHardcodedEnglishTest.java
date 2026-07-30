package dev.nodera.mod.debug.command;

import dev.nodera.testkit.harness.LayoutManifest;
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
 * MC-GUI-5, command half: {@code /nodera} and {@code /noderac} were the last surface assembling
 * English. Their replies were {@code StringBuilder}s handed to {@code Component.literal}, which is
 * strictly worse than a hardcoded label — a sentence built at runtime cannot be reached by a lang
 * file <em>or</em> spotted by grepping for one.
 *
 * <p>So this scanner enforces two rules over the four command sources, and the second is the one
 * that matters: {@code Component.literal} may not be given prose, <b>and it may not be given a
 * variable at all</b>. A reply therefore has nowhere to put a {@code String} it built itself; it
 * must name a key from {@link CommandLang} and pass the values separately. Punctuation and
 * separators stay literal on purpose — they are not words.
 */
final class CommandsHaveNoHardcodedEnglishTest {

    private static final List<String> COMMAND_SOURCES = List.of(
            "NoderaCommand.java", "NoderaClientCommand.java", "CommandTree.java", "SelfTest.java");

    /** {@code Component.literal(} plus whatever it opens with. */
    private static final Pattern LITERAL_CALL = Pattern.compile("Component\\.literal\\(\\s*(.)");

    /** {@code Component.literal("…")} with a string constant argument. */
    private static final Pattern LITERAL_STRING =
            Pattern.compile("Component\\.literal\\(\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    /** Any run of three or more letters — the signature of a word rather than a symbol. */
    private static final Pattern WORDY = Pattern.compile("[A-Za-z]{3,}");

    static Path commandPackage() {
        Path pkg = LayoutManifest.load()
                .module("neoforge-mod")
                .resolve("src/main/java/dev/nodera/mod/debug/command");
        if (!Files.isDirectory(pkg)) {
            throw new AssertionError("cannot locate the debug command package sources at " + pkg);
        }
        return pkg;
    }

    @Test
    void noCommandReplyHoldsHardcodedEnglish() throws IOException {
        Path pkg = commandPackage();
        List<String> violations = new ArrayList<>();
        for (String name : COMMAND_SOURCES) {
            Path file = pkg.resolve(name);
            assertThat(file).as("command source " + name).exists();
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = LITERAL_STRING.matcher(lines.get(i));
                while (m.find()) {
                    if (WORDY.matcher(m.group(1)).find()) {
                        violations.add(name + ":" + (i + 1) + " → \"" + m.group(1) + "\"");
                    }
                }
            }
        }
        assertThat(violations)
                .as("a command reply may not hold English; name a CommandLang key and let "
                        + "en_us.json say the words (MC-GUI-5)")
                .isEmpty();
    }

    @Test
    void noCommandReplyBuildsItsTextAtRuntime() throws IOException {
        Path pkg = commandPackage();
        List<String> violations = new ArrayList<>();
        for (String name : COMMAND_SOURCES) {
            List<String> lines = Files.readAllLines(pkg.resolve(name), StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = LITERAL_CALL.matcher(lines.get(i));
                while (m.find()) {
                    // A quote opens a constant, which the prose scanner above judges. Anything else
                    // is an expression — the StringBuilder shape this refactor removed.
                    if (!"\"".equals(m.group(1))) {
                        violations.add(name + ":" + (i + 1) + " → " + lines.get(i).trim());
                    }
                }
            }
        }
        assertThat(violations)
                .as("Component.literal may only wrap punctuation constants here: a reply built "
                        + "from a variable is text no lang file can reach (MC-GUI-5)")
                .isEmpty();
    }

    @Test
    void theScannerActuallyRejectsBothShapes() {
        Matcher prose = LITERAL_STRING.matcher("Component.literal(\"Nodera: diagnostics offline\")");
        assertThat(prose.find()).isTrue();
        assertThat(WORDY.matcher(prose.group(1)).find()).isTrue();
        assertThat(WORDY.matcher("\\n").find()).isFalse();

        Matcher built = LITERAL_CALL.matcher("ctx.getSource().sendSuccess(() -> Component.literal(text), false);");
        assertThat(built.find()).isTrue();
        assertThat(built.group(1)).isNotEqualTo("\"");
    }

    @Test
    void theScannerReadsEveryCommandSource() {
        Path pkg = commandPackage();
        for (String name : COMMAND_SOURCES) {
            assertThat(pkg.resolve(name)).as("scanned command source").exists();
        }
    }
}
