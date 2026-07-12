package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Synchronous owning-world boundary for adopting one already-live vanilla coop projection.
 *
 * <p>The import service calls {@link #inspect(InspectionRequest)} before it journals the immutable
 * source audit, then calls {@link #adopt(AdoptionRequest)} only after the exact managed resident and
 * IMPORT operation have committed. Implementations must never spawn or despawn an entity.</p>
 */
public interface ManagedCoopVanillaProjectionAdoptionGateway {
    enum InspectionStatus {
        VERIFIED,
        CONFLICT,
        UNAVAILABLE
    }

    enum AdoptionStatus {
        PENDING,
        ADOPTED,
        ALREADY_ADOPTED,
        CONFLICT,
        UNAVAILABLE
    }

    /** Immutable values needed to prove and snapshot one deployed vanilla source. */
    record InspectionRequest(@Nonnull ManagedCoopAuthorityKey authorityKey,
                             @Nonnull String coopId,
                             @Nonnull String sourceFingerprint,
                             int sourceSlot,
                             int sourceOrder,
                             @Nonnull UUID sourceNpcUuid,
                             @Nonnull String profileId,
                             @Nonnull String roleId,
                             int managedResidentSlot) {
        public InspectionRequest {
            Objects.requireNonNull(authorityKey, "authorityKey");
            coopId = text(coopId, "coopId").toLowerCase(Locale.ROOT);
            sourceFingerprint = hash(sourceFingerprint, "sourceFingerprint");
            Objects.requireNonNull(sourceNpcUuid, "sourceNpcUuid");
            profileId = text(profileId, "profileId");
            roleId = text(roleId, "roleId").toLowerCase(Locale.ROOT);
            if (sourceSlot < 0 || sourceOrder < 0 || managedResidentSlot < 0) {
                throw new IllegalArgumentException("projection inspection slots must not be negative");
            }
        }
    }

    /** Portable full-state evidence copied from the unique live projection. */
    record InspectionResult(@Nonnull InspectionStatus status,
                            @Nullable String snapshotJson,
                            @Nullable String snapshotHash,
                            int snapshotVersion,
                            @Nullable String conflictKind,
                            @Nullable String detail) {
        public InspectionResult {
            Objects.requireNonNull(status, "status");
            conflictKind = optionalText(conflictKind);
            detail = optionalText(detail);
            boolean verified = status == InspectionStatus.VERIFIED;
            if (verified != (snapshotJson != null && !snapshotJson.isBlank()
                    && snapshotHash != null && snapshotHash.matches("[0-9a-f]{64}")
                    && snapshotVersion > 0)) {
                throw new IllegalArgumentException("verified projection evidence is incomplete");
            }
            if ((status == InspectionStatus.CONFLICT) != (conflictKind != null)) {
                throw new IllegalArgumentException("projection conflict kind is inconsistent");
            }
            if (!verified && (snapshotJson != null || snapshotHash != null
                    || snapshotVersion != 0)) {
                throw new IllegalArgumentException("unverified projection cannot carry a snapshot");
            }
        }

        @Nonnull
        public static InspectionResult verified(@Nonnull String snapshotJson,
                                                @Nonnull String snapshotHash,
                                                int snapshotVersion) {
            return new InspectionResult(
                    InspectionStatus.VERIFIED,
                    Objects.requireNonNull(snapshotJson, "snapshotJson"),
                    Objects.requireNonNull(snapshotHash, "snapshotHash"),
                    snapshotVersion,
                    null,
                    null);
        }

        @Nonnull
        public static InspectionResult conflict(@Nonnull String conflictKind,
                                                @Nullable String detail) {
            return new InspectionResult(
                    InspectionStatus.CONFLICT, null, null, 0, conflictKind, detail);
        }

        @Nonnull
        public static InspectionResult unavailable(@Nullable String detail) {
            return new InspectionResult(
                    InspectionStatus.UNAVAILABLE, null, null, 0, null, detail);
        }

        public boolean verified() {
            return status == InspectionStatus.VERIFIED;
        }
    }

    /** Exact committed IMPORT graph identity required before removing vanilla ownership. */
    record AdoptionRequest(@Nonnull ManagedCoopAuthorityKey authorityKey,
                           @Nonnull String coopId,
                           @Nonnull String sessionId,
                           @Nonnull String sourceId,
                           @Nonnull String sourceFingerprint,
                           @Nonnull String operationId,
                           @Nonnull String residentId,
                           @Nonnull String profileId,
                           int residentSlot,
                           @Nonnull UUID sourceNpcUuid,
                           long residentGeneration,
                           @Nonnull String snapshotHash) {
        public AdoptionRequest {
            Objects.requireNonNull(authorityKey, "authorityKey");
            coopId = text(coopId, "coopId").toLowerCase(Locale.ROOT);
            sessionId = text(sessionId, "sessionId");
            sourceId = text(sourceId, "sourceId");
            sourceFingerprint = hash(sourceFingerprint, "sourceFingerprint");
            operationId = text(operationId, "operationId");
            residentId = text(residentId, "residentId");
            profileId = text(profileId, "profileId");
            Objects.requireNonNull(sourceNpcUuid, "sourceNpcUuid");
            snapshotHash = hash(snapshotHash, "snapshotHash");
            if (residentSlot < 0 || residentGeneration < 0L) {
                throw new IllegalArgumentException("adoption slot and generation must not be negative");
            }
        }

        @Nonnull
        public String authoritySlotKey() {
            return authorityKey.slotKey(residentSlot);
        }
    }

    /** Result of marker installation plus vanilla component detachment. */
    record AdoptionResult(@Nonnull AdoptionStatus status,
                          @Nullable String detail) {
        public AdoptionResult {
            Objects.requireNonNull(status, "status");
            detail = optionalText(detail);
        }

        @Nonnull
        public static AdoptionResult pending() {
            return new AdoptionResult(AdoptionStatus.PENDING, null);
        }

        @Nonnull
        public static AdoptionResult adopted() {
            return new AdoptionResult(AdoptionStatus.ADOPTED, null);
        }

        @Nonnull
        public static AdoptionResult alreadyAdopted() {
            return new AdoptionResult(AdoptionStatus.ALREADY_ADOPTED, null);
        }

        @Nonnull
        public static AdoptionResult conflict(@Nullable String detail) {
            return new AdoptionResult(AdoptionStatus.CONFLICT, detail);
        }

        @Nonnull
        public static AdoptionResult unavailable(@Nullable String detail) {
            return new AdoptionResult(AdoptionStatus.UNAVAILABLE, detail);
        }

        public boolean adoptedOrAlreadyAdopted() {
            return status == AdoptionStatus.ADOPTED || status == AdoptionStatus.ALREADY_ADOPTED;
        }
    }

    /** Reads and copies one exact projection without mutating vanilla or managed state. */
    @Nonnull
    InspectionResult inspect(@Nonnull InspectionRequest request);

    /** Installs the exact adoption marker, then removes only the vanilla resident component. */
    @Nonnull
    AdoptionResult adopt(@Nonnull AdoptionRequest request);

    /** Fail-closed default used until production supplies the owning-world adapter. */
    @Nonnull
    static ManagedCoopVanillaProjectionAdoptionGateway unavailable() {
        return new ManagedCoopVanillaProjectionAdoptionGateway() {
            @Nonnull
            @Override
            public InspectionResult inspect(@Nonnull InspectionRequest request) {
                return InspectionResult.unavailable("deployed_projection_gateway_not_wired");
            }

            @Nonnull
            @Override
            public AdoptionResult adopt(@Nonnull AdoptionRequest request) {
                return AdoptionResult.unavailable("deployed_projection_gateway_not_wired");
            }
        };
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String hash(String value, String field) {
        String normalized = text(value, field);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be canonical lowercase SHA-256");
        }
        return normalized;
    }

    @Nullable
    private static String optionalText(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
