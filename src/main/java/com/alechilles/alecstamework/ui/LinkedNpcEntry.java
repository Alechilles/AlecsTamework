package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.ProgressionView;
import com.alechilles.alecstamework.npc.progression.AnimalProgressionService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * View model for one linked NPC row in the command radial side panel.
 */
public final class LinkedNpcEntry {
    private final UUID npcUuid;
    private final String displayName;
    private final String roleSubtitle;
    private final String portraitIcon;
    private final String gender;
    private final int currentHealth;
    private final int maxHealth;
    private final int currentHappiness;
    private final int maxHappiness;
    private final int targetHappinessPercent;
    private final double breedingHappinessRatio;
    private final String happinessModifierBreakdown;
    private final int currentHunger;
    private final int maxHunger;
    private final int currentThirst;
    private final int maxThirst;
    private final boolean loaded;
    private final boolean linked;
    private final boolean ownedActions;
    private final boolean active;
    private final boolean dead;
    private final boolean captured;
    private final boolean inCoop;
    private final boolean lost;
    private final boolean hasHome;
    private final long deadRespawnRemainingMs;
    private final String deathCauseHint;
    private final String speciesId;
    private final String speciesLabel;
    private final String groupId;
    private final String groupName;
    private final String groupColorHex;
    private final boolean breedingEnabled;
    private final boolean breedingAvailable;
    private final boolean breedingCooldownActive;
    private final long breedingCooldownRemainingMs;
    private final double breedingCooldownRatio;
    private final boolean breedingCooldownKnown;
    private final boolean harvestCooldownActive;
    private final long harvestCooldownRemainingMs;
    private final double harvestCooldownRatio;
    private final boolean harvestCooldownKnown;
    private final boolean recallPending;
    private final long recallLostRemainingMs;
    private final boolean recoveryHeld;
    private final String recoveryIncidentId;
    private final FutureStat futureStatA;
    private final FutureStat futureStatB;
    private final LinkedNpcTraitIndicator[] traitIndicators;
    private final ProgressionView.TraitsView traitValues;
    private final boolean traitsActionVisible;
    private final boolean traitsActionEnabled;
    private final boolean talentsActionVisible;
    private final boolean talentsActionEnabled;
    private final boolean flightToggleAvailable;
    private final boolean flightToggleAirborne;
    private final boolean shoulderRideAvailable;
    private final boolean shoulderRideMounted;
    private final Location location;
    private final AnimalLifecycle animalLifecycle;

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
                          boolean inCoop,
                          boolean lost,
                          long deadRespawnRemainingMs,
                          LinkedNpcTraitIndicator[] traitIndicators) {
        this(
                npcUuid,
                displayName,
                null,
                currentHealth,
                maxHealth,
                currentHappiness,
                maxHappiness,
                computePercent(currentHappiness, maxHappiness),
                happinessModifierBreakdown,
                currentHunger,
                maxHunger,
                currentThirst,
                maxThirst,
                loaded,
                hasHome,
                dead,
                captured,
                inCoop,
                lost,
                deadRespawnRemainingMs,
                null,
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
                false,
                false,
                0L,
                0.0,
                false,
                false,
                0L,
                0.0,
                false,
                false,
                0L
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
                          boolean inCoop,
                          boolean lost,
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
                null,
                currentHealth,
                maxHealth,
                currentHappiness,
                maxHappiness,
                computePercent(currentHappiness, maxHappiness),
                happinessModifierBreakdown,
                currentHunger,
                maxHunger,
                currentThirst,
                maxThirst,
                loaded,
                hasHome,
                dead,
                captured,
                inCoop,
                lost,
                deadRespawnRemainingMs,
                null,
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
                false,
                false,
                0L,
                0.0,
                false,
                false,
                0L,
                0.0,
                false,
                false,
                0L
        );
    }

    public LinkedNpcEntry(UUID npcUuid,
                          String displayName,
                          int currentHealth,
                          int maxHealth,
                          int currentHappiness,
                          int maxHappiness,
                          int targetHappinessPercent,
                          String happinessModifierBreakdown,
                          int currentHunger,
                          int maxHunger,
                          int currentThirst,
                          int maxThirst,
                          boolean loaded,
                          boolean hasHome,
                          boolean dead,
                          boolean captured,
                          boolean inCoop,
                          boolean lost,
                          long deadRespawnRemainingMs,
                          String deathCauseHint,
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
                          boolean breedingEnabled,
                          boolean breedingCooldownActive,
                          long breedingCooldownRemainingMs,
                          double breedingCooldownRatio,
                          boolean breedingCooldownKnown) {
        this(
                npcUuid,
                displayName,
                currentHealth,
                maxHealth,
                currentHappiness,
                maxHappiness,
                targetHappinessPercent,
                happinessModifierBreakdown,
                currentHunger,
                maxHunger,
                currentThirst,
                maxThirst,
                loaded,
                hasHome,
                dead,
                captured,
                inCoop,
                lost,
                deadRespawnRemainingMs,
                deathCauseHint,
                futureStatA,
                futureStatB,
                traitIndicators,
                traitsActionVisible,
                traitsActionEnabled,
                talentsActionVisible,
                talentsActionEnabled,
                linked,
                active,
                speciesId,
                speciesLabel,
                groupId,
                groupName,
                groupColorHex,
                breedingEnabled,
                breedingCooldownActive,
                breedingCooldownRemainingMs,
                breedingCooldownRatio,
                breedingCooldownKnown,
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
                          int targetHappinessPercent,
                          String happinessModifierBreakdown,
                          int currentHunger,
                          int maxHunger,
                          int currentThirst,
                          int maxThirst,
                          boolean loaded,
                          boolean hasHome,
                          boolean dead,
                          boolean captured,
                          boolean inCoop,
                          boolean lost,
                          long deadRespawnRemainingMs,
                          String deathCauseHint,
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
                          boolean breedingEnabled,
                          boolean breedingCooldownActive,
                          long breedingCooldownRemainingMs,
                          double breedingCooldownRatio,
                          boolean breedingCooldownKnown,
                          boolean harvestCooldownActive,
                          long harvestCooldownRemainingMs,
                          double harvestCooldownRatio,
                          boolean harvestCooldownKnown) {
        this(
                npcUuid,
                displayName,
                null,
                currentHealth,
                maxHealth,
                currentHappiness,
                maxHappiness,
                targetHappinessPercent,
                happinessModifierBreakdown,
                currentHunger,
                maxHunger,
                currentThirst,
                maxThirst,
                loaded,
                hasHome,
                dead,
                captured,
                inCoop,
                lost,
                deadRespawnRemainingMs,
                deathCauseHint,
                futureStatA,
                futureStatB,
                traitIndicators,
                traitsActionVisible,
                traitsActionEnabled,
                talentsActionVisible,
                talentsActionEnabled,
                linked,
                active,
                speciesId,
                speciesLabel,
                groupId,
                groupName,
                groupColorHex,
                breedingEnabled,
                breedingCooldownKnown,
                breedingCooldownActive,
                breedingCooldownRemainingMs,
                breedingCooldownRatio,
                breedingCooldownKnown,
                harvestCooldownActive,
                harvestCooldownRemainingMs,
                harvestCooldownRatio,
                harvestCooldownKnown,
                false,
                0L
        );
    }

    public LinkedNpcEntry(UUID npcUuid,
                          String displayName,
                          String gender,
                          int currentHealth,
                          int maxHealth,
                          int currentHappiness,
                          int maxHappiness,
                          int targetHappinessPercent,
                          String happinessModifierBreakdown,
                          int currentHunger,
                          int maxHunger,
                          int currentThirst,
                          int maxThirst,
                          boolean loaded,
                          boolean hasHome,
                          boolean dead,
                          boolean captured,
                          boolean inCoop,
                          boolean lost,
                          long deadRespawnRemainingMs,
                          String deathCauseHint,
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
                          boolean breedingEnabled,
                          boolean breedingAvailable,
                          boolean breedingCooldownActive,
                          long breedingCooldownRemainingMs,
                          double breedingCooldownRatio,
                          boolean breedingCooldownKnown,
                          boolean harvestCooldownActive,
                          long harvestCooldownRemainingMs,
                          double harvestCooldownRatio,
                          boolean harvestCooldownKnown,
                          boolean recallPending,
                          long recallLostRemainingMs) {
        this.npcUuid = npcUuid;
        this.displayName = displayName;
        this.roleSubtitle = "";
        this.portraitIcon = "";
        this.gender = normalizeGender(gender);
        this.currentHealth = currentHealth;
        this.maxHealth = maxHealth;
        this.currentHappiness = currentHappiness;
        this.maxHappiness = maxHappiness;
        this.targetHappinessPercent = Math.max(0, Math.min(100, targetHappinessPercent));
        this.breedingHappinessRatio = -1.0;
        this.happinessModifierBreakdown = happinessModifierBreakdown;
        this.currentHunger = currentHunger;
        this.maxHunger = maxHunger;
        this.currentThirst = currentThirst;
        this.maxThirst = maxThirst;
        this.loaded = loaded;
        this.linked = linked;
        this.ownedActions = false;
        this.active = active;
        this.hasHome = hasHome;
        this.dead = dead;
        this.captured = captured;
        this.inCoop = inCoop;
        this.lost = lost;
        this.deadRespawnRemainingMs = deadRespawnRemainingMs < 0L ? -1L : deadRespawnRemainingMs;
        this.deathCauseHint = deathCauseHint;
        this.speciesId = speciesId;
        this.speciesLabel = speciesLabel;
        this.groupId = groupId;
        this.groupName = groupName;
        this.groupColorHex = groupColorHex;
        this.breedingEnabled = breedingEnabled;
        this.breedingAvailable = breedingAvailable || breedingCooldownKnown;
        this.breedingCooldownActive = breedingCooldownActive;
        this.breedingCooldownRemainingMs = !loaded && breedingCooldownRemainingMs < 0L
                ? -1L : Math.max(0L, breedingCooldownRemainingMs);
        this.breedingCooldownRatio = sanitizeRatio(breedingCooldownRatio);
        this.breedingCooldownKnown = breedingCooldownKnown;
        this.harvestCooldownActive = harvestCooldownActive;
        this.harvestCooldownRemainingMs = !loaded && harvestCooldownRemainingMs < 0L
                ? -1L : Math.max(0L, harvestCooldownRemainingMs);
        this.harvestCooldownRatio = sanitizeRatio(harvestCooldownRatio);
        this.harvestCooldownKnown = harvestCooldownKnown;
        this.recallPending = recallPending;
        this.recallLostRemainingMs = Math.max(0L, recallLostRemainingMs);
        this.recoveryHeld = false;
        this.recoveryIncidentId = null;
        this.futureStatA = futureStatA;
        this.futureStatB = futureStatB;
        this.traitIndicators = sanitizeTraitIndicators(traitIndicators);
        this.traitValues = null;
        this.traitsActionVisible = traitsActionVisible;
        this.traitsActionEnabled = traitsActionEnabled;
        this.talentsActionVisible = talentsActionVisible;
        this.talentsActionEnabled = talentsActionEnabled;
        this.flightToggleAvailable = false;
        this.flightToggleAirborne = false;
        this.shoulderRideAvailable = false;
        this.shoulderRideMounted = false;
        this.location = Location.EMPTY;
        this.animalLifecycle = AnimalLifecycle.NONE;
    }

    public boolean hasHealth() {
        return maxHealth > 0;
    }

    public UUID npcUuid() {
        return npcUuid;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * Localized species or role name shown under a player-assigned companion name.
     * Empty when the display name is already the role name.
     */
    public String roleSubtitle() {
        return roleSubtitle;
    }

    public String gender() {
        return gender;
    }

    public boolean isMale() {
        return "Male".equals(gender);
    }

    public boolean isFemale() {
        return "Female".equals(gender);
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

    public int targetHappinessPercent() {
        return targetHappinessPercent;
    }

    /** Required happiness on the meter's scale, or -1 when no marker applies. */
    public double breedingHappinessRatio() {
        return breedingHappinessRatio;
    }

    /** Returns a presentation copy; non-finite or negative ratios hide the marker. */
    public LinkedNpcEntry withBreedingHappinessRatio(double ratio) {
        return new LinkedNpcEntry(this, recoveryHeld, recoveryIncidentId,
                flightToggleAvailable, flightToggleAirborne, shoulderRideAvailable,
                shoulderRideMounted, Double.isFinite(ratio) && ratio >= 0.0 ? ratio : -1.0,
                ownedActions);
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

    /** Whether this Owned-mode presentation may use generic ownership actions. */
    public boolean ownedActions() {
        return ownedActions;
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

    public boolean inCoop() {
        return inCoop;
    }

    public boolean lost() {
        return lost;
    }

    public boolean hasHome() {
        return hasHome;
    }

    public long deadRespawnRemainingMs() {
        return deadRespawnRemainingMs;
    }

    public String deathCauseHint() {
        return deathCauseHint;
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

    public boolean breedingEnabled() {
        return breedingEnabled;
    }

    public boolean breedingAvailable() {
        return breedingAvailable;
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

    public boolean harvestCooldownActive() {
        return harvestCooldownActive;
    }

    public long harvestCooldownRemainingMs() {
        return harvestCooldownRemainingMs;
    }

    public double harvestCooldownRatio() {
        return harvestCooldownRatio;
    }

    public boolean harvestCooldownKnown() {
        return harvestCooldownKnown;
    }

    boolean hasKnownCooldowns() {
        return breedingCooldownKnown || harvestCooldownKnown;
    }

    public boolean recallPending() {
        return recallPending;
    }

    public long recallLostRemainingMs() {
        return recallLostRemainingMs;
    }

    public boolean recoveryHeld() {
        return recoveryHeld;
    }

    /** Whether this live linked companion can switch controller family. */
    public boolean flightToggleAvailable() {
        return flightToggleAvailable;
    }

    /** Whether the configured flight toggle currently resolves to flight. */
    public boolean flightToggleAirborne() {
        return flightToggleAirborne;
    }

    public boolean shoulderRideAvailable() {
        return shoulderRideAvailable;
    }

    public boolean shoulderRideMounted() {
        return shoulderRideMounted;
    }

    public String recoveryIncidentId() {
        return recoveryIncidentId;
    }

    /** Returns an immutable presentation copy marked with its scoped recovery incident. */
    public LinkedNpcEntry withRecoveryHold(String incidentId) {
        return new LinkedNpcEntry(this, true, incidentId);
    }

    /** Returns an immutable Owned-mode presentation copy with generic ownership actions. */
    public LinkedNpcEntry withOwnedActions() {
        return ownedActions ? this : new LinkedNpcEntry(this, true);
    }

    /** Returns an immutable presentation copy with its live flight-toggle state. */
    public LinkedNpcEntry withFlightToggle(boolean available, boolean airborne) {
        return new LinkedNpcEntry(this, recoveryHeld, recoveryIncidentId,
                available, airborne, shoulderRideAvailable, shoulderRideMounted);
    }

    /** Returns an immutable presentation copy with its live shoulder-ride state. */
    public LinkedNpcEntry withShoulderRide(boolean available, boolean mounted) {
        return new LinkedNpcEntry(this, recoveryHeld, recoveryIncidentId,
                flightToggleAvailable, flightToggleAirborne, available, mounted);
    }

    /** Returns an immutable presentation copy with an optional role subtitle. */
    public LinkedNpcEntry withRoleSubtitle(String subtitle) {
        String normalized = normalizeRoleSubtitle(subtitle);
        return Objects.equals(roleSubtitle, normalized) ? this
                : new LinkedNpcEntry(this, normalized);
    }

    /** Existing capture/model icon used only for card presentation; blank means unavailable. */
    public String portraitIcon() {
        return portraitIcon;
    }

    public LinkedNpcEntry withPortraitIcon(String icon) {
        String normalized = icon == null ? "" : icon.trim();
        return Objects.equals(portraitIcon, normalized) ? this
                : new LinkedNpcEntry(this, recoveryHeld, recoveryIncidentId, flightToggleAvailable,
                        flightToggleAirborne, shoulderRideAvailable, shoulderRideMounted,
                        breedingHappinessRatio, ownedActions, roleSubtitle, normalized);
    }

    /** Returns an immutable presentation copy with the current location details. */
    public LinkedNpcEntry withLocation(Location location) {
        Location normalized = Location.normalize(location);
        return Objects.equals(this.location, normalized) ? this
                : new LinkedNpcEntry(this, recoveryHeld, recoveryIncidentId,
                        flightToggleAvailable, flightToggleAirborne, shoulderRideAvailable,
                        shoulderRideMounted, breedingHappinessRatio, ownedActions,
                        roleSubtitle, portraitIcon, normalized);
    }

    /** Localized location details for the inline companion card. */
    public Location location() {
        return location;
    }

    public double healthRatio() {
        if (!hasHealth()) {
            return 0.0;
        }
        return (double) currentHealth / (double) maxHealth;
    }

    public boolean hasHappiness() {
        return maxHappiness > 0;
    }

    public double happinessRatio() {
        if (!hasHappiness()) {
            return 0.0;
        }
        return (double) currentHappiness / (double) maxHappiness;
    }

    public boolean hasHunger() {
        return maxHunger > 0;
    }

    public double hungerRatio() {
        if (!hasHunger()) {
            return 0.0;
        }
        return (double) currentHunger / (double) maxHunger;
    }

    public boolean hasThirst() {
        return maxThirst > 0;
    }

    boolean hasKnownCardDetails() {
        return animalLifecycle.active()
                || hasHealth()
                || hasHappiness()
                || hasHunger()
                || hasThirst()
                || hasKnownCooldowns()
                || traitIndicators.length > 0
                || futureStatA != null
                || futureStatB != null;
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

    /** Resolved detached traits when the card source has the underlying values. */
    public ProgressionView.TraitsView traitValues() {
        return traitValues;
    }

    /** Returns an immutable presentation copy with detached resolved trait values. */
    public LinkedNpcEntry withTraitValues(ProgressionView.TraitsView values) {
        return Objects.equals(traitValues, values) ? this
                : new LinkedNpcEntry(this, recoveryHeld, recoveryIncidentId,
                        flightToggleAvailable, flightToggleAirborne,
                        shoulderRideAvailable, shoulderRideMounted,
                        breedingHappinessRatio, ownedActions, roleSubtitle,
                        portraitIcon, location, values);
    }

    public AnimalLifecycle animalLifecycle() { return animalLifecycle; }

    public LinkedNpcEntry withAnimalLifecycle(AnimalProgressionService.Presentation presentation) {
        AnimalLifecycle lifecycle = AnimalLifecycle.from(presentation);
        return Objects.equals(animalLifecycle, lifecycle) ? this
                : new LinkedNpcEntry(this, recoveryHeld, recoveryIncidentId,
                        flightToggleAvailable, flightToggleAirborne, shoulderRideAvailable,
                        shoulderRideMounted, breedingHappinessRatio, ownedActions, roleSubtitle,
                        portraitIcon, location, traitValues, lifecycle);
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

    private LinkedNpcEntry(LinkedNpcEntry source,
                           boolean recoveryHeld,
                           String incidentId) {
        this(source, recoveryHeld, incidentId, source.flightToggleAvailable,
                source.flightToggleAirborne, source.shoulderRideAvailable,
                source.shoulderRideMounted);
    }

    private LinkedNpcEntry(LinkedNpcEntry source, boolean ownedActions) {
        this(source, source.recoveryHeld, source.recoveryIncidentId,
                source.flightToggleAvailable, source.flightToggleAirborne,
                source.shoulderRideAvailable, source.shoulderRideMounted,
                source.breedingHappinessRatio, ownedActions);
    }

    private LinkedNpcEntry(LinkedNpcEntry source, String roleSubtitle) {
        this(source, source.recoveryHeld, source.recoveryIncidentId,
                source.flightToggleAvailable, source.flightToggleAirborne,
                source.shoulderRideAvailable, source.shoulderRideMounted,
                source.breedingHappinessRatio, source.ownedActions, roleSubtitle);
    }

    private LinkedNpcEntry(LinkedNpcEntry source,
                           boolean recoveryHeld,
                           String incidentId,
                           boolean flightToggleAvailable,
                           boolean flightToggleAirborne,
                           boolean shoulderRideAvailable,
                           boolean shoulderRideMounted) {
        this(source, recoveryHeld, incidentId, flightToggleAvailable, flightToggleAirborne,
                shoulderRideAvailable, shoulderRideMounted, source.breedingHappinessRatio,
                source.ownedActions);
    }

    private LinkedNpcEntry(LinkedNpcEntry source,
                           boolean recoveryHeld,
                           String incidentId,
                           boolean flightToggleAvailable,
                           boolean flightToggleAirborne,
                           boolean shoulderRideAvailable,
                           boolean shoulderRideMounted,
                           double breedingHappinessRatio,
                           boolean ownedActions) {
        this(source, recoveryHeld, incidentId, flightToggleAvailable, flightToggleAirborne,
                shoulderRideAvailable, shoulderRideMounted, breedingHappinessRatio,
                ownedActions, source.roleSubtitle);
    }

    private LinkedNpcEntry(LinkedNpcEntry source,
                           boolean recoveryHeld,
                           String incidentId,
                           boolean flightToggleAvailable,
                           boolean flightToggleAirborne,
                           boolean shoulderRideAvailable,
                           boolean shoulderRideMounted,
                           double breedingHappinessRatio,
                           boolean ownedActions,
                           String roleSubtitle) {
        this(source, recoveryHeld, incidentId, flightToggleAvailable, flightToggleAirborne,
                shoulderRideAvailable, shoulderRideMounted, breedingHappinessRatio,
                ownedActions, roleSubtitle, source.portraitIcon);
    }

    private LinkedNpcEntry(LinkedNpcEntry source, boolean recoveryHeld, String incidentId,
                          boolean flightToggleAvailable, boolean flightToggleAirborne,
                          boolean shoulderRideAvailable, boolean shoulderRideMounted,
                          double breedingHappinessRatio, boolean ownedActions,
                          String roleSubtitle, String portraitIcon) {
        this(source, recoveryHeld, incidentId, flightToggleAvailable, flightToggleAirborne,
                shoulderRideAvailable, shoulderRideMounted, breedingHappinessRatio,
                ownedActions, roleSubtitle, portraitIcon, source.location);
    }

    private LinkedNpcEntry(LinkedNpcEntry source, boolean recoveryHeld, String incidentId,
                          boolean flightToggleAvailable, boolean flightToggleAirborne,
                          boolean shoulderRideAvailable, boolean shoulderRideMounted,
                          double breedingHappinessRatio, boolean ownedActions,
                          String roleSubtitle, String portraitIcon, Location location) {
        this(source, recoveryHeld, incidentId, flightToggleAvailable,
                flightToggleAirborne, shoulderRideAvailable, shoulderRideMounted,
                breedingHappinessRatio, ownedActions, roleSubtitle, portraitIcon,
                location, source.traitValues);
    }

    private LinkedNpcEntry(LinkedNpcEntry source, boolean recoveryHeld, String incidentId,
                          boolean flightToggleAvailable, boolean flightToggleAirborne,
                          boolean shoulderRideAvailable, boolean shoulderRideMounted,
                          double breedingHappinessRatio, boolean ownedActions,
                          String roleSubtitle, String portraitIcon, Location location,
                          ProgressionView.TraitsView traitValues) {
        this(source, recoveryHeld, incidentId, flightToggleAvailable, flightToggleAirborne,
                shoulderRideAvailable, shoulderRideMounted, breedingHappinessRatio, ownedActions,
                roleSubtitle, portraitIcon, location, traitValues, source.animalLifecycle);
    }

    private LinkedNpcEntry(LinkedNpcEntry source, boolean recoveryHeld, String incidentId,
                          boolean flightToggleAvailable, boolean flightToggleAirborne,
                          boolean shoulderRideAvailable, boolean shoulderRideMounted,
                          double breedingHappinessRatio, boolean ownedActions,
                          String roleSubtitle, String portraitIcon, Location location,
                          ProgressionView.TraitsView traitValues, AnimalLifecycle animalLifecycle) {
        this.portraitIcon = portraitIcon;
        this.npcUuid = source.npcUuid;
        this.displayName = source.displayName;
        this.roleSubtitle = normalizeRoleSubtitle(roleSubtitle);
        this.gender = source.gender;
        this.currentHealth = source.currentHealth;
        this.maxHealth = source.maxHealth;
        this.currentHappiness = source.currentHappiness;
        this.maxHappiness = source.maxHappiness;
        this.targetHappinessPercent = source.targetHappinessPercent;
        this.breedingHappinessRatio = breedingHappinessRatio;
        this.happinessModifierBreakdown = source.happinessModifierBreakdown;
        this.currentHunger = source.currentHunger;
        this.maxHunger = source.maxHunger;
        this.currentThirst = source.currentThirst;
        this.maxThirst = source.maxThirst;
        this.loaded = source.loaded;
        this.linked = source.linked;
        this.ownedActions = ownedActions;
        this.active = source.active;
        this.dead = source.dead;
        this.captured = source.captured;
        this.inCoop = source.inCoop;
        this.lost = source.lost;
        this.hasHome = source.hasHome;
        this.deadRespawnRemainingMs = source.deadRespawnRemainingMs;
        this.deathCauseHint = source.deathCauseHint;
        this.speciesId = source.speciesId;
        this.speciesLabel = source.speciesLabel;
        this.groupId = source.groupId;
        this.groupName = source.groupName;
        this.groupColorHex = source.groupColorHex;
        this.breedingEnabled = source.breedingEnabled;
        this.breedingAvailable = source.breedingAvailable;
        this.breedingCooldownActive = source.breedingCooldownActive;
        this.breedingCooldownRemainingMs = source.breedingCooldownRemainingMs;
        this.breedingCooldownRatio = source.breedingCooldownRatio;
        this.breedingCooldownKnown = source.breedingCooldownKnown;
        this.harvestCooldownActive = source.harvestCooldownActive;
        this.harvestCooldownRemainingMs = source.harvestCooldownRemainingMs;
        this.harvestCooldownRatio = source.harvestCooldownRatio;
        this.harvestCooldownKnown = source.harvestCooldownKnown;
        this.recallPending = source.recallPending;
        this.recallLostRemainingMs = source.recallLostRemainingMs;
        this.futureStatA = source.futureStatA;
        this.futureStatB = source.futureStatB;
        this.traitIndicators = source.traitIndicators.clone();
        this.traitValues = traitValues;
        this.traitsActionVisible = source.traitsActionVisible;
        this.traitsActionEnabled = source.traitsActionEnabled;
        this.talentsActionVisible = source.talentsActionVisible;
        this.talentsActionEnabled = source.talentsActionEnabled;
        this.flightToggleAvailable = flightToggleAvailable;
        this.flightToggleAirborne = flightToggleAvailable && flightToggleAirborne;
        this.shoulderRideAvailable = shoulderRideAvailable;
        this.shoulderRideMounted = shoulderRideAvailable && shoulderRideMounted;
        this.recoveryHeld = recoveryHeld;
        this.recoveryIncidentId = recoveryHeld ? normalizeIncidentId(incidentId) : null;
        this.location = Location.normalize(location);
        this.animalLifecycle = animalLifecycle == null ? AnimalLifecycle.NONE : animalLifecycle;
    }

    private static String normalizeIncidentId(String incidentId) {
        if (incidentId == null || incidentId.isBlank()) return null;
        String normalized = incidentId.trim();
        return normalized.substring(0, Math.min(8, normalized.length()));
    }

    private static double sanitizeRatio(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static int computePercent(int current, int max) {
        if (max <= 0) {
            return 0;
        }
        double ratio = Math.max(0.0, Math.min(1.0, ((double) current) / (double) max));
        return Math.max(0, Math.min(100, Math.round((float) (ratio * 100.0))));
    }

    private static String normalizeGender(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if ("male".equalsIgnoreCase(trimmed)) {
            return "Male";
        }
        if ("female".equalsIgnoreCase(trimmed)) {
            return "Female";
        }
        return null;
    }

    private static String normalizeRoleSubtitle(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof LinkedNpcEntry other)) {
            return false;
        }
        return currentHealth == other.currentHealth
                && maxHealth == other.maxHealth
                && currentHappiness == other.currentHappiness
                && maxHappiness == other.maxHappiness
                && targetHappinessPercent == other.targetHappinessPercent
                && Double.compare(breedingHappinessRatio, other.breedingHappinessRatio) == 0
                && currentHunger == other.currentHunger
                && maxHunger == other.maxHunger
                && currentThirst == other.currentThirst
                && maxThirst == other.maxThirst
                && loaded == other.loaded
                && linked == other.linked
                && ownedActions == other.ownedActions
                && active == other.active
                && dead == other.dead
                && captured == other.captured
                && inCoop == other.inCoop
                && lost == other.lost
                && hasHome == other.hasHome
                && deadRespawnRemainingMs == other.deadRespawnRemainingMs
                && breedingEnabled == other.breedingEnabled
                && breedingAvailable == other.breedingAvailable
                && breedingCooldownActive == other.breedingCooldownActive
                && breedingCooldownRemainingMs == other.breedingCooldownRemainingMs
                && Double.compare(breedingCooldownRatio, other.breedingCooldownRatio) == 0
                && breedingCooldownKnown == other.breedingCooldownKnown
                && harvestCooldownActive == other.harvestCooldownActive
                && harvestCooldownRemainingMs == other.harvestCooldownRemainingMs
                && Double.compare(harvestCooldownRatio, other.harvestCooldownRatio) == 0
                && harvestCooldownKnown == other.harvestCooldownKnown
                && recallPending == other.recallPending
                && recallLostRemainingMs == other.recallLostRemainingMs
                && traitsActionVisible == other.traitsActionVisible
                && traitsActionEnabled == other.traitsActionEnabled
                && talentsActionVisible == other.talentsActionVisible
                && talentsActionEnabled == other.talentsActionEnabled
                && flightToggleAvailable == other.flightToggleAvailable
                && flightToggleAirborne == other.flightToggleAirborne
                && shoulderRideAvailable == other.shoulderRideAvailable
                && shoulderRideMounted == other.shoulderRideMounted
                && Objects.equals(animalLifecycle, other.animalLifecycle)
                && Objects.equals(npcUuid, other.npcUuid)
                && Objects.equals(displayName, other.displayName)
                && Objects.equals(roleSubtitle, other.roleSubtitle)
                && Objects.equals(portraitIcon, other.portraitIcon)
                && Objects.equals(gender, other.gender)
                && Objects.equals(happinessModifierBreakdown, other.happinessModifierBreakdown)
                && Objects.equals(deathCauseHint, other.deathCauseHint)
                && Objects.equals(speciesId, other.speciesId)
                && Objects.equals(speciesLabel, other.speciesLabel)
                && Objects.equals(groupId, other.groupId)
                && Objects.equals(groupName, other.groupName)
                && Objects.equals(groupColorHex, other.groupColorHex)
                && Objects.equals(location, other.location)
                && Objects.equals(futureStatA, other.futureStatA)
                && Objects.equals(futureStatB, other.futureStatB)
                && Arrays.equals(traitIndicators, other.traitIndicators);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                npcUuid,
                displayName,
                roleSubtitle,
                portraitIcon,
                gender,
                currentHealth,
                maxHealth,
                currentHappiness,
                maxHappiness,
                targetHappinessPercent,
                breedingHappinessRatio,
                happinessModifierBreakdown,
                currentHunger,
                maxHunger,
                currentThirst,
                maxThirst,
                loaded,
                linked,
                ownedActions,
                active,
                dead,
                captured,
                inCoop,
                lost,
                hasHome,
                deadRespawnRemainingMs,
                deathCauseHint,
                speciesId,
                speciesLabel,
                groupId,
                groupName,
                groupColorHex,
                breedingEnabled,
                breedingAvailable,
                breedingCooldownActive,
                breedingCooldownRemainingMs,
                breedingCooldownRatio,
                breedingCooldownKnown,
                harvestCooldownActive,
                harvestCooldownRemainingMs,
                harvestCooldownRatio,
                harvestCooldownKnown,
                recallPending,
                recallLostRemainingMs,
                futureStatA,
                futureStatB,
                traitsActionVisible,
                traitsActionEnabled,
                talentsActionVisible,
                talentsActionEnabled,
                flightToggleAvailable,
                flightToggleAirborne,
                shoulderRideAvailable,
                shoulderRideMounted,
                location,
                animalLifecycle
        );
        result = 31 * result + Arrays.hashCode(traitIndicators);
        return result;
    }

    /** Localized text displayed in the inline location section. */
    public record Location(String status, String world, String coordinates, String relativeDistance) {
        public static final Location EMPTY = new Location("", "", "");

        public Location(String status, String world, String coordinates) {
            this(status, world, coordinates, "");
        }

        public Location {
            status = normalizeText(status);
            world = normalizeText(world);
            coordinates = normalizeText(coordinates);
            relativeDistance = normalizeText(relativeDistance);
        }

        private static Location normalize(Location location) {
            return location == null ? EMPTY : location;
        }

        private static String normalizeText(String value) {
            return value == null ? "" : value.trim();
        }
    }

    /** Detached lifecycle display values shared by live, unloaded, captured, and coop cards. */
    public record AnimalLifecycle(String stage, boolean prime, boolean frozen, boolean nextDeath,
                                  long remainingMs, double yieldMultiplier, double stageProgress, String nextStage) {
        public AnimalLifecycle(String stage, boolean prime, boolean frozen, boolean nextDeath,
                               long remainingMs, double yieldMultiplier, double stageProgress) {
            this(stage, prime, frozen, nextDeath, remainingMs, yieldMultiplier, stageProgress, "");
        }
        public AnimalLifecycle(String stage, boolean prime, boolean frozen, boolean nextDeath,
                               long remainingMs, double yieldMultiplier) {
            this(stage, prime, frozen, nextDeath, remainingMs, yieldMultiplier, 0);
        }
        static final AnimalLifecycle NONE = new AnimalLifecycle("", false, false, false, -1L, 1.0, 0);
        static AnimalLifecycle from(AnimalProgressionService.Presentation value) {
            return value == null ? NONE : new AnimalLifecycle(value.stage(), value.prime(), value.frozen(),
                    value.nextDeath(), value.remainingMs(), value.yieldMultiplier(), value.stageProgress(), value.nextStage());
        }
        public boolean active() { return !stage.isBlank(); }
    }

    /**
     * Placeholder stat entry used for future linked-panel bars (hunger/thirst/happiness/etc.).
     */
    public static final class FutureStat {
        private final String label;
        private final int current;
        private final int max;
        private final String tooltipHeaderText;
        private final String tooltipText;

        public FutureStat(String label, int current, int max) {
            this(label, current, max, null, null);
        }

        public FutureStat(String label, int current, int max, String tooltipText) {
            this(label, current, max, null, tooltipText);
        }

        public FutureStat(String label, int current, int max, String tooltipHeaderText, String tooltipText) {
            this.label = label;
            this.current = current;
            this.max = max;
            this.tooltipHeaderText = tooltipHeaderText;
            this.tooltipText = tooltipText;
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

        public String tooltipHeaderText() {
            return tooltipHeaderText;
        }

        public String tooltipText() {
            return tooltipText;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof FutureStat other)) {
                return false;
            }
            return current == other.current
                    && max == other.max
                    && Objects.equals(label, other.label)
                    && Objects.equals(tooltipHeaderText, other.tooltipHeaderText)
                    && Objects.equals(tooltipText, other.tooltipText);
        }

        @Override
        public int hashCode() {
            return Objects.hash(label, current, max, tooltipHeaderText, tooltipText);
        }
    }
}
