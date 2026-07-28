package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionProfileView;
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
        return new BondedCompanionProfileView(
                profile.profileId(), profile.ownerUuid(), profile.rosterId(),
                profile.familyId(), profile.roleId(), displayName.trim(),
                profile.species(), profile.gender(), profile.revision(),
                profile.state(), profile.summonAvailable(),
                profile.storeAvailable(), profile.reviveAvailable(),
                profile.snapshotPresentationData(), profile.activeLease(),
                profile.summonCooldownUntilMs(), profile.reviveQuote());
    }
}
