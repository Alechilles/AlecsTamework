package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.*;
import com.alechilles.alecstamework.companion.lifecycle.*;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandOwnedActionServiceTest {
    /** Unlinked saved animals can act, but a stale card cannot authorize a new owner's animal. */
    @Test void authorizesUnlinkedDormantProfilesAndRejectsTransferredOrReleasedProfiles() {
        UUID owner = UUID.randomUUID();
        for (var state : new LifecycleState[]{LifecycleState.UNLOADED,
                LifecycleState.DEAD_REVIVABLE, LifecycleState.LOST}) {
            var profile = profile(owner, state);
            assertTrue(CommandOwnedActionService.allows(owner, profile, java.util.Set.of(), java.util.Set.of()));
            assertFalse(CommandOwnedActionService.allows(UUID.randomUUID(), profile, java.util.Set.of(), java.util.Set.of()));
        }
        assertFalse(CommandOwnedActionService.allows(owner, profile(owner, LifecycleState.RELEASED), java.util.Set.of(), java.util.Set.of()));
        assertFalse(CommandOwnedActionService.allows(owner, null, java.util.Set.of(), java.util.Set.of()));
    }

    /** Requests retain the durable profile while targeting its current alias, without creating a link. */
    @Test void laggingManagedRosterCannotFallThroughToGenericActions() {
        UUID owner = UUID.randomUUID();
        var profile = profile(owner, LifecycleState.UNLOADED);
        assertFalse(CommandOwnedActionService.allows(owner, profile, java.util.Set.of(),
                java.util.Set.of(profile.identity().profileId())));
        assertFalse(CommandOwnedActionService.allows(owner, profile,
                java.util.Set.of(profile.identity().profileId()), java.util.Set.of()));
    }

    @Test void actionRecordUsesCurrentAliasInsteadOfStaleCardId() {
        var profile = profile(UUID.randomUUID(), LifecycleState.UNLOADED);
        var record = CommandOwnedActionService.record(profile, UUID.randomUUID());
        assertEquals(profile.currentAlias().alias().value(), record.npcUuid);
        assertEquals(profile.identity().profileId().toString(), record.profileId);
    }

    private static CompanionProfileReadModel profile(UUID owner, LifecycleState state) {
        var id = new ProfileId(UUID.randomUUID());
        return new CompanionProfileReadModel(
                new CompanionIdentity(id, "Sheep", "Sheep", null, null, "OtherWorld", -3, -2, -1, 0),
                new CompanionAlias(new NpcAlias(UUID.randomUUID()), id, 0, CompanionAlias.State.CURRENT,
                        null, -1, null),
                new CompanionLifecycle(id, new OwnerId(owner), state, LifecycleLocation.none(),
                        new LifecycleRevision(0), null, -1, ReconciliationGeneration.INITIAL, null, null),
                List.of(), List.of(), null);
    }
}

