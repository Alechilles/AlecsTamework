package com.alechilles.alecstamework.npc.progression;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Owns the bounded path-preflight stores, authority directory, and operation state.
 * All methods with a {@code Locked} suffix require {@link #lock()} to be held.
 */
final class NeedsResourcePathPreflightState {
    private final LinkedHashMap<NeedsResourcePathPreflightService.PreflightKey, CachedPreflight> cache =
            new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<RecentReadyKey, RecentReadyPreflight> recentReadyTargets =
            new LinkedHashMap<>(16, 0.75f, true);
    private final Map<AuthorityKey, AuthorityState> authorityStates = new HashMap<>();
    private final Map<AuthorityKey, AuthorityIndex> authorityIndex = new HashMap<>();
    /**
     * Maps the worldless invalidation identity to only the exact-world authorities that exist
     * for that identity. This keeps compatibility invalidation independent of all other NPCs,
     * resources, and target blocks.
     */
    private final Map<TargetAuthorityKey, Set<AuthorityKey>> worldlessAuthorityIndex = new HashMap<>();
    private final Object stateLock = new Object();
    private long cacheAdmissionWork;
    private int targetInvalidationBucketVisits;

    @Nonnull
    Object lock() {
        return stateLock;
    }

    @Nonnull
    ComputationOperation registerOperationLocked(
            @Nonnull NeedsResourcePathPreflightService.PreflightKey key) {
        AuthorityKey authority = AuthorityKey.from(key);
        ensureAuthorityIndexLocked(authority);
        AuthorityState state = authorityStates.computeIfAbsent(authority, ignored -> new AuthorityState());
        ComputationOperation operation = new ComputationOperation(key, authority, state.generation);
        state.operations.add(operation);
        return operation;
    }

    @Nullable
    ComputationOperation findActiveOperationLocked(
            @Nonnull NeedsResourcePathPreflightService.PreflightKey key) {
        AuthorityState state = authorityStates.get(AuthorityKey.from(key));
        if (state == null) {
            return null;
        }
        for (ComputationOperation operation : state.operations) {
            if (operation.key.equals(key) && isCurrentOperationLocked(operation)) {
                return operation;
            }
        }
        return null;
    }

    boolean isCurrentOperationLocked(@Nullable ComputationOperation operation) {
        if (operation == null || operation.cancelled) {
            return false;
        }
        AuthorityState state = authorityStates.get(operation.authority);
        return state != null
                && state.generation == operation.generation
                && state.operations.contains(operation);
    }

    void cancelAuthorityStateLocked(@Nonnull AuthorityKey authority) {
        AuthorityState state = authorityStates.get(authority);
        if (state == null) {
            return;
        }
        state.generation++;
        for (ComputationOperation operation : new ArrayList<>(state.operations)) {
            cancelOperationLocked(operation);
        }
    }

    void cancelOperationLocked(@Nullable ComputationOperation operation) {
        if (operation == null) {
            return;
        }
        operation.cancelled = true;
        if (!operation.computing) {
            clearOperationLocked(operation);
        }
    }

    void clearOperationLocked(@Nonnull ComputationOperation operation) {
        if (!operation.cleared && operation.computation != null) {
            operation.computation.clear();
            operation.cleared = true;
        }
        removeOperationLocked(operation);
    }

    void removeOperationLocked(@Nonnull ComputationOperation operation) {
        AuthorityState state = authorityStates.get(operation.authority);
        if (state != null
                && state.operations.remove(operation)
                && state.operations.isEmpty()) {
            authorityStates.remove(operation.authority, state);
            pruneAuthorityIndexLocked(operation.authority);
        }
    }

    @Nullable
    CachedPreflight cacheEntryLocked(@Nonnull NeedsResourcePathPreflightService.PreflightKey key) {
        return cache.get(key);
    }

    void cacheComputingLocked(@Nonnull NeedsResourcePathPreflightService.PreflightKey key,
                              @Nonnull ComputationOperation operation,
                              @Nonnull String reason,
                              long nowMs) {
        replaceCacheEntryLocked(
                key,
                new CachedPreflight(
                        NeedsResourcePathPreflightService.PathPreflightStatus.COMPUTING,
                        reason,
                        nowMs + NeedsResourcePathPreflightService.COMPUTING_TTL_MS,
                        operation
                )
        );
    }

    void cacheTerminalResultLocked(@Nonnull NeedsResourcePathPreflightService.PreflightKey key,
                                   @Nonnull NeedsResourcePathPreflightService.PathPreflightStatus status,
                                   @Nonnull String reason,
                                   long expiresAtMs) {
        replaceCacheEntryLocked(key, new CachedPreflight(status, reason, expiresAtMs, null));
    }

    void removeCacheEntryLocked(@Nonnull NeedsResourcePathPreflightService.PreflightKey key,
                                @Nonnull CachedPreflight value) {
        if (cache.remove(key, value)) {
            unlinkCacheKeyLocked(key);
            cancelOperationLocked(value.operation());
        }
    }

    @Nullable
    RecentReadyPreflight resolveRecentReadyLocked(
            @Nonnull NeedsResourcePathPreflightService.PreflightKey key,
            long nowMs) {
        RecentReadyKey recentKey = RecentReadyKey.from(key);
        RecentReadyPreflight recent = recentReadyTargets.get(recentKey);
        if (recent == null) {
            return null;
        }
        if (nowMs >= recent.expiresAtMs()) {
            recentReadyTargets.remove(recentKey, recent);
            unlinkRecentReadyKeyLocked(recentKey);
            return null;
        }
        return recent;
    }

    void cacheRecentReadyLocked(@Nonnull NeedsResourcePathPreflightService.PreflightKey key,
                                long expiresAtMs) {
        RecentReadyKey recentKey = RecentReadyKey.from(key);
        recentReadyTargets.put(
                recentKey,
                new RecentReadyPreflight(key, expiresAtMs)
        );
        linkRecentReadyKeyLocked(recentKey);
        enforceRecentReadyCapacityLocked();
    }

    void invalidateTargetLocked(@Nonnull UUID npcUuid,
                                @Nullable String worldName,
                                @Nonnull String resourceType,
                                int targetX,
                                int targetY,
                                int targetZ) {
        if (worldName != null) {
            targetInvalidationBucketVisits = 1;
            invalidateAuthorityLocked(new AuthorityKey(
                    npcUuid, worldName, resourceType, targetX, targetY, targetZ
            ));
            return;
        }
        Set<AuthorityKey> indexedAuthorities = worldlessAuthorityIndex.get(
                new TargetAuthorityKey(npcUuid, resourceType, targetX, targetY, targetZ)
        );
        if (indexedAuthorities == null || indexedAuthorities.isEmpty()) {
            targetInvalidationBucketVisits = 0;
            return;
        }
        int inspected = 0;
        for (AuthorityKey authority : new ArrayList<>(indexedAuthorities)) {
            inspected++;
            invalidateAuthorityLocked(authority);
        }
        targetInvalidationBucketVisits = inspected;
    }

    void clearWorldLocked(@Nonnull String worldName) {
        for (AuthorityKey authority : new ArrayList<>(authorityIndex.keySet())) {
            if (worldName.equals(authority.worldName())) {
                invalidateAuthorityLocked(authority);
            }
        }
    }

    void clearForTestsLocked() {
        for (AuthorityState state : new ArrayList<>(authorityStates.values())) {
            for (ComputationOperation operation : new ArrayList<>(state.operations)) {
                cancelOperationLocked(operation);
            }
        }
        for (CachedPreflight value : new ArrayList<>(cache.values())) {
            if (value != null && value.operation() != null) {
                cancelOperationLocked(value.operation());
            }
        }
        cache.clear();
        recentReadyTargets.clear();
        authorityIndex.clear();
        worldlessAuthorityIndex.clear();
        authorityStates.clear();
        cacheAdmissionWork = 0L;
        targetInvalidationBucketVisits = 0;
    }

    int cacheSizeLocked() {
        return cache.size();
    }

    int recentReadySizeLocked() {
        return recentReadyTargets.size();
    }

    int indexedCacheKeyCountLocked() {
        int count = 0;
        for (AuthorityIndex index : authorityIndex.values()) {
            count += index.cacheKeys.size();
        }
        return count;
    }

    int indexedRecentReadyKeyCountLocked() {
        int count = 0;
        for (AuthorityIndex index : authorityIndex.values()) {
            count += index.recentReadyKeys.size();
        }
        return count;
    }

    int authorityIndexSizeLocked() {
        return authorityIndex.size();
    }

    long cacheAdmissionWorkLocked() {
        return cacheAdmissionWork;
    }

    int targetInvalidationBucketVisitsLocked() {
        return targetInvalidationBucketVisits;
    }

    private void invalidateAuthorityLocked(@Nonnull AuthorityKey authority) {
        cancelAuthorityStateLocked(authority);
        AuthorityIndex index = authorityIndex.get(authority);
        if (index == null) {
            return;
        }
        for (NeedsResourcePathPreflightService.PreflightKey key : new ArrayList<>(index.cacheKeys)) {
            CachedPreflight value = cache.remove(key);
            unlinkCacheKeyLocked(key);
            if (value != null) {
                cancelOperationLocked(value.operation());
            }
        }
        for (RecentReadyKey key : new ArrayList<>(index.recentReadyKeys)) {
            recentReadyTargets.remove(key);
            unlinkRecentReadyKeyLocked(key);
        }
        pruneAuthorityIndexLocked(authority);
    }

    private void replaceCacheEntryLocked(
            @Nonnull NeedsResourcePathPreflightService.PreflightKey key,
            @Nonnull CachedPreflight replacement) {
        CachedPreflight previous = cache.put(key, replacement);
        linkCacheKeyLocked(key);
        if (previous != null && previous.operation() != replacement.operation()) {
            cancelOperationLocked(previous.operation());
        }
        enforceCacheCapacityLocked();
    }

    private void enforceCacheCapacityLocked() {
        while (cache.size() > NeedsResourcePathPreflightService.PRECHECK_CACHE_MAX_ENTRIES) {
            Iterator<Map.Entry<NeedsResourcePathPreflightService.PreflightKey, CachedPreflight>> iterator =
                    cache.entrySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            Map.Entry<NeedsResourcePathPreflightService.PreflightKey, CachedPreflight> eldest = iterator.next();
            NeedsResourcePathPreflightService.PreflightKey key = eldest.getKey();
            CachedPreflight value = eldest.getValue();
            iterator.remove();
            unlinkCacheKeyLocked(key);
            if (value != null) {
                cancelOperationLocked(value.operation());
            }
            cacheAdmissionWork++;
        }
    }

    private void enforceRecentReadyCapacityLocked() {
        while (recentReadyTargets.size() > NeedsResourcePathPreflightService.PRECHECK_CACHE_MAX_ENTRIES) {
            Iterator<Map.Entry<RecentReadyKey, RecentReadyPreflight>> iterator =
                    recentReadyTargets.entrySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            RecentReadyKey key = iterator.next().getKey();
            iterator.remove();
            unlinkRecentReadyKeyLocked(key);
        }
    }

    private void pruneAuthorityIndexLocked(@Nonnull AuthorityKey authority) {
        AuthorityIndex index = authorityIndex.get(authority);
        if (index != null
                && index.cacheKeys.isEmpty()
                && index.recentReadyKeys.isEmpty()
                && !authorityStates.containsKey(authority)) {
            authorityIndex.remove(authority, index);
            unlinkWorldlessAuthorityLocked(authority);
        }
    }

    private void linkCacheKeyLocked(@Nonnull NeedsResourcePathPreflightService.PreflightKey key) {
        authorityIndexForLocked(AuthorityKey.from(key)).cacheKeys.add(key);
    }

    private void unlinkCacheKeyLocked(@Nonnull NeedsResourcePathPreflightService.PreflightKey key) {
        AuthorityKey authority = AuthorityKey.from(key);
        AuthorityIndex index = authorityIndex.get(authority);
        if (index != null) {
            index.cacheKeys.remove(key);
            pruneAuthorityIndexLocked(authority);
        }
    }

    private void linkRecentReadyKeyLocked(@Nonnull RecentReadyKey key) {
        authorityIndexForLocked(authorityFrom(key)).recentReadyKeys.add(key);
    }

    private void unlinkRecentReadyKeyLocked(@Nonnull RecentReadyKey key) {
        AuthorityKey authority = authorityFrom(key);
        AuthorityIndex index = authorityIndex.get(authority);
        if (index != null) {
            index.recentReadyKeys.remove(key);
            pruneAuthorityIndexLocked(authority);
        }
    }

    @Nonnull
    private AuthorityIndex authorityIndexForLocked(@Nonnull AuthorityKey authority) {
        return ensureAuthorityIndexLocked(authority);
    }

    @Nonnull
    private AuthorityIndex ensureAuthorityIndexLocked(@Nonnull AuthorityKey authority) {
        AuthorityIndex index = authorityIndex.computeIfAbsent(authority, ignored -> new AuthorityIndex());
        worldlessAuthorityIndex
                .computeIfAbsent(TargetAuthorityKey.from(authority), ignored -> new HashSet<>())
                .add(authority);
        return index;
    }

    private void unlinkWorldlessAuthorityLocked(@Nonnull AuthorityKey authority) {
        TargetAuthorityKey targetAuthority = TargetAuthorityKey.from(authority);
        Set<AuthorityKey> authorities = worldlessAuthorityIndex.get(targetAuthority);
        if (authorities == null || !authorities.remove(authority)) {
            return;
        }
        if (authorities.isEmpty()) {
            worldlessAuthorityIndex.remove(targetAuthority, authorities);
        }
    }

    @Nonnull
    private static AuthorityKey authorityFrom(@Nonnull RecentReadyKey key) {
        return new AuthorityKey(
                key.npcUuid(),
                key.worldName(),
                key.resourceType(),
                key.targetX(),
                key.targetY(),
                key.targetZ()
        );
    }

    record AuthorityKey(@Nonnull UUID npcUuid,
                         @Nonnull String worldName,
                         @Nonnull String resourceType,
                         int targetX,
                         int targetY,
                         int targetZ) {
        @Nonnull
        static AuthorityKey from(@Nonnull NeedsResourcePathPreflightService.PreflightKey key) {
            return new AuthorityKey(
                    key.npcUuid(),
                    key.worldName(),
                    key.resourceType(),
                    key.targetX(),
                    key.targetY(),
                    key.targetZ()
            );
        }
    }

    private record TargetAuthorityKey(@Nonnull UUID npcUuid,
                                       @Nonnull String resourceType,
                                       int targetX,
                                       int targetY,
                                       int targetZ) {
        @Nonnull
        private static TargetAuthorityKey from(@Nonnull AuthorityKey authority) {
            return new TargetAuthorityKey(
                    authority.npcUuid(),
                    authority.resourceType(),
                    authority.targetX(),
                    authority.targetY(),
                    authority.targetZ()
            );
        }
    }

    private static final class AuthorityIndex {
        private final Set<NeedsResourcePathPreflightService.PreflightKey> cacheKeys = new HashSet<>();
        private final Set<RecentReadyKey> recentReadyKeys = new HashSet<>();
    }

    private static final class AuthorityState {
        private long generation;
        private final Set<ComputationOperation> operations = new HashSet<>();
    }

    static final class ComputationOperation {
        final NeedsResourcePathPreflightService.PreflightKey key;
        final AuthorityKey authority;
        final long generation;
        @Nullable
        NeedsResourcePathPreflightService.PathComputation computation;
        boolean computing;
        boolean cancelled;
        boolean cleared;

        private ComputationOperation(@Nonnull NeedsResourcePathPreflightService.PreflightKey key,
                                     @Nonnull AuthorityKey authority,
                                     long generation) {
            this.key = key;
            this.authority = authority;
            this.generation = generation;
        }
    }

    record CachedPreflight(@Nonnull NeedsResourcePathPreflightService.PathPreflightStatus status,
                           @Nonnull String reason,
                           long expiresAtMs,
                           @Nullable ComputationOperation operation) {
    }

    record RecentReadyKey(@Nonnull UUID npcUuid,
                          @Nonnull String worldName,
                          @Nonnull String resourceType,
                          @Nonnull String motionControllerType,
                          int targetX,
                          int targetY,
                          int targetZ,
                          int stopDistanceKey) {
        @Nonnull
        static RecentReadyKey from(@Nonnull NeedsResourcePathPreflightService.PreflightKey key) {
            return new RecentReadyKey(
                    key.npcUuid(),
                    key.worldName(),
                    key.resourceType(),
                    key.motionControllerType(),
                    key.targetX(),
                    key.targetY(),
                    key.targetZ(),
                    key.stopDistanceKey()
            );
        }
    }

    record RecentReadyPreflight(@Nonnull NeedsResourcePathPreflightService.PreflightKey key,
                                long expiresAtMs) {
    }
}
