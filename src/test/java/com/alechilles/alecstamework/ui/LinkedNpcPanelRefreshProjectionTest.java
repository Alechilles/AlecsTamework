package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.api.BondedCompanionActionBlockReason;
import com.alechilles.alecstamework.api.CommandTimedSummoningState;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LinkedNpcPanelRefreshProjectionTest {
    @Test
    void falsePermitRetainsXpButKeepsCurrentHealthAndLifecycle() {
        CommandPanelFeaturePresentation projected = BondedCompanionProgressionProjection.project(
                row("40", "100", true), row("45", "50", false), false);
        assertEquals("40", projected.bonded().attributes().get("currentXp"));
        assertEquals("50", projected.bonded().attributes().get("currentHealth"));
        assertEquals(false, projected.bonded().status().actionEnabled());
    }

    @Test
    void countdownsUseBondedStatusCooldown() {
        assertEquals(700L, LinkedNpcPanelCountdowns.shortest(Map.of(UUID.randomUUID(),
                bondedWithStatusCooldown(700L))));
    }

    @Test
    void countdownsUseBondedRevivalCooldown() {
        assertEquals(2_000L, LinkedNpcPanelCountdowns.shortest(Map.of(UUID.randomUUID(),
                bondedWithRevivalCooldown(2_000L))));
    }

    @Test
    void countdownsUseGenericRosterRemainingMs() {
        assertEquals(900L, LinkedNpcPanelCountdowns.shortest(Map.of(UUID.randomUUID(),
                genericRoster(900L, 0L))));
    }

    @Test
    void countdownsUseGenericRosterCooldown() {
        assertEquals(1_000L, LinkedNpcPanelCountdowns.shortest(Map.of(UUID.randomUUID(),
                genericRoster(null, 1_000L))));
    }

    @Test
    void visibleRosterZeroPreservesTheExpirationWake() {
        assertEquals(0L,
                LinkedNpcPanelCountdowns.shortest(Map.of(UUID.randomUUID(),
                        genericRoster(0L, 0L))));
    }

    @Test
    void countdownsUseLegacyRecallRemainingMs() {
        assertEquals(3_500L, LinkedNpcPanelCountdowns.shortest(
                Map.of(), new LinkedNpcEntry[] {legacyEntry(
                        false, -1L, false, 0L, false, 0L, true, 3_500L
                )}
        ));
    }

    @Test
    void countdownsUseLegacyRespawnRemainingMs() {
        assertEquals(8_000L, LinkedNpcPanelCountdowns.shortest(
                Map.of(), new LinkedNpcEntry[] {legacyEntry(
                        true, 8_000L, false, 0L, false, 0L, false, 0L
                )}
        ));
    }

    @Test
    void countdownsUseLegacyBreedingAndHarvestCooldowns() {
        assertEquals(4_000L, LinkedNpcPanelCountdowns.shortest(
                Map.of(), new LinkedNpcEntry[] {legacyEntry(
                        false, -1L, true, 9_000L, true, 4_000L, false, 0L
                )}
        ));
    }

    @Test
    void projectionPreservesCurrentNonProgressionStateAndClassifiesDynamicAndFullChanges() {
        UUID id = UUID.randomUUID();
        LinkedNpcEntry[] entries = {entry(id)};
        LinkedNpcPanelCardRenderState state = new LinkedNpcPanelCardRenderState();
        CommandPanelFeaturePresentation old = row("40", "100", true);
        state.markRendered(entries, null, Map.of(id, old));

        CommandPanelFeaturePresentation dynamic = BondedCompanionProgressionProjection.project(
                old, row("45", "50", true, 1_500L), false);
        assertEquals(LinkedNpcPanelCardRenderState.Update.DYNAMIC, state.updateAt(
                0, entries, null, Map.of(id, dynamic)));
        state.markRendered(entries, null, Map.of(id, dynamic));
        assertEquals("40", state.presentation(id).bonded().attributes().get("currentXp"));
        assertEquals("50", state.presentation(id).bonded().attributes().get("currentHealth"));
        assertEquals(1_500L, state.presentation(id).bonded().status().cooldownRemainingMs());

        CommandPanelFeaturePresentation full = BondedCompanionProgressionProjection.project(
                state.presentation(id), row("60", "45", false, 1_000L), false);
        assertEquals(LinkedNpcPanelCardRenderState.Update.FULL, state.updateAt(
                0, entries, null, Map.of(id, full)));
        assertEquals("40", full.bonded().attributes().get("currentXp"));

        CommandPanelFeaturePresentation eligible = BondedCompanionProgressionProjection.project(
                state.presentation(id), row("60", "45", false, 1_000L), true);
        assertEquals("60", eligible.bonded().attributes().get("currentXp"));
    }

    private static CommandPanelFeaturePresentation row(String xp, String health, boolean enabled) {
        return row(xp, health, enabled, 0L);
    }

    private static CommandPanelFeaturePresentation row(
            String xp, String health, boolean enabled, long cooldownRemainingMs
    ) {
        return CommandPanelFeaturePresentation.bonded(new BondedCompanionPanelPresentation(
                "p", "r", "role", 1, null, null, null, null,
                Map.of("currentXp", xp, "currentHealth", health), Map.of(),
                new BondedCompanionStatusPresentation(BondedCompanionStateView.ACTIVE,
                        BondedCompanionStatusPresentation.Action.DISMISS, enabled,
                        null, null, cooldownRemainingMs), null));
    }

    private static CommandPanelFeaturePresentation bondedWithStatusCooldown(long cooldownMs) {
        return CommandPanelFeaturePresentation.bonded(new BondedCompanionPanelPresentation(
                "p", "r", "role", 1L, null, null, null, null, Map.of(), Map.of(),
                new BondedCompanionStatusPresentation(BondedCompanionStateView.ACTIVE,
                        BondedCompanionStatusPresentation.Action.DISMISS, true,
                        BondedCompanionActionBlockReason.COOLDOWN_ACTIVE, null, cooldownMs), null));
    }

    private static CommandPanelFeaturePresentation bondedWithRevivalCooldown(long cooldownMs) {
        return CommandPanelFeaturePresentation.bonded(new BondedCompanionPanelPresentation(
                "p", "r", "role", 1L, null, null, null, null, Map.of(), Map.of(),
                new BondedCompanionStatusPresentation(BondedCompanionStateView.DEAD,
                        BondedCompanionStatusPresentation.Action.REVIVE, true,
                        null, null, 0L),
                new com.alechilles.alecstamework.api.BondedCompanionReviveQuote(
                        "p", true, java.util.List.of(), cooldownMs / 1_000L, 1L)));
    }

    private static CommandPanelFeaturePresentation genericRoster(
            Long remainingMs, long cooldownRemainingMs
    ) {
        return new CommandPanelFeaturePresentation(new CommandRosterStatusPresentation(
                "p", "family", CommandTimedSummoningState.ROSTER_STORED, 1L,
                remainingMs, 0L, false, cooldownRemainingMs, 0, 1, null, null), null);
    }

    private static LinkedNpcEntry entry(UUID id) {
        return new LinkedNpcEntry(id, "Wyatt", 100, 100, 0, 0, "", 0, 0,
                0, 0, true, false, false, false, false, false, 0L,
                LinkedNpcTraitIndicator.EMPTY);
    }

    private static LinkedNpcEntry legacyEntry(
            boolean dead,
            long respawnRemainingMs,
            boolean breedingCooldownActive,
            long breedingCooldownRemainingMs,
            boolean harvestCooldownActive,
            long harvestCooldownRemainingMs,
            boolean recallPending,
            long recallRemainingMs
    ) {
        return new LinkedNpcEntry(
                UUID.randomUUID(), "Wyatt", null,
                dead ? 0 : 100, 100, 50, 100, 50, "",
                50, 100, 50, 100,
                !recallPending, false, dead, false, false, false,
                respawnRemainingMs, null, null, null, LinkedNpcTraitIndicator.EMPTY,
                false, false, false, false, true, true,
                "species", "Species", null, null, null,
                true, true, breedingCooldownActive, breedingCooldownRemainingMs,
                0.5, true, harvestCooldownActive, harvestCooldownRemainingMs,
                0.5, true, recallPending, recallRemainingMs
        );
    }
}
