package com.alechilles.alecstamework.integration.questlinesclaims;

import com.alechilles.alecstamework.integration.claims.ClaimLookupResult;
import com.alechilles.alecstamework.integration.claims.ClaimPopulationKey;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Regression coverage for the optional QuestLines Claims bridge. */
class QuestLinesClaimsBridgeTest {
    @Test
    void lookupReturnsNoClaimWhenApiReturnsNull() {
        QuestLinesClaimsBridge bridge = QuestLinesClaimsBridge.forApiForTests(new FakeApi(null));

        ClaimLookupResult result = bridge.lookupClaim("world", 15, 31);

        assertEquals(ClaimLookupResult.Status.NO_CLAIM, result.status());
        assertNull(result.key());
    }

    @Test
    void lookupBuildsPopulationKeyFromClaimAccessors() {
        UUID ownerId = UUID.randomUUID();
        FakeClaim claim = new FakeClaim(42, "PLAYER", ownerId, 7);
        QuestLinesClaimsBridge bridge = QuestLinesClaimsBridge.forApiForTests(new FakeApi(claim));

        ClaimLookupResult result = bridge.lookupClaim("world", 15, 31);

        assertEquals(ClaimLookupResult.Status.CLAIM_FOUND, result.status());
        assertEquals(7, result.claimChunkCount());
        ClaimPopulationKey key = result.key();
        assertNotNull(key);
        assertEquals("questlines-claims", key.providerId());
        assertEquals("world", key.worldName());
        assertEquals("PLAYER", key.ownerType());
        assertEquals(ownerId, key.ownerId());
        assertEquals("42", key.claimId());
    }

    @Test
    void lookupErrorsWhenOwnerUuidIsMissing() {
        QuestLinesClaimsBridge bridge = QuestLinesClaimsBridge.forApiForTests(
                new FakeApi(new FakeClaim(5, "PLAYER", null, 1))
        );

        ClaimLookupResult result = bridge.lookupClaim("world", 0, 0);

        assertEquals(ClaimLookupResult.Status.ERROR, result.status());
    }

    public static final class FakeApi {
        private final FakeClaim claim;

        FakeApi(FakeClaim claim) {
            this.claim = claim;
        }

        public FakeClaim getClaimAtBlock(String world, int bx, int bz) {
            return claim;
        }
    }

    public static final class FakeClaim {
        private final int id;
        private final String ownerType;
        private final UUID ownerUuid;
        private final int chunkCount;

        FakeClaim(int id, String ownerType, UUID ownerUuid, int chunkCount) {
            this.id = id;
            this.ownerType = ownerType;
            this.ownerUuid = ownerUuid;
            this.chunkCount = chunkCount;
        }

        public int getId() {
            return id;
        }

        public String getOwnerType() {
            return ownerType;
        }

        public UUID getOwnerUuid() {
            return ownerUuid;
        }

        public int getChunkCount() {
            return chunkCount;
        }
    }
}
