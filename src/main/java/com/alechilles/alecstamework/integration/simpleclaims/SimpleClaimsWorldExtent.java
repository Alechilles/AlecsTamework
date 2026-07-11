package com.alechilles.alecstamework.integration.simpleclaims;

import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimFootprint;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Independently probed SimpleClaims world-topology capability.
 *
 * <p>Each instance belongs to one plugin generation. Its short-lived cache is additionally keyed
 * by world and party so a party's chunks in other worlds can never inflate a world-local cap.</p>
 */
final class SimpleClaimsWorldExtent {
    private static final int SNAPSHOT_ATTEMPTS = 2;
    private static final int MAX_CACHE_ENTRIES = 256;
    private static final long DEFAULT_CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(2);

    enum Status {
        AVAILABLE,
        UNAVAILABLE,
        ERROR
    }

    record Result(@Nonnull Status status,
                  @Nullable ClaimFootprint footprint,
                  @Nullable String message) {
    }

    private final boolean available;
    @Nullable
    private final String unavailableReason;
    @Nullable
    private final Method getInstance;
    @Nullable
    private final Method getChunks;
    @Nullable
    private final Method getPartyOwner;
    @Nullable
    private final Method getChunkX;
    @Nullable
    private final Method getChunkZ;
    private final long cacheTtlNanos;
    @Nonnull
    private final LongSupplier nanoTime;
    @Nonnull
    private final ConcurrentHashMap<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

    private SimpleClaimsWorldExtent(boolean available,
                                    @Nullable String unavailableReason,
                                    @Nullable Method getInstance,
                                    @Nullable Method getChunks,
                                    @Nullable Method getPartyOwner,
                                    @Nullable Method getChunkX,
                                    @Nullable Method getChunkZ,
                                    long cacheTtlNanos,
                                    @Nonnull LongSupplier nanoTime) {
        this.available = available;
        this.unavailableReason = unavailableReason;
        this.getInstance = getInstance;
        this.getChunks = getChunks;
        this.getPartyOwner = getPartyOwner;
        this.getChunkX = getChunkX;
        this.getChunkZ = getChunkZ;
        this.cacheTtlNanos = Math.max(0L, cacheTtlNanos);
        this.nanoTime = nanoTime;
    }

    @Nonnull
    static SimpleClaimsWorldExtent probe(@Nonnull ClassLoader classLoader) {
        try {
            Class<?> managerType = SimpleClaimsReflection.load(
                    classLoader,
                    SimpleClaimsReflection.CLAIM_MANAGER_CLASS
            );
            Class<?> chunkType = SimpleClaimsReflection.load(
                    classLoader,
                    SimpleClaimsReflection.CHUNK_INFO_CLASS
            );
            return forTypes(managerType, chunkType);
        } catch (Throwable throwable) {
            return unavailable("SimpleClaims extent contract is unavailable: "
                    + SimpleClaimsReflection.message(throwable));
        }
    }

    @Nonnull
    static SimpleClaimsWorldExtent forTypes(@Nonnull Class<?> managerType, @Nonnull Class<?> chunkType) {
        return forTypes(managerType, chunkType, DEFAULT_CACHE_TTL_NANOS, System::nanoTime);
    }

    @Nonnull
    static SimpleClaimsWorldExtent forTypes(@Nonnull Class<?> managerType,
                                            @Nonnull Class<?> chunkType,
                                            long cacheTtlNanos,
                                            @Nonnull LongSupplier nanoTime) {
        try {
            return new SimpleClaimsWorldExtent(
                    true,
                    null,
                    SimpleClaimsReflection.requiredMethod(managerType, "getInstance"),
                    SimpleClaimsReflection.requiredMethod(managerType, "getChunks"),
                    SimpleClaimsReflection.requiredMethod(chunkType, "getPartyOwner"),
                    SimpleClaimsReflection.requiredMethod(chunkType, "getChunkX"),
                    SimpleClaimsReflection.requiredMethod(chunkType, "getChunkZ"),
                    cacheTtlNanos,
                    nanoTime
            );
        } catch (Throwable throwable) {
            return unavailable("SimpleClaims extent contract is incompatible: "
                    + SimpleClaimsReflection.message(throwable));
        }
    }

    boolean isAvailable() {
        return available;
    }

    @Nullable
    String unavailableReason() {
        return unavailableReason;
    }

    @Nonnull
    Result resolve(@Nullable String worldName, @Nullable UUID partyId) {
        if (!available) {
            return new Result(Status.UNAVAILABLE, null, unavailableReason);
        }
        String world = normalizeWorld(worldName);
        if (world == null) {
            return new Result(Status.ERROR, null, "World name is missing.");
        }
        if (partyId == null) {
            return new Result(Status.ERROR, null, "SimpleClaims party ID is missing.");
        }
        CacheKey key = new CacheKey(world, partyId);
        Result cached = readCache(key);
        if (cached != null) {
            return cached;
        }
        return resolveAndCache(key);
    }

    @Nullable
    private Result readCache(@Nonnull CacheKey key) {
        if (cacheTtlNanos == 0L) {
            return null;
        }
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (nanoTime.getAsLong() < entry.expiresAtNanos()) {
            return entry.result();
        }
        cache.remove(key, entry);
        return null;
    }

    @Nonnull
    private Result resolveAndCache(@Nonnull CacheKey key) {
        Result result = loadWithRetry(key.worldName(), key.partyId());
        if (result.status() != Status.AVAILABLE || cacheTtlNanos == 0L) {
            return result;
        }
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        long now = nanoTime.getAsLong();
        long expiresAt = now > Long.MAX_VALUE - cacheTtlNanos ? Long.MAX_VALUE : now + cacheTtlNanos;
        cache.put(key, new CacheEntry(result, expiresAt));
        return result;
    }

    @Nonnull
    private Result loadWithRetry(@Nonnull String worldName, @Nonnull UUID partyId) {
        for (int attempt = 1; attempt <= SNAPSHOT_ATTEMPTS; attempt++) {
            try {
                Object manager = getInstance.invoke(null);
                if (manager == null) {
                    return new Result(Status.ERROR, null, "SimpleClaims manager was null.");
                }
                return snapshotWorld(manager, worldName, partyId);
            } catch (Throwable throwable) {
                Throwable unwrapped = SimpleClaimsReflection.unwrap(throwable);
                if (unwrapped instanceof ConcurrentModificationException && attempt < SNAPSHOT_ATTEMPTS) {
                    continue;
                }
                return new Result(
                        Status.ERROR,
                        null,
                        "SimpleClaims world extent failed: " + SimpleClaimsReflection.message(unwrapped)
                );
            }
        }
        return new Result(Status.ERROR, null, "SimpleClaims world extent could not be snapshotted.");
    }

    @Nonnull
    private Result snapshotWorld(@Nonnull Object manager,
                                 @Nonnull String worldName,
                                 @Nonnull UUID partyId) throws ReflectiveOperationException {
        Object allChunksValue = getChunks.invoke(manager);
        if (!(allChunksValue instanceof Map<?, ?> allChunks)) {
            return new Result(Status.ERROR, null, "SimpleClaims chunks snapshot was not a map.");
        }
        Object worldChunksValue = allChunks.get(worldName);
        if (!(worldChunksValue instanceof Map<?, ?> worldChunks)) {
            return new Result(Status.ERROR, null, "SimpleClaims world chunks were unavailable for " + worldName + ".");
        }
        List<?> chunks = new ArrayList<>(worldChunks.values());
        return mapFootprint(chunks, worldName, partyId);
    }

    @Nonnull
    private Result mapFootprint(@Nonnull List<?> chunks,
                                @Nonnull String worldName,
                                @Nonnull UUID partyId) throws ReflectiveOperationException {
        ArrayList<ClaimChunkCoordinate> coordinates = new ArrayList<>();
        for (Object chunk : chunks) {
            if (chunk == null) {
                return new Result(Status.ERROR, null, "SimpleClaims world chunks contained null.");
            }
            Object ownerValue = getPartyOwner.invoke(chunk);
            if (!(ownerValue instanceof UUID ownerId)) {
                return new Result(Status.ERROR, null, "SimpleClaims chunk owner was not a UUID.");
            }
            if (!partyId.equals(ownerId)) {
                continue;
            }
            Object xValue = getChunkX.invoke(chunk);
            Object zValue = getChunkZ.invoke(chunk);
            if (!(xValue instanceof Number x) || !(zValue instanceof Number z)) {
                return new Result(Status.ERROR, null, "SimpleClaims chunk coordinates were not numeric.");
            }
            coordinates.add(new ClaimChunkCoordinate(worldName, x.intValue(), z.intValue()));
        }
        if (coordinates.isEmpty()) {
            return new Result(Status.ERROR, null, "SimpleClaims party has no chunks in world " + worldName + ".");
        }
        return new Result(Status.AVAILABLE, new ClaimFootprint(coordinates), null);
    }

    @Nullable
    private static String normalizeWorld(@Nullable String worldName) {
        if (worldName == null) {
            return null;
        }
        String trimmed = worldName.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    @Nonnull
    private static SimpleClaimsWorldExtent unavailable(@Nonnull String reason) {
        return new SimpleClaimsWorldExtent(
                false,
                reason,
                null,
                null,
                null,
                null,
                null,
                0L,
                System::nanoTime
        );
    }

    /** Creates an explicit non-capability for damage-only bridge generations. */
    @Nonnull
    static SimpleClaimsWorldExtent notProbedForDamage() {
        return unavailable("SimpleClaims world extent is not part of the damage capability.");
    }

    private record CacheKey(@Nonnull String worldName, @Nonnull UUID partyId) {
    }

    private record CacheEntry(@Nonnull Result result, long expiresAtNanos) {
    }
}
