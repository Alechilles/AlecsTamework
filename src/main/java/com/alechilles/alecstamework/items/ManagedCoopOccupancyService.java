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

    private final ManagedCoopResidentIndex index;

    public ManagedCoopOccupancyService(@Nonnull ManagedCoopResidentIndex index) {
        this.index = Objects.requireNonNull(index, "index");
    }

    /** Resolves authority and occupancy from one point-in-time index snapshot. */
    @Nonnull
    public View inspect(@Nonnull ManagedCoopContext context) {
        Objects.requireNonNull(context, "context");
        ManagedCoopResidentIndex.Snapshot snapshot = index.snapshot();
        if (!index.isTrusted() || snapshot.revision() == 0L) {
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

    /** Returns the first free configured slot, or {@code -1} when full or fail-closed. */
    public int firstEmptySlot(@Nonnull ManagedCoopContext context) {
        View view = inspect(context);
        if (!view.permitsCaptureClaim()) {
            return -1;
        }
        int maxResidents = Math.max(0, context.config().getLifecycleRules().getMaxResidents());
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

    /** Returns the first strictly housed slot eligible for a new release operation. */
    public int firstHousedSlot(@Nonnull ManagedCoopContext context) {
        View view = inspect(context);
        if (view.status() != AuthorityStatus.READY) {
            return -1;
        }
        int maxResidents = Math.max(0, context.config().getLifecycleRules().getMaxResidents());
        int first = Integer.MAX_VALUE;
        for (ResidentRecord resident : view.residents()) {
            if (resident.state() == ResidentState.HOUSED
                    && resident.residentSlot() >= 0
                    && resident.residentSlot() < maxResidents) {
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
        if (normalizedWorld == null || !index.isTrusted() || snapshot.revision() == 0L) {
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
        return npcUuid == null || !index.isTrusted() || snapshot.revision() == 0L
                ? null
                : snapshot.residentByUuid(npcUuid);
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
