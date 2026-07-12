package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.sqlite.CompanionIdentityRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves canonical companion profiles without querying SQLite from a world thread.
 */
public final class CompanionIdentityResolver {
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<UUID, String> profileByNpcUuid = new HashMap<>();
    private final Map<String, UUID> currentUuidByProfile = new HashMap<>();
    private final Map<String, ProvisionalIdentity> provisionalByKey = new HashMap<>();
    private final Map<UUID, ProvisionalIdentity> provisionalByNpcUuid = new HashMap<>();
    private final Map<String, Integer> provisionalLeaseCountByProfile = new HashMap<>();

    /**
     * Atomically replaces the durable alias snapshot. Provisional allocations remain available.
     */
    public void replaceDurableAliases(
            @Nonnull Collection<CompanionIdentityRepository.AliasRecord> aliases
    ) {
        Objects.requireNonNull(aliases, "aliases");
        lock.lock();
        try {
            Map<UUID, String> replacementAliases = new HashMap<>();
            Map<String, UUID> replacementCurrent = new HashMap<>();
            for (CompanionIdentityRepository.AliasRecord alias : aliases) {
                Objects.requireNonNull(alias, "alias");
                String profileId = OwnerPopulationEntry.normalizeProfileId(alias.profileId());
                String previous = replacementAliases.put(alias.npcUuid(), profileId);
                if (previous != null && !previous.equals(profileId)) {
                    throw new IllegalArgumentException("NPC UUID maps to multiple profiles: " + alias.npcUuid());
                }
                if (alias.current()) {
                    UUID previousCurrent = replacementCurrent.put(profileId, alias.npcUuid());
                    if (previousCurrent != null && !previousCurrent.equals(alias.npcUuid())) {
                        throw new IllegalArgumentException("Profile has multiple current UUIDs: " + profileId);
                    }
                }
            }
            Map<String, ProvisionalIdentity> remainingByKey = new HashMap<>();
            Map<UUID, ProvisionalIdentity> remainingByNpcUuid = new HashMap<>();
            for (Map.Entry<String, ProvisionalIdentity> entry : provisionalByKey.entrySet()) {
                ProvisionalIdentity provisional = entry.getValue();
                String durableProfile = replacementAliases.get(provisional.npcUuid());
                if (durableProfile != null && !durableProfile.equals(provisional.profileId())) {
                    throw new IllegalStateException("Durable aliases conflict with a provisional profile.");
                }
                if (durableProfile == null) {
                    replacementAliases.put(provisional.npcUuid(), provisional.profileId());
                    replacementCurrent.putIfAbsent(provisional.profileId(), provisional.npcUuid());
                    remainingByKey.put(entry.getKey(), provisional);
                    remainingByNpcUuid.put(provisional.npcUuid(), provisional);
                }
            }
            profileByNpcUuid.clear();
            profileByNpcUuid.putAll(replacementAliases);
            currentUuidByProfile.clear();
            currentUuidByProfile.putAll(replacementCurrent);
            provisionalByKey.clear();
            provisionalByKey.putAll(remainingByKey);
            provisionalByNpcUuid.clear();
            provisionalByNpcUuid.putAll(remainingByNpcUuid);
            provisionalLeaseCountByProfile.keySet().retainAll(
                    remainingByNpcUuid.values().stream()
                            .map(ProvisionalIdentity::profileId)
                            .collect(java.util.stream.Collectors.toSet())
            );
        } finally {
            lock.unlock();
        }
    }

    @Nonnull
    public Optional<String> resolveProfileId(@Nullable UUID npcUuid) {
        if (npcUuid == null) {
            return Optional.empty();
        }
        lock.lock();
        try {
            return Optional.ofNullable(profileByNpcUuid.get(npcUuid));
        } finally {
            lock.unlock();
        }
    }

    @Nonnull
    public Optional<UUID> currentNpcUuid(@Nullable String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return Optional.empty();
        }
        lock.lock();
        try {
            return Optional.ofNullable(currentUuidByProfile.get(profileId.trim()));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Resolves an alias or allocates one stable provisional profile for the idempotency key.
     */
    @Nonnull
    public Resolution resolveOrAllocate(@Nonnull UUID npcUuid, @Nonnull String idempotencyKey) {
        Objects.requireNonNull(npcUuid, "npcUuid");
        String normalizedKey = requireText(idempotencyKey, "idempotencyKey");
        lock.lock();
        try {
            ProvisionalIdentity existing = provisionalByKey.get(normalizedKey);
            if (existing != null) {
                if (!existing.npcUuid().equals(npcUuid)) {
                    throw new IllegalArgumentException("Idempotency key was already used for another NPC UUID.");
                }
                provisionalLeaseCountByProfile.merge(existing.profileId(), 1, Integer::sum);
                return new Resolution(existing.profileId(), npcUuid, true);
            }
            ProvisionalIdentity provisionalForNpc = provisionalByNpcUuid.get(npcUuid);
            if (provisionalForNpc != null) {
                throw new IllegalArgumentException(
                        "NPC UUID already has a provisional identity owned by another operation."
                );
            }
            String existingProfile = profileByNpcUuid.get(npcUuid);
            if (existingProfile != null) {
                return new Resolution(existingProfile, npcUuid, false);
            }
            String profileId = UUID.randomUUID().toString();
            ProvisionalIdentity provisional = new ProvisionalIdentity(profileId, npcUuid);
            provisionalByKey.put(normalizedKey, provisional);
            provisionalByNpcUuid.put(npcUuid, provisional);
            provisionalLeaseCountByProfile.put(profileId, 1);
            profileByNpcUuid.put(npcUuid, profileId);
            currentUuidByProfile.put(profileId, npcUuid);
            return new Resolution(profileId, npcUuid, true);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retains historical aliases while switching the canonical current UUID.
     */
    public void remap(@Nonnull String profileId,
                      @Nullable UUID previousNpcUuid,
                      @Nonnull UUID currentNpcUuid) {
        String normalizedProfile = OwnerPopulationEntry.normalizeProfileId(profileId);
        Objects.requireNonNull(currentNpcUuid, "currentNpcUuid");
        lock.lock();
        try {
            String conflicting = profileByNpcUuid.get(currentNpcUuid);
            if (conflicting != null && !normalizedProfile.equals(conflicting)) {
                throw new IllegalArgumentException("Replacement UUID belongs to another profile.");
            }
            if (previousNpcUuid != null) {
                String previousProfile = profileByNpcUuid.get(previousNpcUuid);
                if (previousProfile != null && !normalizedProfile.equals(previousProfile)) {
                    throw new IllegalArgumentException("Previous UUID belongs to another profile.");
                }
                profileByNpcUuid.put(previousNpcUuid, normalizedProfile);
            }
            profileByNpcUuid.put(currentNpcUuid, normalizedProfile);
            currentUuidByProfile.put(normalizedProfile, currentNpcUuid);
        } finally {
            lock.unlock();
        }
    }

    /** Marks a successfully committed provisional identity as durable. */
    public void markDurable(@Nonnull String profileId, @Nonnull UUID currentNpcUuid) {
        String normalizedProfile = OwnerPopulationEntry.normalizeProfileId(profileId);
        Objects.requireNonNull(currentNpcUuid, "currentNpcUuid");
        lock.lock();
        try {
            String mappedProfile = profileByNpcUuid.get(currentNpcUuid);
            if (mappedProfile != null && !normalizedProfile.equals(mappedProfile)) {
                throw new IllegalArgumentException("NPC UUID belongs to another profile.");
            }
            profileByNpcUuid.put(currentNpcUuid, normalizedProfile);
            currentUuidByProfile.put(normalizedProfile, currentNpcUuid);
            provisionalByKey.entrySet().removeIf(entry ->
                    normalizedProfile.equals(entry.getValue().profileId()));
            provisionalByNpcUuid.entrySet().removeIf(entry ->
                    normalizedProfile.equals(entry.getValue().profileId()));
            provisionalLeaseCountByProfile.remove(normalizedProfile);
        } finally {
            lock.unlock();
        }
    }

    /** Releases an allocation that never reached a durable or live population transition. */
    public boolean releaseProvisional(@Nonnull String profileId, @Nonnull UUID npcUuid) {
        String normalizedProfile = OwnerPopulationEntry.normalizeProfileId(profileId);
        Objects.requireNonNull(npcUuid, "npcUuid");
        lock.lock();
        try {
            ProvisionalIdentity provisional = provisionalByNpcUuid.get(npcUuid);
            if (provisional == null || !normalizedProfile.equals(provisional.profileId())) {
                return false;
            }
            int leases = provisionalLeaseCountByProfile.getOrDefault(normalizedProfile, 1);
            if (leases > 1) {
                provisionalLeaseCountByProfile.put(normalizedProfile, leases - 1);
                return true;
            }
            provisionalLeaseCountByProfile.remove(normalizedProfile);
            provisionalByNpcUuid.remove(npcUuid, provisional);
            provisionalByKey.entrySet().removeIf(entry -> provisional.equals(entry.getValue()));
            profileByNpcUuid.remove(npcUuid, normalizedProfile);
            currentUuidByProfile.remove(normalizedProfile, npcUuid);
            return true;
        } finally {
            lock.unlock();
        }
    }

    boolean isProvisional(@Nonnull String profileId, @Nonnull UUID npcUuid) {
        String normalizedProfile = OwnerPopulationEntry.normalizeProfileId(profileId);
        Objects.requireNonNull(npcUuid, "npcUuid");
        lock.lock();
        try {
            ProvisionalIdentity provisional = provisionalByNpcUuid.get(npcUuid);
            return provisional != null && normalizedProfile.equals(provisional.profileId());
        } finally {
            lock.unlock();
        }
    }

    public int aliasCount() {
        lock.lock();
        try {
            return profileByNpcUuid.size();
        } finally {
            lock.unlock();
        }
    }

    @Nonnull
    private static String requireText(@Nonnull String value, @Nonnull String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return normalized;
    }

    public record Resolution(@Nonnull String profileId,
                             @Nonnull UUID currentNpcUuid,
                             boolean provisional) {
    }

    private record ProvisionalIdentity(@Nonnull String profileId, @Nonnull UUID npcUuid) {
    }
}
