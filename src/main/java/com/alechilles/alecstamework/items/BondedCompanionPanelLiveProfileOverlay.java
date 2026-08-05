package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionPresentationAttributes;
import com.alechilles.alecstamework.npc.progression.CompanionHealthStateService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Applies short-lived live-NPC presentation fields to an immutable profile view. */
final class BondedCompanionPanelLiveProfileOverlay {
    private BondedCompanionPanelLiveProfileOverlay() {
    }

    /** Returns the original view unless an active projection supplies a name. */
    @Nonnull
    static BondedCompanionProfileView withDisplayName(
            @Nonnull BondedCompanionProfileView profile,
            @Nullable String displayName
    ) {
        if (displayName == null || displayName.isBlank()
                || displayName.trim().equals(profile.displayName())) {
            return profile;
        }
        return copy(profile, displayName.trim(), profile.snapshotPresentationData());
    }

    /**
     * Uses current live vitals for an active card without treating them as a
     * durable profile mutation. The next store operation remains responsible
     * for persisting the latest health snapshot.
     */
    @Nonnull
    static BondedCompanionProfileView withHealth(
            @Nonnull BondedCompanionProfileView profile,
            @Nullable CompanionHealthStateService.HealthSnapshot health
    ) {
        if (health == null) {
            return profile;
        }
        Map<String, String> data = profile.snapshotPresentationData();
        String current = Double.toString(health.currentHealth());
        String maximum = Double.toString(health.maximumHealth());
        String percent = Double.toString(health.healthPercent());
        if (current.equals(data.get("currentHealth"))
                && maximum.equals(data.get("maxHealth"))
                && percent.equals(data.get("healthPercent"))) {
            return profile;
        }
        Map<String, String> updated = new LinkedHashMap<>(data);
        updated.put("currentHealth", current);
        updated.put("maxHealth", maximum);
        updated.put("healthPercent", percent);
        return copy(profile, profile.displayName(), updated);
    }

    /** Applies exact live progression while retaining unavailable talent data. */
    @Nonnull
    static BondedCompanionProfileView withProgression(
            @Nonnull BondedCompanionProfileView profile,
            @Nullable ProgressionSnapshot progression
    ) {
        if (progression == null) {
            return profile;
        }
        Map<String, String> data = profile.snapshotPresentationData();
        String level = Integer.toString(progression.level());
        String currentXp = Double.toString(progression.currentXp());
        if (progression.levelingConfigId().equals(data.get("levelingConfigId"))
                && level.equals(data.get("level"))
                && currentXp.equals(data.get("currentXp"))
                && (progression.talentConfigId() == null
                || progression.talentConfigId().equals(data.get("talentConfigId")))
                && (progression.talentSpentPoints() == null
                || Integer.toString(progression.talentSpentPoints()).equals(
                        data.get("talentSpentPoints")))
                && (progression.talentAllocationRevision() == null
                || Long.toString(progression.talentAllocationRevision()).equals(
                        data.get("talentAllocationRevision")))
                && (progression.purchasedTalentIds() == null
                || String.join(", ", progression.purchasedTalentIds()).equals(
                        data.get("talents")))) {
            return profile;
        }
        Map<String, String> updated = new LinkedHashMap<>(data);
        updated.put("levelingConfigId", progression.levelingConfigId());
        updated.put("level", level);
        updated.put("currentXp", currentXp);
        if (progression.talentConfigId() != null) {
            updated.put("talentConfigId", progression.talentConfigId());
        }
        if (progression.talentSpentPoints() != null) {
            updated.put("talentSpentPoints", Integer.toString(
                    progression.talentSpentPoints()));
        }
        if (progression.talentAllocationRevision() != null) {
            updated.put("talentAllocationRevision", Long.toString(
                    progression.talentAllocationRevision()));
        }
        if (progression.purchasedTalentIds() != null) {
            updated.put("talents", String.join(", ",
                    progression.purchasedTalentIds()));
        }
        return copy(profile, profile.displayName(), updated);
    }

    /** Applies the configured live flight state without persisting card data. */
    @Nonnull
    static BondedCompanionProfileView withFlightMode(
            @Nonnull BondedCompanionProfileView profile,
            @Nonnull Optional<Boolean> airborne
    ) {
        Map<String, String> data = profile.snapshotPresentationData();
        String available = airborne.isPresent() ? "true" : null;
        String mode = airborne.map(String::valueOf).orElse(null);
        if (java.util.Objects.equals(available, data.get(
                BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE))
                && java.util.Objects.equals(mode, data.get(
                BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AIRBORNE))) {
            return profile;
        }
        Map<String, String> updated = new LinkedHashMap<>(data);
        if (airborne.isPresent()) {
            updated.put(BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE,
                    available);
            updated.put(BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AIRBORNE,
                    mode);
        } else {
            updated.remove(BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE);
            updated.remove(BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AIRBORNE);
        }
        return copy(profile, profile.displayName(), updated);
    }

    /** Applies the live shoulder-mount state without persisting card data. */
    @Nonnull
    static BondedCompanionProfileView withShoulderRide(
            @Nonnull BondedCompanionProfileView profile,
            @Nonnull Optional<Boolean> mounted
    ) {
        Map<String, String> data = profile.snapshotPresentationData();
        Map<String, String> updated = new LinkedHashMap<>(data);
        if (mounted.isPresent()) {
            updated.put(BondedCompanionPresentationAttributes.SHOULDER_RIDE_AVAILABLE,
                    "true");
            updated.put(BondedCompanionPresentationAttributes.SHOULDER_RIDE_MOUNTED,
                    Boolean.toString(mounted.get()));
        } else {
            updated.remove(BondedCompanionPresentationAttributes.SHOULDER_RIDE_AVAILABLE);
            updated.remove(BondedCompanionPresentationAttributes.SHOULDER_RIDE_MOUNTED);
        }
        return updated.equals(data) ? profile
                : copy(profile, profile.displayName(), updated);
    }

    private static BondedCompanionProfileView copy(
            BondedCompanionProfileView profile,
            @Nullable String displayName,
            Map<String, String> snapshotPresentationData
    ) {
        return new BondedCompanionProfileView(
                profile.profileId(), profile.ownerUuid(), profile.rosterId(),
                profile.familyId(), profile.roleId(), displayName,
                profile.species(), profile.gender(), profile.revision(),
                profile.state(), profile.summonAvailable(),
                profile.storeAvailable(), profile.reviveAvailable(),
                snapshotPresentationData, profile.activeLease(),
                profile.summonCooldownUntilMs(), profile.reviveQuote());
    }

    /** Live-only level and talent values projected over durable panel data. */
    record ProgressionSnapshot(
            @Nonnull String levelingConfigId,
            int level,
            double currentXp,
            @Nullable String talentConfigId,
            @Nullable Integer talentSpentPoints,
            @Nullable Long talentAllocationRevision,
            @Nullable List<String> purchasedTalentIds
    ) {
        ProgressionSnapshot(
                @Nonnull String levelingConfigId,
                int level,
                double currentXp,
                @Nullable String talentConfigId,
                @Nullable Integer talentSpentPoints
        ) {
            this(levelingConfigId, level, currentXp, talentConfigId,
                    talentSpentPoints, null, null);
        }

        ProgressionSnapshot {
            purchasedTalentIds = purchasedTalentIds == null
                    ? null : List.copyOf(purchasedTalentIds);
        }
    }
}
