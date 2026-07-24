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
import java.util.HashSet;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** One typed profile creation, live adoption, or metadata/tool-link update request. */
public sealed interface CompanionProfileMutation
        permits CompanionProfileMutation.Create,
                CompanionProfileMutation.AdoptLive,
                CompanionProfileMutation.ReconcileLoaded,
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

    /**
     * Resolves one imported, canonical {@code UNRESOLVED} lifecycle from a sealed
     * positive loaded-entity observation.
     *
     * <p>The expected current alias is part of the evidence fence. The observed
     * alias may be newer when modern ECS identity differs from the imported
     * legacy NPC UUID; any required rotation and the lifecycle transition commit
     * in this same shared operation.</p>
     */
    record ReconcileLoaded(
            @Nonnull ProfileId profileId,
            @Nonnull LifecycleRevision expectedLifecycleRevision,
            @Nonnull ReconciliationGeneration expectedReconciliationGeneration,
            @Nonnull NpcAlias expectedCurrentAlias,
            @Nonnull NpcAlias observedAlias,
            @Nonnull String worldKey,
            long requestedAtMs
    ) implements CompanionProfileMutation {
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
