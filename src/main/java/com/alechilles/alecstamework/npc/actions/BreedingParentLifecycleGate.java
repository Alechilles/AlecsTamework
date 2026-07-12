package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.breeding.BreedingParentIdentity;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerPopulationEntry;
import com.alechilles.alecstamework.ownership.OwnerPopulationIndex;
import com.alechilles.alecstamework.ownership.OwnerPopulationProfileStateSnapshot;
import com.alechilles.alecstamework.ownership.OwnerPopulationReadiness;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Rejects delayed breeding work when either parent's canonical lifecycle is no longer active. */
final class BreedingParentLifecycleGate {
    private final ProfileSnapshotLookup snapshots;

    BreedingParentLifecycleGate() {
        this(BreedingParentLifecycleGate::runtimeSnapshot);
    }

    BreedingParentLifecycleGate(@Nonnull ProfileSnapshotLookup snapshots) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    @Nonnull
    Decision inspect(@Nonnull BreedingParentIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        OwnerPopulationProfileStateSnapshot snapshot;
        try {
            snapshot = snapshots.snapshot(identity.profileId());
        } catch (RuntimeException | LinkageError failure) {
            return Decision.deny("parent-lifecycle-authority-error");
        }
        if (snapshot == null) {
            return Decision.deny("parent-lifecycle-authority-unavailable");
        }
        if (snapshot.readiness() != OwnerPopulationReadiness.READY
                || snapshot.canonicalReloadInProgress()) {
            return Decision.deny("parent-lifecycle-authority-not-ready");
        }
        if (snapshot.transitionPending()) {
            return Decision.deny("parent-lifecycle-transition-pending");
        }
        OwnerPopulationEntry entry = snapshot.entry().orElse(null);
        if (entry != null) {
            return entry.lifecycleState() == CompanionLifecycleState.ACTIVE
                    ? Decision.permit()
                    : Decision.deny("parent-lifecycle-not-active");
        }
        String syntheticProfile = "entity:" + identity.entityUuid();
        return syntheticProfile.equals(identity.profileId())
                ? Decision.permit()
                : Decision.deny("parent-lifecycle-profile-missing");
    }

    @Nullable
    private static OwnerPopulationProfileStateSnapshot runtimeSnapshot(String profileId) {
        Tamework plugin = Tamework.getInstance();
        OwnerPopulationIndex index = plugin == null ? null : plugin.getOwnerPopulationIndex();
        return index == null ? null : index.profileStateSnapshot(profileId);
    }

    @FunctionalInterface
    interface ProfileSnapshotLookup {
        @Nullable
        OwnerPopulationProfileStateSnapshot snapshot(@Nonnull String profileId);
    }

    record Decision(boolean allowed, @Nullable String reason) {
        private static Decision permit() {
            return new Decision(true, null);
        }

        private static Decision deny(String reason) {
            return new Decision(false, Objects.requireNonNull(reason, "reason"));
        }
    }
}
