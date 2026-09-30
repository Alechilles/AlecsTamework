package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.flow.CompanionTransitions;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandPersistenceViewIndexTest {
    private final CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (b, a) -> { });
    private final CommandPersistenceView view =
            new CommandPersistenceView(new CompanionQueries(index, new LoadedBodies<>()));

    private CompanionRecord live(UUID npc, List<String> tools) {
        CompanionRecord record = CompanionTransitions.newLive(UUID.randomUUID(), 0, new CompanionTransitions.BodyFacts(
                npc, UUID.randomUUID(), "Alec", "Tamed_Chicken", "Chicken", "default", 0, 0, 0, tools,
                CompanionSummary.EMPTY));
        index.insert(record);
        return index.get(record.profileId());
    }

    private static LinkedNpcRecord record(UUID npcUuid, String profileId) {
        return new LinkedNpcRecord(npcUuid, profileId, null, null, null, null, null, "Tamed_Chicken", "Follow",
                true, false, null);
    }

    @Test
    void aLiveRecordIsActiveAndBlocksNothing() {
        UUID npc = UUID.randomUUID();
        live(npc, List.of());

        CommandPersistenceView.ProfileSnapshot snapshot = view.find(record(npc, null)).orElseThrow();

        assertEquals(LifecycleState.ACTIVE, snapshot.lifecycleState());
        assertFalse(snapshot.blocksLiveAction());
    }

    @Test
    void aDeadRecordIsRestorableWithItsReviveTime() {
        CompanionRecord live = live(UUID.randomUUID(), List.of());
        index.update(live.profileId(), live.revision(),
                CompanionTransitions.died(live, CompanionSummary.EMPTY, 1_000L, 61_000L, "PLAYER", null));

        CommandPersistenceView.ProfileSnapshot snapshot =
                view.find(record(UUID.randomUUID(), live.profileId().toString())).orElseThrow();

        assertTrue(snapshot.dead());
        assertTrue(snapshot.restorable());
        assertEquals(61_000L, snapshot.restorationAvailableAtMs());
    }

    @Test
    void aReleasedRecordIsReportedAsReleased() {
        CompanionRecord live = live(UUID.randomUUID(), List.of());
        index.update(live.profileId(), live.revision(), CompanionTransitions.released(live));

        CommandPersistenceView.ProfileSnapshot snapshot =
                view.find(record(UUID.randomUUID(), live.profileId().toString())).orElseThrow();

        assertEquals(LifecycleState.RELEASED, snapshot.lifecycleState());
    }

    @Test
    void linkedRecordsForToolKeepsOnlyRecordsThatListTheTool() {
        UUID tool = UUID.randomUUID();
        UUID linkedNpc = UUID.randomUUID();
        UUID otherNpc = UUID.randomUUID();
        live(linkedNpc, List.of(tool.toString()));
        live(otherNpc, List.of());
        LinkedNpcRecord linked = record(linkedNpc, null);
        LinkedNpcRecord other = record(otherNpc, null);
        LinkedNpcRecord unknown = record(UUID.randomUUID(), null);

        assertEquals(List.of(linked, unknown),
                view.linkedRecordsForTool(List.of(linked, other, unknown), tool.toString()));
    }
}
