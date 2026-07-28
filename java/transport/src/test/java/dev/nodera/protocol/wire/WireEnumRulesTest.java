package dev.nodera.protocol.wire;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.core.identity.WorldHealth;
import dev.nodera.core.region.RegionReplicaRole;
import dev.nodera.protocol.discovery.AnnounceEvent;
import dev.nodera.protocol.rendezvous.CandidateKind;
import dev.nodera.protocol.rendezvous.RegistrationEvent;
import dev.nodera.protocol.service.ServiceKind;
import dev.nodera.protocol.service.ServiceLifecycle;
import dev.nodera.protocol.session.RejectCode;
import dev.nodera.protocol.session.SessionRole;
import dev.nodera.protocol.session.WireFeature;
import dev.nodera.protocol.simulationmsg.RegionRefusal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every enum that crosses the wire carries assigned codes, and the new wire cannot reach for
 * {@code ordinal()} (Task 14 phase 6, retiring {@code Plan.7} R5).
 *
 * <p>An ordinal makes the <em>source order of a Java declaration</em> part of a hashed, signed
 * network contract. Inserting a constant in the middle silently renumbers everything after it;
 * reordering an enum for readability is a network break that nothing detects. One of the enums on
 * this wire was not even ours — Minecraft's {@code Pose} — so an upstream refactor could change
 * Nodera's state roots without a line of Nodera changing.
 *
 * <p>Thread-context: JUnit test; single-threaded.
 */
class WireEnumRulesTest {

    private static JavaClasses wirePackage;

    @BeforeAll
    static void importClasses() {
        wirePackage = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackagesOf(WireRegistry.class);
    }

    @Test
    @DisplayName("ArchUnit found the wire package (a rule over zero classes proves nothing)")
    void theImportFoundTheWirePackage() {
        assertThat(wirePackage.size())
                .withFailMessage("ArchUnit imported no classes; every rule below would pass "
                        + "vacuously")
                .isGreaterThan(10);
    }

    @Test
    @DisplayName("no encode path in the wire package calls ordinal()")
    void theWirePackageNeverEncodesAnOrdinal() {
        // Scoped to the new wire deliberately. The legacy positional codec still writes ordinals,
        // and must: those bytes are already hashed and signed across a live network, so changing
        // them is not a cleanup, it is a fork. What this rule guarantees is that the encoding being
        // built to replace it never inherits the defect.
        // Matched on the CALL TARGET's name, not on `Enum` as the declaring class. The obvious
        // spelling — `callMethod(Enum.class, "ordinal")` — is vacuous: `PeerRole.BOOTSTRAP.ordinal()`
        // compiles to an invokevirtual whose owner is `PeerRole`, so a rule keyed on `Enum` matches
        // nothing and passes while the call sits there. This was verified by planting one.
        ArchRule rule = noClasses()
                .that().resideInAPackage("dev.nodera.protocol.wire..")
                .should().callMethodWhere(target(name("ordinal")))
                .because("a wire code must be written down, not derived from a declaration's "
                        + "position; assign one in WireEnums instead");
        rule.check(wirePackage);
    }

    @Test
    @DisplayName("every boundary enum has a total code table")
    void everyBoundaryEnumHasATotalTable() {
        // The builder refuses a table that leaves a constant uncoded, so merely constructing these
        // is the assertion. Naming them here is what makes a NEW boundary enum visible: it has to
        // be added to this list, and the list is the checklist.
        Map<String, Integer> sizes = new LinkedHashMap<>();
        sizes.put("PeerRole", WireEnums.PEER_ROLE.assignments().size());
        sizes.put("WorldHealth", WireEnums.WORLD_HEALTH.assignments().size());
        sizes.put("AnnounceEvent", WireEnums.ANNOUNCE_EVENT.assignments().size());
        sizes.put("CandidateKind", WireEnums.CANDIDATE_KIND.assignments().size());
        sizes.put("RegistrationEvent", WireEnums.REGISTRATION_EVENT.assignments().size());
        sizes.put("ServiceKind", WireEnums.SERVICE_KIND.assignments().size());
        sizes.put("ServiceLifecycle", WireEnums.SERVICE_LIFECYCLE.assignments().size());
        sizes.put("RegionReplicaRole", WireEnums.REGION_REPLICA_ROLE.assignments().size());
        sizes.put("RegionRefusal.Reason", WireEnums.REGION_REFUSAL_REASON.assignments().size());

        assertThat(sizes).containsExactlyInAnyOrderEntriesOf(Map.of(
                "PeerRole", PeerRole.values().length,
                "WorldHealth", WorldHealth.values().length,
                "AnnounceEvent", AnnounceEvent.values().length,
                "CandidateKind", CandidateKind.values().length,
                "RegistrationEvent", RegistrationEvent.values().length,
                "ServiceKind", ServiceKind.values().length,
                "ServiceLifecycle", ServiceLifecycle.values().length,
                "RegionReplicaRole", RegionReplicaRole.values().length,
                "RegionRefusal.Reason", RegionRefusal.Reason.values().length));
    }

    @Test
    @DisplayName("a constant with no code fails at class initialisation, not on the wire")
    void anUncodedConstantIsRefused() {
        assertThatThrownBy(() -> WireEnum.builder(WorldHealth.class)
                .code(1, WorldHealth.HEALTHY)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no wire code");
    }

    @Test
    @DisplayName("a code assigned twice is refused")
    void aDuplicateCodeIsRefused() {
        assertThatThrownBy(() -> WireEnum.builder(WorldHealth.class)
                .code(1, WorldHealth.HEALTHY)
                .code(1, WorldHealth.DEAD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assigned twice");
    }

    @Test
    @DisplayName("the assigned codes are pinned, so a renumbering has to be deliberate")
    void theAssignedCodesArePinned() {
        Map<Integer, String> peerRoles = new TreeMap<>();
        WireEnums.PEER_ROLE.assignments().forEach((code, role) -> peerRoles.put(code, role.name()));
        assertThat(peerRoles).containsExactlyInAnyOrderEntriesOf(Map.of(
                1, "BOOTSTRAP", 2, "RELAY", 3, "SESSION_GATEWAY", 4, "REGION_EXECUTOR",
                5, "REGION_VALIDATOR", 6, "PARTIAL_ARCHIVE", 7, "FULL_ARCHIVE", 8, "WORLD_SEEDER"));

        Map<Integer, String> health = new TreeMap<>();
        WireEnums.WORLD_HEALTH.assignments().forEach((code, h) -> health.put(code, h.name()));
        assertThat(health).containsExactlyInAnyOrderEntriesOf(
                Map.of(1, "HEALTHY", 2, "DEGRADED", 3, "DEAD"));
    }

    @Test
    @DisplayName("the session enums carry explicit codes too")
    void theSessionEnumsCarryExplicitCodes() {
        assertThat(SessionRole.ADMITTED.code()).isEqualTo(1);
        assertThat(SessionRole.OBSERVER.code()).isEqualTo(2);
        assertThat(SessionRole.REFUSED.code()).isEqualTo(3);
        assertThat(RejectCode.NONE.code()).isZero();
        assertThat(RejectCode.UNSUPPORTED_KIND.code()).isEqualTo(1);
        assertThat(WireFeature.KEEP_ALIVE_REGION_PROGRESS.code()).isEqualTo(1);

        // Every code is distinct, in every one of them.
        for (Class<?> type : new Class<?>[] {SessionRole.class, RejectCode.class,
                WireFeature.class, WireType.class}) {
            assertThat(distinctCodes(type))
                    .withFailMessage("%s has two constants sharing a wire code", type.getSimpleName())
                    .isTrue();
        }
    }

    private static boolean distinctCodes(Class<?> type) {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Object constant : type.getEnumConstants()) {
            try {
                int code = (int) type.getMethod("code").invoke(constant);
                if (!seen.add(code)) {
                    return false;
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(type + " has no code() accessor", e);
            }
        }
        return true;
    }

    @Test
    @DisplayName("an unknown infrastructure code is preserved rather than flattened")
    void anUnknownInfrastructureCodeIsPreserved() {
        // D7's two halves in one test. Infrastructure: the raw code survives, so re-encoding
        // reproduces it. Consensus: an unknown code is refused, because a validator that ignores a
        // field it does not understand computes a state root nobody else computes.
        WireEnum.CodedValue<WorldHealth> fromTheFuture =
                WireEnums.WORLD_HEALTH.resolve(4242, WorldHealth.DEGRADED);
        assertThat(fromTheFuture.recognised()).isFalse();
        assertThat(fromTheFuture.code()).isEqualTo(4242);
        assertThat(fromTheFuture.value()).isEqualTo(WorldHealth.DEGRADED);

        assertThatThrownBy(() -> WireEnums.REGION_REPLICA_ROLE.require(4242))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be guessed at");
    }

    @Test
    @DisplayName("a refusal reason from a later release survives a relay unchanged")
    void anUnknownRefusalReasonSurvivesARelay() {
        RegionRefusal fromTheFuture = new RegionRefusal(
                new dev.nodera.core.region.RegionId(
                        dev.nodera.core.region.DimensionKey.overworld(), 1, 2),
                4242);
        assertThat(fromTheFuture.reasonRecognised()).isFalse();
        assertThat(fromTheFuture.reason()).isEqualTo(RegionRefusal.Reason.UNKNOWN);
        assertThat(WireCodec.decode(WireCodec.encode(fromTheFuture))).isEqualTo(fromTheFuture);
    }

    @Test
    @DisplayName("UNKNOWN is never emitted — it is the absence of a reason, not one")
    void unknownIsNeverEmitted() {
        assertThatThrownBy(() -> RegionRefusal.codeOf(RegionRefusal.Reason.UNKNOWN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never re-encoded");
    }
}
