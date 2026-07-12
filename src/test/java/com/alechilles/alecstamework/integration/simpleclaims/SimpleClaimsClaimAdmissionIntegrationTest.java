package com.alechilles.alecstamework.integration.simpleclaims;

import com.alechilles.alecstamework.integration.claims.ClaimAdmissionDecision;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionOperation;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionRequest;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionService;
import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.alechilles.alecstamework.integration.claims.ClaimLookupSession;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyReadiness;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyTransition;
import com.alechilles.alecstamework.integration.claims.ClaimPolicyContext;
import com.alechilles.alecstamework.integration.claims.ClaimProviderCapability;
import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.integration.claims.ClaimProviderState;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.hypixel.hytale.math.util.ChunkUtil;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end admission regressions over an API-shaped SimpleClaims 1.0.38 bridge. */
class SimpleClaimsClaimAdmissionIntegrationTest {
    private static final String WORLD = "world";
    private static final UUID PARTY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OWNER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final ClaimChunkCoordinate DESTINATION = new ClaimChunkCoordinate(WORLD, 0, 0);

    @BeforeEach
    void resetFixture() {
        AdmissionClaimManager.reset();
    }

    @Test
    void totalOnlyAdmissionCountsLookupOnlyPopulationWithoutTraversingProviderExtent() {
        AdmissionClaimManager manager = claimedChunks(0, 1, 2);
        ClaimOccupancyIndex index = readyIndex(List.of(
                entry("existing-0", 0),
                entry("existing-1", 1),
                entry("existing-2", 2)
        ));
        ClaimAdmissionService service = new ClaimAdmissionService(index);
        ClaimPolicyContext context = context();
        ClaimLookupSession session = new ClaimLookupSession(context, false);

        ClaimAdmissionDecision decision = service.reserve(request(context, 0, 4, false), session);

        assertTrue(decision.allowed());
        assertEquals(3L, decision.committedPopulation());
        assertEquals(1L, decision.requestedSlots());
        assertEquals(0, manager.extentCalls, "A total-only policy must not request SimpleClaims topology.");
        assertEquals(3, manager.lookupCalls, "Each unique occupied claim chunk should use identity lookup only.");
        assertEquals(3L, session.providerCallCount());
        assertEquals(3, session.uniqueChunkCount());
    }

    @Test
    void lookupOnlyAdmissionStopsAfterTargetIdentityWithoutTraversingPopulationOrExtent() {
        AdmissionClaimManager manager = claimedChunks(0, 1, 2);
        ClaimOccupancyIndex index = readyIndex(List.of(
                entry("existing-0", 0),
                entry("existing-1", 1),
                entry("existing-2", 2)
        ));
        ClaimAdmissionService service = new ClaimAdmissionService(index);
        ClaimPolicyContext context = context();
        ClaimLookupSession session = new ClaimLookupSession(context, false);

        ClaimAdmissionDecision decision = service.reserve(request(context, 0, 0, true), session);

        assertTrue(decision.allowed());
        assertEquals(0L, decision.committedPopulation());
        assertEquals(1, manager.lookupCalls, "Population traversal would perform additional chunk lookups.");
        assertEquals(0, manager.extentCalls, "Lookup-only admission must not request SimpleClaims topology.");
        assertEquals(1L, session.requestCount());
        assertEquals(1L, session.providerCallCount());
        assertEquals(1, session.uniqueChunkCount());
    }

    private static ClaimAdmissionRequest request(ClaimPolicyContext context,
                                                 int perChunkLimit,
                                                 int totalLimit,
                                                 boolean requireClaim) {
        ClaimOccupancyEntry proposed = new ClaimOccupancyEntry(
                "new-profile",
                OWNER_ID,
                CompanionLifecycleState.ACTIVE,
                DESTINATION,
                1L
        );
        return new ClaimAdmissionRequest(
                ClaimAdmissionOperation.EXTERNAL,
                List.of(new ClaimOccupancyTransition(null, proposed)),
                DESTINATION,
                context,
                perChunkLimit,
                totalLimit,
                requireClaim,
                false,
                60_000_000_000L
        );
    }

    private static ClaimPolicyContext context() {
        SimpleClaimsBreedingBridge bridge = SimpleClaimsBreedingBridge.forTypesForTests(
                AdmissionClaimManager.class,
                AdmissionChunk.class,
                AdmissionParty.class
        );
        return new ClaimPolicyContext(
                "SimpleClaims",
                ClaimIntegrationProvider.SIMPLE_CLAIMS,
                ClaimIntegrationProvider.SIMPLE_CLAIMS,
                bridge.providerId(),
                ClaimProviderState.READY,
                Set.of(
                        ClaimProviderCapability.STABLE_CLAIM_IDENTITY,
                        ClaimProviderCapability.WORLD_SCOPED_EXTENT
                ),
                "1.0.38",
                null,
                new ClaimProviderGeneration("fixture-instance", "fixture-loader", 1L),
                1L,
                bridge
        );
    }

    private static ClaimOccupancyIndex readyIndex(List<ClaimOccupancyEntry> entries) {
        ClaimOccupancyIndex index = new ClaimOccupancyIndex();
        index.replaceCommittedEntries(entries, ClaimOccupancyReadiness.READY);
        return index;
    }

    private static ClaimOccupancyEntry entry(String profileId, int chunkX) {
        return new ClaimOccupancyEntry(
                profileId,
                OWNER_ID,
                CompanionLifecycleState.ACTIVE,
                new ClaimChunkCoordinate(WORLD, chunkX, 0),
                1L
        );
    }

    private static AdmissionClaimManager claimedChunks(int... chunkCoordinates) {
        AdmissionClaimManager manager = AdmissionClaimManager.getInstance();
        AdmissionParty party = new AdmissionParty(PARTY_ID);
        manager.addParty(party);
        for (int chunkX : chunkCoordinates) {
            manager.putChunk(WORLD, party, chunkX, 0);
        }
        return manager;
    }

    /** Minimal manager fixture matching every independently reflected SimpleClaims capability. */
    public static final class AdmissionClaimManager {
        private static AdmissionClaimManager instance = new AdmissionClaimManager();

        private final Map<UUID, AdmissionParty> parties = new HashMap<>();
        private final Map<String, Map<String, AdmissionChunk>> chunks = new HashMap<>();
        private int lookupCalls;
        private int extentCalls;

        public static AdmissionClaimManager getInstance() {
            return instance;
        }

        static void reset() {
            instance = new AdmissionClaimManager();
        }

        public AdmissionChunk getChunkRawCoords(String worldName, int blockX, int blockZ) {
            lookupCalls++;
            Map<String, AdmissionChunk> worldChunks = chunks.get(worldName);
            return worldChunks == null
                    ? null
                    : worldChunks.get(coordinate(ChunkUtil.chunkCoordinate(blockX), ChunkUtil.chunkCoordinate(blockZ)));
        }

        public AdmissionParty getPartyById(UUID partyId) {
            return parties.get(partyId);
        }

        public Map<String, Map<String, AdmissionChunk>> getChunks() {
            extentCalls++;
            return chunks;
        }

        public boolean isAllowedToInteract(UUID playerId,
                                           String worldName,
                                           int blockX,
                                           int blockZ,
                                           Predicate<AdmissionParty> outsiderFallback,
                                           String permissionKey) {
            return true;
        }

        void addParty(AdmissionParty party) {
            parties.put(party.id, party);
        }

        void putChunk(String worldName, AdmissionParty party, int chunkX, int chunkZ) {
            chunks.computeIfAbsent(worldName, ignored -> new HashMap<>())
                    .put(coordinate(chunkX, chunkZ), new AdmissionChunk(party.id, chunkX, chunkZ));
        }

        private static String coordinate(int chunkX, int chunkZ) {
            return chunkX + "," + chunkZ;
        }
    }

    /** Minimal claim chunk fixture for identity and optional extent reflection. */
    public record AdmissionChunk(UUID partyOwner, int chunkX, int chunkZ) {
        public UUID getPartyOwner() {
            return partyOwner;
        }

        public int getChunkX() {
            return chunkX;
        }

        public int getChunkZ() {
            return chunkZ;
        }
    }

    /** Minimal party fixture for lookup validation and native-damage capability probing. */
    public static final class AdmissionParty {
        private final UUID id;

        AdmissionParty(UUID id) {
            this.id = id;
        }

        public boolean isTamedDamageEnabled() {
            return true;
        }
    }
}
