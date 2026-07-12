package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.ownership.CompanionIdentityResolver;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerPopulationEntry;
import com.alechilles.alecstamework.ownership.OwnerPopulationIndex;
import com.alechilles.alecstamework.ownership.OwnerPopulationReadiness;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyReadiness;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards `/tw npcclean` against recreating ghost owner slots through raw entity removal. */
class NpcCleanOwnershipGuardTest {
    @Test
    void liveOwnerComponentAlwaysProtectsCompanion() {
        Fixture fixture = new Fixture();

        assertTrue(fixture.guard.isProtectedOwnedCompanion(
                UUID.randomUUID(),
                new TameworkOwnerComponent(UUID.randomUUID(), "Owner"),
                null,
                null
        ));
    }

    @Test
    void staleLinkedOrNameOwnedCompanionIsProtectedForReconciliation() {
        Fixture fixture = new Fixture();
        UUID ownerId = UUID.randomUUID();
        TameworkCommandLinksComponent links =
                new TameworkCommandLinksComponent(ownerId, new String[]{"CommandTool"});
        TameworkNpcNameComponent name = new TameworkNpcNameComponent(
                "Companion", ownerId, 1L, TameworkNpcNameComponent.NameSource.Player
        );

        assertTrue(fixture.guard.isProtectedOwnedCompanion(UUID.randomUUID(), null, links, null));
        assertTrue(fixture.guard.isProtectedOwnedCompanion(UUID.randomUUID(), null, null, name));
    }

    @Test
    void canonicalOwnerEntryProtectsCompanionWhenLiveComponentIsMissing() {
        Fixture fixture = new Fixture();
        UUID npcUuid = UUID.randomUUID();
        String profileId = fixture.identityResolver.resolveOrAllocate(
                npcUuid,
                "npcclean-owned-fixture"
        ).profileId();
        fixture.populationIndex.reconcileCommittedEntry(new OwnerPopulationEntry(
                profileId,
                UUID.randomUUID(),
                "fixture-world",
                CompanionLifecycleState.ACTIVE,
                1L
        ));

        assertTrue(fixture.guard.isProtectedOwnedCompanion(npcUuid, null, null, null));
    }

    @Test
    void unresolvedCanonicalAliasFailsClosed() {
        Fixture fixture = new Fixture();
        UUID npcUuid = UUID.randomUUID();
        fixture.identityResolver.resolveOrAllocate(npcUuid, "npcclean-provisional-fixture");

        assertTrue(fixture.guard.isProtectedOwnedCompanion(npcUuid, null, null, null));
    }

    @Test
    void missingLiveIdentityFailsClosed() {
        Fixture fixture = new Fixture();

        assertTrue(fixture.guard.isProtectedOwnedCompanion(null, null, null, null));
    }

    @Test
    void canonicalReleasedNpcAndOrdinaryUnownedNpcRemainCleanable() {
        Fixture fixture = new Fixture();
        UUID releasedNpcUuid = UUID.randomUUID();
        String profileId = fixture.identityResolver.resolveOrAllocate(
                releasedNpcUuid,
                "npcclean-released-fixture"
        ).profileId();
        fixture.populationIndex.reconcileCommittedEntry(new OwnerPopulationEntry(
                profileId,
                null,
                "fixture-world",
                CompanionLifecycleState.RELEASED,
                1L
        ));

        assertFalse(fixture.guard.isProtectedOwnedCompanion(releasedNpcUuid, null, null, null));
        assertFalse(fixture.guard.isProtectedOwnedCompanion(UUID.randomUUID(), null, null, null));
    }

    @Test
    void uncertainOwnerOrClaimReadinessProtectsEveryNpc() {
        Fixture fixture = new Fixture();
        fixture.populationIndex.setReadiness(OwnerPopulationReadiness.RECONCILING);
        assertFalse(fixture.guard.readyForDestructiveCleanup());
        assertTrue(fixture.guard.isProtectedOwnedCompanion(UUID.randomUUID(), null, null, null));

        fixture.populationIndex.setReadiness(OwnerPopulationReadiness.READY);
        fixture.claimIndex.setReadiness(ClaimOccupancyReadiness.DEGRADED);
        assertFalse(fixture.guard.readyForDestructiveCleanup());
        assertTrue(fixture.guard.isProtectedOwnedCompanion(UUID.randomUUID(), null, null, null));
    }

    private static final class Fixture {
        private final CompanionIdentityResolver identityResolver = new CompanionIdentityResolver();
        private final OwnerPopulationIndex populationIndex = new OwnerPopulationIndex();
        private final ClaimOccupancyIndex claimIndex = new ClaimOccupancyIndex();
        private final NpcCleanOwnershipGuard guard =
                new NpcCleanOwnershipGuard(identityResolver, populationIndex, claimIndex);

        private Fixture() {
            populationIndex.setReadiness(OwnerPopulationReadiness.READY);
            claimIndex.setReadiness(ClaimOccupancyReadiness.READY);
        }
    }
}
