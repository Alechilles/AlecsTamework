package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves disposable command records against the canonical replacement profile projection.
 *
 * <p>A projected profile supplies durable identity and lifecycle. Before that projection exists,
 * only one exact live ECS location can authorize an action; absence, ambiguity, and probe failure
 * all fail closed.</p>
 */
final class CommandNpcIdentityService {
    enum ResolutionStatus {
        RESOLVED,
        UNRESOLVED,
        CONFLICT,
        FAILED
    }

    record DurableStateFlags(
            @Nonnull LifecycleState lifecycleState
    ) {
        private static final DurableStateFlags LIVE =
                new DurableStateFlags(LifecycleState.ACTIVE);
        private static final DurableStateFlags UNAVAILABLE =
                new DurableStateFlags(LifecycleState.UNRESOLVED);

        DurableStateFlags {
            Objects.requireNonNull(
                    lifecycleState, "Lifecycle state is required"
            );
        }

        boolean captured() {
            return lifecycleState == LifecycleState.CAPTURED;
        }

        boolean dead() {
            return lifecycleState == LifecycleState.DEAD_REVIVABLE;
        }

        boolean lost() {
            return lifecycleState == LifecycleState.LOST;
        }

        boolean inCoop() {
            return lifecycleState == LifecycleState.COOP;
        }

        boolean suppressesLiveAction() {
            return lifecycleState != LifecycleState.ACTIVE
                    && lifecycleState != LifecycleState.UNLOADED;
        }
    }

    record IdentityResolution(
            @Nonnull ResolutionStatus status,
            @Nullable String profileId,
            @Nullable String conflictingProfileId,
            @Nullable UUID cachedHistoricalUuid,
            @Nullable UUID currentNpcUuid,
            @Nonnull List<UUID> aliases,
            @Nonnull List<UUID> checkedUuids,
            @Nonnull List<UUID> liveUuids,
            @Nonnull DurableStateFlags durableState,
            @Nullable String failureReason,
            @Nullable Throwable failure
    ) {
        IdentityResolution {
            aliases = List.copyOf(aliases);
            checkedUuids = List.copyOf(checkedUuids);
            liveUuids = List.copyOf(liveUuids);
        }
    }

    record CanonicalizationResult(
            @Nonnull List<LinkedNpcRecord> records,
            @Nonnull List<IdentityResolution> resolutions
    ) {
        CanonicalizationResult {
            records = List.copyOf(records);
            resolutions = List.copyOf(resolutions);
        }

        boolean hasConflicts() {
            return resolutions.stream().anyMatch(
                    result -> result.status() == ResolutionStatus.CONFLICT
            );
        }

        boolean hasFailures() {
            return resolutions.stream().anyMatch(
                    result -> result.status() == ResolutionStatus.FAILED
            );
        }
    }

    @FunctionalInterface
    interface LiveNpcProbe {
        @Nonnull
        LoadedNpcIdentityIndex.Probe probe(@Nonnull UUID npcUuid);
    }

    private final CommandPersistenceView persistence;
    private final LiveNpcProbe liveNpcProbe;
    private final LinkedNpcRecordCollection recordCollection;

    CommandNpcIdentityService(
            @Nonnull CommandPersistenceView persistence,
            @Nonnull CommandNpcExistenceService existenceService
    ) {
        this(
                persistence,
                existenceService::probe,
                new LinkedNpcRecordCollection()
        );
    }

    CommandNpcIdentityService(
            @Nonnull CommandPersistenceView persistence,
            @Nonnull LiveNpcProbe liveNpcProbe
    ) {
        this(persistence, liveNpcProbe, new LinkedNpcRecordCollection());
    }

    CommandNpcIdentityService(
            @Nonnull CommandPersistenceView persistence,
            @Nonnull LiveNpcProbe liveNpcProbe,
            @Nonnull LinkedNpcRecordCollection recordCollection
    ) {
        this.persistence = Objects.requireNonNull(
                persistence, "Command persistence view is required"
        );
        this.liveNpcProbe = Objects.requireNonNull(
                liveNpcProbe, "Live NPC probe is required"
        );
        this.recordCollection = Objects.requireNonNull(
                recordCollection, "Linked record collection is required"
        );
    }

    @Nonnull
    IdentityResolution resolve(@Nullable LinkedNpcRecord record) {
        if (record == null || record.npcUuid == null) {
            return failed(null, null, "record_identity_required", null);
        }
        CommandPersistenceView.ProfileSnapshot projected =
                persistence.find(record).orElse(null);
        if (projected == null) {
            return resolveUnprojected(record);
        }
        String explicitProfile = LinkedNpcRecordCodec.normalizeProfileId(
                record.profileId
        );
        if (explicitProfile != null
                && !explicitProfile.equals(projected.profileId().toString())
                && !persistence.isKnownAliasForProfile(
                        explicitProfile,
                        projected.profileId()
                )) {
            return conflict(record, explicitProfile, projected);
        }
        return resolveProjected(record, projected);
    }

    @Nonnull
    LinkedNpcRecord canonicalRecord(
            @Nonnull LinkedNpcRecord original,
            @Nonnull IdentityResolution resolution
    ) {
        if (resolution.status() != ResolutionStatus.RESOLVED
                || resolution.profileId() == null) {
            return original;
        }
        UUID canonicalUuid = resolution.currentNpcUuid() != null
                ? resolution.currentNpcUuid()
                : original.npcUuid;
        return new LinkedNpcRecord(
                canonicalUuid,
                resolution.profileId(),
                original.lastKnownPosition,
                original.lastKnownWorldName,
                original.homePosition,
                original.cachedDisplayName,
                original.cachedNameKey,
                original.cachedRoleId,
                original.cachedCommandState,
                original.active,
                original.breedingEnabled,
                original.groupId
        );
    }

    @Nonnull
    CanonicalizationResult canonicalize(
            @Nullable List<LinkedNpcRecord> input
    ) {
        if (input == null || input.isEmpty()) {
            return new CanonicalizationResult(List.of(), List.of());
        }
        ArrayList<LinkedNpcRecord> original = new ArrayList<>(input.size());
        ArrayList<LinkedNpcRecord> canonical = new ArrayList<>(input.size());
        ArrayList<IdentityResolution> resolutions =
                new ArrayList<>(input.size());
        for (LinkedNpcRecord record : input) {
            if (record == null || record.npcUuid == null) {
                continue;
            }
            original.add(record);
            IdentityResolution resolution = resolve(record);
            resolutions.add(resolution);
            canonical.add(canonicalRecord(record, resolution));
        }
        boolean unsafe = resolutions.stream().anyMatch(result ->
                result.status() == ResolutionStatus.CONFLICT
                        || result.status() == ResolutionStatus.FAILED
        );
        return new CanonicalizationResult(
                unsafe
                        ? original
                        : recordCollection.deduplicate(canonical),
                resolutions
        );
    }

    @Nonnull
    private IdentityResolution resolveUnprojected(
            LinkedNpcRecord record
    ) {
        String profileId = persistence.profileId(record).toString();
        try {
            LoadedNpcIdentityIndex.Probe probe =
                    liveNpcProbe.probe(record.npcUuid);
            if (probe != null && probe.status()
                    == LoadedNpcIdentityIndex.ProbeStatus.ONE_LOCATION) {
                return new IdentityResolution(
                        ResolutionStatus.RESOLVED,
                        profileId,
                        null,
                        record.npcUuid,
                        record.npcUuid,
                        List.of(record.npcUuid),
                        List.of(record.npcUuid),
                        List.of(record.npcUuid),
                        DurableStateFlags.LIVE,
                        null,
                        null
                );
            }
            if (probe != null && probe.status()
                    == LoadedNpcIdentityIndex.ProbeStatus
                    .MULTIPLE_LOCATIONS) {
                return new IdentityResolution(
                        ResolutionStatus.CONFLICT,
                        profileId,
                        null,
                        record.npcUuid,
                        null,
                        List.of(record.npcUuid),
                        List.of(record.npcUuid),
                        List.of(record.npcUuid),
                        DurableStateFlags.UNAVAILABLE,
                        "multiple_live_locations_for_uuid",
                        null
                );
            }
            return new IdentityResolution(
                    ResolutionStatus.UNRESOLVED,
                    profileId,
                    null,
                    record.npcUuid,
                    null,
                    List.of(record.npcUuid),
                    List.of(record.npcUuid),
                    List.of(),
                    DurableStateFlags.UNAVAILABLE,
                    "canonical_profile_absent_and_live_identity_missing",
                    null
            );
        } catch (RuntimeException failure) {
            return failed(
                    profileId,
                    record.npcUuid,
                    "live_identity_probe_failed",
                    failure
            );
        }
    }

    @Nonnull
    private IdentityResolution resolveProjected(
            LinkedNpcRecord record,
            CommandPersistenceView.ProfileSnapshot projected
    ) {
        LinkedHashSet<UUID> checked = new LinkedHashSet<>();
        checked.add(record.npcUuid);
        if (projected.currentNpcUuid() != null) {
            checked.add(projected.currentNpcUuid());
        }
        ArrayList<UUID> live = new ArrayList<>(checked.size());
        boolean multipleLocations = false;
        try {
            for (UUID candidate : checked) {
                LoadedNpcIdentityIndex.Probe probe =
                        liveNpcProbe.probe(candidate);
                if (probe == null
                        || probe.status()
                        == LoadedNpcIdentityIndex.ProbeStatus.UNKNOWN) {
                    continue;
                }
                if (probe.status()
                        == LoadedNpcIdentityIndex.ProbeStatus.ONE_LOCATION) {
                    live.add(candidate);
                } else if (probe.status()
                        == LoadedNpcIdentityIndex.ProbeStatus.MULTIPLE_LOCATIONS) {
                    live.add(candidate);
                    multipleLocations = true;
                }
            }
        } catch (RuntimeException failure) {
            return failed(
                    projected.profileId().toString(),
                    record.npcUuid,
                    "live_identity_probe_failed",
                    failure
            );
        }
        DurableStateFlags durable = new DurableStateFlags(
                projected.lifecycleState()
        );
        boolean conflict = multipleLocations || live.size() > 1;
        return new IdentityResolution(
                conflict
                        ? ResolutionStatus.CONFLICT
                        : ResolutionStatus.RESOLVED,
                projected.profileId().toString(),
                null,
                record.npcUuid,
                projected.currentNpcUuid(),
                projected.currentNpcUuid() == null
                        ? List.of()
                        : List.of(projected.currentNpcUuid()),
                List.copyOf(checked),
                live,
                durable,
                multipleLocations
                        ? "multiple_live_locations_for_uuid"
                        : live.size() > 1
                        ? "multiple_live_profile_aliases"
                        : null,
                null
        );
    }

    @Nonnull
    private IdentityResolution conflict(
            LinkedNpcRecord record,
            String explicitProfile,
            CommandPersistenceView.ProfileSnapshot projected
    ) {
        return new IdentityResolution(
                ResolutionStatus.CONFLICT,
                explicitProfile,
                projected.profileId().toString(),
                record.npcUuid,
                projected.currentNpcUuid(),
                projected.currentNpcUuid() == null
                        ? List.of()
                        : List.of(projected.currentNpcUuid()),
                List.of(record.npcUuid),
                List.of(),
                DurableStateFlags.UNAVAILABLE,
                "record_profile_conflicts_with_current_alias",
                null
        );
    }

    @Nonnull
    private IdentityResolution failed(
            @Nullable String profileId,
            @Nullable UUID cachedUuid,
            @Nonnull String reason,
            @Nullable Throwable failure
    ) {
        return new IdentityResolution(
                ResolutionStatus.FAILED,
                profileId,
                null,
                cachedUuid,
                null,
                List.of(),
                cachedUuid == null ? List.of() : List.of(cachedUuid),
                List.of(),
                DurableStateFlags.UNAVAILABLE,
                reason,
                failure
        );
    }
}
