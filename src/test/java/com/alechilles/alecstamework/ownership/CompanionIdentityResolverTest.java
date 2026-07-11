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
}
