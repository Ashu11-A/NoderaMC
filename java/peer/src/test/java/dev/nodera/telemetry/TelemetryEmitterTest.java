package dev.nodera.telemetry;

import dev.nodera.core.identity.NodeId;
import dev.nodera.diagnostics.model.EntityControl;
import dev.nodera.diagnostics.model.HealthStat;
import dev.nodera.diagnostics.model.NetStats;
import dev.nodera.diagnostics.model.PeerLink;
import dev.nodera.diagnostics.model.RegionOwnership;
import dev.nodera.diagnostics.model.SessionInfo;
import dev.nodera.diagnostics.model.TelemetrySnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Network task 12 — the emitter core.
 *
 * <p>The tests that matter here are the privacy ones: consent gating collection rather than
 * transmission, and the impossibility of putting a name, a path, or a message into an attribute.
 */
final class TelemetryEmitterTest {

    private static final NodeId SELF = new NodeId(UUID.nameUUIDFromBytes("self".getBytes()));

    private static TelemetrySnapshot snapshot(long bytesTx, long bytesRx, List<PeerLink> peers) {
        return new TelemetrySnapshot(1L, SELF, false,
                new SessionInfo(0L, null, false, peers.size() + 1, "peer", peers),
                new NetStats(bytesTx, bytesRx, 0, 0, 0, 0, 0, 0, Map.of()),
                RegionOwnership.empty(), EntityControl.empty(), HealthStat.healthy());
    }

    private static PeerLink peer(String name, String route) {
        return new PeerLink(new NodeId(UUID.nameUUIDFromBytes(name.getBytes())), route, false,
                "peer", 0L, 0L, true);
    }

    // ---- consent --------------------------------------------------------------------------------

    /**
     * The acceptance criterion for network task 12: a denied node constructs no event object at
     * all. Not "builds them and drops them" — a design one flag away from shipping data nobody
     * agreed to.
     */
    @Test
    void consentDeniedProducesNoEventsAtAll() {
        SnapshotProjector projector = new SnapshotProjector();
        TelemetrySnapshot busy = snapshot(50_000_000L, 20_000_000L, List.of(peer("a", "tcp://a:1")));

        assertThat(projector.project(busy, null, TelemetryConsent.DENIED, 300, 1_000L)).isEmpty();
        assertThat(projector.project(busy, null, TelemetryConsent.UNANSWERED, 300, 1_000L)).isEmpty();
        assertThat(projector.project(busy, null, TelemetryConsent.GRANTED, 300, 1_000L)).isNotEmpty();
    }

    @Test
    void anUnrecognisedConsentTokenIsReadAsUnansweredRatherThanGranted() {
        assertThat(TelemetryConsent.parse(null)).isEqualTo(TelemetryConsent.UNANSWERED);
        assertThat(TelemetryConsent.parse("")).isEqualTo(TelemetryConsent.UNANSWERED);
        assertThat(TelemetryConsent.parse("yes")).isEqualTo(TelemetryConsent.UNANSWERED);
        assertThat(TelemetryConsent.parse("GRANTED")).isEqualTo(TelemetryConsent.GRANTED);
        assertThat(TelemetryConsent.parse("granted").collects()).isTrue();
        assertThat(TelemetryConsent.parse("denied").collects()).isFalse();
    }

    // ---- the registry gate ----------------------------------------------------------------------

    /** A world name, a player name, a path, or a message cannot be given to an attribute. */
    @Test
    void anUndeclaredStringValueIsRefusedAtTheCallSite() {
        TelemetryEvent.Builder builder =
                TelemetryEvent.named(TelemetryRegistry.WORLD_SHARE, 1_000L);
        assertThatThrownBy(() -> builder.enumeration("origin", "My Secret Base"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not declared");
        assertThatThrownBy(() -> builder.enumeration("world_name", "existing_world"))
                .isInstanceOf(IllegalArgumentException.class);
        // The declared member is accepted.
        assertThat(builder.enumeration("origin", "existing_world").build().attrs())
                .containsEntry("origin", "existing_world");
    }

    @Test
    void aFingerprintIsSixteenLowercaseHexCharactersDerivedFromBytes() {
        String fingerprint = TelemetryRegistry.fingerprint("StateRoot mismatch".getBytes());
        assertThat(fingerprint).hasSize(16).matches("[0-9a-f]{16}");
        assertThat(TelemetryRegistry.admits(TelemetryRegistry.ENGINE_DIVERGENCE, "fingerprint",
                fingerprint)).isTrue();
        assertThat(TelemetryRegistry.admits(TelemetryRegistry.ENGINE_DIVERGENCE, "fingerprint",
                "NullPointerException at Foo")).isFalse();
    }

    @Test
    void aVersionAttributeAcceptsOnlyDigitsDotsAndDashes() {
        assertThat(TelemetryRegistry.isVersion("0.1.0")).isTrue();
        assertThat(TelemetryRegistry.isVersion("21.1.238")).isTrue();
        assertThat(TelemetryRegistry.isVersion("0.1.0-user-ashu")).isFalse();
        assertThat(TelemetryRegistry.isVersion("")).isFalse();
    }

    // ---- bucketing ------------------------------------------------------------------------------

    @Test
    void bucketsCoarsenMeasurementsIntoStatistics() {
        assertThat(Buckets.megabytes(5 * 1024 * 1024 + 999)).isEqualTo(5);
        assertThat(Buckets.megabytes(-1)).isZero();
        // Exact below a minute, 10-second steps below an hour, minute steps above.
        assertThat(Buckets.seconds(42_000)).isEqualTo(42);
        assertThat(Buckets.seconds(97_000)).isEqualTo(90);
        assertThat(Buckets.seconds(7_265_000)).isEqualTo(7_260); // 7,265 s → whole minutes
        assertThat(Buckets.tps(19.6)).isEqualTo(20);
        assertThat(Buckets.tps(24.0)).isEqualTo(20); // a meter above vanilla's ceiling is an artefact
        assertThat(Buckets.tps(Double.NaN)).isZero();
        assertThat(Buckets.percent(1, 3)).isEqualTo(33);
        assertThat(Buckets.percent(5, 0)).isZero();
        // Exact to 8, then a power-of-two ladder: the magnitude, not the hardware fingerprint.
        assertThat(Buckets.magnitude(6)).isEqualTo(6);
        assertThat(Buckets.magnitude(12)).isEqualTo(16);
        assertThat(Buckets.magnitude(48)).isEqualTo(64);
    }

    // ---- the projection -------------------------------------------------------------------------

    /**
     * The first window reports zero rather than a lifetime of counters — otherwise a node that has
     * been up for a week reports a week of traffic as one window and skews every rate.
     */
    @Test
    void theFirstWindowIsZeroAndTheSecondIsTheDelta() {
        SnapshotProjector projector = new SnapshotProjector();
        List<TelemetryEvent> first = projector.project(snapshot(100L << 20, 50L << 20, List.of()),
                null, TelemetryConsent.GRANTED, 300, 1_000L);
        assertThat(traffic(first).attrs()).containsEntry("up_mb_bucket", 0L);

        List<TelemetryEvent> second = projector.project(snapshot(103L << 20, 51L << 20, List.of()),
                null, TelemetryConsent.GRANTED, 300, 2_000L);
        assertThat(traffic(second).attrs())
                .containsEntry("up_mb_bucket", 3L)
                .containsEntry("down_mb_bucket", 1L);
    }

    @Test
    void relayedPeersAreCountedSeparatelyFromDirectOnes() {
        SnapshotProjector projector = new SnapshotProjector();
        projector.project(snapshot(0, 0, List.of()), null, TelemetryConsent.GRANTED, 300, 1L);
        List<TelemetryEvent> events = projector.project(
                snapshot(0, 0, List.of(peer("a", "tcp://a:1"), peer("b", "relay://r:2"),
                        peer("c", "relay://r:3"))),
                null, TelemetryConsent.GRANTED, 300, 2L);
        assertThat(traffic(events).attrs())
                .containsEntry("peers", 3L)
                .containsEntry("relayed_peers", 2L);
    }

    /** No emitted attribute may carry an address, a node id, or a route. */
    @Test
    void aProjectedEventCarriesNoIdentifyingValue() {
        SnapshotProjector projector = new SnapshotProjector();
        List<TelemetryEvent> events = projector.project(
                snapshot(1 << 20, 1 << 20, List.of(peer("a", "tcp://198.51.100.7:25599"))),
                new SnapshotProjector.TickHealth(19.4, 2), TelemetryConsent.GRANTED, 300, 5_000L);
        for (TelemetryEvent event : events) {
            String json = event.toJson();
            assertThat(json).doesNotContain("198.51.100").doesNotContain("tcp://")
                    .doesNotContain(SELF.value().toString());
        }
    }

    private static TelemetryEvent traffic(List<TelemetryEvent> events) {
        return events.stream()
                .filter(event -> event.name().equals(TelemetryRegistry.NET_TRAFFIC))
                .findFirst().orElseThrow();
    }

    // ---- the spool ------------------------------------------------------------------------------

    @Test
    void theSpoolIsBoundedAndDropsTheOldest(@TempDir Path dir) {
        TelemetrySpool spool = new TelemetrySpool(3, dir.resolve("spool.ndjson"));
        for (int i = 0; i < 5; i++) {
            spool.offer(TelemetryEvent.named(TelemetryRegistry.SESSION_END, i)
                    .number("minutes_bucket", i).build());
        }
        assertThat(spool.size()).isEqualTo(3);
        assertThat(spool.dropped()).isEqualTo(2);
        // The survivors are the NEWEST: the events just before a failure are the interesting ones.
        assertThat(spool.drain(10)).extracting(TelemetryEvent::atMillis).containsExactly(2L, 3L, 4L);
    }

    @Test
    void aFailedBatchGoesBackAtTheFrontInOrder() {
        TelemetrySpool spool = TelemetrySpool.inMemory();
        List<TelemetryEvent> batch = List.of(
                TelemetryEvent.named(TelemetryRegistry.SESSION_END, 1).build(),
                TelemetryEvent.named(TelemetryRegistry.SESSION_END, 2).build());
        spool.offer(TelemetryEvent.named(TelemetryRegistry.SESSION_END, 3).build());
        spool.requeue(batch);
        assertThat(spool.drain(10)).extracting(TelemetryEvent::atMillis).containsExactly(1L, 2L, 3L);
    }

    @Test
    void theSpoolSurvivesARestart(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("spool.ndjson");
        TelemetrySpool before = new TelemetrySpool(100, file);
        before.offer(TelemetryEvent.named(TelemetryRegistry.WORLD_JOIN, 7)
                .flag("ok", true).enumeration("path", "relayed").number("seconds_bucket", 4)
                .build());
        before.persist();

        TelemetrySpool after = new TelemetrySpool(100, file);
        after.restore();
        List<TelemetryEvent> restored = after.drain(10);
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).name()).isEqualTo(TelemetryRegistry.WORLD_JOIN);
        assertThat(restored.get(0).attrs())
                .containsEntry("ok", true)
                .containsEntry("path", "relayed")
                .containsEntry("seconds_bucket", 4L);
    }

    /** A line naming an event the registry does not declare never becomes an event. */
    @Test
    void aPersistedLineWithAnUndeclaredNameIsRefused() {
        assertThat(TelemetrySpool.parse("{\"name\":\"world.name\",\"t\":1,\"attrs\":{}}")).isNull();
        assertThat(TelemetrySpool.parse("nonsense")).isNull();
        assertThat(TelemetrySpool.parse("{\"name\":\"session.end\",\"t\":1,\"attrs\":{}}"))
                .isNotNull();
    }

    @Test
    void clearingTheSpoolRemovesTheFileToo(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("spool.ndjson");
        TelemetrySpool spool = new TelemetrySpool(10, file);
        spool.offer(TelemetryEvent.named(TelemetryRegistry.SESSION_END, 1).build());
        spool.persist();
        assertThat(file).exists();
        spool.clear();
        assertThat(file).doesNotExist();
        assertThat(spool.size()).isZero();
    }

    // ---- the install identifier -------------------------------------------------------------------

    @Test
    void anInstallIdentifierIsOneHundredAndTwentyEightBitsOfHex() {
        assertThat(InstallId.generate().value()).hasSize(32).matches("[0-9a-f]{32}");
        assertThat(InstallId.parse("not-hex")).isNull();
        assertThat(InstallId.parse("0123456789ABCDEF0123456789ABCDEF")).isNull();
    }

    /**
     * A revoke/grant cycle produces a NEW installation, so a revocation cannot be undone by
     * re-granting: nothing the client sends can link the two eras of reports.
     */
    @Test
    void forgettingTheIdentifierYieldsADifferentOneOnTheNextGrant(@TempDir Path dir)
            throws IOException {
        Path file = dir.resolve("install-id");
        String first = InstallId.loadOrCreate(file).value();
        assertThat(InstallId.loadOrCreate(file).value()).isEqualTo(first); // stable while it exists
        InstallId.forget(file);
        assertThat(InstallId.loadOrCreate(file).value()).isNotEqualTo(first);
    }

    @Test
    void aCorruptIdentifierFileIsReplacedRatherThanThrowing(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("install-id");
        java.nio.file.Files.writeString(file, "garbage");
        assertThat(InstallId.loadOrCreate(file).value()).matches("[0-9a-f]{32}");
    }

    @Test
    void theIdentifierIsTruncatedWhenItIsPrinted() {
        // toString ends up in logs, and a log line is where an identifier gets copied into a bug
        // report. Truncation is the cheap mitigation.
        assertThat(InstallId.generate().toString()).hasSizeLessThan(24).contains("…");
    }

    // ---- the consent store ------------------------------------------------------------------------

    @Test
    void anAbsentConsentFileMeansUnanswered(@TempDir Path dir) {
        assertThat(new TelemetryConsentStore(dir.resolve("missing")).consent())
                .isEqualTo(TelemetryConsent.UNANSWERED);
    }

    @Test
    void aRecordedDecisionSurvivesARestart(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("consent");
        TelemetryConsentStore store = new TelemetryConsentStore(file);
        assertThat(store.set(TelemetryConsent.GRANTED)).isTrue();
        assertThat(store.set(TelemetryConsent.GRANTED)).isFalse(); // no change, no side effects
        assertThat(new TelemetryConsentStore(file).consent()).isEqualTo(TelemetryConsent.GRANTED);

        store.set(TelemetryConsent.DENIED);
        assertThat(new TelemetryConsentStore(file).consent()).isEqualTo(TelemetryConsent.DENIED);
    }

    @Test
    void aCorruptConsentFileFailsClosed(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("consent");
        java.nio.file.Files.writeString(file, "granted-ish ");
        assertThat(new TelemetryConsentStore(file).consent()).isEqualTo(TelemetryConsent.UNANSWERED);
    }
}
