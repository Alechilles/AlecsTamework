package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import com.alechilles.alecstamework.persistence.sqlite.NpcIdentityRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves command records to stable profiles while failing closed on ambiguous durable or live state.
 *
 * <p>This service is intended for command/event boundaries. Its adapters keep SQLite and loaded-world
 * access out of the identity rules, allowing deterministic tests and avoiding accidental tick polling.
 */
final class CommandNpcIdentityService {
    enum ResolutionStatus {
        RESOLVED,
        UNRESOLVED,
        CONFLICT,
        FAILED
    }

    record DurableStateFlags(boolean captured,
                             boolean dead,
                             boolean lost,
                             @Nullable UUID lostReplacementUuid,
                             boolean legacyCoop,
                             boolean managedCoop,
                             boolean managedCoopProjectionRelocatable,
                             boolean activeRecovery) {
        private static final DurableStateFlags NONE =
                new DurableStateFlags(false, false, false, null, false, false, false, false);

        boolean lostAwaitingRecovery() {
            return lost && lostReplacementUuid == null;
        }

        boolean suppressesReplacement() {
            return captured || dead || lostReplacementUuid != null
                    || legacyCoop || managedCoop || activeRecovery;
        }

        /** A released projection remains managed, but is eligible for normal command relocation. */
        boolean managedCoopBlocksRelocation() {
            return managedCoop && !managedCoopProjectionRelocatable;
        }
    }

    record IdentityResolution(@Nonnull ResolutionStatus status,
                              @Nullable String profileId,
                              @Nullable String conflictingProfileId,
                              @Nullable UUID cachedHistoricalUuid,
                              @Nullable UUID currentNpcUuid,
                              @Nonnull List<UUID> aliases,
                              @Nonnull List<UUID> checkedUuids,
                              @Nonnull List<UUID> liveUuids,
                              boolean historicalUuidKnown,
                              @Nonnull DurableStateFlags durableState,
                              boolean replacementAllowed,
                              @Nullable String failureReason,
                              @Nullable Throwable failure) {
        IdentityResolution {
            aliases = List.copyOf(aliases);
            checkedUuids = List.copyOf(checkedUuids);
            liveUuids = List.copyOf(liveUuids);
        }
    }

    record CanonicalizationResult(@Nonnull List<LinkedNpcRecord> records,
                                  @Nonnull List<IdentityResolution> resolutions) {
        CanonicalizationResult {
            records = List.copyOf(records);
            resolutions = List.copyOf(resolutions);
        }

        boolean hasConflicts() {
            return resolutions.stream().anyMatch(result -> result.status() == ResolutionStatus.CONFLICT);
        }

        boolean hasFailures() {
            return resolutions.stream().anyMatch(result -> result.status() == ResolutionStatus.FAILED);
        }
    }

    @FunctionalInterface
    interface ProfileIdentityLoader {
        NpcIdentityRepository.IdentityLoadResult load(@Nullable String profileId,
                                                       @Nullable UUID historicalUuid);
    }

    @FunctionalInterface
    interface LiveNpcProbe {
        @Nonnull
        LoadedNpcIdentityIndex.Probe probe(@Nonnull UUID npcUuid);
    }

    private final ProfileIdentityLoader identityLoader;
    private final LiveNpcProbe liveNpcProbe;
    private final LinkedNpcRecordCollection recordCollection;

    CommandNpcIdentityService(@Nonnull NpcIdentityRepository identityRepository,
                              @Nonnull CommandNpcExistenceService existenceService) {
        this(identityRepository::load,
                existenceService::probe,
                new LinkedNpcRecordCollection());
    }

    CommandNpcIdentityService(@Nonnull ProfileIdentityLoader identityLoader,
                              @Nonnull LiveNpcProbe liveNpcProbe) {
        this(identityLoader, liveNpcProbe, new LinkedNpcRecordCollection());
    }

    CommandNpcIdentityService(@Nonnull ProfileIdentityLoader identityLoader,
                              @Nonnull LiveNpcProbe liveNpcProbe,
                              @Nonnull LinkedNpcRecordCollection recordCollection) {
        this.identityLoader = Objects.requireNonNull(identityLoader, "identityLoader");
        this.liveNpcProbe = Objects.requireNonNull(liveNpcProbe, "liveNpcProbe");
        this.recordCollection = Objects.requireNonNull(recordCollection, "recordCollection");
    }

    @Nonnull
    IdentityResolution resolve(@Nullable LinkedNpcRecord record) {
        if (record == null || record.npcUuid == null) {
            return failed(null, null, "record_identity_required", null);
        }
        NpcIdentityRepository.IdentityLoadResult loaded;
        try {
            loaded = identityLoader.load(record.profileId, record.npcUuid);
        } catch (RuntimeException exception) {
            return failed(record.profileId, record.npcUuid, "identity_loader_threw", exception);
        }
        if (loaded == null) {
            return failed(record.profileId, record.npcUuid, "identity_loader_returned_null", null);
        }
        return switch (loaded.status()) {
            case FOUND -> resolveFound(record, loaded.identity());
            case NOT_FOUND -> unresolved(record);
            case CONFLICT -> repositoryConflict(record, loaded);
            case FAILED -> failed(record.profileId, record.npcUuid,
                    loaded.failureReason(), loaded.failure());
        };
    }

    @Nonnull
    LinkedNpcRecord canonicalRecord(@Nonnull LinkedNpcRecord original,
                                    @Nonnull IdentityResolution resolution) {
        if (resolution.status() != ResolutionStatus.RESOLVED || resolution.profileId() == null) {
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
    CanonicalizationResult canonicalize(@Nullable List<LinkedNpcRecord> input) {
        if (input == null || input.isEmpty()) {
            return new CanonicalizationResult(List.of(), List.of());
        }
        ArrayList<LinkedNpcRecord> canonical = new ArrayList<>(input.size());
        ArrayList<LinkedNpcRecord> originals = new ArrayList<>(input.size());
        ArrayList<IdentityResolution> resolutions = new ArrayList<>(input.size());
        for (LinkedNpcRecord record : input) {
            if (record == null || record.npcUuid == null) {
                continue;
            }
            originals.add(record);
            IdentityResolution resolution = resolve(record);
            resolutions.add(resolution);
            canonical.add(canonicalRecord(record, resolution));
        }
        boolean unsafeToRewrite = resolutions.stream().anyMatch(resolution ->
                resolution.status() == ResolutionStatus.CONFLICT
                        || resolution.status() == ResolutionStatus.FAILED);
        List<LinkedNpcRecord> output = unsafeToRewrite
                ? List.copyOf(originals)
                : recordCollection.deduplicate(canonical);
        return new CanonicalizationResult(output, resolutions);
    }

    @Nonnull
    private IdentityResolution resolveFound(@Nonnull LinkedNpcRecord record,
                                            @Nullable NpcIdentityRepository.ProfileIdentity identity) {
        if (identity == null) {
            return failed(record.profileId, record.npcUuid, "found_identity_missing_payload", null);
        }
        DurableStateFlags durable = durableFlags(identity);
        LinkedHashSet<UUID> checked = new LinkedHashSet<>(identity.aliases());
        if (identity.currentNpcUuid() != null) {
            checked.add(identity.currentNpcUuid());
        }
        addEvidence(checked, identity.flags().lostReplacementUuid());
        addManagedEvidence(checked, identity.managedAssignment());
        addRecoveryEvidence(checked, identity.activeRecovery());
        if (!identity.historicalUuidKnown()) {
            checked.add(record.npcUuid);
        }
        ArrayList<UUID> live = new ArrayList<>();
        boolean unknownPresence = false;
        boolean multipleLocations = false;
        try {
            for (UUID candidate : checked) {
                if (candidate == null) {
                    continue;
                }
                LoadedNpcIdentityIndex.Probe probe = liveNpcProbe.probe(candidate);
                if (probe == null) {
                    return failedFound(
                            record, identity, checked, live, durable,
                            "live_identity_probe_returned_null", null
                    );
                }
                switch (probe.status()) {
                    case UNKNOWN -> unknownPresence = true;
                    case ABSENT -> {
                    }
                    case ONE_LOCATION -> live.add(candidate);
                    case MULTIPLE_LOCATIONS -> {
                        live.add(candidate);
                        multipleLocations = true;
                    }
                }
            }
        } catch (RuntimeException exception) {
            return failedFound(
                    record, identity, checked, live, durable,
                    "live_identity_probe_failed", exception
            );
        }
        if (unknownPresence) {
            return failedFound(
                    record, identity, checked, live, durable,
                    "loaded_identity_index_incomplete", null
            );
        }
        ResolutionStatus status = multipleLocations || live.size() > 1
                ? ResolutionStatus.CONFLICT
                : ResolutionStatus.RESOLVED;
        String reason = multipleLocations
                ? "multiple_live_locations_for_uuid"
                : live.size() > 1 ? "multiple_live_profile_aliases" : null;
        boolean replacementAllowed = status == ResolutionStatus.RESOLVED
                && live.isEmpty()
                && durable.lostAwaitingRecovery()
                && !durable.suppressesReplacement();
        return new IdentityResolution(
                status, identity.profileId(), null, record.npcUuid, identity.currentNpcUuid(),
                identity.aliases(), List.copyOf(checked), live, identity.historicalUuidKnown(),
                durable, replacementAllowed, reason, null
        );
    }

    @Nonnull
    private IdentityResolution unresolved(@Nonnull LinkedNpcRecord record) {
        return new IdentityResolution(
                ResolutionStatus.UNRESOLVED, record.profileId, null, record.npcUuid, null,
                List.of(), List.of(record.npcUuid), List.of(), false,
                DurableStateFlags.NONE, false, "profile_not_found", null
        );
    }

    @Nonnull
    private IdentityResolution repositoryConflict(
            @Nonnull LinkedNpcRecord record,
            @Nonnull NpcIdentityRepository.IdentityLoadResult loaded) {
        String requested = loaded.requestedProfileId() != null
                ? loaded.requestedProfileId()
                : record.profileId;
        return new IdentityResolution(
                ResolutionStatus.CONFLICT, requested, loaded.uuidProfileId(), record.npcUuid, null,
                List.of(), List.of(record.npcUuid), List.of(), false,
                DurableStateFlags.NONE, false, loaded.failureReason(), loaded.failure()
        );
    }

    @Nonnull
    private IdentityResolution failedFound(@Nonnull LinkedNpcRecord record,
                                           @Nonnull NpcIdentityRepository.ProfileIdentity identity,
                                           @Nonnull LinkedHashSet<UUID> checked,
                                           @Nonnull List<UUID> live,
                                           @Nonnull DurableStateFlags durable,
                                           @Nonnull String reason,
                                           @Nullable Throwable failure) {
        return new IdentityResolution(
                ResolutionStatus.FAILED, identity.profileId(), null, record.npcUuid,
                identity.currentNpcUuid(), identity.aliases(), List.copyOf(checked), live,
                identity.historicalUuidKnown(), durable, false, reason, failure
        );
    }

    @Nonnull
    private IdentityResolution failed(@Nullable String profileId,
                                      @Nullable UUID cachedUuid,
                                      @Nullable String reason,
                                      @Nullable Throwable failure) {
        String resolvedReason = reason == null || reason.isBlank() ? "identity_read_failed" : reason;
        return new IdentityResolution(
                ResolutionStatus.FAILED, profileId, null, cachedUuid, null,
                List.of(), cachedUuid != null ? List.of(cachedUuid) : List.of(), List.of(), false,
                DurableStateFlags.NONE, false, resolvedReason, failure
        );
    }

    @Nonnull
    private DurableStateFlags durableFlags(@Nonnull NpcIdentityRepository.ProfileIdentity identity) {
        NpcIdentityRepository.ProfileFlags flags = identity.flags();
        NpcIdentityRepository.ManagedAssignment assignment = identity.managedAssignment();
        return new DurableStateFlags(
                flags.captured(), flags.dead(), flags.lost(), flags.lostReplacementUuid(),
                flags.legacyInCoop(),
                assignment != null,
                assignment != null
                        && assignment.state() == ResidentState.DEPLOYED
                        && Objects.equals(assignment.deployedNpcUuid(), identity.currentNpcUuid())
                        && Objects.equals(assignment.residentUuid(), identity.currentNpcUuid()),
                identity.activeRecovery() != null
        );
    }

    private void addManagedEvidence(@Nonnull LinkedHashSet<UUID> checked,
                                    @Nullable NpcIdentityRepository.ManagedAssignment assignment) {
        if (assignment == null) {
            return;
        }
        addEvidence(checked, assignment.residentUuid());
        addEvidence(checked, assignment.sourceNpcUuid());
        addEvidence(checked, assignment.deployedNpcUuid());
    }

    private void addRecoveryEvidence(@Nonnull LinkedHashSet<UUID> checked,
                                     @Nullable NpcIdentityRepository.ActiveRecovery recovery) {
        if (recovery == null) {
            return;
        }
        addEvidence(checked, recovery.plannedTargetUuid());
        addEvidence(checked, recovery.actualTargetUuid());
    }

    private void addEvidence(@Nonnull LinkedHashSet<UUID> checked, @Nullable UUID npcUuid) {
        if (npcUuid != null) {
            checked.add(npcUuid);
        }
    }
}
