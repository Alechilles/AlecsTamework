package com.alechilles.alecstamework.ui;

import java.util.ArrayList;
import java.util.UUID;

/**
 * View model for one linked NPC row in the command radial side panel.
 */
public final class LinkedNpcEntry {
    private final UUID npcUuid;
    private final String displayName;
    private final int currentHealth;
    private final int maxHealth;
    private final int currentHappiness;
    private final int maxHappiness;
    private final String happinessModifierBreakdown;
    private final int currentHunger;
    private final int maxHunger;
    private final int currentThirst;
    private final int maxThirst;
    private final boolean loaded;
    private final boolean linked;
    private final boolean active;
    private final boolean dead;
    private final boolean captured;
    private final boolean hasHome;
    private final long deadRespawnRemainingMs;
    private final String speciesId;
    private final String speciesLabel;
    private final String groupId;
    private final String groupName;
    private final String groupColorHex;
    private final boolean breedingCooldownActive;
    private final long breedingCooldownRemainingMs;
    private final double breedingCooldownRatio;
    private final boolean breedingCooldownKnown;
    private final FutureStat futureStatA;
    private final FutureStat futureStatB;
    private final LinkedNpcTraitIndicator[] traitIndicators;
    private final boolean traitsActionVisible;
    private final boolean traitsActionEnabled;
    private final boolean talentsActionVisible;
    private final boolean talentsActionEnabled;

    public LinkedNpcEntry(UUID npcUuid,
                          String displayName,
                          int currentHealth,
                          int maxHealth,
                          int currentHappiness,
                          int maxHappiness,
                          String happinessModifierBreakdown,
                          int currentHunger,
                          int maxHunger,
                          int currentThirst,
                          int maxThirst,
                          boolean loaded,
                          boolean hasHome,
                          boolean dead,
                          boolean captured,
                          long deadRespawnRemainingMs,
                          LinkedNpcTraitIndicator[] traitIndicators) {
        this(
                npcUuid,
                displayName,
                currentHealth,
                maxHealth,
                currentHappiness,
                maxHappiness,
                happinessModifierBreakdown,
                currentHunger,
                maxHunger,
                currentThirst,
                maxThirst,
                loaded,
                hasHome,
                dead,
                captured,
                deadRespawnRemainingMs,
                null,
                null,
                traitIndicators,
                false,
                false,
                false,
                false,
                true,
                true,
                null,
                null,
                null,
                null,
                null,
                false,
                0L,
                0.0,
                false
        );
    }

    public LinkedNpcEntry(UUID npcUuid,
                          String displayName,
                          int currentHealth,
                          int maxHealth,
                          int currentHappiness,
                          int maxHappiness,
                          String happinessModifierBreakdown,
                          int currentHunger,
                          int maxHunger,
                          int currentThirst,
                          int maxThirst,
                          boolean loaded,
                          boolean hasHome,
                          boolean dead,
                          boolean captured,
                          long deadRespawnRemainingMs,
                          FutureStat futureStatA,
                          FutureStat futureStatB,
                          LinkedNpcTraitIndicator[] traitIndicators,
                          boolean traitsActionVisible,
                          boolean traitsActionEnabled,
                          boolean talentsActionVisible,
                          boolean talentsActionEnabled) {
        this(
                npcUuid,
                displayName,
                currentHealth,
                maxHealth,
                currentHappiness,
                maxHappiness,
                happinessModifierBreakdown,
                currentHunger,
                maxHunger,
                currentThirst,
                maxThirst,
                loaded,
                hasHome,
                dead,
                captured,
                deadRespawnRemainingMs,
                futureStatA,
                futureStatB,
                traitIndicators,
                traitsActionVisible,
                traitsActionEnabled,
                talentsActionVisible,
                talentsActionEnabled,
                true,
                true,
                null,
                null,
                null,
                null,
                null,
                false,
                0L,
                0.0,
                false
        );
    }

    public LinkedNpcEntry(UUID npcUuid,
                          String displayName,
                          int currentHealth,
                          int maxHealth,
                          int currentHappiness,
                          int maxHappiness,
                          String happinessModifierBreakdown,
                          int currentHunger,
                          int maxHunger,
                          int currentThirst,
                          int maxThirst,
                          boolean loaded,
                          boolean hasHome,
                          boolean dead,
                          boolean captured,
                          long deadRespawnRemainingMs,
                          FutureStat futureStatA,
                          FutureStat futureStatB,
                          LinkedNpcTraitIndicator[] traitIndicators,
                          boolean traitsActionVisible,
                          boolean traitsActionEnabled,
                          boolean talentsActionVisible,
                          boolean talentsActionEnabled,
                          boolean linked,
                          boolean active,
                          String speciesId,
                          String speciesLabel,
                          String groupId,
                          String groupName,
                          String groupColorHex,
                          boolean breedingCooldownActive,
                          long breedingCooldownRemainingMs,
                          double breedingCooldownRatio,
                          boolean breedingCooldownKnown) {
        this.npcUuid = npcUuid;
        this.displayName = displayName;
        this.currentHealth = currentHealth;
        this.maxHealth = maxHealth;
        this.currentHappiness = currentHappiness;
        this.maxHappiness = maxHappiness;
        this.happinessModifierBreakdown = happinessModifierBreakdown;
        this.currentHunger = currentHunger;
        this.maxHunger = maxHunger;
        this.currentThirst = currentThirst;
        this.maxThirst = maxThirst;
        this.loaded = loaded;
        this.linked = linked;
        this.active = active;
        this.hasHome = hasHome;
        this.dead = dead;
        this.captured = captured;
        this.deadRespawnRemainingMs = Math.max(0L, deadRespawnRemainingMs);
        this.speciesId = speciesId;
        this.speciesLabel = speciesLabel;
        this.groupId = groupId;
        this.groupName = groupName;
        this.groupColorHex = groupColorHex;
        this.breedingCooldownActive = breedingCooldownActive;
        this.breedingCooldownRemainingMs = Math.max(0L, breedingCooldownRemainingMs);
        this.breedingCooldownRatio = sanitizeRatio(breedingCooldownRatio);
        this.breedingCooldownKnown = breedingCooldownKnown;
        this.futureStatA = futureStatA;
        this.futureStatB = futureStatB;
        this.traitIndicators = sanitizeTraitIndicators(traitIndicators);
        this.traitsActionVisible = traitsActionVisible;
        this.traitsActionEnabled = traitsActionEnabled;
        this.talentsActionVisible = talentsActionVisible;
        this.talentsActionEnabled = talentsActionEnabled;
    }

    public boolean hasHealth() {
        return loaded && maxHealth > 0;
    }

    public UUID npcUuid() {
        return npcUuid;
    }

    public String displayName() {
        return displayName;
    }

    public int currentHealth() {
        return currentHealth;
    }

    public int maxHealth() {
        return maxHealth;
    }

    public int currentHappiness() {
        return currentHappiness;
    }

    public int maxHappiness() {
        return maxHappiness;
    }

    public String happinessModifierBreakdown() {
        return happinessModifierBreakdown;
    }

    public int currentHunger() {
        return currentHunger;
    }

    public int maxHunger() {
        return maxHunger;
    }

    public int currentThirst() {
        return currentThirst;
    }

    public int maxThirst() {
        return maxThirst;
    }

    public boolean loaded() {
        return loaded;
    }

    public boolean linked() {
        return linked;
    }

    public boolean active() {
        return active;
    }

    public boolean dead() {
        return dead;
    }

    public boolean captured() {
        return captured;
    }

    public boolean hasHome() {
        return hasHome;
    }

    public long deadRespawnRemainingMs() {
        return deadRespawnRemainingMs;
    }

    public String speciesId() {
        return speciesId;
    }

    public String speciesLabel() {
        return speciesLabel;
    }

    public String groupId() {
        return groupId;
    }

    public String groupName() {
        return groupName;
    }

    public String groupColorHex() {
        return groupColorHex;
    }

    public boolean breedingCooldownActive() {
        return breedingCooldownActive;
    }

    public long breedingCooldownRemainingMs() {
        return breedingCooldownRemainingMs;
    }

    public double breedingCooldownRatio() {
        return breedingCooldownRatio;
    }

    public boolean breedingCooldownKnown() {
        return breedingCooldownKnown;
    }

    public double healthRatio() {
        if (!hasHealth()) {
            return 0.0;
        }
        return (double) currentHealth / (double) maxHealth;
    }

    public boolean hasHappiness() {
        return loaded && maxHappiness > 0;
    }

    public double happinessRatio() {
        if (!hasHappiness()) {
            return 0.0;
        }
        return (double) currentHappiness / (double) maxHappiness;
    }

    public boolean hasHunger() {
        return loaded && maxHunger > 0;
    }

    public double hungerRatio() {
        if (!hasHunger()) {
            return 0.0;
        }
        return (double) currentHunger / (double) maxHunger;
    }

    public boolean hasThirst() {
        return loaded && maxThirst > 0;
    }

    public double thirstRatio() {
        if (!hasThirst()) {
            return 0.0;
        }
        return (double) currentThirst / (double) maxThirst;
    }

    public boolean hasFutureStatA() {
        return futureStatA != null;
    }

    public boolean hasFutureStatB() {
        return futureStatB != null;
    }

    public FutureStat futureStatA() {
        return futureStatA;
    }

    public FutureStat futureStatB() {
        return futureStatB;
    }

    public LinkedNpcTraitIndicator[] traitIndicators() {
        return traitIndicators;
    }

    public boolean hasAnyFutureAction() {
        return traitsActionVisible || talentsActionVisible;
    }

    public boolean isTraitsActionVisible() {
        return traitsActionVisible;
    }

    public boolean isTraitsActionEnabled() {
        return traitsActionEnabled;
    }

    public boolean isTalentsActionVisible() {
        return talentsActionVisible;
    }

    public boolean isTalentsActionEnabled() {
        return talentsActionEnabled;
    }

    private static LinkedNpcTraitIndicator[] sanitizeTraitIndicators(LinkedNpcTraitIndicator[] input) {
        if (input == null || input.length == 0) {
            return LinkedNpcTraitIndicator.EMPTY;
        }
        ArrayList<LinkedNpcTraitIndicator> out = new ArrayList<>(input.length);
        for (LinkedNpcTraitIndicator indicator : input) {
            if (indicator == null) {
                continue;
            }
            out.add(indicator);
            if (out.size() >= LinkedNpcTraitIndicatorBinder.MAX_VISIBLE_TRAIT_INDICATORS) {
                break;
            }
        }
        return out.isEmpty() ? LinkedNpcTraitIndicator.EMPTY : out.toArray(new LinkedNpcTraitIndicator[0]);
    }

    private static double sanitizeRatio(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    /**
     * Placeholder stat entry used for future linked-panel bars (hunger/thirst/happiness/etc.).
     */
    public static final class FutureStat {
        private final String label;
        private final int current;
        private final int max;

        public FutureStat(String label, int current, int max) {
            this.label = label;
            this.current = current;
            this.max = max;
        }

        public String label() {
            return label;
        }

        public int current() {
            return current;
        }

        public int max() {
            return max;
        }
    }
}
