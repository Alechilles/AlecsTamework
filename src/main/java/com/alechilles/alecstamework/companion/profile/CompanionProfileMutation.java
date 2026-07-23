package com.alechilles.alecstamework.companion.profile;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import java.util.HashSet;
import java.util.List;
import javax.annotation.Nonnull;

/** One typed profile create or metadata/tool-link update request. */
public sealed interface CompanionProfileMutation
        permits CompanionProfileMutation.Create, CompanionProfileMutation.Update {
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
}
