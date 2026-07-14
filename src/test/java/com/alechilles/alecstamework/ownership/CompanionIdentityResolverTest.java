package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.sqlite.CompanionIdentityRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionIdentityResolverTest {
    @Test
    void historicalAndReplacementUuidsResolveToOneProfile() {
        UUID historical = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        CompanionIdentityResolver resolver = new CompanionIdentityResolver();
        resolver.replaceDurableAliases(List.of(
                new CompanionIdentityRepository.AliasRecord(historical, "profile-a", false, 1L),
                new CompanionIdentityRepository.AliasRecord(current, "profile-a", true, 2L)
        ));

        assertEquals("profile-a", resolver.resolveProfileId(historical).orElseThrow());
        assertEquals("profile-a", resolver.resolveProfileId(current).orElseThrow());
        assertEquals(current, resolver.currentNpcUuid("profile-a").orElseThrow());
        assertEquals(2, resolver.aliasCount());
    }

    @Test
    void idempotencyKeyAllocatesExactlyOneProvisionalProfile() {
        UUID npcUuid = UUID.randomUUID();
        CompanionIdentityResolver resolver = new CompanionIdentityResolver();

        CompanionIdentityResolver.Resolution first = resolver.resolveOrAllocate(npcUuid, "spawn:token");
        CompanionIdentityResolver.Resolution second = resolver.resolveOrAllocate(npcUuid, "spawn:token");

        assertTrue(first.provisional());
        assertEquals(first, second);
        assertEquals(first.profileId(), resolver.resolveProfileId(npcUuid).orElseThrow());
        assertThrows(IllegalArgumentException.class, () ->
                resolver.resolveOrAllocate(UUID.randomUUID(), "spawn:token")
        );
    }

    /** Regression: a competing operation cannot release or take over another key's provisional identity. */
    @Test
    void provisionalIdentityRemainsOwnedByItsIdempotencyKeyUntilExplicitRelease() {
        UUID npcUuid = UUID.randomUUID();
        CompanionIdentityResolver resolver = new CompanionIdentityResolver();
        CompanionIdentityResolver.Resolution original =
                resolver.resolveOrAllocate(npcUuid, "owner-operation");

        assertEquals(original, resolver.resolveOrAllocate(npcUuid, "owner-operation"));
        assertThrows(IllegalArgumentException.class, () ->
                resolver.resolveOrAllocate(npcUuid, "competing-operation")
        );
        assertFalse(resolver.releaseProvisional("another-profile", npcUuid));
        assertFalse(resolver.releaseProvisional(original.profileId(), UUID.randomUUID()));
        assertEquals(original.profileId(), resolver.resolveProfileId(npcUuid).orElseThrow());

        assertTrue(resolver.releaseProvisional(original.profileId(), npcUuid));
        assertEquals(original.profileId(), resolver.resolveProfileId(npcUuid).orElseThrow());
        assertTrue(resolver.releaseProvisional(original.profileId(), npcUuid));
        assertTrue(resolver.resolveProfileId(npcUuid).isEmpty());
        CompanionIdentityResolver.Resolution replacement =
                resolver.resolveOrAllocate(npcUuid, "replacement-operation");
        assertTrue(replacement.provisional());
        assertFalse(original.profileId().equals(replacement.profileId()));
    }

    /** Regression: canceling one duplicate schedule cannot release another schedule's identity. */
    @Test
    void sameKeyProvisionalIdentityIsRetainedUntilEveryResolveLeaseCloses() {
        UUID npcUuid = UUID.randomUUID();
        CompanionIdentityResolver resolver = new CompanionIdentityResolver();
        CompanionIdentityResolver.Resolution first =
                resolver.resolveOrAllocate(npcUuid, "shared-operation");
        CompanionIdentityResolver.Resolution second =
                resolver.resolveOrAllocate(npcUuid, "shared-operation");

        assertEquals(first, second);
        assertTrue(resolver.releaseProvisional(first.profileId(), npcUuid));
        assertEquals(first.profileId(), resolver.resolveProfileId(npcUuid).orElseThrow());
        assertThrows(IllegalArgumentException.class, () ->
                resolver.resolveOrAllocate(npcUuid, "competing-operation")
        );

        assertTrue(resolver.releaseProvisional(first.profileId(), npcUuid));
        assertTrue(resolver.resolveProfileId(npcUuid).isEmpty());
    }

    /** A snapshot writer may persist an identity already reserved by the projection operation. */
    @Test
    void profileWriteRetainsAProvisionalIdentityOwnedByAnotherOperation() {
        UUID npcUuid = UUID.randomUUID();
        CompanionIdentityResolver resolver = new CompanionIdentityResolver();
        CompanionIdentityResolver.Resolution spawn =
                resolver.resolveOrAllocate(npcUuid, "spawn-operation");

        CompanionIdentityResolver.Resolution profileWrite =
                resolver.resolveOrRetainForProfileWrite(npcUuid, "profile-write");

        assertEquals(spawn.profileId(), profileWrite.profileId());
        assertTrue(profileWrite.provisional());
        assertTrue(resolver.releaseProvisional(spawn.profileId(), npcUuid));
        assertEquals(spawn.profileId(), resolver.resolveProfileId(npcUuid).orElseThrow());
        assertTrue(resolver.releaseProvisional(profileWrite.profileId(), npcUuid));
        assertTrue(resolver.resolveProfileId(npcUuid).isEmpty());
    }

    @Test
    void existingAliasWinsWithoutAllocatingAnotherProfile() {
        UUID npcUuid = UUID.randomUUID();
        CompanionIdentityResolver resolver = new CompanionIdentityResolver();
        resolver.replaceDurableAliases(List.of(
                new CompanionIdentityRepository.AliasRecord(npcUuid, "profile-a", true, 1L)
        ));

        CompanionIdentityResolver.Resolution resolution =
                resolver.resolveOrAllocate(npcUuid, "unused-key");

        assertFalse(resolution.provisional());
        assertEquals("profile-a", resolution.profileId());
        assertEquals(1, resolver.aliasCount());
    }

    @Test
    void committedProvisionalIdentityBecomesDurable() {
        UUID npcUuid = UUID.randomUUID();
        CompanionIdentityResolver resolver = new CompanionIdentityResolver();
        CompanionIdentityResolver.Resolution provisional =
                resolver.resolveOrAllocate(npcUuid, "spawn:token");

        resolver.markDurable(provisional.profileId(), npcUuid);

        CompanionIdentityResolver.Resolution durable =
                resolver.resolveOrAllocate(npcUuid, "another-operation");
        assertFalse(durable.provisional());
        assertEquals(provisional.profileId(), durable.profileId());
    }

    @Test
    void durablePreparationPromotionInvalidatesAllOutstandingProvisionalLeases() {
        UUID npcUuid = UUID.randomUUID();
        CompanionIdentityResolver resolver = new CompanionIdentityResolver();
        CompanionIdentityResolver.Resolution first =
                resolver.resolveOrAllocate(npcUuid, "shared-prepare");
        CompanionIdentityResolver.Resolution duplicate =
                resolver.resolveOrAllocate(npcUuid, "shared-prepare");
        assertEquals(first, duplicate);

        resolver.markDurable(first.profileId(), npcUuid);

        assertFalse(resolver.releaseProvisional(first.profileId(), npcUuid));
        assertFalse(resolver.releaseProvisional(first.profileId(), npcUuid));
        CompanionIdentityResolver.Resolution retry =
                resolver.resolveOrAllocate(npcUuid, "different-retry-key");
        assertFalse(retry.provisional());
        assertEquals(first.profileId(), retry.profileId());
    }

    @Test
    void remapKeepsOldAliasAndRejectsCrossProfileCollision() {
        UUID first = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID replacement = UUID.randomUUID();
        CompanionIdentityResolver resolver = new CompanionIdentityResolver();
        resolver.replaceDurableAliases(List.of(
                new CompanionIdentityRepository.AliasRecord(first, "profile-a", true, 1L),
                new CompanionIdentityRepository.AliasRecord(other, "profile-b", true, 1L)
        ));

        resolver.remap("profile-a", first, replacement);

        assertEquals("profile-a", resolver.resolveProfileId(first).orElseThrow());
        assertEquals("profile-a", resolver.resolveProfileId(replacement).orElseThrow());
        assertEquals(replacement, resolver.currentNpcUuid("profile-a").orElseThrow());
        assertThrows(IllegalArgumentException.class, () ->
                resolver.remap("profile-a", replacement, other)
        );
    }

    /** Regression: live observers must resolve a claimed batch spawn before its async commit. */
    @Test
    void preparedSpawnAliasPreventsObserverFromAllocatingACompetingProfile() {
        UUID planned = UUID.randomUUID();
        CompanionIdentityResolver resolver = new CompanionIdentityResolver();

        assertTrue(resolver.retainPreparedAlias("profile-a", planned));

        assertEquals("profile-a", resolver.resolveProfileId(planned).orElseThrow());
        assertEquals(
                "profile-a",
                resolver.resolveOrAllocate(planned, "runtime-observation:" + planned).profileId()
        );
        resolver.remap("profile-a", null, planned);
        assertEquals(planned, resolver.currentNpcUuid("profile-a").orElseThrow());
        assertTrue(resolver.releasePreparedAlias("profile-a", planned));
    }

    @Test
    void canceledPreparedSpawnReleasesOnlyItsOwnAliasLease() {
        UUID planned = UUID.randomUUID();
        CompanionIdentityResolver resolver = new CompanionIdentityResolver();

        assertTrue(resolver.retainPreparedAlias("profile-a", planned));
        assertTrue(resolver.retainPreparedAlias("profile-a", planned));
        assertFalse(resolver.retainPreparedAlias("profile-b", planned));
        assertFalse(resolver.releasePreparedAlias("profile-b", planned));

        assertTrue(resolver.releasePreparedAlias("profile-a", planned));
        assertEquals("profile-a", resolver.resolveProfileId(planned).orElseThrow());
        assertTrue(resolver.releasePreparedAlias("profile-a", planned));
        assertTrue(resolver.resolveProfileId(planned).isEmpty());
        assertTrue(resolver.releasePreparedAlias("profile-a", planned));
    }

    @Test
    void invalidReplacementSnapshotDoesNotPartiallyReplaceAliases() {
        UUID existing = UUID.randomUUID();
        UUID duplicate = UUID.randomUUID();
        CompanionIdentityResolver resolver = new CompanionIdentityResolver();
        resolver.replaceDurableAliases(List.of(
                new CompanionIdentityRepository.AliasRecord(existing, "profile-a", true, 1L)
        ));

        assertThrows(IllegalArgumentException.class, () -> resolver.replaceDurableAliases(List.of(
                new CompanionIdentityRepository.AliasRecord(duplicate, "profile-b", true, 1L),
                new CompanionIdentityRepository.AliasRecord(duplicate, "profile-c", true, 2L)
        )));

        assertEquals("profile-a", resolver.resolveProfileId(existing).orElseThrow());
        assertEquals(1, resolver.aliasCount());
    }

    @Test
    void durableReloadConflictWithPreparedAliasIsAtomic() {
        UUID existing = UUID.randomUUID();
        UUID planned = UUID.randomUUID();
        CompanionIdentityResolver resolver = new CompanionIdentityResolver();
        resolver.replaceDurableAliases(List.of(
                new CompanionIdentityRepository.AliasRecord(existing, "profile-a", true, 1L)
        ));
        assertTrue(resolver.retainPreparedAlias("profile-b", planned));

        assertThrows(IllegalStateException.class, () -> resolver.replaceDurableAliases(List.of(
                new CompanionIdentityRepository.AliasRecord(planned, "profile-c", true, 2L)
        )));

        assertEquals("profile-a", resolver.resolveProfileId(existing).orElseThrow());
        assertEquals("profile-b", resolver.resolveProfileId(planned).orElseThrow());
        assertEquals(existing, resolver.currentNpcUuid("profile-a").orElseThrow());
    }
}
