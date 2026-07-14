package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyReadiness;
import com.alechilles.alecstamework.ownership.CompanionIdentityResolver;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerPopulationEntry;
import com.alechilles.alecstamework.ownership.OwnerPopulationIndex;
import com.alechilles.alecstamework.ownership.OwnerPopulationReadiness;
import com.alechilles.alecstamework.persistence.sqlite.CompanionIdentityRepository;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for filtering stale housed rows before release journaling. */
class ManagedCoopReleaseEligibilityTest {
    private static final String PROFILE = "profile-a";
    private static final UUID NPC = new UUID(1L, 2L);
    private static final UUID OWNER = new UUID(3L, 4L);

    @Test
    void exactCanonicalCoopStateIsEligible() {
        assertTrue(eligibility(
                CompanionLifecycleState.COOP,
                CompanionLifecycleState.COOP,
                7L,
                7L,
                OWNER,
                NPC).permitsRelease(resident()));
    }

    @Test
    void nonCoopRevisionAndIdentityContradictionsAreRejected() {
        assertFalse(eligibility(
                CompanionLifecycleState.ACTIVE,
                CompanionLifecycleState.COOP,
                7L,
                7L,
                OWNER,
                NPC).permitsRelease(resident()));
        assertFalse(eligibility(
                CompanionLifecycleState.COOP,
                CompanionLifecycleState.ACTIVE,
                7L,
                7L,
                OWNER,
                NPC).permitsRelease(resident()));
        assertFalse(eligibility(
                CompanionLifecycleState.COOP,
                CompanionLifecycleState.COOP,
                7L,
                8L,
                OWNER,
                NPC).permitsRelease(resident()));
        assertFalse(eligibility(
                CompanionLifecycleState.COOP,
                CompanionLifecycleState.COOP,
                7L,
                7L,
                UUID.randomUUID(),
                NPC).permitsRelease(resident()));
        assertFalse(eligibility(
                CompanionLifecycleState.COOP,
                CompanionLifecycleState.COOP,
                7L,
                7L,
                OWNER,
                UUID.randomUUID()).permitsRelease(resident()));
    }

    private static ManagedCoopReleaseEligibility eligibility(
            CompanionLifecycleState ownerState,
            CompanionLifecycleState claimState,
            long ownerRevision,
            long claimRevision,
            UUID claimOwner,
            UUID currentUuid) {
        OwnerPopulationIndex owners = new OwnerPopulationIndex();
        owners.replaceCommittedEntries(List.of(new OwnerPopulationEntry(
                PROFILE, OWNER, "world", ownerState, ownerRevision)),
                OwnerPopulationReadiness.READY);
        ClaimOccupancyIndex claims = new ClaimOccupancyIndex();
        claims.replaceCommittedEntries(List.of(new ClaimOccupancyEntry(
                PROFILE, claimOwner, claimState, null, claimRevision)),
                ClaimOccupancyReadiness.READY);
        CompanionIdentityResolver identities = new CompanionIdentityResolver();
        List<CompanionIdentityRepository.AliasRecord> aliases = NPC.equals(currentUuid)
                ? List.of(new CompanionIdentityRepository.AliasRecord(
                        NPC, PROFILE, true, 1L))
                : List.of(
                        new CompanionIdentityRepository.AliasRecord(
                                currentUuid, PROFILE, true, 1L),
                        new CompanionIdentityRepository.AliasRecord(
                                NPC, PROFILE, false, 1L));
        identities.replaceDurableAliases(aliases);
        return new ManagedCoopReleaseEligibility(owners, claims, identities);
    }

    private static ResidentRecord resident() {
        return new ResidentRecord(
                "resident-a",
                new ManagedCoopAuthorityKey("world", 1, 2, 3),
                "coop_chicken",
                0,
                PROFILE,
                "mob_chicken",
                NPC,
                NPC,
                null,
                "{}",
                "hash",
                1,
                ResidentState.HOUSED,
                1L,
                true,
                1L,
                0L,
                1L,
                1L);
    }
}
