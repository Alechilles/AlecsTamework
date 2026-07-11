package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Atomic immutable runtime projection of committed managed-coop authority and resident rows.
 *
 * <p>Tick paths read only this index. Rebuilds prepare and validate a complete replacement before
 * swapping the visible snapshot, so a failed persistence read or corrupt candidate cannot expose
 * partial occupancy.</p>
 */
public final class ManagedCoopResidentIndex {
    private static final Comparator<AuthorityRecord> AUTHORITY_ORDER = Comparator
            .comparing((AuthorityRecord record) -> record.authorityKey().worldName())
            .thenComparingInt(record -> record.authorityKey().x())
            .thenComparingInt(record -> record.authorityKey().y())
            .thenComparingInt(record -> record.authorityKey().z())
            .thenComparing(AuthorityRecord::coopId)
            .thenComparing(AuthorityRecord::authorityId);
    private static final Comparator<ResidentRecord> RESIDENT_ORDER = Comparator
            .comparing((ResidentRecord record) -> record.authorityKey().worldName())
            .thenComparingInt(record -> record.authorityKey().x())
            .thenComparingInt(record -> record.authorityKey().y())
            .thenComparingInt(record -> record.authorityKey().z())
            .thenComparingInt(ResidentRecord::residentSlot)
            .thenComparing(ResidentRecord::profileId)
            .thenComparing(ResidentRecord::residentId);

    public enum RebuildStatus {
        REBUILT,
        REJECTED
    }

    public record RebuildResult(@Nonnull RebuildStatus status, @Nullable String detail) {
        public boolean rebuilt() {
            return status == RebuildStatus.REBUILT;
        }
    }

    private final AtomicReference<Snapshot> current = new AtomicReference<>(Snapshot.empty());
    private final AtomicLong revisions = new AtomicLong();
    private final AtomicBoolean trusted = new AtomicBoolean();

    /**
     * Atomically replaces this index only when both persistence snapshots loaded successfully and
     * their cross-table invariants hold.
     */
    @Nonnull
    public synchronized RebuildResult rebuild(
            @Nonnull ManagedCoopReadResult<List<AuthorityRecord>> authoritiesResult,
            @Nonnull ManagedCoopReadResult<List<ResidentRecord>> residentsResult) {
        if (authoritiesResult == null || residentsResult == null) {
            trusted.set(false);
            return new RebuildResult(RebuildStatus.REJECTED, "missing_managed_coop_read_result");
        }
        if (authoritiesResult.status() != ManagedCoopReadResult.Status.LOADED
                || residentsResult.status() != ManagedCoopReadResult.Status.LOADED
                || authoritiesResult.value() == null
                || residentsResult.value() == null) {
            trusted.set(false);
            return new RebuildResult(RebuildStatus.REJECTED, "managed_coop_snapshot_not_loaded");
        }
        final Snapshot replacement;
        try {
            replacement = Snapshot.build(
                    revisions.incrementAndGet(),
                    authoritiesResult.value(),
                    residentsResult.value()
            );
        } catch (RuntimeException exception) {
            trusted.set(false);
            return new RebuildResult(RebuildStatus.REJECTED, exception.getMessage());
        }
        current.set(replacement);
        trusted.set(true);
        return new RebuildResult(RebuildStatus.REBUILT, null);
    }

    /** Returns whether the last complete persistence read validated successfully. */
    public boolean isTrusted() {
        return trusted.get();
    }

    /** Returns the current immutable point-in-time index. */
    @Nonnull
    public Snapshot snapshot() {
        return current.get();
    }

    @Nullable
    public AuthorityRecord authority(@Nonnull ManagedCoopAuthorityKey key, @Nonnull String coopId) {
        return current.get().authority(key, coopId);
    }

    @Nonnull
    public List<ResidentRecord> residents(@Nonnull ManagedCoopAuthorityKey key) {
        return current.get().residents(key);
    }

    @Nullable
    public ResidentRecord residentAt(@Nonnull ManagedCoopAuthorityKey key, int residentSlot) {
        return current.get().residentAt(key, residentSlot);
    }

    @Nullable
    public ResidentRecord residentByProfile(@Nonnull String profileId) {
        return current.get().residentByProfile(profileId);
    }

    @Nullable
    public ResidentRecord residentByUuid(@Nonnull UUID npcUuid) {
        return current.get().residentByUuid(npcUuid);
    }

    /** Immutable lookup snapshot safe to retain for one tick or operation admission decision. */
    public static final class Snapshot {
        private final long revision;
        private final List<AuthorityRecord> authorities;
        private final List<ResidentRecord> residents;
        private final Map<ManagedCoopAuthorityKey, AuthorityRecord> authorityByKey;
        private final Map<ManagedCoopAuthorityKey, List<ResidentRecord>> residentsByAuthority;
        private final Map<String, ResidentRecord> residentBySlot;
        private final Map<String, ResidentRecord> residentByProfile;
        private final Map<UUID, ResidentRecord> residentByUuid;

        private Snapshot(long revision,
                         List<AuthorityRecord> authorities,
                         List<ResidentRecord> residents,
                         Map<ManagedCoopAuthorityKey, AuthorityRecord> authorityByKey,
                         Map<ManagedCoopAuthorityKey, List<ResidentRecord>> residentsByAuthority,
                         Map<String, ResidentRecord> residentBySlot,
                         Map<String, ResidentRecord> residentByProfile,
                         Map<UUID, ResidentRecord> residentByUuid) {
            this.revision = revision;
            this.authorities = List.copyOf(authorities);
            this.residents = List.copyOf(residents);
            this.authorityByKey = Map.copyOf(authorityByKey);
            this.residentsByAuthority = immutableNestedLists(residentsByAuthority);
            this.residentBySlot = Map.copyOf(residentBySlot);
            this.residentByProfile = Map.copyOf(residentByProfile);
            this.residentByUuid = Map.copyOf(residentByUuid);
        }

        @Nonnull
        private static Snapshot empty() {
            return new Snapshot(0L, List.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }

        @Nonnull
        private static Snapshot build(long revision,
                                      List<AuthorityRecord> sourceAuthorities,
                                      List<ResidentRecord> sourceResidents) {
            ArrayList<AuthorityRecord> authorities = new ArrayList<>(sourceAuthorities);
            ArrayList<ResidentRecord> residents = new ArrayList<>(sourceResidents);
            authorities.sort(AUTHORITY_ORDER);
            residents.sort(RESIDENT_ORDER);

            LinkedHashMap<ManagedCoopAuthorityKey, AuthorityRecord> authorityByKey = new LinkedHashMap<>();
            HashMap<String, ManagedCoopAuthorityKey> authorityIdOwners = new HashMap<>();
            for (AuthorityRecord authority : authorities) {
                requireActiveAuthority(authority);
                AuthorityRecord previous = authorityByKey.putIfAbsent(authority.authorityKey(), authority);
                if (previous != null) {
                    throw invalid("duplicate_managed_coop_authority:" + authority.authorityKey().authorityId());
                }
                ManagedCoopAuthorityKey previousKey = authorityIdOwners.putIfAbsent(
                        authority.authorityId(), authority.authorityKey());
                if (previousKey != null) {
                    throw invalid("duplicate_managed_coop_authority_id:" + authority.authorityId());
                }
            }

            LinkedHashMap<ManagedCoopAuthorityKey, List<ResidentRecord>> residentsByAuthority =
                    new LinkedHashMap<>();
            LinkedHashMap<String, ResidentRecord> residentBySlot = new LinkedHashMap<>();
            LinkedHashMap<String, ResidentRecord> residentByProfile = new LinkedHashMap<>();
            LinkedHashMap<UUID, ResidentRecord> residentByUuid = new LinkedHashMap<>();
            HashMap<String, ResidentRecord> residentById = new HashMap<>();
            for (ResidentRecord resident : residents) {
                requireActiveResident(resident);
                AuthorityRecord authority = authorityByKey.get(resident.authorityKey());
                if (authority == null || !authority.coopId().equals(normalize(resident.coopId()))) {
                    throw invalid("resident_without_matching_authority:" + resident.residentId());
                }
                putUniqueAssignment(residentById, resident.residentId(), resident, "resident_id");
                putUniqueAssignment(residentBySlot, resident.authorityKey().slotKey(resident.residentSlot()),
                        resident, "resident_slot");
                putUniqueAssignment(residentByProfile, resident.profileId(), resident, "resident_profile");
                putUuidAliases(residentByUuid, resident);
                residentsByAuthority.computeIfAbsent(resident.authorityKey(), ignored -> new ArrayList<>())
                        .add(resident);
            }
            return new Snapshot(revision, authorities, residents, authorityByKey, residentsByAuthority,
                    residentBySlot, residentByProfile, residentByUuid);
        }

        public long revision() {
            return revision;
        }

        @Nonnull
        public List<AuthorityRecord> authorities() {
            return authorities;
        }

        @Nonnull
        public List<ResidentRecord> allResidents() {
            return residents;
        }

        @Nullable
        public AuthorityRecord authority(@Nonnull ManagedCoopAuthorityKey key, @Nonnull String coopId) {
            AuthorityRecord authority = authorityByKey.get(key);
            return authority != null && authority.coopId().equals(normalize(coopId)) ? authority : null;
        }

        @Nonnull
        public List<ResidentRecord> residents(@Nonnull ManagedCoopAuthorityKey key) {
            return residentsByAuthority.getOrDefault(key, List.of());
        }

        @Nullable
        public ResidentRecord residentAt(@Nonnull ManagedCoopAuthorityKey key, int residentSlot) {
            return residentSlot < 0 ? null : residentBySlot.get(key.slotKey(residentSlot));
        }

        @Nullable
        public ResidentRecord residentByProfile(@Nonnull String profileId) {
            return residentByProfile.get(profileId);
        }

        @Nullable
        public ResidentRecord residentByUuid(@Nonnull UUID npcUuid) {
            return residentByUuid.get(npcUuid);
        }

        private static void requireActiveAuthority(AuthorityRecord authority) {
            if (authority == null || !authority.active()
                    || !authority.authorityId().equals(authority.authorityKey().authorityId())) {
                throw invalid("invalid_active_managed_coop_authority");
            }
        }

        private static void requireActiveResident(ResidentRecord resident) {
            if (resident == null || !resident.active() || resident.residentSlot() < 0
                    || resident.generation() < 0L || resident.snapshotVersion() < 1) {
                throw invalid("invalid_active_managed_coop_resident");
            }
        }

        private static <K> void putUniqueAssignment(Map<K, ResidentRecord> target,
                                                    K key,
                                                    ResidentRecord resident,
                                                    String field) {
            ResidentRecord previous = target.putIfAbsent(key, resident);
            if (previous != null) {
                throw invalid("duplicate_" + field + ":" + key);
            }
        }

        private static void putUuidAliases(Map<UUID, ResidentRecord> target, ResidentRecord resident) {
            putUuidAlias(target, resident.residentUuid(), resident);
            if (resident.sourceNpcUuid() != null) {
                putUuidAlias(target, resident.sourceNpcUuid(), resident);
            }
            if (resident.deployedNpcUuid() != null) {
                putUuidAlias(target, resident.deployedNpcUuid(), resident);
            }
        }

        private static void putUuidAlias(Map<UUID, ResidentRecord> target,
                                         UUID uuid,
                                         ResidentRecord resident) {
            ResidentRecord previous = target.putIfAbsent(uuid, resident);
            if (previous != null && !previous.residentId().equals(resident.residentId())) {
                throw invalid("duplicate_resident_uuid:" + uuid);
            }
        }

        private static Map<ManagedCoopAuthorityKey, List<ResidentRecord>> immutableNestedLists(
                Map<ManagedCoopAuthorityKey, List<ResidentRecord>> source) {
            HashMap<ManagedCoopAuthorityKey, List<ResidentRecord>> copy = new HashMap<>();
            for (Map.Entry<ManagedCoopAuthorityKey, List<ResidentRecord>> entry : source.entrySet()) {
                copy.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            return Map.copyOf(copy);
        }

        private static String normalize(String value) {
            if (value == null || value.isBlank()) {
                throw invalid("blank_managed_coop_identifier");
            }
            return value.trim().toLowerCase(Locale.ROOT);
        }

        private static IllegalArgumentException invalid(String detail) {
            return new IllegalArgumentException(Objects.requireNonNull(detail));
        }
    }
}
