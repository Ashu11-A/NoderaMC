package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deleting a world is the one instruction on this network that destroys something, so this suite is
 * mostly about what must <b>not</b> work.
 *
 * <p>Every negative case is an attack that would otherwise cost somebody their world: a stranger
 * signing a deletion, a real owner deleting a world that is not theirs, a valid deletion lifted onto
 * a different world id, and a tombstone stripped of the evidence that makes it checkable. A receiver
 * has no other defence — it cannot ask anybody whether the request is genuine — so the record has to
 * answer that on its own, every time.
 */
final class WorldTombstoneTest {

    private static Bytes worldId(String seed) {
        return new dev.nodera.core.crypto.HashService().sha256(seed.getBytes());
    }

    /** An owner, their world's key, and the ownership claim binding the two. */
    private record World(NodeIdentity owner, PersistedWorldKey key, WorldOwnership ownership) {
        static World create(String seed) {
            NodeIdentity owner = NodeIdentity.generate();
            PersistedWorldKey key = PersistedWorldKey.generate(worldId(seed));
            return new World(owner, key, WorldOwnership.create(owner, key, 1_000L));
        }

        WorldTombstone delete(String reason) {
            return WorldTombstone.create(owner, key, ownership, reason, 2_000L);
        }
    }

    @Test
    @DisplayName("the owner's deletion verifies, and says who issued it")
    void aGenuineDeletionVerifies() {
        World world = World.create("mine");

        WorldTombstone tombstone = world.delete("finished with it");

        assertThat(tombstone.verify()).isTrue();
        assertThat(tombstone.issuedBy(world.owner().nodeId())).isTrue();
        assertThat(tombstone.issuedBy(NodeIdentity.generate().nodeId())).isFalse();
        assertThat(tombstone.worldId()).isEqualTo(world.ownership().worldId());
        assertThat(tombstone.reason()).isEqualTo("finished with it");
    }

    @Test
    @DisplayName("a receiver that has never seen the world can still verify it")
    void theEvidenceTravelsWithTheRequest() {
        WorldTombstone tombstone = World.create("unknown to the receiver").delete("");

        // Round-tripped through bytes, which is all a peer on the far side of a relay ever gets.
        CanonicalWriter w = new CanonicalWriter();
        tombstone.encode(w);
        WorldTombstone received = WorldTombstone.decode(new CanonicalReader(w.toBytes()));

        // No registry, no prior ownership record, no trusted introducer — and the answer is the
        // same. That symmetry is what lets anyone relay a deletion without being trusted.
        assertThat(received).isEqualTo(tombstone);
        assertThat(received.verify()).isTrue();
        assertThat(received.ownership()).isPresent();
    }

    @Test
    @DisplayName("a stranger cannot delete somebody else's world")
    void aForgedDeletionIsRefused() {
        World world = World.create("victim");
        NodeIdentity attacker = NodeIdentity.generate();
        WorldTombstone genuine = world.delete("");

        // The attacker re-signs the deletion with its own keys, keeping the victim's ownership claim.
        WorldTombstone forged = new WorldTombstone(
                genuine.worldId(), genuine.ownershipRecord(), genuine.issuedAtEpoch(), "",
                attacker.sign(genuine.signedPortion()), attacker.sign(genuine.signedPortion()));

        // The claim inside still names the victim's keys, and neither signature verifies under them.
        assertThat(forged.verify()).isFalse();
    }

    @Test
    @DisplayName("a real owner cannot delete a world that is not theirs")
    void aValidDeletionCannotBeLiftedOntoAnotherWorld() {
        World mine = World.create("mine");
        World theirs = World.create("theirs");
        WorldTombstone forMine = mine.delete("");

        // Their world id, my signatures and my claim: the id and the embedded claim disagree.
        WorldTombstone moved = new WorldTombstone(theirs.ownership().worldId(),
                forMine.ownershipRecord(), forMine.issuedAtEpoch(), "", forMine.worldSignature(),
                forMine.ownerSignature());

        assertThat(moved.verify()).isFalse();
    }

    @Test
    @DisplayName("both signatures are required — a stolen world key alone is not enough")
    void oneSignatureIsNotADeletion() {
        World world = World.create("mine");
        WorldTombstone tombstone = world.delete("");

        assertThat(new WorldTombstone(tombstone.worldId(), tombstone.ownershipRecord(),
                tombstone.issuedAtEpoch(), "", Bytes.empty(), tombstone.ownerSignature()).verify())
                .as("no world signature: nothing proves the administrator asked")
                .isFalse();
        assertThat(new WorldTombstone(tombstone.worldId(), tombstone.ownershipRecord(),
                tombstone.issuedAtEpoch(), "", tombstone.worldSignature(), Bytes.empty()).verify())
                .as("no owner signature: a leaked world key would be enough on its own")
                .isFalse();
    }

    @Test
    @DisplayName("a tombstone with no evidence is refused rather than trusted")
    void evidenceIsNotOptional() {
        World world = World.create("mine");
        WorldTombstone tombstone = world.delete("");

        WorldTombstone stripped = new WorldTombstone(tombstone.worldId(), Bytes.empty(),
                tombstone.issuedAtEpoch(), "", tombstone.worldSignature(),
                tombstone.ownerSignature());

        // Without the claim there is no key to check against. "Cannot verify" must mean "refuse",
        // never "assume the sender is honest".
        assertThat(stripped.verify()).isFalse();
        assertThat(stripped.ownership()).isEmpty();
    }

    @Test
    @DisplayName("a tampered ownership claim is refused")
    void aBrokenClaimIsRefused() {
        World world = World.create("mine");
        WorldTombstone tombstone = world.delete("");
        Bytes claim = tombstone.ownershipRecord();
        byte[] corrupted = claim.toArray();
        corrupted[corrupted.length - 1] ^= 0x01;

        assertThat(new WorldTombstone(tombstone.worldId(), Bytes.unsafeWrap(corrupted),
                tombstone.issuedAtEpoch(), "", tombstone.worldSignature(),
                tombstone.ownerSignature()).verify()).isFalse();
    }

    @Test
    @DisplayName("changing anything the owner signed invalidates it")
    void theSignedPortionCoversEveryField() {
        World world = World.create("mine");
        WorldTombstone tombstone = world.delete("because I said so");

        assertThat(new WorldTombstone(tombstone.worldId(), tombstone.ownershipRecord(),
                tombstone.issuedAtEpoch() + 1, tombstone.reason(), tombstone.worldSignature(),
                tombstone.ownerSignature()).verify())
                .as("a re-dated deletion is a different statement")
                .isFalse();
        assertThat(new WorldTombstone(tombstone.worldId(), tombstone.ownershipRecord(),
                tombstone.issuedAtEpoch(), "something else entirely", tombstone.worldSignature(),
                tombstone.ownerSignature()).verify())
                .as("the reason is quoted to a person, so it must not be editable in flight")
                .isFalse();
    }

    @Test
    @DisplayName("minting refuses parts that do not describe one world")
    void mintingRefusesMismatchedParts() {
        World mine = World.create("mine");
        World theirs = World.create("theirs");

        // Caught at the source: a tombstone whose parts disagree is not a weaker deletion, it is a
        // bug that would reach receivers looking exactly like a forgery.
        assertThatThrownBy(() ->
                WorldTombstone.create(mine.owner(), theirs.key(), mine.ownership(), "", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not for the world");
        assertThatThrownBy(() ->
                WorldTombstone.create(NodeIdentity.generate(), mine.key(), mine.ownership(), "", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only the world's owner");
    }

    @Test
    @DisplayName("a reason cannot be used to smuggle a payload")
    void theReasonIsBounded() {
        World world = World.create("mine");

        WorldTombstone tombstone = WorldTombstone.create(world.owner(), world.key(),
                world.ownership(), "x".repeat(5_000), 1L);

        assertThat(tombstone.reason()).hasSize(WorldTombstone.MAX_REASON);
        assertThat(tombstone.verify()).as("truncation happens before signing, so it still verifies")
                .isTrue();
    }

    @Test
    @DisplayName("a tombstone must name a world")
    void aTombstoneWithoutAWorldIsNotATombstone() {
        assertThatThrownBy(() -> new WorldTombstone(Bytes.empty(), Bytes.empty(), 1L, "",
                Bytes.empty(), Bytes.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must name a world");
    }
}
