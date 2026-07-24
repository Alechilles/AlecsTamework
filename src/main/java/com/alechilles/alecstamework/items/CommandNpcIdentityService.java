package com.alechilles.alecstamework.items;

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
 * <p>Projection absence is deliberately not a failure or lifecycle fact. A new live companion can
 * be commanded immediately using its deterministic UUID-backed profile identity, then its item
 * record is canonicalized once a projection exists. Only contradictory projected identity or
 * duplicate live evidence blocks an action.</p>
 */
final class CommandNpcIdentityService {
    enum ResolutionStatus {
        RESOLVED,
        UNRESOLVED,
        CONFLICT,
        FAILED
    }

    record DurableStateFlags(
            boolean captured,
            boolean dead,
            boolean lost,
            boolean inCoop
    ) {
        private static final DurableStateFlags NONE =
                new DurableStateFlags(false, false, false, false);

        boolean suppressesLiveAction() {
            return captured || dead || lost || inCoop;
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
                && !explicitProfile.equals(projected.profileId().toString())) {
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
        UUID live = live(record.npcUuid);
        String profileId = persistence.profileId(record).toString();
        return new IdentityResolution(
                ResolutionStatus.RESOLVED,
                profileId,
                null,
                record.npcUuid,
                record.npcUuid,
                List.of(record.npcUuid),
                List.of(record.npcUuid),
                live == null ? List.of() : List.of(live),
                DurableStateFlags.NONE,
                null,
                null
        );
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
                projected.captured(),
                projected.dead(),
                projected.lost(),
                projected.inCoop()
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
                DurableStateFlags.NONE,
                "record_profile_conflicts_with_current_alias",
                null
        );
    }

    @Nullable
    private UUID live(UUID npcUuid) {
        try {
            LoadedNpcIdentityIndex.Probe probe = liveNpcProbe.probe(npcUuid);
            return probe != null
                    && (probe.status()
                    == LoadedNpcIdentityIndex.ProbeStatus.ONE_LOCATION
                    || probe.status()
                    == LoadedNpcIdentityIndex.ProbeStatus.MULTIPLE_LOCATIONS)
                    ? npcUuid
                    : null;
        } catch (RuntimeException ignored) {
            return null;
        }
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
                DurableStateFlags.NONE,
                reason,
                failure
        );
    }
}
