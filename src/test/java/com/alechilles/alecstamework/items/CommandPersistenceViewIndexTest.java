package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.flow.CompanionTransitions;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.live.CompanionSummaries;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.LinkedNpcTraitIndicator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandPersistenceViewIndexTest {
    private final CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (b, a) -> { });
    private final CommandPersistenceView view =
            new CommandPersistenceView(new CompanionQueries(index, new LoadedBodies<>()));

    private CompanionRecord live(UUID npc, List<String> tools) {
        return live(npc, tools, CompanionSummary.EMPTY);
    }

    private CompanionRecord live(UUID npc, List<String> tools, CompanionSummary summary) {
        CompanionRecord record = CompanionTransitions.newLive(UUID.randomUUID(), 0, new CompanionTransitions.BodyFacts(
                npc, UUID.randomUUID(), "Alec", "Tamed_Chicken", "Chicken", "default", 0, 0, 0, tools,
                summary));
        index.insert(record);
        return index.get(record.profileId());
    }

    private static LinkedNpcRecord record(UUID npcUuid, String profileId) {
        return new LinkedNpcRecord(npcUuid, profileId, null, null, null, null, null, "Tamed_Chicken", "Follow",
                true, false, null);
    }

    /** Plan 7 R19: a link saved by 3.x/4.x may name a body the companion has since left. */
    @Test
    void aUuidOnlyLinkNamingARetiredAliasResolvesToItsProfile() {
        UUID retired = UUID.randomUUID();
        CompanionRecord live = live(UUID.randomUUID(), List.of());
        CommandPersistenceView imported = new CommandPersistenceView(new CompanionQueries(index, new LoadedBodies<>()),
                Map.of(retired, live.profileId())::get);

        assertEquals(live.profileId(), imported.find(record(retired, null)).orElseThrow().profileId().value());
        assertTrue(view.find(record(retired, null)).isEmpty(), "unknown without the import's alias file");
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
    void anUnloadedCompanionsSavedPanelComesFromItsSummary() {
        UUID npc = UUID.randomUUID();
        // Breeding toggled off before its first breeding: present, disabled, no cooldown.
        live(npc, List.of(), CompanionSummaries.build(new CompanionSummaries.Inputs(null, null, "Tamed_Chicken",
                null, 12f, 20f, null, 0.0, null, 0.0, 0.0, true, false, 0L, 0L, 0L, 0L, null, 0, 0.0, 0.0, 0,
                Map.of(), 0L, 0L, null, null, null), 1_000L));

        CommandSavedNpcPanelSnapshot saved = view.savedPanel(record(npc, null), UUID.randomUUID());

        assertNotNull(saved);
        LinkedNpcEntry panel = saved.apply(new LinkedNpcEntry(npc, "Chicken", 1, 1, 0, 0, null, 0, 0, 0, 0,
                false, false, false, false, false, false, 0L, new LinkedNpcTraitIndicator[0]), null, 1.0);
        assertEquals(12, panel.currentHealth());
        assertEquals(20, panel.maxHealth());
        assertFalse(panel.breedingEnabled());
        assertTrue(panel.breedingAvailable());
        assertTrue(panel.breedingCooldownKnown());
        assertFalse(panel.breedingCooldownActive());
    }

    @Test
    void aCompanionLinkedOnlyByItemMetadataStaysInThatItemsPanel() {
        // Generic items keep their selection in item metadata only; the body and record carry no tool id.
        UUID npc = UUID.randomUUID();
        CompanionRecord companion = live(npc, List.of());
        LinkedNpcRecord linked = record(npc, companion.profileId().toString());

        assertEquals(List.of(linked), view.linkedRecordsForTool(List.of(linked), UUID.randomUUID().toString()));
    }
}
