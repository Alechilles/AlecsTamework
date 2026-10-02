package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.flow.CompanionTransitions;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRecallRoutingTest {
    private static CompanionRecord liveIn(String world) {
        return CompanionTransitions.newLive(UUID.randomUUID(), 0, new CompanionTransitions.BodyFacts(UUID.randomUUID(),
                UUID.randomUUID(), "Alec", "Tamed_Sheep", null, world, 12, 64, -7, List.of(), CompanionSummary.EMPTY));
    }

    @Test
    void eachCompanionStateGetsItsRecallRoute() {
        CompanionRecord here = liveIn("default");
        CompanionRecord dead = CompanionTransitions.died(here, CompanionSummary.EMPTY, 1L, 2L, "PLAYER", null)
                .apply(here.toBuilder()).build();

        assertEquals(RecallRoute.REFUSE, RecallRoute.decide(null, false, "default"));
        assertEquals(RecallRoute.MOVE_LOADED, RecallRoute.decide(here, true, "default"));
        assertEquals(RecallRoute.LOAD_AND_MOVE, RecallRoute.decide(here, false, "default"));
        assertEquals(RecallRoute.RESTORE, RecallRoute.decide(liveIn("other"), false, "default"));
        assertEquals(RecallRoute.REFUSE, RecallRoute.decide(dead, false, "default"));
    }

    /**
     * An import with no checkpoint is LIVE at generation 0 and 0,0,0 in a world that may be a
     * guess. A recall must neither restore it (a fresh animal would replace the real one) nor
     * queue a relocation for it (there is no position to load, and the relocation's timeout would
     * end in the same restore).
     */
    @Test
    void anImportThatWasNeverSeenIsNotRecalledUntilItsBodyLoads() {
        CompanionRecord elsewhere = liveIn("other").toBuilder()
                .location(CompanionLocation.live("other", 0.0, 0.0, 0.0)).build();
        CompanionRecord sameWorld = liveIn("default").toBuilder()
                .location(CompanionLocation.live("default", 0.0, 0.0, 0.0)).build();

        assertEquals(RecallRoute.UNSEEN_IMPORT, RecallRoute.decide(elsewhere, false, "default"));
        assertEquals(RecallRoute.UNSEEN_IMPORT, RecallRoute.decide(sameWorld, false, "default"));
        // Its body is loaded next to the player: it is simply moved.
        assertEquals(RecallRoute.MOVE_LOADED, RecallRoute.decide(sameWorld, true, "default"));
        // Restored once by 5.0 (generation above 0): its snapshot is its own again.
        assertEquals(RecallRoute.RESTORE,
                RecallRoute.decide(elsewhere.toBuilder().generation(1L).build(), false, "default"));
    }

    /**
     * While the saved-chunk pass is looking for a never-seen import, Recover is refused and both
     * refusals say it is still being located. Once it is located, or when no pass runs, Recover
     * is open again.
     */
    @Test
    void recoverWaitsWhileANeverSeenImportIsStillBeingLocated() {
        CompanionRecord unseen = liveIn("default").toBuilder()
                .location(CompanionLocation.live("default", 0.0, 0.0, 0.0)).build();

        assertTrue(CommandCompanionRestorationService.stillLocating(unseen, false, true));
        assertFalse(CommandCompanionRestorationService.stillLocating(unseen, false, false));
        assertFalse(CommandCompanionRestorationService.stillLocating(unseen, true, true));
        assertFalse(CommandCompanionRestorationService.stillLocating(liveIn("default"), false, true));
        assertEquals(CommandRelocationDispatchService.KEY_STILL_LOCATING,
                CommandRelocationDispatchService.unseenImportKey(true));
        assertEquals(CommandRelocationDispatchService.KEY_STILL_LOCATING,
                CommandFeedbackService.restorationRequestFeedbackKey(
                        CommandCompanionRestorationService.RequestStatus.STILL_LOCATING));
        assertEquals(CommandRelocationDispatchService.KEY_UNSEEN_IMPORT,
                CommandRelocationDispatchService.unseenImportKey(false));
    }
}
