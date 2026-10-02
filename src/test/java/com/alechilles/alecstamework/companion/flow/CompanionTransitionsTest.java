package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.ExtensionEntry;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.live.CompanionFence;
import com.alechilles.alecstamework.companion.live.FenceAction;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionTransitionsTest {
    private static final UUID PROFILE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NPC = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private static CompanionTransitions.BodyFacts body(String world, double x) {
        return new CompanionTransitions.BodyFacts(NPC, OWNER, "Alec", "Tamed_Sheep", "Wooly", world, x, 64, 5,
                List.of("tool-1"), CompanionSummary.EMPTY);
    }

    private static CompanionRecord apply(CompanionRecord before,
                                         java.util.function.UnaryOperator<CompanionRecord.Builder> change) {
        return change.apply(before.toBuilder()).build();
    }

    @Test
    void aNewLiveRecordCarriesTheBody() {
        CompanionRecord record = CompanionTransitions.newLive(PROFILE, 0, body("default", 10));

        assertEquals(LocationKind.LIVE, record.location().kind());
        assertEquals("default", record.location().world());
        assertEquals(NPC, record.currentNpcUuid());
        assertEquals(OWNER, record.ownerUuid());
        assertEquals("default", record.homeWorld());
        assertEquals(List.of("tool-1"), record.toolIds());
    }

    @Test
    void refreshIsNeededOnlyWhenTheBodyMovedOrChanged() {
        CompanionRecord record = CompanionTransitions.newLive(PROFILE, 0, body("default", 10));

        assertFalse(CompanionTransitions.needsRefresh(record, body("default", 10.5)));
        assertTrue(CompanionTransitions.needsRefresh(record, body("default", 40)));
        assertTrue(CompanionTransitions.needsRefresh(record, body("other", 10)));
    }

    /** A provisioned companion is named in its record only; its body has no name component. */
    @Test
    void aBodyWithoutANameKeepsTheStoredNameAndARenameReplacesIt() {
        CompanionRecord record = CompanionTransitions.newLive(PROFILE, 0, body("default", 10));
        CompanionTransitions.BodyFacts unnamed = new CompanionTransitions.BodyFacts(NPC, OWNER, "Alec",
                "Tamed_Sheep", null, "default", 10, 64, 5, List.of("tool-1"), CompanionSummary.EMPTY);
        CompanionTransitions.BodyFacts renamed = new CompanionTransitions.BodyFacts(NPC, OWNER, "Alec",
                "Tamed_Sheep", "Fluffy", "default", 10, 64, 5, List.of("tool-1"), CompanionSummary.EMPTY);

        assertFalse(CompanionTransitions.needsRefresh(record, unnamed));
        assertEquals("Wooly", apply(record, CompanionTransitions.seenAt(unnamed)).displayName());
        assertTrue(CompanionTransitions.needsRefresh(record, renamed));
        assertEquals("Fluffy", apply(record, CompanionTransitions.seenAt(renamed)).displayName());
    }

    @Test
    void deathRaisesTheGenerationSoTheDyingBodyIsFencedWhenItReloads() {
        CompanionRecord live = CompanionTransitions.newLive(PROFILE, 3, body("default", 10));

        CompanionRecord dead = apply(live, CompanionTransitions.died(live, CompanionSummary.EMPTY,
                1_000L, 61_000L, "PLAYER", 1_000L));

        assertEquals(LocationKind.DEAD, dead.location().kind());
        assertEquals(4, dead.generation());
        assertEquals(61_000L, dead.reviveAvailableAtMs());
        assertNull(dead.currentNpcUuid());
        assertEquals(FenceAction.REMOVE, CompanionFence.decide(dead, false, 3, true, false));
    }

    @Test
    void aLossWithoutANewSnapshotKeepsTheOldSnapshotTime() {
        CompanionRecord live = CompanionTransitions.newLive(PROFILE, 0, body("default", 10)).toBuilder()
                .lastSnapshotAtMs(500L).build();

        CompanionRecord lost = apply(live, CompanionTransitions.lost(live, null, CompanionTransitions.CAUSE_REMOVED, null));

        assertEquals(LocationKind.LOST, lost.location().kind());
        assertEquals(1, lost.generation());
        assertEquals(500L, lost.lastSnapshotAtMs());
    }

    @Test
    void releaseLeavesATombstoneWithoutExtensionsOrLinks() {
        CompanionRecord live = CompanionTransitions.newLive(PROFILE, 0, body("default", 10)).toBuilder()
                .extension("hydragon/state", new ExtensionEntry(2, "{}")).build();

        CompanionRecord released = apply(live, CompanionTransitions.released(live));

        assertEquals(LocationKind.RELEASED, released.location().kind());
        assertEquals(1, released.generation());
        assertTrue(released.extensions().isEmpty());
        assertTrue(released.toolIds().isEmpty());
        assertEquals(FenceAction.REMOVE, CompanionFence.decide(released, false, 0, true, false));
    }

    @Test
    void captureRaisesTheGenerationAndLeavesNoBodyOrOwnerWhenTheOwnerIsCleared() {
        CompanionRecord live = CompanionTransitions.newLive(PROFILE, 4, body("default", 10));

        CompanionRecord item = apply(live, CompanionTransitions.capturedToItem(live, CompanionSummary.EMPTY, null, null));

        assertEquals(LocationKind.ITEM, item.location().kind());
        assertEquals(5, item.generation());
        assertNull(item.currentNpcUuid());
        assertNull(item.ownerUuid());
    }

    @Test
    void coopIntakeRaisesTheGenerationAndRecordsTheBlockAndSlotWithoutABody() {
        CompanionRecord live = CompanionTransitions.newLive(PROFILE, 4, body("default", 10));

        CompanionRecord cooped = apply(live, CompanionTransitions.coopIntake(live, "default", 7, 64, -3, 2, null));

        assertEquals(LocationKind.COOP, cooped.location().kind());
        assertEquals(5, cooped.generation());
        assertEquals(7, cooped.location().x());
        assertEquals(-3, cooped.location().z());
        assertEquals(2, cooped.location().slot());
        assertNull(cooped.currentNpcUuid());
        assertEquals(FenceAction.REMOVE, CompanionFence.decide(cooped, false, 4, true, false));
    }

    @Test
    void storingRaisesTheGenerationAndLeavesNoBody() {
        CompanionRecord live = CompanionTransitions.newLive(PROFILE, 4, body("default", 10));

        CompanionRecord stored = apply(live, CompanionTransitions.stored(live,
                com.alechilles.alecstamework.companion.index.StoredReason.TIMED, null, null, 12_000L));

        assertEquals(LocationKind.STORED, stored.location().kind());
        assertEquals(5, stored.generation());
        assertNull(stored.currentNpcUuid());
    }

    @Test
    void aReleaseKeepsItsCauseAndClearsTheSummonTimer() {
        CompanionRecord live = CompanionTransitions.newLive(PROFILE, 0, body("default", 10)).toBuilder()
                .summonedUntilMs(9_000L).build();

        CompanionRecord released = apply(live,
                CompanionTransitions.released(live, CompanionTransitions.CAUSE_ITEM_DESTROYED));

        assertEquals(CompanionTransitions.CAUSE_ITEM_DESTROYED, released.location().cause());
        assertEquals(0L, released.summonedUntilMs());
    }

    @Test
    void aRestoreMakesTheRecordLiveOneGenerationNewerWithoutReviveTimers() {
        CompanionRecord live = CompanionTransitions.newLive(PROFILE, 3, body("default", 10));
        CompanionRecord dead = apply(live, CompanionTransitions.died(live, CompanionSummary.EMPTY,
                1_000L, 61_000L, "PLAYER", 1_000L));
        UUID newNpc = UUID.fromString("00000000-0000-0000-0000-000000000004");

        CompanionRecord restored = apply(dead, CompanionTransitions.restored(dead, "other", 1, 2, 3, newNpc));

        assertEquals(LocationKind.LIVE, restored.location().kind());
        assertEquals("other", restored.location().world());
        assertEquals(dead.generation() + 1, restored.generation());
        assertEquals(newNpc, restored.currentNpcUuid());
        assertEquals(0L, restored.reviveAvailableAtMs());
        assertEquals(0L, restored.diedAtMs());
    }

    @Test
    void aRestoreClearsTheSummonTimer() {
        CompanionRecord timed = CompanionTransitions.newLive(PROFILE, 0, body("default", 10)).toBuilder()
                .summonedUntilMs(9_000L).build();

        assertEquals(0L, apply(timed, CompanionTransitions.restored(timed, "w", 0, 0, 0, UUID.randomUUID())).summonedUntilMs());
    }

    @Test
    void anUnloadSnapshotIsDueAfterFiveMinutesOrWhenNoneExists() {
        CompanionRecord record = CompanionTransitions.newLive(PROFILE, 0, body("default", 10));

        assertTrue(CompanionTransitions.snapshotDue(record, 1_000L));
        CompanionRecord recent = record.toBuilder().lastSnapshotAtMs(1_000L).build();
        assertFalse(CompanionTransitions.snapshotDue(recent, 1_000L + 299_999L));
        assertTrue(CompanionTransitions.snapshotDue(recent, 1_000L + 300_000L));
    }
}
