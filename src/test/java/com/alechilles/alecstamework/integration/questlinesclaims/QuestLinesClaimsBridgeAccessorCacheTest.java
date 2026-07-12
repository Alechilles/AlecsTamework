package com.alechilles.alecstamework.integration.questlinesclaims;

import com.alechilles.alecstamework.integration.claims.ClaimLookupResult;
import com.alechilles.alecstamework.integration.claims.ClaimResolution;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies accessor discovery cost is bounded by runtime classes, not claims or footprint size. */
class QuestLinesClaimsBridgeAccessorCacheTest {
    @Test
    void methodDiscoveryIsConstantAcrossLargeFootprints() {
        for (int size : new int[]{100, 1_000, 5_000}) {
            CountingMethodFinder methods = new CountingMethodFinder();
            QuestLinesClaimsBridge bridge = QuestLinesClaimsBridge.forApiForTests(
                    new CachedApi(new CachedClaim(size, chunks(size))),
                    methods
            );

            ClaimResolution result = bridge.resolveClaim("world", 0, 0);

            assertEquals(ClaimLookupResult.Status.CLAIM_FOUND, result.status());
            assertEquals(size, result.claimChunkCount());
            assertEquals(
                    9,
                    methods.count(),
                    () -> "Accessor discovery must not scale with " + size + " footprint elements."
            );
        }
    }

    @Test
    void repeatedClaimHitsReuseClaimAndCoordinateAccessorBundles() {
        CountingMethodFinder methods = new CountingMethodFinder();
        QuestLinesClaimsBridge bridge = QuestLinesClaimsBridge.forApiForTests(
                new CachedApi(new CachedClaim(61, chunks(100))),
                methods
        );

        assertEquals(ClaimLookupResult.Status.CLAIM_FOUND, bridge.resolveClaim("world", 0, 0).status());
        int firstDiscoveryCount = methods.count();
        for (int hit = 0; hit < 100; hit++) {
            assertEquals(ClaimLookupResult.Status.CLAIM_FOUND, bridge.resolveClaim("world", hit, hit).status());
        }

        assertEquals(9, firstDiscoveryCount);
        assertEquals(firstDiscoveryCount, methods.count());
    }

    @Test
    void missingAndUnsupportedAccessorBundlesAreNegativeCached() {
        CountingMethodFinder missingMethods = new CountingMethodFinder();
        QuestLinesClaimsBridge missingExtent = QuestLinesClaimsBridge.forApiForTests(
                new MissingExtentApi(new MissingExtentClaim()),
                missingMethods
        );
        assertEquals(ClaimLookupResult.Status.ERROR, missingExtent.resolveClaim("world", 0, 0).status());
        int missingDiscoveryCount = missingMethods.count();
        assertEquals(ClaimLookupResult.Status.ERROR, missingExtent.resolveClaim("world", 0, 0).status());
        assertEquals(6, missingDiscoveryCount);
        assertEquals(missingDiscoveryCount, missingMethods.count());

        Map<CachedChunk, String> coordinatesAsKeys = new LinkedHashMap<>();
        coordinatesAsKeys.put(new CachedChunk(1, 2), "metadata-a");
        coordinatesAsKeys.put(new CachedChunk(3, 4), "metadata-b");
        CountingMethodFinder unsupportedMethods = new CountingMethodFinder();
        QuestLinesClaimsBridge unsupportedValues = QuestLinesClaimsBridge.forApiForTests(
                new CachedApi(new CachedClaim(72, coordinatesAsKeys)),
                unsupportedMethods
        );
        assertEquals(ClaimLookupResult.Status.CLAIM_FOUND, unsupportedValues.resolveClaim("world", 0, 0).status());
        int unsupportedDiscoveryCount = unsupportedMethods.count();
        assertEquals(ClaimLookupResult.Status.CLAIM_FOUND, unsupportedValues.resolveClaim("world", 0, 0).status());

        assertEquals(15, unsupportedDiscoveryCount);
        assertEquals(unsupportedDiscoveryCount, unsupportedMethods.count());
    }

    private static LinkedHashSet<CachedChunk> chunks(int count) {
        LinkedHashSet<CachedChunk> chunks = new LinkedHashSet<>();
        for (int index = 0; index < count; index++) {
            chunks.add(new CachedChunk(index - 2, index * 2 - 3));
        }
        return chunks;
    }

    private static final class CountingMethodFinder implements QuestLinesReflectionAccess.MethodFinder {
        private final AtomicInteger discoveries = new AtomicInteger();

        @Override
        public Method find(Class<?> type, String name, Class<?>... parameterTypes) {
            discoveries.incrementAndGet();
            return QuestLinesReflectionAccess.findMethod(type, name, parameterTypes);
        }

        int count() {
            return discoveries.get();
        }
    }

    public record CachedApi(CachedClaim claim) {
        public CachedClaim getClaimAtBlock(String worldName, int blockX, int blockZ) {
            return claim;
        }
    }

    public record CachedClaim(Object claimId, Object chunks) {
        public Object getClaimId() {
            return claimId;
        }

        public String getOwnerType() {
            return "PLAYER";
        }

        public UUID getOwnerUuid() {
            return UUID.fromString("00000000-0000-0000-0000-000000000001");
        }

        public String getWorldName() {
            return "world";
        }

        public Object getChunks() {
            return chunks;
        }
    }

    public record CachedChunk(int chunkX, int chunkZ) {
        public int getChunkX() {
            return chunkX;
        }

        public int getChunkZ() {
            return chunkZ;
        }

        public String getWorldName() {
            return "world";
        }
    }

    public record MissingExtentApi(MissingExtentClaim claim) {
        public MissingExtentClaim getClaimAtBlock(String worldName, int blockX, int blockZ) {
            return claim;
        }
    }

    public static final class MissingExtentClaim {
        public int getClaimId() {
            return 73;
        }

        public String getOwnerType() {
            return "PLAYER";
        }

        public UUID getOwnerUuid() {
            return UUID.fromString("00000000-0000-0000-0000-000000000002");
        }

        public String getWorldName() {
            return "world";
        }

        public int getChunkCount() {
            return 2;
        }
    }
}
