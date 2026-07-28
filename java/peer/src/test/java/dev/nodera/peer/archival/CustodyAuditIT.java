package dev.nodera.peer.archival;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.CustodyClass;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exit test for <b>L-62</b>: an endpoint advertising {@code custody: FULL} while missing a
 * region is caught by a random spot-check against its advertised digest and <b>downgraded to
 * {@code VIEW}</b>, with the world staying available throughout.
 *
 * <p>The lying endpoint here is the hardest shape of the failure this row describes: it has silently
 * lost part of its world and advertises a digest that is internally perfect, so every region it does
 * still hold proves out and nothing but sampling the loss itself tells it apart from a whole replica.
 */
class CustodyAuditIT {

    private static final int WORLD_REGIONS = 64;

    private final HashService hasher = new HashService();
    private final NodeId liar = ArchivalFixtures.node(1);
    private final NodeId honest = ArchivalFixtures.node(2);

    private static RegionId region(int index) {
        return new RegionId(DimensionKey.overworld(), index % 8 - 4, index / 8 - 4);
    }

    private static List<RegionId> world() {
        List<RegionId> regions = new ArrayList<>();
        for (int i = 0; i < WORLD_REGIONS; i++) {
            regions.add(region(i));
        }
        return List.copyOf(regions);
    }

    private static Map<RegionId, SnapshotVersion> heads(List<RegionId> regions) {
        Map<RegionId, SnapshotVersion> heads = new LinkedHashMap<>();
        for (int i = 0; i < regions.size(); i++) {
            heads.put(regions.get(i), new SnapshotVersion(1_000 + i));
        }
        return heads;
    }

    /** A node that answers spot-checks out of whatever it actually holds. */
    private record Endpoint(CustodyDigest held, Bytes advertisedRoot) implements CustodyAudit.Responder {

        @Override
        public Optional<CustodyDigest.Proof> proofFor(RegionId region) {
            return held.proofFor(region);
        }
    }

    private Endpoint completeEndpoint(List<RegionId> world) {
        CustodyDigest digest = CustodyDigest.of(heads(world), hasher);
        return new Endpoint(digest, digest.root());
    }

    private Endpoint lyingEndpoint(List<RegionId> world, RegionId lost) {
        Map<RegionId, SnapshotVersion> actual = new LinkedHashMap<>(heads(world));
        actual.remove(lost);
        // The sharpest version of the lie: it advertises a digest that is internally perfect — every
        // region it still holds proves out against it — and claims FULL over a world it no longer
        // covers. Nothing but sampling the region it lost can tell it apart from a whole replica.
        CustodyDigest digest = CustodyDigest.of(actual, hasher);
        return new Endpoint(digest, digest.root());
    }

    @Test
    @DisplayName("L-62 exit: a FULL endpoint missing a region is caught by random spot-checks and downgraded to VIEW")
    void aLyingFullClaimIsCaughtAndDowngraded() {
        List<RegionId> world = world();
        RegionId lost = region(37);
        Endpoint endpoint = lyingEndpoint(world, lost);
        CustodyAudit audit = new CustodyAudit(new Random(20260728L));

        // A single random sample has a 1/64 chance of landing on the loss, so the audit is a
        // repeated sample — which is how a real auditor runs it, one region per round.
        CustodyAudit.Outcome caught = null;
        int rounds = 0;
        for (int i = 0; i < 512 && caught == null; i++) {
            rounds++;
            CustodyAudit.Outcome outcome = audit.spotCheck(
                    liar, CustodyClass.FULL, endpoint.advertisedRoot(), world, endpoint);
            if (outcome.downgraded()) {
                caught = outcome;
            } else {
                // Until the loss is sampled the claim survives — the audit invents no failures.
                assertThat(outcome.effective()).isEqualTo(CustodyClass.FULL);
                assertThat(outcome.sampled()).isPresent().get().isNotEqualTo(lost);
            }
        }

        assertThat(caught).as("the missing region is sampled within 512 rounds").isNotNull();
        assertThat(caught.effective()).isEqualTo(CustodyClass.VIEW);
        assertThat(caught.claimed()).isEqualTo(CustodyClass.FULL);
        assertThat(caught.sampled()).contains(lost);
        assertThat(caught.reason()).contains(lost.toString()).contains("VIEW");
        assertThat(rounds).isLessThanOrEqualTo(512);

        // The world stays available throughout: the downgrade withdrew the claim, not the node.
        // Every region the endpoint still holds is still answered, and still verifies against the
        // digest of what it actually has.
        for (RegionId region : world) {
            Optional<CustodyDigest.Proof> answer = endpoint.proofFor(region);
            if (region.equals(lost)) {
                assertThat(answer).isEmpty();
                continue;
            }
            assertThat(answer).as("%s still served after the downgrade", region).isPresent();
            assertThat(CustodyDigest.verify(endpoint.held().root(), answer.orElseThrow(), hasher))
                    .isTrue();
        }
    }

    @Test
    @DisplayName("an honest FULL endpoint is never downgraded, however many times it is sampled")
    void anHonestClaimSurvivesEveryRound() {
        List<RegionId> world = world();
        Endpoint endpoint = completeEndpoint(world);
        CustodyAudit audit = new CustodyAudit(new Random(7L));

        for (int i = 0; i < 4 * WORLD_REGIONS; i++) {
            CustodyAudit.Outcome outcome = audit.spotCheck(
                    honest, CustodyClass.FULL, endpoint.advertisedRoot(), world, endpoint);
            assertThat(outcome.downgraded()).as("round %d", i).isFalse();
            assertThat(outcome.effective()).isEqualTo(CustodyClass.FULL);
        }
    }

    @Test
    @DisplayName("an endpoint that quietly rolled a region back is caught by the same check")
    void aStaleHeadIsCaught() {
        List<RegionId> world = world();
        RegionId stale = region(11);
        Map<RegionId, SnapshotVersion> complete = heads(world);
        Bytes advertised = CustodyDigest.of(complete, hasher).root();
        Map<RegionId, SnapshotVersion> actual = new LinkedHashMap<>(complete);
        actual.put(stale, new SnapshotVersion(1));
        Endpoint endpoint = new Endpoint(CustodyDigest.of(actual, hasher), advertised);

        CustodyAudit.Outcome outcome = new CustodyAudit(new Random(0L)).spotCheck(
                liar, CustodyClass.FULL, advertised, List.of(stale), endpoint);

        assertThat(outcome.effective()).isEqualTo(CustodyClass.VIEW);
        assertThat(outcome.reason()).contains("does not reconstruct");
    }

    @Test
    @DisplayName("a VIEW claim is not audited, and FULL over no regions is not a claim worth keeping")
    void viewIsNotAuditedAndAnEmptyFullIsRefused() {
        Endpoint endpoint = completeEndpoint(world());
        CustodyAudit audit = new CustodyAudit(new Random(1L));

        CustodyAudit.Outcome view = audit.spotCheck(
                honest, CustodyClass.VIEW, endpoint.advertisedRoot(), world(), endpoint);
        assertThat(view.downgraded()).isFalse();
        assertThat(view.sampled()).isEmpty();

        CustodyAudit.Outcome empty = audit.spotCheck(
                liar, CustodyClass.FULL, endpoint.advertisedRoot(), List.of(), endpoint);
        assertThat(empty.effective()).isEqualTo(CustodyClass.VIEW);
    }

    @Test
    @DisplayName("an endpoint answering about a region other than the sampled one is caught")
    void answeringTheWrongQuestionIsCaught() {
        List<RegionId> world = world();
        Endpoint complete = completeEndpoint(world);
        RegionId elsewhere = region(3);
        CustodyAudit.Responder evasive = r -> complete.proofFor(elsewhere);

        CustodyAudit.Outcome outcome = new CustodyAudit(new Random(2L)).spotCheck(
                liar, CustodyClass.FULL, complete.advertisedRoot(), List.of(region(42)), evasive);

        assertThat(outcome.effective()).isEqualTo(CustodyClass.VIEW);
        assertThat(outcome.reason()).contains("when asked about");
    }
}
