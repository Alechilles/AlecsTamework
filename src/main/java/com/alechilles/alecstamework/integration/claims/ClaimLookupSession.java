package com.alechilles.alecstamework.integration.claims;

import com.hypixel.hytale.math.util.ChunkUtil;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Operation-local claim lookup cache scoped to one provider and reflected-contract generation.
 *
 * <p>The session is deliberately short lived and not shared between top-level operations, so a
 * provider-side topology change is observed by the next operation.</p>
 */
public final class ClaimLookupSession {
    private final ClaimPolicyContext context;
    private final boolean extentRequired;
    private final ClaimLookupMetrics sharedMetrics;
    private final Map<LookupKey, ClaimResolution> resolutions = new HashMap<>();
    private long requestCount;
    private long providerCallCount;
    private long cacheHitCount;

    public ClaimLookupSession(@Nonnull ClaimPolicyContext context) {
        this(context, true, null);
    }

    public ClaimLookupSession(@Nonnull ClaimPolicyContext context, boolean extentRequired) {
        this(context, extentRequired, null);
    }

    public ClaimLookupSession(@Nonnull ClaimPolicyContext context,
                              boolean extentRequired,
                              @Nullable ClaimLookupMetrics sharedMetrics) {
        this.context = Objects.requireNonNull(context, "context");
        this.extentRequired = extentRequired;
        this.sharedMetrics = sharedMetrics;
        if (sharedMetrics != null) {
            sharedMetrics.sessionStarted(context);
        }
    }

    @Nonnull
    public ClaimPolicyContext context() {
        return context;
    }

    @Nonnull
    public ClaimResolution resolveBlock(@Nullable String worldName, double blockX, double blockZ) {
        requestCount++;
        if (sharedMetrics != null) {
            sharedMetrics.requestRecorded();
        }
        String normalizedWorld = normalizeWorld(worldName);
        if (normalizedWorld == null) {
            return ClaimResolution.error("Claim lookup world is missing.");
        }
        if (!Double.isFinite(blockX) || !Double.isFinite(blockZ)) {
            return ClaimResolution.error("Claim lookup coordinates must be finite.");
        }
        int chunkX = ChunkUtil.chunkCoordinate(blockX);
        int chunkZ = ChunkUtil.chunkCoordinate(blockZ);
        LookupKey key = new LookupKey(
                context.providerId(),
                context.providerGeneration(),
                normalizedWorld,
                chunkX,
                chunkZ
        );
        ClaimResolution cached = resolutions.get(key);
        if (cached != null) {
            cacheHitCount++;
            if (sharedMetrics != null) {
                sharedMetrics.cacheHitRecorded();
            }
            return cached;
        }
        if (sharedMetrics != null) {
            sharedMetrics.uniqueChunkRecorded();
        }
        ClaimResolution resolved = callProvider(normalizedWorld, blockX, blockZ, chunkX, chunkZ);
        resolutions.put(key, resolved);
        return resolved;
    }

    @Nonnull
    public ClaimResolution resolveChunk(@Nonnull ClaimChunkCoordinate chunk) {
        Objects.requireNonNull(chunk, "chunk");
        double blockX = (double) ChunkUtil.minBlock(chunk.chunkX()) + (ChunkUtil.SIZE / 2.0);
        double blockZ = (double) ChunkUtil.minBlock(chunk.chunkZ()) + (ChunkUtil.SIZE / 2.0);
        return resolveBlock(chunk.worldName(), blockX, blockZ);
    }

    public long requestCount() {
        return requestCount;
    }

    public long providerCallCount() {
        return providerCallCount;
    }

    public long cacheHitCount() {
        return cacheHitCount;
    }

    public int uniqueChunkCount() {
        return resolutions.size();
    }

    @Nonnull
    private ClaimResolution callProvider(String worldName,
                                         double blockX,
                                         double blockZ,
                                         int chunkX,
                                         int chunkZ) {
        if (!context.ready() || context.bridge() == null) {
            return ClaimResolution.unavailable(context.reason());
        }
        ClaimIntegrationBridge bridge = context.bridge();
        if (!bridge.isAvailable()) {
            return ClaimResolution.unavailable(bridge.getUnavailableReason());
        }
        providerCallCount++;
        long startedNanos = System.nanoTime();
        try {
            ClaimResolution resolution = extentRequired
                    ? bridge.resolveClaim(worldName, blockX, blockZ)
                    : ClaimResolution.fromLookupResult(
                            bridge.lookupClaim(worldName, blockX, blockZ)
                    );
            return validateResolution(resolution, worldName, chunkX, chunkZ);
        } catch (RuntimeException | LinkageError exception) {
            return ClaimResolution.error("Claim provider lookup failed: " + exception.getClass().getSimpleName());
        } finally {
            if (sharedMetrics != null) {
                sharedMetrics.providerCallRecorded(System.nanoTime() - startedNanos);
            }
        }
    }

    @Nonnull
    private ClaimResolution validateResolution(@Nullable ClaimResolution resolution,
                                               String worldName,
                                               int chunkX,
                                               int chunkZ) {
        if (resolution == null) {
            return ClaimResolution.error("Claim provider returned no resolution.");
        }
        if (resolution.status() != ClaimLookupResult.Status.CLAIM_FOUND) {
            return resolution;
        }
        ClaimPopulationKey key = resolution.key();
        if (key == null) {
            return ClaimResolution.error("Claim provider returned a found result without an identity.");
        }
        if (!context.providerId().equals(key.providerId()) || !worldName.equals(key.worldName())) {
            return ClaimResolution.error("Claim provider returned an identity for the wrong provider or world.");
        }
        ClaimFootprint footprint = resolution.footprint();
        if (footprint == null) {
            return resolution;
        }
        ClaimChunkCoordinate queried = new ClaimChunkCoordinate(worldName, chunkX, chunkZ);
        for (ClaimChunkCoordinate coordinate : footprint.chunks()) {
            if (!worldName.equals(coordinate.worldName())) {
                return ClaimResolution.error("Claim footprint crossed world boundaries.");
            }
        }
        if (!footprint.chunks().isEmpty() && !footprint.chunks().contains(queried)) {
            return ClaimResolution.error("Claim footprint did not contain the queried chunk.");
        }
        return resolution;
    }

    @Nullable
    private static String normalizeWorld(@Nullable String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return null;
        }
        return worldName.trim();
    }

    private record LookupKey(@Nonnull String providerId,
                             @Nonnull ClaimProviderGeneration generation,
                             @Nonnull String worldName,
                             int chunkX,
                             int chunkZ) {
    }
}
