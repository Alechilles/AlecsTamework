package com.alechilles.alecstamework.integration.questlinesclaims;

import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimFootprint;
import com.alechilles.alecstamework.integration.claims.ClaimLookupResult;
import com.alechilles.alecstamework.integration.claims.ClaimPopulationKey;
import com.alechilles.alecstamework.integration.claims.ClaimResolution;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the optional QuestLines Claims bridge. */
class QuestLinesClaimsBridgeTest {
    @Test
    void lookupReturnsNoClaimWhenApiReturnsNull() {
        QuestLinesClaimsBridge bridge = QuestLinesClaimsBridge.forApiForTests(new FakeApi(null));

        ClaimResolution result = bridge.resolveClaim("world", 15, 31);

        assertEquals(ClaimLookupResult.Status.NO_CLAIM, result.status());
        assertNull(result.key());
        assertNull(result.footprint());
    }

    @Test
    void twelveChunkClaimUsesVerifiedQuestLinesAccessors() {
        UUID ownerId = UUID.randomUUID();
        FakeClaim claim = claim(42, FakeOwnerType.GUILD, ownerId, "world", chunks("world", 12));
        QuestLinesClaimsBridge bridge = QuestLinesClaimsBridge.forApiForTests(new FakeApi(claim));

        ClaimResolution resolution = bridge.resolveClaim("world", 15, 31);
        ClaimLookupResult legacy = bridge.lookupClaim("world", 15, 31);

        assertEquals(ClaimLookupResult.Status.CLAIM_FOUND, resolution.status());
        assertEquals(12, resolution.claimChunkCount());
        assertEquals(12, legacy.claimChunkCount());
        ClaimFootprint footprint = resolution.footprint();
        assertNotNull(footprint);
        assertEquals(12, footprint.chunkCount());
        assertFalse(footprint.digest().isBlank());
        ClaimPopulationKey key = resolution.key();
        assertNotNull(key);
        assertEquals("questlines-claims", key.providerId());
        assertEquals("world", key.worldName());
        assertEquals("GUILD", key.ownerType());
        assertEquals(ownerId, key.ownerId());
        assertEquals("42", key.claimId());
    }

    @Test
    void twoClaimIdsOwnedBySameOwnerRemainDistinctPopulationKeys() {
        UUID ownerId = UUID.randomUUID();
        QuestLinesClaimsBridge firstBridge = QuestLinesClaimsBridge.forApiForTests(
                new FakeApi(claim(101, FakeOwnerType.PLAYER, ownerId, "world", chunks("world", 2)))
        );
        QuestLinesClaimsBridge secondBridge = QuestLinesClaimsBridge.forApiForTests(
                new FakeApi(claim(202, FakeOwnerType.PLAYER, ownerId, "world", chunks("world", 2)))
        );

        ClaimPopulationKey first = firstBridge.resolveClaim("world", 0, 0).key();
        ClaimPopulationKey second = secondBridge.resolveClaim("world", 0, 0).key();

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.ownerId(), second.ownerId());
        assertNotEquals(first.claimId(), second.claimId());
        assertNotEquals(first, second);
    }

    @Test
    void claimWorldMustMatchRequestedWorld() {
        FakeClaim claim = claim(1, FakeOwnerType.PLAYER, UUID.randomUUID(), "other", chunks("other", 1));
        QuestLinesClaimsBridge bridge = QuestLinesClaimsBridge.forApiForTests(new FakeApi(claim));

        ClaimResolution result = bridge.resolveClaim("world", 0, 0);

        assertEquals(ClaimLookupResult.Status.ERROR, result.status());
        assertTrue(result.message().contains("claim world"));
    }

    @Test
    void everyChunkWorldMustMatchRequestedWorld() {
        List<FakeChunk> footprint = List.of(new FakeChunk(0, 0, "world"), new FakeChunk(1, 0, "other"));
        FakeClaim claim = claim(1, FakeOwnerType.PLAYER, UUID.randomUUID(), "world", footprint);
        QuestLinesClaimsBridge bridge = QuestLinesClaimsBridge.forApiForTests(new FakeApi(claim));

        ClaimResolution result = bridge.resolveClaim("world", 0, 0);

        assertEquals(ClaimLookupResult.Status.ERROR, result.status());
        assertTrue(result.message().contains("chunk world"));
    }

    @Test
    void footprintDeduplicatesAndRetainsNegativeCoordinates() {
        List<FakeChunk> raw = List.of(
                new FakeChunk(4, 2, "world"),
                new FakeChunk(-3, -8, "world"),
                new FakeChunk(-3, -8, "world")
        );
        FakeClaim claim = claim(9, FakeOwnerType.PLAYER, UUID.randomUUID(), "world", raw);
        QuestLinesClaimsBridge bridge = QuestLinesClaimsBridge.forApiForTests(new FakeApi(claim));

        ClaimResolution result = bridge.resolveClaim("world", 0, 0);

        assertEquals(ClaimLookupResult.Status.CLAIM_FOUND, result.status());
        assertEquals(2, result.claimChunkCount());
        assertEquals(
                List.of(
                        new ClaimChunkCoordinate("world", -3, -8),
                        new ClaimChunkCoordinate("world", 4, 2)
                ),
                result.footprint().chunks()
        );
    }

    @Test
    void mapExtentAcceptsCoordinatesFromValuesAndKeys() {
        FakeChunk first = new FakeChunk(-4, 7, "world");
        FakeChunk second = new FakeChunk(12, -9, "world");
        Map<String, FakeChunk> coordinatesAsValues = new LinkedHashMap<>();
        coordinatesAsValues.put("first", first);
        coordinatesAsValues.put("second", second);
        Map<FakeChunk, String> coordinatesAsKeys = new LinkedHashMap<>();
        coordinatesAsKeys.put(first, "first");
        coordinatesAsKeys.put(second, "second");

        ClaimResolution valueResult = QuestLinesClaimsBridge.forApiForTests(
                new FakeApi(claim(31, FakeOwnerType.PLAYER, UUID.randomUUID(), "world", coordinatesAsValues))
        ).resolveClaim("world", 0, 0);
        ClaimResolution keyResult = QuestLinesClaimsBridge.forApiForTests(
                new FakeApi(claim(32, FakeOwnerType.PLAYER, UUID.randomUUID(), "world", coordinatesAsKeys))
        ).resolveClaim("world", 0, 0);

        assertEquals(ClaimLookupResult.Status.CLAIM_FOUND, valueResult.status());
        assertEquals(ClaimLookupResult.Status.CLAIM_FOUND, keyResult.status());
        assertEquals(valueResult.footprint().chunks(), keyResult.footprint().chunks());
    }

    @Test
    void arrayExtentProducesACompleteFootprint() {
        FakeChunk[] array = chunks("world", 5).toArray(FakeChunk[]::new);
        FakeClaim claim = claim(41, FakeOwnerType.PLAYER, UUID.randomUUID(), "world", array);

        ClaimResolution result = QuestLinesClaimsBridge.forApiForTests(
                new FakeApi(claim)
        ).resolveClaim("world", 0, 0);

        assertEquals(ClaimLookupResult.Status.CLAIM_FOUND, result.status());
        assertNotNull(result.footprint());
        assertEquals(5, result.footprint().chunkCount());
    }

    @Test
    void collectionIsSnapshottedBeforeCoordinateTraversal() {
        SnapshotClearingCollection<FakeChunk> mutating = new SnapshotClearingCollection<>(
                chunks("world", 4)
        );
        FakeClaim claim = claim(51, FakeOwnerType.PLAYER, UUID.randomUUID(), "world", mutating);

        ClaimResolution result = QuestLinesClaimsBridge.forApiForTests(
                new FakeApi(claim)
        ).resolveClaim("world", 0, 0);

        assertTrue(mutating.isEmpty(), "The source should mutate immediately after yielding its snapshot.");
        assertEquals(ClaimLookupResult.Status.CLAIM_FOUND, result.status());
        assertEquals(4, result.claimChunkCount());
    }

    @Test
    void emptyChunkCollectionIsAnExplicitContractError() {
        FakeClaim claim = claim(9, FakeOwnerType.PLAYER, UUID.randomUUID(), "world", Set.of());
        QuestLinesClaimsBridge bridge = QuestLinesClaimsBridge.forApiForTests(new FakeApi(claim));

        ClaimResolution result = bridge.resolveClaim("world", 0, 0);

        assertEquals(ClaimLookupResult.Status.ERROR, result.status());
        assertTrue(result.message().contains("empty"));
    }

    @Test
    void malformedChunkElementIsAnExplicitContractError() {
        FakeClaim claim = claim(
                9,
                FakeOwnerType.PLAYER,
                UUID.randomUUID(),
                "world",
                List.of(new MalformedChunk(0, "world"))
        );
        QuestLinesClaimsBridge bridge = QuestLinesClaimsBridge.forApiForTests(new FakeApi(claim));

        ClaimResolution result = bridge.resolveClaim("world", 0, 0);

        assertEquals(ClaimLookupResult.Status.ERROR, result.status());
        assertTrue(result.message().contains("missing X, Z, or world"));
    }

    @Test
    void missingClaimIdUsesStableOrderIndependentFootprintIdentity() {
        UUID ownerId = UUID.randomUUID();
        ArrayList<FakeChunk> forward = new ArrayList<>(chunks("world", 4));
        ArrayList<FakeChunk> reversed = new ArrayList<>(forward);
        Collections.reverse(reversed);
        QuestLinesClaimsBridge forwardBridge = QuestLinesClaimsBridge.forApiForTests(
                new FakeApi(claim(null, FakeOwnerType.PLAYER, ownerId, "world", forward))
        );
        QuestLinesClaimsBridge reversedBridge = QuestLinesClaimsBridge.forApiForTests(
                new FakeApi(claim(null, FakeOwnerType.PLAYER, ownerId, "world", reversed))
        );

        ClaimResolution forwardResult = forwardBridge.resolveClaim("world", 0, 0);
        ClaimResolution reversedResult = reversedBridge.resolveClaim("world", 0, 0);

        assertEquals(ClaimLookupResult.Status.CLAIM_FOUND, forwardResult.status());
        assertNotNull(forwardResult.key());
        assertTrue(forwardResult.key().claimId().startsWith("footprint:"));
        assertEquals(forwardResult.key().claimId(), reversedResult.key().claimId());
        assertEquals(forwardResult.footprint().digest(), reversedResult.footprint().digest());
    }

    @Test
    void apiInvocationFailureReturnsErrorWithoutThrowing() {
        QuestLinesClaimsBridge bridge = QuestLinesClaimsBridge.forApiForTests(new ThrowingApi());

        ClaimResolution result = bridge.resolveClaim("world", 0, 0);

        assertEquals(ClaimLookupResult.Status.ERROR, result.status());
        assertTrue(result.message().contains("lookup exploded"));
    }

    @Test
    void chunkAccessorInvocationFailureReturnsErrorWithoutNumericFallback() {
        ThrowingChunksClaim claim = new ThrowingChunksClaim(
                8,
                FakeOwnerType.PLAYER,
                UUID.randomUUID(),
                "world"
        );
        QuestLinesClaimsBridge bridge = QuestLinesClaimsBridge.forApiForTests(new FakeApi(claim));

        ClaimResolution result = bridge.resolveClaim("world", 0, 0);

        assertEquals(ClaimLookupResult.Status.ERROR, result.status());
        assertTrue(result.message().contains("chunks exploded"));
    }

    @Test
    void numericChunkCountCannotSubstituteForVerifiedGetChunksExtent() {
        LegacyClaim claim = new LegacyClaim(7, UUID.randomUUID(), "world", 3);
        QuestLinesClaimsBridge bridge = QuestLinesClaimsBridge.forApiForTests(new LegacyApi(claim));

        ClaimResolution result = bridge.resolveClaim("world", 0, 0);

        assertEquals(ClaimLookupResult.Status.ERROR, result.status());
        assertNull(result.footprint());
        assertNull(result.key());
        assertTrue(result.message().contains("getChunks() accessor is missing"));
    }

    @Test
    void missingClaimIdWithoutCompleteFootprintNeverSynthesizesIdentity() {
        ClaimResolution malformed = QuestLinesClaimsBridge.forApiForTests(new FakeApi(claim(
                null,
                FakeOwnerType.PLAYER,
                UUID.randomUUID(),
                "world",
                List.of(new MalformedChunk(0, "world"))
        ))).resolveClaim("world", 0, 0);
        ClaimResolution missingExtent = QuestLinesClaimsBridge.forApiForTests(new LegacyApi(
                new LegacyClaim(null, UUID.randomUUID(), "world", 4)
        )).resolveClaim("world", 0, 0);

        assertEquals(ClaimLookupResult.Status.ERROR, malformed.status());
        assertNull(malformed.key());
        assertTrue(malformed.message().contains("missing X, Z, or world"));
        assertEquals(ClaimLookupResult.Status.ERROR, missingExtent.status());
        assertNull(missingExtent.key());
        assertTrue(missingExtent.message().contains("getChunks() accessor is missing"));
    }

    @Test
    void lookupErrorsWhenOwnerUuidIsMissing() {
        FakeClaim claim = claim(5, FakeOwnerType.PLAYER, null, "world", chunks("world", 1));
        QuestLinesClaimsBridge bridge = QuestLinesClaimsBridge.forApiForTests(new FakeApi(claim));

        ClaimLookupResult result = bridge.lookupClaim("world", 0, 0);

        assertEquals(ClaimLookupResult.Status.ERROR, result.status());
    }

    private static FakeClaim claim(@Nullable Object id,
                                   FakeOwnerType ownerType,
                                   UUID ownerUuid,
                                   String worldName,
                                   Object chunks) {
        return new FakeClaim(id, ownerType, ownerUuid, worldName, chunks);
    }

    private static LinkedHashSet<FakeChunk> chunks(String worldName, int count) {
        LinkedHashSet<FakeChunk> chunks = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            chunks.add(new FakeChunk(i - 2, i * 2 - 3, worldName));
        }
        return chunks;
    }

    private static final class SnapshotClearingCollection<E> extends AbstractCollection<E> {
        private final ArrayList<E> values;

        private SnapshotClearingCollection(Collection<E> values) {
            this.values = new ArrayList<>(values);
        }

        @Override
        public Iterator<E> iterator() {
            return values.iterator();
        }

        @Override
        public int size() {
            return values.size();
        }

        @Override
        public Object[] toArray() {
            Object[] snapshot = values.toArray();
            values.clear();
            return snapshot;
        }

        @Override
        public <T> T[] toArray(T[] target) {
            T[] snapshot = values.toArray(target);
            values.clear();
            return snapshot;
        }
    }

    public enum FakeOwnerType {
        PLAYER,
        GUILD
    }

    public static class FakeApi {
        private final FakeClaim claim;

        FakeApi(FakeClaim claim) {
            this.claim = claim;
        }

        public FakeClaim getClaimAtBlock(String world, int bx, int bz) {
            return claim;
        }
    }

    public static final class ThrowingApi {
        public FakeClaim getClaimAtBlock(String world, int bx, int bz) {
            throw new IllegalStateException("lookup exploded");
        }
    }

    public static class FakeClaim {
        private final Object claimId;
        private final FakeOwnerType ownerType;
        private final UUID ownerUuid;
        private final String worldName;
        private final Object chunks;

        FakeClaim(Object claimId, FakeOwnerType ownerType, UUID ownerUuid, String worldName, Object chunks) {
            this.claimId = claimId;
            this.ownerType = ownerType;
            this.ownerUuid = ownerUuid;
            this.worldName = worldName;
            this.chunks = chunks;
        }

        public Object getClaimId() {
            return claimId;
        }

        public FakeOwnerType getOwnerType() {
            return ownerType;
        }

        public UUID getOwnerUuid() {
            return ownerUuid;
        }

        public String getWorldName() {
            return worldName;
        }

        public Object getChunks() {
            return chunks;
        }
    }

    public static final class ThrowingChunksClaim extends FakeClaim {
        ThrowingChunksClaim(Object id, FakeOwnerType ownerType, UUID ownerUuid, String worldName) {
            super(id, ownerType, ownerUuid, worldName, null);
        }

        @Override
        public Object getChunks() {
            throw new IllegalStateException("chunks exploded");
        }

        public int getChunkCount() {
            return 99;
        }
    }

    public record FakeChunk(int chunkX, int chunkZ, String worldName) {
        public int getChunkX() {
            return chunkX;
        }

        public int getChunkZ() {
            return chunkZ;
        }

        public String getWorldName() {
            return worldName;
        }
    }

    public record MalformedChunk(int chunkX, String worldName) {
        public int getChunkX() {
            return chunkX;
        }

        public String getWorldName() {
            return worldName;
        }
    }

    public static final class LegacyApi {
        private final LegacyClaim claim;

        LegacyApi(LegacyClaim claim) {
            this.claim = claim;
        }

        public LegacyClaim getClaimAtBlock(String world, int bx, int bz) {
            return claim;
        }
    }

    public static final class LegacyClaim {
        private final Object claimId;
        private final UUID ownerUuid;
        private final String worldName;
        private final int chunkCount;

        LegacyClaim(Object claimId, UUID ownerUuid, String worldName, int chunkCount) {
            this.claimId = claimId;
            this.ownerUuid = ownerUuid;
            this.worldName = worldName;
            this.chunkCount = chunkCount;
        }

        public Object getClaimId() {
            return claimId;
        }

        public FakeOwnerType getOwnerType() {
            return FakeOwnerType.PLAYER;
        }

        public UUID getOwnerUuid() {
            return ownerUuid;
        }

        public String getWorldName() {
            return worldName;
        }

        public int getChunkCount() {
            return chunkCount;
        }
    }
}
