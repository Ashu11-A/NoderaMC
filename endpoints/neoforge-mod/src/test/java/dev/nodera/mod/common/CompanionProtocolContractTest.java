package dev.nodera.mod.common;

import dev.nodera.endpoint.control.CompanionProtocol;
import dev.nodera.peer.control.ControlProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mod and the worker speak the same protocol, and this is the only thing that says so.
 *
 * <p>{@link CompanionProtocol} holds literals rather than delegating to {@link ControlProtocol},
 * because the mod and the worker are separately installed artifacts that talk over a socket — a
 * shared compile-time constant cannot make two independently updated builds agree, it only hides
 * that they might not. What a shared constant genuinely bought was a drift alarm, and this test is
 * that alarm, kept where it belongs: in a test, which fails loudly, rather than in a coupling, which
 * fails silently in the field.
 *
 * <p>This is also the mod's <b>only</b> compile-time reference to the peer's control plane, and it
 * is test-scoped. The shipped mod carries no worker code.
 */
final class CompanionProtocolContractTest {

    /** Every public constant of a protocol class, by name. */
    private static Map<String, Object> constants(Class<?> type) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field field : type.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers)
                    || !Modifier.isFinal(modifiers)) {
                continue;
            }
            try {
                out.put(field.getName(), field.get(null));
            } catch (IllegalAccessException unreachable) {
                throw new AssertionError(unreachable);
            }
        }
        return out;
    }

    @Test
    @DisplayName("every verb the mod knows has the same spelling in the worker")
    void everyMirroredConstantMatches() {
        Map<String, Object> mod = constants(CompanionProtocol.class);
        Map<String, Object> worker = constants(ControlProtocol.class);

        assertThat(mod).isNotEmpty();
        mod.forEach((name, value) -> {
            assertThat(worker)
                    .as("the mod declares %s, which the worker does not — one of them is wrong", name)
                    .containsKey(name);
            assertThat(value)
                    .as("%s differs between the mod and the worker", name)
                    .isEqualTo(worker.get(name));
        });
    }

    @Test
    @DisplayName("a verb added to the worker is added to the mod in the same commit")
    void theModKnowsEveryVerbTheWorkerServes() {
        Map<String, Object> mod = constants(CompanionProtocol.class);
        Map<String, Object> worker = constants(ControlProtocol.class);

        // Deliberately strict in both directions. A verb the mod has never heard of is how the two
        // copies drift, and the drift only shows up on the day somebody needs the verb.
        assertThat(worker.keySet())
                .as("the worker serves verbs the mod does not mirror")
                .isSubsetOf(mod.keySet());
    }

    @Test
    @DisplayName("the handshake lines are byte-identical")
    void theHandshakeAgrees() {
        assertThat(CompanionProtocol.probeLine()).isEqualTo(ControlProtocol.probeLine());
        assertThat(CompanionProtocol.okLine(2, "1.2.3"))
                .isEqualTo(ControlProtocol.okLine(2, "1.2.3"));
        assertThat(CompanionProtocol.PROTOCOL_VERSION)
                .isEqualTo(ControlProtocol.PROTOCOL_VERSION);
    }
}
