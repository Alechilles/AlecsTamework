package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact and world-scoped replacement coverage for current managed-authority evidence. */
class ManagedCoopAuthorityEligibilityIndexTest {
    @Test
    void exactAuthorityAndCoopIdMustBothMatch() {
        ManagedCoopAuthorityEligibilityIndex index =
                new ManagedCoopAuthorityEligibilityIndex();
        ManagedCoopAuthorityKey chicken =
                new ManagedCoopAuthorityKey("world-a", 1, 2, 3);
        index.replaceWorld(" WORLD-A ", List.of(
                new ManagedCoopAuthorityEligibilityIndex.AuthorityEvidence(
                        chicken, " COOP_CHICKEN ")));

        assertTrue(index.snapshot().contains(chicken, "coop_chicken"));
        assertFalse(index.snapshot().contains(chicken, "coop_duck"));
        assertFalse(index.snapshot().contains(
                new ManagedCoopAuthorityKey("world-a", 1, 2, 4), "coop_chicken"));
    }

    @Test
    void reliableWorldReplacementAndInvalidationPreserveOtherWorlds() {
        ManagedCoopAuthorityEligibilityIndex index =
                new ManagedCoopAuthorityEligibilityIndex();
        ManagedCoopAuthorityKey worldA =
                new ManagedCoopAuthorityKey("world-a", 1, 2, 3);
        ManagedCoopAuthorityKey worldB =
                new ManagedCoopAuthorityKey("world-b", 4, 5, 6);
        index.replaceWorld("world-a", List.of(
                new ManagedCoopAuthorityEligibilityIndex.AuthorityEvidence(
                        worldA, "coop_chicken")));
        index.replaceWorld("world-b", List.of(
                new ManagedCoopAuthorityEligibilityIndex.AuthorityEvidence(
                        worldB, "coop_duck")));

        index.replaceWorld("world-a", List.of());
        assertFalse(index.snapshot().contains(worldA, "coop_chicken"));
        assertTrue(index.snapshot().contains(worldB, "coop_duck"));

        index.invalidateWorld("world-b");
        assertTrue(index.snapshot().coopIds().isEmpty());

        index.replaceWorld("world-a", List.of(
                new ManagedCoopAuthorityEligibilityIndex.AuthorityEvidence(
                        worldA, "coop_chicken")));
        index.invalidateAll();
        assertTrue(index.snapshot().coopIds().isEmpty());
    }

    @Test
    void staleScanCannotRepublishEvidenceAfterLifecycleInvalidation() {
        ManagedCoopAuthorityEligibilityIndex index =
                new ManagedCoopAuthorityEligibilityIndex();
        ManagedCoopAuthorityKey authority =
                new ManagedCoopAuthorityKey("world-a", 1, 2, 3);
        ManagedCoopAuthorityEligibilityIndex.PublicationToken token =
                index.publicationToken("world-a");

        index.invalidateAll();

        assertFalse(index.replaceWorldIfCurrent(
                "world-a",
                List.of(new ManagedCoopAuthorityEligibilityIndex.AuthorityEvidence(
                        authority, "coop_chicken")),
                token));
        assertTrue(index.snapshot().coopIds().isEmpty());
    }

    @Test
    void unrelatedWorldPublicationAndInvalidationDoNotRejectThisWorldToken() {
        ManagedCoopAuthorityEligibilityIndex index =
                new ManagedCoopAuthorityEligibilityIndex();
        ManagedCoopAuthorityKey worldA =
                new ManagedCoopAuthorityKey("world-a", 1, 2, 3);
        ManagedCoopAuthorityKey worldB =
                new ManagedCoopAuthorityKey("world-b", 4, 5, 6);
        var evidenceA = List.of(
                new ManagedCoopAuthorityEligibilityIndex.AuthorityEvidence(
                        worldA, "coop_chicken"));
        var evidenceB = List.of(
                new ManagedCoopAuthorityEligibilityIndex.AuthorityEvidence(
                        worldB, "coop_duck"));
        var tokenA = index.publicationToken("world-a");
        var tokenB = index.publicationToken("world-b");

        assertTrue(index.replaceWorldIfCurrent("world-a", evidenceA, tokenA));
        assertTrue(index.replaceWorldIfCurrent("world-b", evidenceB, tokenB),
                "world-a publication must not stale world-b's concurrent token");

        var nextTokenA = index.publicationToken("world-a");
        var staleTokenB = index.publicationToken("world-b");
        index.invalidateWorld("world-b");

        assertTrue(index.replaceWorldIfCurrent("world-a", evidenceA, nextTokenA));
        assertFalse(index.replaceWorldIfCurrent("world-b", evidenceB, staleTokenB));
    }

    @Test
    void closePermanentlyRejectsLaterRuntimePublication() {
        ManagedCoopAuthorityEligibilityIndex index =
                new ManagedCoopAuthorityEligibilityIndex();
        ManagedCoopAuthorityKey authority =
                new ManagedCoopAuthorityKey("world-a", 1, 2, 3);
        var evidence = List.of(
                new ManagedCoopAuthorityEligibilityIndex.AuthorityEvidence(
                        authority, "coop_chicken"));
        index.replaceWorld("world-a", evidence);
        long beforeClose = index.snapshot().revision();
        var token = index.publicationToken("world-a");

        index.close();

        assertTrue(index.snapshot().revision() > beforeClose);
        assertTrue(index.snapshot().coopIds().isEmpty());
        assertThrows(IllegalStateException.class,
                () -> index.replaceWorld("world-a", evidence));
        assertFalse(index.replaceWorldIfCurrent("world-a", evidence, token));
    }
}
