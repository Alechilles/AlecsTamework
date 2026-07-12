package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Fail-closed occupancy queries over one immutable managed-coop index revision.
 *
 * <p>An enabled config establishes the runtime authority boundary. A previously unseen physical
 * coop may therefore admit its first atomic capture claim before its authority row exists, but an
 * unread index, mismatched persisted coop ID, or non-managed transition state always blocks
 * mutation.</p>
 */
public final class ManagedCoopOccupancyService {
    public enum AuthorityStatus {
        READY,
        UNREGISTERED,
        INDEX_UNAVAILABLE,
        COOP_ID_CONFLICT,
        TRANSITION_BLOCKED
    }

    public record View(@Nonnull AuthorityStatus status,
                       long revision,
                       @Nonnull List<ResidentRecord> residents) {
        public View {
            Objects.requireNonNull(status, "status");
            residents = List.copyOf(residents);
        }

        /** A fresh capture transaction may proceed and repeat all constraints in SQLite. */
        public boolean permitsCaptureClaim() {
            return status == AuthorityStatus.READY || status == AuthorityStatus.UNREGISTERED;
        }
    }

    public enum CapturePlacementStatus {
        NEW_SLOT,
        RECAPTURE,
        REJECTED
    }

    /** Immutable admission result for a new capture or an exact deployed-resident recapture. */
    public record CapturePlacement(@Nonnull CapturePlacementStatus status,
                                   int residentSlot,
                                   long expectedResidentGeneration,
                                   @Nullable String detail) {
        public CapturePlacement {
            Objects.requireNonNull(status, "status");
            if (status != CapturePlacementStatus.REJECTED
                    && (residentSlot < 0 || expectedResidentGeneration < 0L)) {
                throw new IllegalArgumentException("accepted capture placement must be valid");
            }
        }

        public boolean permitted() {
            return status != CapturePlacementStatus.REJECTED;
        }
    }

    private final ManagedCoopResidentIndex index;
    private final BooleanSupplier trustGate;

    public ManagedCoopOccupancyService(@Nonnull ManagedCoopResidentIndex index) {
        this(index, index::isTrusted);
    }

    public ManagedCoopOccupancyService(@Nonnull ManagedCoopResidentIndex index,
                                       @Nonnull BooleanSupplier trustGate) {
        this.index = Objects.requireNonNull(index, "index");
        this.trustGate = Objects.requireNonNull(trustGate, "trustGate");
    }

    /** Resolves authority and occupancy from one point-in-time index snapshot. */
    @Nonnull
    public View inspect(@Nonnull ManagedCoopContext context) {
        Objects.requireNonNull(context, "context");
        ManagedCoopResidentIndex.Snapshot snapshot = index.snapshot();
        return inspect(context, snapshot);
    }

    @Nonnull
    private View inspect(ManagedCoopContext context, ManagedCoopResidentIndex.Snapshot snapshot) {
        if (!indexesTrusted() || snapshot.revision() == 0L) {
            return new View(AuthorityStatus.INDEX_UNAVAILABLE, snapshot.revision(), List.of());
        }
        AuthorityRecord exact = null;
        AuthorityRecord conflicting = null;
        for (AuthorityRecord authority : snapshot.authorities()) {
            if (!authority.authorityKey().equals(context.authorityKey())) {
                continue;
            }
            if (normalize(authority.coopId()).equals(context.coopId())) {
                exact = authority;
            } else {
                conflicting = authority;
            }
        }
        if (conflicting != null || exact == null && !snapshot.residents(context.authorityKey()).isEmpty()) {
            return new View(AuthorityStatus.COOP_ID_CONFLICT, snapshot.revision(), List.of());
        }
        if (exact == null) {
            return new View(AuthorityStatus.UNREGISTERED, snapshot.revision(), List.of());
        }
        if (exact.state() != AuthorityState.TWORK_MANAGED) {
            return new View(AuthorityStatus.TRANSITION_BLOCKED, snapshot.revision(), List.of());
        }
        return new View(
                AuthorityStatus.READY,
                snapshot.revision(),
                snapshot.residents(context.authorityKey())
        );
    }

    /**
     * Resolves capacity without losing an existing resident's generation.
     *
     * <p>A deployed resident continues to own its durable slot. It may only be captured back into
     * that same managed coop when the live UUID is the exact current deployed UUID. Historical
     * source aliases and profile/UUID disagreements are rejected as stale projections.</p>
     */
    @Nonnull
    public CapturePlacement resolveCapturePlacement(@Nonnull ManagedCoopContext context,
                                                     @Nonnull UUID sourceNpcUuid,
                                                     @Nullable String stableProfileId) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(sourceNpcUuid, "sourceNpcUuid");
        ManagedCoopResidentIndex.Snapshot snapshot = index.snapshot();
        View view = inspect(context, snapshot);
        if (!view.permitsCaptureClaim()) {
            return rejected("managed_coop_capture_" + view.status().name().toLowerCase(Locale.ROOT));
        }

        ResidentRecord byUuid = snapshot.residentByUuid(sourceNpcUuid);
        ResidentRecord byProfile = stableProfileId == null || stableProfileId.isBlank()
                ? null
                : snapshot.residentByProfile(stableProfileId.trim());
        if (byUuid != null) {
            if (byProfile != null && !byProfile.residentId().equals(byUuid.residentId())) {
                return rejected("managed_coop_capture_profile_uuid_conflict");
            }
            if (isExactRecapture(context, sourceNpcUuid, stableProfileId, byUuid)) {
                return new CapturePlacement(
                        CapturePlacementStatus.RECAPTURE,
                        byUuid.residentSlot(),
                        byUuid.generation(),
                        null
                );
            }
            return rejected("managed_coop_capture_source_not_current_deployed_resident");
        }
        if (byProfile != null) {
            return rejected("managed_coop_capture_profile_already_managed_by_other_uuid");
        }

        int slot = firstEmptySlot(context, view);
        return slot < 0
                ? rejected("managed_coop_capture_capacity_unavailable")
                : new CapturePlacement(CapturePlacementStatus.NEW_SLOT, slot, 0L, null);
    }

    /** Returns the first free configured slot, or {@code -1} when full or fail-closed. */
    public int firstEmptySlot(@Nonnull ManagedCoopContext context) {
        View view = inspect(context);
        return firstEmptySlot(context, view);
    }

    private int firstEmptySlot(ManagedCoopContext context, View view) {
        if (!view.permitsCaptureClaim()) {
            return -1;
        }
        int maxResidents = Math.max(0, context.config().getLifecycleRules().getMaxResidents());
        if (view.residents().size() >= maxResidents) {
            return -1;
        }
        boolean[] occupied = new boolean[maxResidents];
        for (ResidentRecord resident : view.residents()) {
            if (resident.residentSlot() >= 0 && resident.residentSlot() < maxResidents) {
                occupied[resident.residentSlot()] = true;
            }
        }
        for (int slot = 0; slot < maxResidents; slot++) {
            if (!occupied[slot]) {
                return slot;
            }
        }
        return -1;
    }

    private boolean isExactRecapture(ManagedCoopContext context,
                                     UUID sourceNpcUuid,
                                     @Nullable String stableProfileId,
                                     ResidentRecord resident) {
        boolean profileMatches = stableProfileId == null || stableProfileId.isBlank()
                || resident.profileId().equals(stableProfileId.trim());
        return resident.state() == ResidentState.DEPLOYED
                && sourceNpcUuid.equals(resident.residentUuid())
                && sourceNpcUuid.equals(resident.deployedNpcUuid())
                && resident.authorityKey().equals(context.authorityKey())
                && normalize(resident.coopId()).equals(context.coopId())
                && resident.residentSlot() >= 0
                && profileMatches;
    }

    private CapturePlacement rejected(String detail) {
        return new CapturePlacement(CapturePlacementStatus.REJECTED, -1, -1L, detail);
    }

    /** Returns the first strictly housed slot eligible for a new release operation. */
    public int firstHousedSlot(@Nonnull ManagedCoopContext context) {
        View view = inspect(context);
        if (view.status() != AuthorityStatus.READY) {
            return -1;
        }
        int first = Integer.MAX_VALUE;
        for (ResidentRecord resident : view.residents()) {
            if (resident.state() == ResidentState.HOUSED
                    && resident.residentSlot() >= 0) {
                first = Math.min(first, resident.residentSlot());
            }
        }
        return first == Integer.MAX_VALUE ? -1 : first;
    }

    @Nullable
    public ResidentRecord residentAt(@Nonnull ManagedCoopContext context, int slot) {
        View view = inspect(context);
        if (view.status() != AuthorityStatus.READY || slot < 0) {
            return null;
        }
        for (ResidentRecord resident : view.residents()) {
            if (resident.residentSlot() == slot) {
                return resident;
            }
        }
        return null;
    }

    /** Returns committed housed rows for one world without consulting SQLite from a tick path. */
    @Nonnull
    public List<ResidentRecord> housedResidentsForWorld(@Nullable String worldName) {
        String normalizedWorld = normalizeNullable(worldName);
        ManagedCoopResidentIndex.Snapshot snapshot = index.snapshot();
        if (normalizedWorld == null || !indexesTrusted() || snapshot.revision() == 0L) {
            return List.of();
        }
        ArrayList<ResidentRecord> housed = new ArrayList<>();
        for (ResidentRecord resident : snapshot.allResidents()) {
            if (resident.state() == ResidentState.HOUSED
                    && resident.authorityKey().worldName().equals(normalizedWorld)
                    && managedAuthority(snapshot, resident)) {
                housed.add(resident);
            }
        }
        return List.copyOf(housed);
    }

    private boolean managedAuthority(ManagedCoopResidentIndex.Snapshot snapshot,
                                     ResidentRecord resident) {
        AuthorityRecord authority = snapshot.authority(
                resident.authorityKey(),
                resident.coopId()
        );
        return authority != null && authority.state() == AuthorityState.TWORK_MANAGED;
    }

    @Nullable
    public ResidentRecord residentByUuid(@Nullable UUID npcUuid) {
        ManagedCoopResidentIndex.Snapshot snapshot = index.snapshot();
        return npcUuid == null || !indexesTrusted() || snapshot.revision() == 0L
                ? null
                : snapshot.residentByUuid(npcUuid);
    }

    private boolean indexesTrusted() {
        return index.isTrusted() && trustGate.getAsBoolean();
    }

    @Nonnull
    private static String normalize(@Nonnull String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    @Nullable
    private static String normalizeNullable(@Nullable String value) {
        return value == null || value.isBlank() ? null : normalize(value);
    }
}
