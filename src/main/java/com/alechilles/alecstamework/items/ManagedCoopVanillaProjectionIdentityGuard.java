package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.Location;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.Probe;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.ProbeStatus;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import com.alechilles.alecstamework.persistence.sqlite.NpcIdentityRepository;
import com.alechilles.alecstamework.persistence.sqlite.NpcIdentityRepository.IdentityLoadResult;
import com.alechilles.alecstamework.persistence.sqlite.NpcIdentityRepository.LoadStatus;
import com.alechilles.alecstamework.persistence.sqlite.NpcIdentityRepository.ManagedAssignment;
import com.alechilles.alecstamework.persistence.sqlite.NpcIdentityRepository.ProfileIdentity;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Proves one deployed import UUID is the profile's only live durable alias. */
final class ManagedCoopVanillaProjectionIdentityGuard {
    enum Status {
        VERIFIED,
        CONFLICT,
        UNAVAILABLE
    }

    record Result(@Nonnull Status status, @Nullable String detail) {
        Result {
            Objects.requireNonNull(status, "status");
        }

        private static Result verifiedResult() {
            return new Result(Status.VERIFIED, null);
        }

        private static Result conflict(String detail) {
            return new Result(Status.CONFLICT, detail);
        }

        private static Result unavailable(String detail) {
            return new Result(Status.UNAVAILABLE, detail);
        }

        boolean isVerified() {
            return status == Status.VERIFIED;
        }
    }

    @FunctionalInterface
    interface IdentityReader {
        @Nonnull IdentityLoadResult load(@Nonnull String profileId, @Nonnull UUID sourceUuid);
    }

    private final IdentityReader identities;
    private final LoadedNpcIdentityIndex loaded;

    ManagedCoopVanillaProjectionIdentityGuard(
            @Nonnull NpcIdentityRepository identities,
            @Nonnull LoadedNpcIdentityIndex loaded) {
        this(identities::load, loaded);
    }

    ManagedCoopVanillaProjectionIdentityGuard(
            @Nonnull IdentityReader identities,
            @Nonnull LoadedNpcIdentityIndex loaded) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
    }

    @Nonnull
    Result verify(@Nonnull String profileId,
                  @Nonnull UUID sourceUuid,
                  @Nonnull ManagedCoopAuthorityKey authorityKey,
                  int residentSlot) {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(sourceUuid, "sourceUuid");
        Objects.requireNonNull(authorityKey, "authorityKey");
        if (residentSlot < 0) {
            return Result.conflict("deployed_projection_identity_slot_invalid");
        }
        if (!loaded.isInitializationComplete()) {
            return Result.unavailable("deployed_projection_loaded_identity_index_incomplete");
        }
        final IdentityLoadResult load;
        try {
            load = identities.load(profileId, sourceUuid);
        } catch (RuntimeException exception) {
            return Result.unavailable("deployed_projection_identity_read_failed:"
                    + detail(exception));
        }
        if (load == null || load.status() == LoadStatus.FAILED) {
            return Result.unavailable("deployed_projection_identity_read_failed:"
                    + (load == null || load.failureReason() == null
                    ? "missing_result" : load.failureReason()));
        }
        if (load.status() == LoadStatus.CONFLICT) {
            return Result.conflict("deployed_projection_profile_uuid_conflict");
        }
        if (load.status() == LoadStatus.NOT_FOUND || load.identity() == null) {
            return Result.conflict("deployed_projection_identity_not_found");
        }
        ProfileIdentity identity = load.identity();
        if (!profileId.equals(identity.profileId()) || !identity.historicalUuidKnown()
                || !identity.aliases().contains(sourceUuid)) {
            return Result.conflict("deployed_projection_source_alias_not_durable");
        }
        if (identity.flags().captured() || identity.flags().dead()
                || identity.flags().lost()) {
            return Result.conflict("deployed_projection_durable_profile_state_conflict");
        }
        if (identity.flags().legacyInCoop()
                && !exactLegacyCoopKey(
                identity.flags().legacyCoopKey(), authorityKey, residentSlot)) {
            return Result.conflict("deployed_projection_legacy_coop_key_unverified");
        }
        if (identity.activeRecovery() != null) {
            return Result.conflict("deployed_projection_active_recovery_conflict");
        }
        if (!managedAssignmentCompatible(
                identity.managedAssignment(), sourceUuid, authorityKey, residentSlot)) {
            return Result.conflict("deployed_projection_managed_assignment_conflict");
        }
        Set<UUID> distinctAliases = new HashSet<>(identity.aliases());
        if (distinctAliases.size() != identity.aliases().size()) {
            return Result.conflict("deployed_projection_duplicate_durable_alias");
        }
        Result sourceProbe = verifySourceProbe(sourceUuid, authorityKey.worldName());
        if (!sourceProbe.isVerified()) {
            return sourceProbe;
        }
        for (UUID alias : distinctAliases) {
            if (alias == null || alias.equals(sourceUuid)) {
                continue;
            }
            Probe probe = loaded.probe(alias);
            if (probe.status() == ProbeStatus.UNKNOWN) {
                return Result.unavailable("deployed_projection_other_alias_probe_unknown:" + alias);
            }
            if (probe.status() != ProbeStatus.ABSENT) {
                return Result.conflict("deployed_projection_other_live_alias:" + alias);
            }
        }
        return Result.verifiedResult();
    }

    private Result verifySourceProbe(UUID sourceUuid, String expectedWorld) {
        Probe probe = loaded.probe(sourceUuid);
        if (probe.status() == ProbeStatus.UNKNOWN || probe.status() == ProbeStatus.ABSENT) {
            return Result.unavailable("deployed_projection_source_probe_"
                    + probe.status().name().toLowerCase());
        }
        if (probe.status() == ProbeStatus.MULTIPLE_LOCATIONS) {
            return Result.conflict("deployed_projection_source_has_multiple_locations");
        }
        if (probe.locations().size() != 1) {
            return Result.conflict("deployed_projection_source_location_count_invalid");
        }
        Location location = probe.locations().getFirst();
        return location.worldName().equalsIgnoreCase(expectedWorld)
                ? Result.verifiedResult()
                : Result.conflict("deployed_projection_source_world_mismatch");
    }

    private boolean managedAssignmentCompatible(@Nullable ManagedAssignment assignment,
                                                UUID sourceUuid,
                                                ManagedCoopAuthorityKey authorityKey,
                                                int residentSlot) {
        if (assignment == null) {
            return true;
        }
        boolean containsSource = sourceUuid.equals(assignment.residentUuid())
                || sourceUuid.equals(assignment.sourceNpcUuid())
                || sourceUuid.equals(assignment.deployedNpcUuid());
        return containsSource
                && assignment.authorityId().equals(authorityKey.authorityId())
                && assignment.residentSlot() == residentSlot
                && assignment.state() == ResidentState.DEPLOYED;
    }

    private boolean exactLegacyCoopKey(@Nullable String legacyCoopKey,
                                       ManagedCoopAuthorityKey authorityKey,
                                       int residentSlot) {
        if (legacyCoopKey == null || legacyCoopKey.isBlank()) {
            return false;
        }
        String expected = LegacyCoopLedgerSupport.slotKey(
                CommandLinkedNpcCoopService.CoopSlotContext.of(
                        authorityKey.worldName(), null,
                        authorityKey.x(), authorityKey.y(), authorityKey.z(), residentSlot));
        return expected.equals(legacyCoopKey.trim());
    }

    private static String detail(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }
}
