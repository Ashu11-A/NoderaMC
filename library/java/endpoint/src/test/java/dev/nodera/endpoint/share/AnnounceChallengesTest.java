package dev.nodera.endpoint.share;

import dev.nodera.endpoint.share.AnnounceChallenges;
import dev.nodera.core.Bytes;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #36 (F1): single-use, expiring, per-login announce challenges. */
final class AnnounceChallengesTest {

    @Test
    void issuedChallengeConsumesOnce() {
        AnnounceChallenges c = new AnnounceChallenges();
        UUID player = UUID.randomUUID();
        long now = 1_000L;
        Bytes nonce = c.issue(player, now);
        assertEquals(AnnounceChallenges.NONCE_LENGTH, nonce.length());
        Optional<Bytes> first = c.consume(player, now + 1);
        assertTrue(first.isPresent());
        assertEquals(nonce, first.get());
        // Single-use: a second consume returns nothing (replay defence).
        assertTrue(c.consume(player, now + 2).isEmpty());
    }

    @Test
    void expiredChallengeIsRejected() {
        AnnounceChallenges c = new AnnounceChallenges();
        UUID player = UUID.randomUUID();
        c.issue(player, 0L);
        assertTrue(c.consume(player, AnnounceChallenges.TTL_MILLIS + 1).isEmpty());
    }

    @Test
    void reissueReplacesOutstanding() {
        AnnounceChallenges c = new AnnounceChallenges();
        UUID player = UUID.randomUUID();
        Bytes first = c.issue(player, 0L);
        Bytes second = c.issue(player, 10L);
        assertNotEquals(first, second);
        assertEquals(1, c.outstanding());
        // Only the latest is valid.
        assertEquals(second, c.consume(player, 20L).orElseThrow());
    }

    @Test
    void unknownPlayerHasNoChallenge() {
        AnnounceChallenges c = new AnnounceChallenges();
        assertTrue(c.consume(UUID.randomUUID(), 0L).isEmpty());
    }

    @Test
    void forgetDropsChallenge() {
        AnnounceChallenges c = new AnnounceChallenges();
        UUID player = UUID.randomUUID();
        c.issue(player, 0L);
        c.forget(player);
        assertEquals(0, c.outstanding());
        assertFalse(c.consume(player, 1L).isPresent());
    }

    @Test
    void distinctPlayersGetDistinctNonces() {
        AnnounceChallenges c = new AnnounceChallenges();
        Bytes a = c.issue(UUID.randomUUID(), 0L);
        Bytes b = c.issue(UUID.randomUUID(), 0L);
        assertNotEquals(a, b);
        assertEquals(2, c.outstanding());
    }
}
