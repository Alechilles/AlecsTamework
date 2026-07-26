package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture
        .CaptureCommandAccessEvidence;
import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRoster;
import com.alechilles.alecstamework.companion.command.CommandRosterHome;
import com.alechilles.alecstamework.companion.command
        .CommandRosterMembership;
import com.alechilles.alecstamework.companion.command
        .CommandRosterSlotId;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonPolicy;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.population.group
        .PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group
        .PopulationGroupPolicy;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.population
        .PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.items.persistence
        .SpawnerTameAndLinkIntentEvidence.CommandActivationEvidence;
import com.alechilles.alecstamework.items.persistence
        .SpawnerTameAndLinkIntentEvidence.OwnerPopulationEvidence;
import com.alechilles.alecstamework.items.persistence
        .SpawnerTameAndLinkIntentEvidence.PopulationGroupCountEvidence;
import com.alechilles.alecstamework.items.persistence
        .SpawnerTameAndLinkIntentEvidence.PopulationGroupEvidence;
import com.alechilles.alecstamework.items.persistence
        .SpawnerTameAndLinkIntentEvidence.TargetEvidence;
import com.alechilles.alecstamework.persistence.authoring
        .ReplacementFeaturePolicySource;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.runtime
        .PublicPersistenceQueries;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Production world-thread evidence source for in-place tame/link capture.
 *
 * <p>Every mutable Hytale and projection authority is copied into immutable
 * values before this method returns. It performs no persistence reads, player
 * scans, asynchronous waits, or gameplay effects. The durable SQLite
 * transaction remains the final reservation and duplicate authority.</p>
 */
public final class TameworkSpawnerTameAndLinkEvidenceSource
        implements SpawnerTameAndLinkEvidenceSource {
    private final ConfigSource configs;
    private final ProjectionSource projections;
    private final LiveTargetSource live;
    private final ThreadLocal<String> lastFailureReason = new ThreadLocal<>();

    public TameworkSpawnerTameAndLinkEvidenceSource(
            @Nonnull PublicPersistenceQueries queries,
            @Nonnull PopulationGroupConfigRegistry groups,
            @Nonnull ItemFeatureRegistry items,
            @Nonnull CommandItemRegistry commands,
            @Nonnull ReplacementFeaturePolicySource policies,
            @Nonnull TameworkFullStateSnapshotReader snapshots
    ) {
        this(
                new SpawnerTameAndLinkConfigSource(
                        groups, items, commands, policies
                ),
                new SpawnerTameAndLinkProjectionSource(queries),
                new SpawnerTameAndLinkLiveTargetSource(snapshots)
        );
    }

    TameworkSpawnerTameAndLinkEvidenceSource(
            ConfigSource configs,
            ProjectionSource projections,
            LiveTargetSource live
    ) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.projections = Objects.requireNonNull(
                projections, "projections"
        );
        this.live = Objects.requireNonNull(live, "live");
    }

    @Override
    @Nullable
    public SpawnerTameAndLinkIntentEvidence freeze(
            @Nonnull SpawnerTameAndLinkIntentFactory.Input input
    ) {
        lastFailureReason.remove();
        if (input == null) {
            lastFailureReason.set("invalid-input");
            return null;
        }
        try {
            ConfigSnapshot config = configs.freeze(input);
            if (config == null) {
                lastFailureReason.set("config-evidence-unavailable");
                return null;
            }
            LiveTargetSnapshot target = live.freeze(input, config);
            if (target == null) {
                lastFailureReason.set("live-target-evidence-unavailable");
                return null;
            }
            ProjectionSnapshot projection =
                    projections.freeze(input, config);
            if (projection == null) {
                lastFailureReason.set("projection-evidence-unavailable");
                return null;
            }
            return evidence(input, config, target, projection);
        } catch (RuntimeException | LinkageError failure) {
            lastFailureReason.set("evidence-freeze-exception");
            return null;
        }
    }

    @Override
    @Nullable
    public String lastFailureReason() {
        return lastFailureReason.get();
    }

    private SpawnerTameAndLinkIntentEvidence evidence(
            SpawnerTameAndLinkIntentFactory.Input input,
            ConfigSnapshot config,
            LiveTargetSnapshot target,
            ProjectionSnapshot projection
    ) {
        OwnerId owner = new OwnerId(input.actorUuid());
        return new SpawnerTameAndLinkIntentEvidence(
                new TargetEvidence(
                        owner,
                        input.actorName(),
                        config.targetRoleId(),
                        target.metadataJson(),
                        target.expectedStateHash(),
                        target.targetStateHash(),
                        config.commandAccess()
                ),
                projection.ownerPopulation(),
                new PopulationGroupEvidence(
                        projection.currentAssignment(),
                        config.groupPolicyRevision(),
                        config.groupPolicies(),
                        projection.groupCounts()
                ),
                new CommandActivationEvidence(
                        projection.expectedRosterRevision(),
                        projection.currentRoster(),
                        projection.existingProfileMembership(),
                        projection.existingSlotMembership(),
                        projection.existingTimedLease(),
                        projection.slotId(),
                        config.familyKey(),
                        null,
                        true,
                        target.home(),
                        config.timedPolicy()
                )
        );
    }

    @FunctionalInterface
    interface ConfigSource {
        @Nullable
        ConfigSnapshot freeze(SpawnerTameAndLinkIntentFactory.Input input);
    }

    @FunctionalInterface
    interface ProjectionSource {
        @Nullable
        ProjectionSnapshot freeze(
                SpawnerTameAndLinkIntentFactory.Input input,
                ConfigSnapshot config
        );
    }

    @FunctionalInterface
    interface LiveTargetSource {
        @Nullable
        LiveTargetSnapshot freeze(
                SpawnerTameAndLinkIntentFactory.Input input,
                ConfigSnapshot config
        );
    }

    record ConfigSnapshot(
            @Nonnull String targetRoleId,
            int globalOwnerLimit,
            int perWorldOwnerLimit,
            long groupPolicyRevision,
            @Nonnull List<PopulationGroupPolicy> groupPolicies,
            @Nonnull CommandFamilyKey familyKey,
            @Nonnull CaptureCommandAccessEvidence commandAccess,
            @Nonnull TimedSummonPolicy timedPolicy
    ) {
        ConfigSnapshot {
            if (targetRoleId == null || targetRoleId.isBlank()
                    || globalOwnerLimit < 0 || perWorldOwnerLimit < 0
                    || groupPolicyRevision < 0 || groupPolicies == null
                    || familyKey == null || commandAccess == null
                    || timedPolicy == null) {
                throw new IllegalArgumentException(
                        "Complete tame/link config snapshot is required"
                );
            }
            targetRoleId = targetRoleId.trim();
            groupPolicies = List.copyOf(groupPolicies);
        }
    }

    record ProjectionSnapshot(
            @Nonnull OwnerPopulationEvidence ownerPopulation,
            @Nullable PopulationGroupAssignment currentAssignment,
            @Nonnull List<PopulationGroupCountEvidence> groupCounts,
            long expectedRosterRevision,
            @Nullable CommandRoster currentRoster,
            @Nullable CommandRosterMembership existingProfileMembership,
            @Nullable CommandRosterMembership existingSlotMembership,
            @Nullable TimedSummonLease existingTimedLease,
            @Nonnull CommandRosterSlotId slotId
    ) {
        ProjectionSnapshot {
            if (ownerPopulation == null || groupCounts == null
                    || expectedRosterRevision < 0 || slotId == null) {
                throw new IllegalArgumentException(
                        "Complete tame/link projection snapshot is required"
                );
            }
            groupCounts = List.copyOf(groupCounts);
        }
    }

    record LiveTargetSnapshot(
            @Nonnull String metadataJson,
            @Nonnull Sha256Hash expectedStateHash,
            @Nonnull Sha256Hash targetStateHash,
            @Nullable CommandRosterHome home
    ) {
        LiveTargetSnapshot {
            if (metadataJson == null || metadataJson.isBlank()
                    || expectedStateHash == null
                    || targetStateHash == null) {
                throw new IllegalArgumentException(
                        "Complete tame/link live target snapshot is required"
                );
            }
        }
    }
}
