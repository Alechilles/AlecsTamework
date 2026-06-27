package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import java.util.ArrayList;
import java.util.List;

/**
 * Normalizes linked-panel entries into immutable snapshots safe for UI refresh cycles.
 */
final class LinkedNpcEntrySnapshotMapper {
    private LinkedNpcEntrySnapshotMapper() {
    }

    static LinkedNpcEntry[] build(List<LinkedNpcEntry> entries) {
        return build(entries, LocalizedText.resolve((String) null, "tamework.ui.linkedPanel.subtitle.defaultNpcName"));
    }

    static LinkedNpcEntry[] build(List<LinkedNpcEntry> entries, String defaultName) {
        if (entries == null || entries.isEmpty()) {
            return new LinkedNpcEntry[0];
        }
        String fallbackName = (defaultName == null || defaultName.isBlank())
                ? LocalizedText.resolve((String) null, "tamework.ui.linkedPanel.subtitle.defaultNpcName")
                : defaultName;
        List<LinkedNpcEntry> out = new ArrayList<>(entries.size());
        for (LinkedNpcEntry entry : entries) {
            if (entry == null || entry.npcUuid() == null) {
                continue;
            }
            String name = entry.displayName();
            if (name == null || name.isBlank()) {
                name = fallbackName;
            }
            int current = Math.max(0, entry.currentHealth());
            int max = Math.max(0, entry.maxHealth());
            int currentHappiness = Math.max(0, entry.currentHappiness());
            int maxHappiness = Math.max(0, entry.maxHappiness());
            int currentHunger = Math.max(0, entry.currentHunger());
            int maxHunger = Math.max(0, entry.maxHunger());
            int currentThirst = Math.max(0, entry.currentThirst());
            int maxThirst = Math.max(0, entry.maxThirst());
            out.add(new LinkedNpcEntry(
                    entry.npcUuid(),
                    name,
                    entry.gender(),
                    current,
                    max,
                    currentHappiness,
                    maxHappiness,
                    entry.targetHappinessPercent(),
                    entry.happinessModifierBreakdown(),
                    currentHunger,
                    maxHunger,
                    currentThirst,
                    maxThirst,
                    entry.loaded(),
                    entry.hasHome(),
                    entry.dead(),
                    entry.captured(),
                    entry.inCoop(),
                    entry.lost(),
                    entry.deadRespawnRemainingMs(),
                    entry.deathCauseHint(),
                    entry.futureStatA(),
                    entry.futureStatB(),
                    entry.traitIndicators(),
                    entry.isTraitsActionVisible(),
                    entry.isTraitsActionEnabled(),
                    entry.isTalentsActionVisible(),
                    entry.isTalentsActionEnabled(),
                    entry.linked(),
                    entry.active(),
                    entry.speciesId(),
                    entry.speciesLabel(),
                    entry.groupId(),
                    entry.groupName(),
                    entry.groupColorHex(),
                    entry.breedingEnabled(),
                    entry.breedingCooldownActive(),
                    entry.breedingCooldownRemainingMs(),
                    entry.breedingCooldownRatio(),
                    entry.breedingCooldownKnown(),
                    entry.harvestCooldownActive(),
                    entry.harvestCooldownRemainingMs(),
                    entry.harvestCooldownRatio(),
                    entry.harvestCooldownKnown()
            ));
        }
        return out.toArray(new LinkedNpcEntry[0]);
    }
}
