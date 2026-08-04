package com.alechilles.alecstamework.ui;

import java.util.Arrays;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Identifies normal linked-card changes that can update existing visuals
 * without replacing control bindings.
 */
final class LinkedNpcPanelCardDynamicState {
    private LinkedNpcPanelCardDynamicState() {
    }

    static boolean changedOnlyByLiveFields(
            @Nonnull LinkedNpcEntry previous,
            @Nonnull LinkedNpcEntry current
    ) {
        return !previous.equals(current)
                && Objects.equals(previous.npcUuid(), current.npcUuid())
                && Objects.equals(previous.displayName(), current.displayName())
                && Objects.equals(previous.gender(), current.gender())
                && previous.loaded() == current.loaded()
                && previous.linked() == current.linked()
                && previous.active() == current.active()
                && previous.dead() == current.dead()
                && previous.captured() == current.captured()
                && previous.inCoop() == current.inCoop()
                && previous.lost() == current.lost()
                && previous.hasHome() == current.hasHome()
                && sameRespawnActionVisibility(previous, current)
                && Objects.equals(previous.deathCauseHint(), current.deathCauseHint())
                && Objects.equals(previous.speciesId(), current.speciesId())
                && Objects.equals(previous.speciesLabel(), current.speciesLabel())
                && Objects.equals(previous.groupId(), current.groupId())
                && Objects.equals(previous.groupName(), current.groupName())
                && Objects.equals(previous.groupColorHex(), current.groupColorHex())
                && previous.breedingEnabled() == current.breedingEnabled()
                && previous.breedingAvailable() == current.breedingAvailable()
                && previous.breedingCooldownActive() == current.breedingCooldownActive()
                && previous.breedingCooldownKnown() == current.breedingCooldownKnown()
                && previous.harvestCooldownActive() == current.harvestCooldownActive()
                && previous.harvestCooldownKnown() == current.harvestCooldownKnown()
                && previous.recallPending() == current.recallPending()
                && previous.recoveryHeld() == current.recoveryHeld()
                && Objects.equals(previous.recoveryIncidentId(), current.recoveryIncidentId())
                && Arrays.equals(previous.traitIndicators(), current.traitIndicators())
                && previous.isTraitsActionVisible() == current.isTraitsActionVisible()
                && previous.isTraitsActionEnabled() == current.isTraitsActionEnabled()
                && previous.isTalentsActionVisible() == current.isTalentsActionVisible()
                && previous.isTalentsActionEnabled() == current.isTalentsActionEnabled()
                && samePresence(previous.futureStatA(), current.futureStatA())
                && sameTalentActionVisibility(previous.futureStatB(), current.futureStatB())
                && previous.flightToggleAvailable() == current.flightToggleAvailable();
    }

    private static boolean sameRespawnActionVisibility(
            LinkedNpcEntry previous,
            LinkedNpcEntry current
    ) {
        return respawnReady(previous) == respawnReady(current);
    }

    private static boolean respawnReady(LinkedNpcEntry entry) {
        return (entry.dead() || entry.lost()) && entry.deadRespawnRemainingMs() == 0L;
    }

    private static boolean samePresence(Object previous, Object current) {
        return (previous == null) == (current == null);
    }

    private static boolean sameTalentActionVisibility(
            LinkedNpcEntry.FutureStat previous,
            LinkedNpcEntry.FutureStat current
    ) {
        return LinkedNpcPanelProgressionBinder.availableTalentPoints(previous) > 0
                == (LinkedNpcPanelProgressionBinder.availableTalentPoints(current) > 0);
    }
}
