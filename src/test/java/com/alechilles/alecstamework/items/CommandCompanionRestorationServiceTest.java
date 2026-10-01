package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.items.CommandCompanionRestorationService.Decision;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandCompanionRestorationServiceTest {
    private static final UUID OWNER = UUID.randomUUID();

    /** Revive serves only the dead; Recover serves the lost and a live record whose body is gone. */
    @Test
    void thePanelButtonPicksTheRestoreItsCompanionNeeds() {
        assertEquals(Decision.REVIVE, decide(LifecycleState.DEAD_REVIVABLE, false, true));
        assertEquals(Decision.REVIVE_DISABLED, decide(LifecycleState.DEAD_REVIVABLE, false, false));
        assertEquals(Decision.RECOVER, decide(LifecycleState.LOST, false, false));
        assertEquals(Decision.RECOVER, decide(LifecycleState.ACTIVE, false, true));
        assertEquals(Decision.NOT_DORMANT, decide(LifecycleState.ACTIVE, true, true));
        assertEquals(Decision.RECOVER, decide(LifecycleState.CAPTURED, false, true));
        assertEquals(Decision.UNAVAILABLE, decide(LifecycleState.RELEASED, false, true));
    }

    @Test
    void anotherPlayersCompanionIsNotRestored() {
        assertEquals(Decision.UNAVAILABLE, CommandCompanionRestorationService.decide(
                profile(LifecycleState.DEAD_REVIVABLE), UUID.randomUUID(), false, true));
    }

    private static Decision decide(LifecycleState state, boolean bodyLoaded, boolean reviveEnabled) {
        return CommandCompanionRestorationService.decide(profile(state), OWNER, bodyLoaded, reviveEnabled);
    }

    private static CommandPersistenceView.ProfileSnapshot profile(LifecycleState state) {
        return new CommandPersistenceView.ProfileSnapshot(new ProfileId(UUID.randomUUID()), UUID.randomUUID(),
                OWNER, "Tamed_Sheep", "Alec", null, Set.of(), state, 0L, 0L);
    }
}
