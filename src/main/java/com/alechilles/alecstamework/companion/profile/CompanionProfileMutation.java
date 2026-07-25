package com.alechilles.alecstamework.companion.profile;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.util.HashSet;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** One typed profile creation, live adoption, or metadata/tool-link update request. */
public sealed interface CompanionProfileMutation
        permits CompanionProfileMutation.Create,
                CompanionProfileMutation.AdoptLive,
                CompanionProfileMutation.StartupReconciliation,
                CompanionProfileMutation.RecoverImportedMissing,
                CompanionProfileMutation.Update {
    @Nonnull
    ProfileId profileId();

    long requestedAtMs();

    /** Creates stable identity, canonical lifecycle, and the initial complete tool-link set. */
    record Create(
            @Nonnull CompanionIdentity identity,
            @Nonnull CompanionLifecycle lifecycle,
            @Nonnull List<CompanionToolLink> toolLinks,
            long requestedAtMs
    ) implements CompanionProfileMutation {
        public Create {
            if (identity == null || lifecycle == null) {
                throw new IllegalArgumentException(
                        "Profile create identity and lifecycle are required"
                );
            }
            if (!identity.profileId().equals(lifecycle.profileId())
                    || identity.metadataRevision() != 0
                    || !lifecycle.revision().equals(LifecycleRevision.INITIAL)) {
                throw new IllegalArgumentException(
                        "Profile create must use one profile and initial revisions"
                );
            }
            toolLinks = validateLinks(identity.profileId(), toolLinks);
        }

        @Override
        public ProfileId profileId() {
            return identity.profileId();
        }
    }

    /**
     * Atomically adopts one already-live companion into canonical persistence.
     *
     * <p>The initial lifecycle is deliberately derived rather than caller-supplied so identity,
     * alias generation zero, owner, world, and ACTIVE location cannot disagree.</p>
     */
    record AdoptLive(
            @Nonnull CompanionIdentity identity,
            @Nonnull NpcAlias alias,
            @Nullable OwnerId ownerId,
            @Nonnull String worldKey,
            @Nonnull List<CompanionToolLink> toolLinks,
            long requestedAtMs
    ) implements CompanionProfileMutation {
        public AdoptLive {
            if (identity == null || alias == null) {
                throw new IllegalArgumentException(
                        "Live adoption identity and alias are required"
                );
            }
            worldKey = normalizeWorldKey(worldKey);
            if (identity.metadataRevision() != 0
                    || !worldKey.equals(identity.lastKnownWorldKey())) {
                throw new IllegalArgumentException(
                        "Live adoption identity must begin at revision zero in its exact world"
                );
            }
            toolLinks = validateLinks(identity.profileId(), toolLinks);
        }

        @Override
        public ProfileId profileId() {
            return identity.profileId();
        }

        /** Returns the only valid initial lifecycle for this live adoption. */
        @Nonnull
        public CompanionLifecycle initialLifecycle() {
            return new CompanionLifecycle(
                    profileId(),
                    ownerId,
                    LifecycleState.ACTIVE,
                    LifecycleLocation.liveEntity(alias.toString(), worldKey),
                    LifecycleRevision.INITIAL,
                    null,
                    requestedAtMs,
                    ReconciliationGeneration.INITIAL,
                    null,
                    ownerId == null ? null : worldKey
            );
        }
    }

    /** Revision-fenced startup resolution shared by loaded and unloaded evidence. */
    sealed interface StartupReconciliation extends CompanionProfileMutation
            permits ReconcileLoaded, ReconcileUnloaded {
        @Nonnull
        LifecycleRevision expectedLifecycleRevision();

        @Nonnull
        ReconciliationGeneration expectedReconciliationGeneration();

        @Nonnull
        NpcAlias expectedCurrentAlias();

        @Nonnull
        CompanionLifecycle resolvedLifecycle(
                @Nonnull CompanionLifecycle current
        );
    }

    /**
     * Reconciles canonical lifecycle location from a sealed positive
     * loaded-entity observation.
     *
     * <p>An imported {@code UNRESOLVED} lifecycle may adopt a newer observed
     * alias during startup. An {@code UNLOADED} lifecycle may return to active,
     * and an {@code ACTIVE} lifecycle may update its world, only for the exact
     * current alias. The alias and lifecycle revisions are evidence fences, so
     * stale runtime observations become no-ops.</p>
     */
    record ReconcileLoaded(
            @Nonnull ProfileId profileId,
            @Nonnull LifecycleRevision expectedLifecycleRevision,
            @Nonnull ReconciliationGeneration expectedReconciliationGeneration,
            @Nonnull NpcAlias expectedCurrentAlias,
            @Nonnull NpcAlias observedAlias,
            @Nonnull String worldKey,
            long requestedAtMs
    ) implements StartupReconciliation {
        public ReconcileLoaded {
            if (profileId == null || expectedLifecycleRevision == null
                    || expectedReconciliationGeneration == null
                    || expectedCurrentAlias == null || observedAlias == null) {
                throw new IllegalArgumentException(
                        "Complete loaded-profile reconciliation evidence is required"
                );
            }
            worldKey = normalizeWorldKey(worldKey);
        }

        /** Builds the only valid resolved lifecycle while retaining owner authority. */
        @Nonnull
        public CompanionLifecycle resolvedLifecycle(
                @Nonnull CompanionLifecycle current
        ) {
            if (current == null || !profileId.equals(current.profileId())
                    || !expectedLifecycleRevision.equals(current.revision())
                    || !expectedReconciliationGeneration.equals(
                    current.lastReconciledGeneration()
            )) {
                throw new IllegalArgumentException(
                        "Loaded-profile reconciliation lifecycle fence mismatch"
                );
            }
            return new CompanionLifecycle(
                    profileId,
                    current.ownerId(),
                    LifecycleState.ACTIVE,
                    LifecycleLocation.liveEntity(
                            observedAlias.toString(),
                            worldKey
                    ),
                    current.revision().next(),
                    null,
                    requestedAtMs,
                    current.lastReconciledGeneration().next(),
                    null,
                    current.ownerWorldKey()
            );
        }
    }

    /**
     * Resolves one imported profile as unloaded from sealed startup-world
     * absence while retaining its current alias and owner authority.
     */
    record ReconcileUnloaded(
            @Nonnull ProfileId profileId,
            @Nonnull LifecycleRevision expectedLifecycleRevision,
            @Nonnull ReconciliationGeneration expectedReconciliationGeneration,
            @Nonnull NpcAlias expectedCurrentAlias,
            long requestedAtMs
    ) implements StartupReconciliation {
        public ReconcileUnloaded {
            if (profileId == null || expectedLifecycleRevision == null
                    || expectedReconciliationGeneration == null
                    || expectedCurrentAlias == null) {
                throw new IllegalArgumentException(
                        "Complete unloaded-profile reconciliation evidence is required"
                );
            }
        }

        @Override
        @Nonnull
        public CompanionLifecycle resolvedLifecycle(
                @Nonnull CompanionLifecycle current
        ) {
            if (current == null || !profileId.equals(current.profileId())
                    || !expectedLifecycleRevision.equals(current.revision())
                    || !expectedReconciliationGeneration.equals(
                    current.lastReconciledGeneration()
            )) {
                throw new IllegalArgumentException(
                        "Unloaded-profile reconciliation lifecycle fence mismatch"
                );
            }
            return new CompanionLifecycle(
                    profileId,
                    current.ownerId(),
                    LifecycleState.UNLOADED,
                    LifecycleLocation.none(),
                    current.revision().next(),
                    null,
                    requestedAtMs,
                    current.lastReconciledGeneration().next(),
                    null,
                    current.ownerWorldKey()
            );
        }
    }

    /**
     * Converts one exact public-import recovery artifact after an explicit
     * recall exhausted every normal relocation attempt.
     *
     * <p>This is deliberately not generic missing-entity inference. It applies
     * only to the single-use released-coop evidence retained by the importer,
     * and the transaction rechecks every lifecycle, alias, owner, snapshot ID,
     * and payload-hash fence before transitioning to {@code LOST}.</p>
     */
    record RecoverImportedMissing(
            @Nonnull ProfileId profileId,
            @Nonnull LifecycleRevision expectedLifecycleRevision,
            long expectedMetadataRevision,
            @Nonnull NpcAlias expectedCurrentAlias,
            @Nonnull OwnerId expectedOwnerId,
            @Nonnull SnapshotId recoverySnapshotId,
            @Nonnull Sha256Hash recoveryPayloadHash,
            long recallQueuedAtMs,
            long requestedAtMs
    ) implements CompanionProfileMutation {
        public RecoverImportedMissing {
            if (profileId == null || expectedLifecycleRevision == null
                    || expectedCurrentAlias == null || expectedOwnerId == null
                    || recoverySnapshotId == null
                    || recoveryPayloadHash == null) {
                throw new IllegalArgumentException(
                        "Complete imported recall recovery evidence is required"
                );
            }
            if (expectedMetadataRevision < 0) {
                throw new IllegalArgumentException(
                        "Imported recall recovery metadata revision is invalid"
                );
            }
            if (recallQueuedAtMs > requestedAtMs) {
                throw new IllegalArgumentException(
                        "Imported recall recovery time ordering is invalid"
                );
            }
        }

        @Nonnull
        public CompanionLifecycle resolvedLifecycle(
                @Nonnull CompanionLifecycle current
        ) {
            if (current == null || !profileId.equals(current.profileId())
                    || !expectedLifecycleRevision.equals(current.revision())
                    || !expectedOwnerId.equals(current.ownerId())) {
                throw new IllegalArgumentException(
                        "Imported recall recovery lifecycle fence mismatch"
                );
            }
            return new CompanionLifecycle(
                    profileId,
                    current.ownerId(),
                    LifecycleState.LOST,
                    LifecycleLocation.none(),
                    current.revision().next(),
                    null,
                    requestedAtMs,
                    current.lastReconciledGeneration(),
                    null,
                    current.ownerWorldKey()
            );
        }
    }

    /** Revision-fenced identity update plus complete tool-link replacement. */
    record Update(
            @Nonnull CompanionIdentity nextIdentity,
            long expectedMetadataRevision,
            @Nonnull List<CompanionToolLink> toolLinks,
            long requestedAtMs
    ) implements CompanionProfileMutation {
        public Update {
            if (nextIdentity == null
                    || expectedMetadataRevision < 0
                    || expectedMetadataRevision == Long.MAX_VALUE
                    || nextIdentity.metadataRevision() != expectedMetadataRevision + 1) {
                throw new IllegalArgumentException(
                        "Profile update must advance one valid expected metadata revision"
                );
            }
            toolLinks = validateLinks(nextIdentity.profileId(), toolLinks);
        }

        @Override
        public ProfileId profileId() {
            return nextIdentity.profileId();
        }
    }

    private static List<CompanionToolLink> validateLinks(
            ProfileId profileId,
            List<CompanionToolLink> links
    ) {
        if (links == null) {
            throw new IllegalArgumentException("Complete profile tool-link set is required");
        }
        HashSet<String> keys = new HashSet<>();
        java.util.ArrayList<CompanionToolLink> normalized = new java.util.ArrayList<>();
        for (CompanionToolLink link : links) {
            String key = link == null ? null : link.toolId() + "|" + link.linkType();
            if (link == null || !link.profileId().equals(profileId) || !keys.add(key)) {
                throw new IllegalArgumentException(
                        "Profile tool links must be complete, unique, and profile-scoped"
                );
            }
            normalized.add(link);
        }
        normalized.sort(java.util.Comparator
                .comparing((CompanionToolLink link) -> link.toolId().toString())
                .thenComparing(CompanionToolLink::linkType));
        return List.copyOf(normalized);
    }

    private static String normalizeWorldKey(String worldKey) {
        if (worldKey == null || worldKey.isBlank()) {
            throw new IllegalArgumentException("Live adoption world key is required");
        }
        return worldKey.trim();
    }
}
