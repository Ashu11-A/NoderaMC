package dev.nodera.mod.debug.command;

import dev.nodera.testkit.harness.LayoutManifest;
import dev.nodera.endpoint.lang.CommandLang;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MC-GUI-5: the command classes themselves cannot be loaded on the gate (they touch Brigadier and
 * {@code net.minecraft}), which is why {@link CommandLang} exists as a Minecraft-free registry of
 * every key they can emit, together with the number of arguments each call site supplies.
 *
 * <p>Moving a reply to {@code Component.translatable} is only half a fix: a key that is missing
 * renders as its own name, and a key given the wrong number of values renders as a broken sentence.
 * Both fail only at runtime, in front of a player. These assertions move both failures onto the
 * build — the same contract {@code LangKeyCoverageTest} enforces for the view models.
 */
final class CommandLangCoverageTest {

    /** {@code "key": "value"} pairs; the lang file is a flat one-pair-per-line object. */
    private static final Pattern PAIR = Pattern.compile(
            "\"((?:\\\\.|[^\"\\\\])*)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    /** A Nodera lang key appearing as a literal in a command source. */
    private static final Pattern KEY_LITERAL = Pattern.compile("\"(nodera\\.[a-z0-9_.]+[a-z0-9_])\"");

    private static Map<String, String> lang() {
        try (InputStream in = CommandLangCoverageTest.class.getClassLoader()
                .getResourceAsStream("assets/nodera/lang/en_us.json")) {
            assertThat(in).as("en_us.json on the test classpath").isNotNull();
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> entries = new HashMap<>();
            Matcher m = PAIR.matcher(json);
            while (m.find()) {
                entries.put(m.group(1), m.group(2));
            }
            assertThat(entries).as("parsed lang entries").isNotEmpty();
            return entries;
        } catch (Exception e) {
            throw new AssertionError("cannot read en_us.json", e);
        }
    }

    /** {@code %s} placeholders in a lang value ({@code %%} is an escaped percent, not one). */
    private static int placeholders(String value) {
        int count = 0;
        for (int i = 0; i < value.length() - 1; i++) {
            if (value.charAt(i) == '%') {
                if (value.charAt(i + 1) == '%') {
                    i++;
                } else {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    void everyCommandKeyExistsInTheLangFile() {
        Map<String, String> lang = lang();
        List<String> missing = new ArrayList<>();
        for (String key : CommandLang.registry().keySet()) {
            if (!lang.containsKey(key)) {
                missing.add(key);
            }
        }
        assertThat(missing).as("command keys with no en_us.json entry").isEmpty();
        assertThat(CommandLang.registry()).as("registered command keys").hasSizeGreaterThan(50);
    }

    @Test
    void everyCommandKeyCarriesAsManyArgumentsAsItHasPlaceholders() {
        Map<String, String> lang = lang();
        List<String> mismatched = new ArrayList<>();
        CommandLang.registry().forEach((key, arity) -> {
            String value = lang.get(key);
            if (value == null) {
                return; // reported by the coverage test
            }
            if (placeholders(value) != arity) {
                mismatched.add(key + ": lang wants " + placeholders(value)
                        + " argument(s), the command supplies " + arity);
            }
        });
        assertThat(mismatched).as("command key/argument arity drift").isEmpty();
    }

    /**
     * Every key constant {@link CommandLang} publishes must be registered — an unregistered
     * constant is a key nothing above checks, which is exactly the hole this class closes.
     */
    @Test
    void everyPublishedConstantIsRegistered() throws Exception {
        List<String> unregistered = new ArrayList<>();
        for (Field f : CommandLang.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) {
                continue;
            }
            f.setAccessible(true);
            String value = (String) f.get(null);
            if (value == null || !value.startsWith("nodera.") || value.endsWith(".")) {
                continue; // a prefix; its children are registered individually
            }
            if (!CommandLang.registry().containsKey(value)) {
                unregistered.add(f.getName() + " → " + value);
            }
        }
        assertThat(unregistered).as("CommandLang constants outside the arity registry").isEmpty();
    }

    /**
     * A key can also be typed straight into a {@code translatable} call without ever becoming a
     * constant. The sources are read here so that shape fails too.
     *
     * <p>Two directories, because the two halves of a command live in different modules now: the
     * KEYS are in {@code :endpoint}'s {@code dev.nodera.endpoint.lang} (Minecraft-free, so a Paper
     * plugin can name the same key), and the Minecraft-bound commands that use them are here.
     * Scanning only this one silently found zero keys the moment {@code CommandLang} moved — which
     * is the failure that brought this comment into existence.
     */
    @Test
    void everyKeyTheCommandSourcesNameResolves() throws IOException {
        Map<String, String> lang = lang();
        List<Path> packages = List.of(
                CommandsHaveNoHardcodedEnglishTest.commandPackage(),
                LayoutManifest.load().module("endpoint")
                        .resolve("src/main/java/dev/nodera/endpoint/lang"));
        List<String> missing = new ArrayList<>();
        int found = 0;
        for (Path pkg : packages) {
            assertThat(pkg).as("scanned source package").isDirectory();
            try (var files = Files.list(pkg)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    Matcher m = KEY_LITERAL.matcher(Files.readString(file, StandardCharsets.UTF_8));
                    while (m.find()) {
                        found++;
                        if (!lang.containsKey(m.group(1))) {
                            missing.add(file.getFileName() + " → " + m.group(1));
                        }
                    }
                }
            }
        }
        assertThat(found).as("command keys discovered in source").isGreaterThanOrEqualTo(50);
        assertThat(missing).as("command keys with no en_us.json entry").isEmpty();
    }
}
