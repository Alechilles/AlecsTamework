package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.npc.progression.CompanionHealthStateService;
import java.util.LinkedHashMap;
import java.util.Map;
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
}
