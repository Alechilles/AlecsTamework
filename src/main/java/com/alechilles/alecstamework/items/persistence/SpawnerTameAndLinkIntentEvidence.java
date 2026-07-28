package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CaptureCommandAccessEvidence;
import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRoster;
import com.alechilles.alecstamework.companion.command.CommandRosterHome;
import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonPolicy;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.population.OwnerPopulationScope;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupBucket;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupCounts;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.util.HashSet;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable non-profile facts frozen for one successful in-place tame/link attempt.
 *
 * <p>The canonical identity and lifecycle are intentionally absent. They are read after alias
 * canonicalization and joined with these facts by the request factory.</p>
 */
public record SpawnerTameAndLinkIntentEvidence(
        @Nonnull TargetEvidence target,
        @Nonnull OwnerPopulationEvidence ownerPopulation,
        @Nonnull PopulationGroupEvidence groups,
        @Nonnull CommandActivationEvidence command
) {
    public SpawnerTameAndLinkIntentEvidence {
        if (target == null || ownerPopulation == null
                || groups == null || command == null) {
            throw new IllegalArgumentException(
                    "Complete tame/link intent evidence is required"
            );
        }
    }

    /** Desired identity and exact live-state convergence facts. */
    public record TargetEvidence(
            @Nonnull OwnerId ownerId,
            @Nonnull String ownerName,
            @Nonnull String roleId,
            @Nonnull String metadataJson,
            @Nonnull Sha256Hash expectedLiveStateHash,
            @Nonnull Sha256Hash targetLiveStateHash,
            @Nonnull CaptureCommandAccessEvidence commandAccess
    ) {
        public TargetEvidence {
            ownerName = text(ownerName, "Target owner name");
            roleId = text(roleId, "Target role ID");
            metadataJson = text(metadataJson, "Target metadata JSON");
            if (ownerId == null || expectedLiveStateHash == null
                    || targetLiveStateHash == null
                    || commandAccess == null) {
                throw new IllegalArgumentException(
                        "Complete tame/link target evidence is required"
                );
            }
        }
    }

    /** Owner limits plus complete authoritative counts for every increasing scope. */
    public record OwnerPopulationEvidence(
            int globalLimit,
            int perWorldLimit,
            @Nonnull List<OwnerPopulationCountEvidence> counts
    ) {
        public OwnerPopulationEvidence {
            if (globalLimit < 0 || perWorldLimit < 0 || counts == null
                    || counts.stream().anyMatch(
                    java.util.Objects::isNull
            )) {
                throw new IllegalArgumentException(
                        "Complete owner population evidence is required"
                );
            }
            counts = List.copyOf(counts);
        }
    }

    /** One owner-scope committed and pending count snapshot. */
    public record OwnerPopulationCountEvidence(
            @Nonnull OwnerPopulationScope scope,
            long committedCount,
            long pendingCount
    ) {
        public OwnerPopulationCountEvidence {
            if (scope == null || committedCount < 0 || pendingCount < 0) {
                throw new IllegalArgumentException(
                        "Non-negative owner population counts are required"
                );
            }
        }
    }

    /** Complete role policy, current assignment, and exact target-bucket counts. */
    public record PopulationGroupEvidence(
            @Nullable PopulationGroupAssignment currentAssignment,
            long policyRevision,
            @Nonnull List<PopulationGroupPolicy> policies,
            @Nonnull List<PopulationGroupCountEvidence> counts
    ) {
        public PopulationGroupEvidence {
            if (policyRevision < 0 || policies == null || counts == null
                    || counts.stream().anyMatch(
                    java.util.Objects::isNull
            )) {
                throw new IllegalArgumentException(
                        "Complete population group evidence is required"
                );
            }
            HashSet<PopulationGroupPolicy> unique = new HashSet<>(policies);
            if (unique.size() != policies.size()
                    || policies.stream().anyMatch(policy ->
                    policy == null
                            || policy.policyRevision() != policyRevision)) {
                throw new IllegalArgumentException(
                        "Group policies must be unique and current"
                );
            }
            policies = List.copyOf(policies);
            counts = List.copyOf(counts);
        }
    }

    /** One target group bucket and its authoritative committed/pending counts. */
    public record PopulationGroupCountEvidence(
            @Nonnull PopulationGroupBucket bucket,
            @Nonnull PopulationGroupCounts counts
    ) {
        public PopulationGroupCountEvidence {
            if (bucket == null || counts == null) {
                throw new IllegalArgumentException(
                        "Complete population group counts are required"
                );
            }
        }
    }

    /**
     * Exact roster source, duplicate fences, desired first membership, and timed policy.
     *
     * <p>The profile ID and change time are assigned from canonical request-factory evidence.</p>
     */
    public record CommandActivationEvidence(
            long expectedRosterRevision,
            @Nullable CommandRoster currentRoster,
            @Nullable CommandRosterMembership existingProfileMembership,
            @Nullable CommandRosterMembership existingSlotMembership,
            @Nullable TimedSummonLease existingTimedLease,
            @Nonnull CommandRosterSlotId slotId,
            @Nonnull CommandFamilyKey familyKey,
            @Nullable String groupId,
            boolean activeForBulkCommands,
            @Nullable CommandRosterHome home,
            @Nonnull TimedSummonPolicy timedPolicy
    ) {
        public CommandActivationEvidence {
            if (expectedRosterRevision < 0 || slotId == null
                    || familyKey == null || timedPolicy == null) {
                throw new IllegalArgumentException(
                        "Complete command activation evidence is required"
                );
            }
            groupId = groupId == null || groupId.isBlank()
                    ? null
                    : groupId.trim();
        }
    }

    private static String text(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
