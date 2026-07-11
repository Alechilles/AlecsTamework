package com.alechilles.alecstamework.integration.simpleclaims;

import com.alechilles.alecstamework.integration.claims.ClaimLookupResult;
import com.alechilles.alecstamework.integration.claims.ClaimResolution;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the independently probed SimpleClaims 1.0.38 capabilities. */
class SimpleClaimsCapabilitiesTest {
    private static final UUID CLAIM_PARTY_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CLAIM_OWNER_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID ATTACKER_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID ATTACKER_PARTY_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");

    @BeforeEach
    void resetFixtures() {
        FixtureClaimManager.reset();
        LookupOnlyClaimManager.reset();
        MalformedClaimManager.reset();
        ThrowingClaimManager.reset();
    }

    @Test
    void extentIsWorldLocalAndPerChunkMathDoesNotCrossWorlds() {
        FixtureClaimManager manager = FixtureClaimManager.getInstance();
        manager.addParty(new FixtureParty(CLAIM_PARTY_ID, CLAIM_OWNER_ID));
        for (int index = 0; index < 5; index++) {
            manager.putChunk("world-a", CLAIM_PARTY_ID, index, 0);
            manager.putChunk("world-b", CLAIM_PARTY_ID, index + 20, 0);
        }
        SimpleClaimsBreedingBridge bridge = bridgeForFixture();

        ClaimResolution worldA = bridge.resolveClaim("world-a", 1.0, 1.0);
        ClaimResolution worldB = bridge.resolveClaim("world-b", 20 * 32.0, 1.0);

        assertEquals(ClaimLookupResult.Status.CLAIM_FOUND, worldA.status());
        assertEquals(ClaimLookupResult.Status.CLAIM_FOUND, worldB.status());
        assertNotNull(worldA.footprint());
        assertNotNull(worldB.footprint());
        assertEquals(5, worldA.footprint().chunkCount());
        assertEquals(5, worldB.footprint().chunkCount());
        assertTrue(worldA.footprint().chunks().stream().allMatch(chunk -> chunk.worldName().equals("world-a")));
        assertTrue(worldB.footprint().chunks().stream().allMatch(chunk -> chunk.worldName().equals("world-b")));
        assertEquals(10, 2 * worldA.claimChunkCount());
        assertEquals(10, 2 * worldB.claimChunkCount());
    }

    @Test
    void lookupAndTotalOnlyIdentityRemainReadyWithoutExtentOrDamageMethods() {
        LookupOnlyClaimManager manager = LookupOnlyClaimManager.getInstance();
        manager.party = new FixtureParty(CLAIM_PARTY_ID, CLAIM_OWNER_ID);
        manager.chunk = new FixtureChunk(CLAIM_PARTY_ID, 0, 0);
        SimpleClaimsBreedingBridge bridge = SimpleClaimsBreedingBridge.forTypesForTests(
                LookupOnlyClaimManager.class,
                FixtureChunk.class,
                FixtureParty.class
        );

        ClaimLookupResult lookup = bridge.lookupClaimIdentity("world", 1.0, 1.0);
        ClaimResolution extentRequired = bridge.resolveClaim("world", 1.0, 1.0);

        assertTrue(bridge.isAvailable());
        assertFalse(bridge.isExtentAvailable());
        assertFalse(bridge.isDamagePolicyAvailable());
        assertEquals(ClaimLookupResult.Status.CLAIM_FOUND, lookup.status());
        assertNotNull(lookup.key(), "A total-only policy still has its stable claim identity.");
        assertEquals(0, lookup.claimChunkCount(), "No global party count may stand in for world extent.");
        assertEquals(ClaimLookupResult.Status.UNAVAILABLE, extentRequired.status());
        assertTrue(extentRequired.message().contains("extent unavailable"));
        assertEquals(0, manager.getAmountOfClaimsCalls);
    }

    @Test
    void lookupOnlyPathMakesNoExtentOrPopulationCallsWhenExtentExists() {
        FixtureClaimManager manager = claimedWorld(false);
        SimpleClaimsBreedingBridge bridge = bridgeForFixture();

        ClaimLookupResult lookup = bridge.lookupClaimIdentity("world", 1.0, 1.0);

        assertEquals(ClaimLookupResult.Status.CLAIM_FOUND, lookup.status());
        assertNotNull(lookup.key());
        assertEquals(0, lookup.claimChunkCount());
        assertEquals(0, manager.getChunksCalls);
    }

    @Test
    void lookupValidationAndInvocationFailuresMapToExplicitErrors() {
        SimpleClaimsClaimLookup lookup = SimpleClaimsClaimLookup.forTypes(
                FixtureClaimManager.class,
                FixtureChunk.class
        );

        assertEquals(SimpleClaimsClaimLookup.Status.ERROR, lookup.lookup(null, 0, 0).status());
        assertEquals(SimpleClaimsClaimLookup.Status.ERROR, lookup.lookup("world", Double.NaN, 0).status());

        FixtureClaimManager.instance = null;
        SimpleClaimsClaimLookup.Result nullManager = lookup.lookup("world", 0, 0);
        assertEquals(SimpleClaimsClaimLookup.Status.ERROR, nullManager.status());
        assertTrue(nullManager.message().contains("manager was null"));

        SimpleClaimsClaimLookup malformed = SimpleClaimsClaimLookup.forTypes(
                MalformedClaimManager.class,
                MalformedChunk.class
        );
        SimpleClaimsClaimLookup.Result malformedOwner = malformed.lookup("world", 0, 0);
        assertEquals(SimpleClaimsClaimLookup.Status.ERROR, malformedOwner.status());
        assertTrue(malformedOwner.message().contains("not a UUID"));

        SimpleClaimsClaimLookup throwing = SimpleClaimsClaimLookup.forTypes(
                ThrowingClaimManager.class,
                FixtureChunk.class
        );
        SimpleClaimsClaimLookup.Result invocationFailure = throwing.lookup("world", 0, 0);
        assertEquals(SimpleClaimsClaimLookup.Status.ERROR, invocationFailure.status());
        assertTrue(invocationFailure.message().contains("fixture-lookup-failure"));
    }

    @Test
    void nullPartyIsAnExplicitLookupError() {
        FixtureClaimManager manager = FixtureClaimManager.getInstance();
        manager.putChunk("world", CLAIM_PARTY_ID, 0, 0);

        SimpleClaimsClaimLookup.Result result = SimpleClaimsClaimLookup.forTypes(
                FixtureClaimManager.class,
                FixtureChunk.class
        ).lookup("world", 0, 0);

        assertEquals(SimpleClaimsClaimLookup.Status.ERROR, result.status());
        assertEquals(CLAIM_PARTY_ID, result.partyId());
        assertTrue(result.message().contains("could not be resolved"));
    }

    @Test
    void extentRetriesConcurrentTopologyMutationAndUsesDeterministicTtl() {
        FixtureClaimManager manager = FixtureClaimManager.getInstance();
        manager.addParty(new FixtureParty(CLAIM_PARTY_ID, CLAIM_OWNER_ID));
        manager.putChunk("world", CLAIM_PARTY_ID, 0, 0);
        manager.remainingExtentFailures = 1;
        AtomicLong clock = new AtomicLong(100L);
        SimpleClaimsWorldExtent extent = SimpleClaimsWorldExtent.forTypes(
                FixtureClaimManager.class,
                FixtureChunk.class,
                50L,
                clock::get
        );

        SimpleClaimsWorldExtent.Result first = extent.resolve("world", CLAIM_PARTY_ID);
        manager.putChunk("world", CLAIM_PARTY_ID, 1, 0);
        SimpleClaimsWorldExtent.Result cached = extent.resolve("world", CLAIM_PARTY_ID);
        clock.set(151L);
        SimpleClaimsWorldExtent.Result refreshed = extent.resolve("world", CLAIM_PARTY_ID);

        assertEquals(SimpleClaimsWorldExtent.Status.AVAILABLE, first.status());
        assertEquals(1, first.footprint().chunkCount());
        assertEquals(1, cached.footprint().chunkCount());
        assertEquals(2, refreshed.footprint().chunkCount());
        assertEquals(3, manager.getChunksCalls, "One retry plus two successful topology snapshots were expected.");
    }

    @Test
    void nativePolicyHonorsNoClaimFullWorldAndAdminOrdering() {
        FixtureClaimManager manager = FixtureClaimManager.getInstance();
        SimpleClaimsBreedingBridge bridge = bridgeForFixture();

        assertDamageStatus(bridge, "world", ATTACKER_ID, SimpleClaimsBreedingBridge.DamageAccessStatus.ALLOWED);
        manager.fullWorldProtection.add("world");
        assertDamageStatus(bridge, "world", ATTACKER_ID, SimpleClaimsBreedingBridge.DamageAccessStatus.DENIED);
        manager.adminOverrides.add(ATTACKER_ID);
        assertDamageStatus(bridge, "world", ATTACKER_ID, SimpleClaimsBreedingBridge.DamageAccessStatus.ALLOWED);
    }

    @Test
    void nativePolicyUsesNativePermissionForMembersAndDirectPlayerAllies() {
        FixtureClaimManager manager = claimedWorld(false);
        FixtureParty claimParty = manager.getPartyById(CLAIM_PARTY_ID);
        claimParty.members.add(ATTACKER_ID);
        SimpleClaimsBreedingBridge bridge = bridgeForFixture();

        assertDamageStatus(bridge, "world", ATTACKER_ID, SimpleClaimsBreedingBridge.DamageAccessStatus.DENIED);
        claimParty.playerPermissions.put(ATTACKER_ID, true);
        assertDamageStatus(bridge, "world", ATTACKER_ID, SimpleClaimsBreedingBridge.DamageAccessStatus.ALLOWED);
        assertEquals(SimpleClaimsNativeDamageAccess.TAMED_DAMAGE_PERMISSION_KEY, manager.lastPermissionKey);

        claimParty.members.clear();
        claimParty.directPlayerAllies.add(ATTACKER_ID);
        assertDamageStatus(bridge, "world", ATTACKER_ID, SimpleClaimsBreedingBridge.DamageAccessStatus.ALLOWED);
    }

    @Test
    void nativePolicyChecksAlliedPartyPermissionWithAttackerPartyId() {
        FixtureClaimManager manager = claimedWorld(false);
        FixtureParty claimParty = manager.getPartyById(CLAIM_PARTY_ID);
        manager.playerToParty.put(ATTACKER_ID, ATTACKER_PARTY_ID);
        claimParty.partyAllies.add(ATTACKER_PARTY_ID);
        claimParty.partyPermissions.put(ATTACKER_PARTY_ID, true);
        SimpleClaimsBreedingBridge bridge = bridgeForFixture();

        SimpleClaimsBreedingBridge.DamageAccessResult result = bridge.evaluateDamageAccess(
                "world",
                new Vector3d(1, 0, 1),
                ATTACKER_ID,
                "legacy.tamework.key"
        );

        assertEquals(SimpleClaimsBreedingBridge.DamageAccessStatus.ALLOWED, result.status());
        assertEquals(ATTACKER_PARTY_ID, claimParty.lastPartyPermissionSubject);
        assertFalse(ATTACKER_ID.equals(claimParty.lastPartyPermissionSubject));
        assertEquals(SimpleClaimsNativeDamageAccess.TAMED_DAMAGE_PERMISSION_KEY, claimParty.lastPermissionKey);
    }

    @Test
    void nativePolicyUsesClaimPartyOutsiderFallback() {
        FixtureClaimManager manager = claimedWorld(false);
        SimpleClaimsBreedingBridge bridge = bridgeForFixture();

        assertDamageStatus(bridge, "world", ATTACKER_ID, SimpleClaimsBreedingBridge.DamageAccessStatus.DENIED);
        manager.getPartyById(CLAIM_PARTY_ID).tamedDamageEnabled = true;
        assertDamageStatus(bridge, "world", ATTACKER_ID, SimpleClaimsBreedingBridge.DamageAccessStatus.ALLOWED);
    }

    @Test
    void nativeInvocationErrorsAreFailOpenInPurePolicy() {
        FixtureClaimManager manager = claimedWorld(false);
        manager.throwNativePolicy = true;
        SimpleClaimsNativeDamageAccess access = SimpleClaimsNativeDamageAccess.forTypes(
                FixtureClaimManager.class,
                FixtureChunk.class,
                FixtureParty.class
        );
        SimpleClaimsNativeTamedDamagePolicy policy = new SimpleClaimsNativeTamedDamagePolicy(access);

        SimpleClaimsNativeTamedDamagePolicy.Decision decision = policy.evaluate(
                "world",
                new Vector3d(1, 0, 1),
                ATTACKER_ID
        );

        assertTrue(decision.allowed());
        assertEquals(SimpleClaimsNativeTamedDamagePolicy.Status.ALLOW_FAIL_OPEN, decision.status());
        assertEquals(SimpleClaimsNativeDamageAccess.Status.ERROR, decision.accessStatus());
        assertTrue(decision.message().contains("fixture-native-failure"));
    }

    private static FixtureClaimManager claimedWorld(boolean outsiderFallback) {
        FixtureClaimManager manager = FixtureClaimManager.getInstance();
        FixtureParty party = new FixtureParty(CLAIM_PARTY_ID, CLAIM_OWNER_ID);
        party.tamedDamageEnabled = outsiderFallback;
        manager.addParty(party);
        manager.putChunk("world", CLAIM_PARTY_ID, 0, 0);
        return manager;
    }

    private static SimpleClaimsBreedingBridge bridgeForFixture() {
        return SimpleClaimsBreedingBridge.forTypesForTests(
                FixtureClaimManager.class,
                FixtureChunk.class,
                FixtureParty.class
        );
    }

    private static void assertDamageStatus(SimpleClaimsBreedingBridge bridge,
                                           String worldName,
                                           UUID attackerId,
                                           SimpleClaimsBreedingBridge.DamageAccessStatus expected) {
        SimpleClaimsBreedingBridge.DamageAccessResult result = bridge.evaluateDamageAccess(
                worldName,
                new Vector3d(1, 0, 1),
                attackerId,
                "ignored.legacy.key"
        );
        assertEquals(expected, result.status());
    }

    /** Deterministic, API-shaped fixture for the verified SimpleClaims 1.0.38 contract. */
    public static final class FixtureClaimManager {
        private static FixtureClaimManager instance = new FixtureClaimManager();

        private final HashMap<UUID, FixtureParty> parties = new HashMap<>();
        private final HashMap<String, HashMap<String, FixtureChunk>> chunks = new HashMap<>();
        private final Set<UUID> adminOverrides = new HashSet<>();
        private final Set<String> fullWorldProtection = new HashSet<>();
        private final Map<UUID, UUID> playerToParty = new HashMap<>();
        private int remainingExtentFailures;
        private int getChunksCalls;
        private boolean throwNativePolicy;
        private String lastPermissionKey;

        public static FixtureClaimManager getInstance() {
            return instance;
        }

        static void reset() {
            instance = new FixtureClaimManager();
        }

        public FixtureChunk getChunkRawCoords(String worldName, int blockX, int blockZ) {
            HashMap<String, FixtureChunk> world = chunks.get(worldName);
            return world == null ? null : world.get(coordinate(Math.floorDiv(blockX, 32), Math.floorDiv(blockZ, 32)));
        }

        public FixtureParty getPartyById(UUID partyId) {
            return parties.get(partyId);
        }

        public FixtureParty getPartyFromPlayer(UUID playerId) {
            return parties.get(playerToParty.get(playerId));
        }

        public HashMap<String, HashMap<String, FixtureChunk>> getChunks() {
            getChunksCalls++;
            if (remainingExtentFailures > 0) {
                remainingExtentFailures--;
                throw new ConcurrentModificationException("fixture-topology-mutated");
            }
            return chunks;
        }

        public boolean isAllowedToInteract(UUID attackerId,
                                           String worldName,
                                           int blockX,
                                           int blockZ,
                                           Predicate<FixtureParty> outsiderFallback,
                                           String permissionKey) {
            if (throwNativePolicy) {
                throw new IllegalStateException("fixture-native-failure");
            }
            lastPermissionKey = permissionKey;
            if (attackerId != null && adminOverrides.contains(attackerId)) {
                return true;
            }
            FixtureChunk chunk = getChunkRawCoords(worldName, blockX, blockZ);
            if (chunk == null) {
                return !fullWorldProtection.contains(worldName);
            }
            FixtureParty claimParty = getPartyById(chunk.getPartyOwner());
            if (claimParty == null) {
                return true;
            }
            if (attackerId == null) {
                return false;
            }
            if (claimParty.isOwnerOrMember(attackerId) || claimParty.isPlayerAllied(attackerId)) {
                return claimParty.hasPermission(attackerId, permissionKey);
            }
            UUID attackerPartyId = playerToParty.get(attackerId);
            if (attackerPartyId != null && claimParty.isPartyAllied(attackerPartyId)) {
                return claimParty.hasPartyPermission(attackerPartyId, permissionKey);
            }
            return outsiderFallback.test(claimParty);
        }

        void addParty(FixtureParty party) {
            parties.put(party.getId(), party);
        }

        void putChunk(String worldName, UUID partyId, int chunkX, int chunkZ) {
            chunks.computeIfAbsent(worldName, ignored -> new HashMap<>())
                    .put(coordinate(chunkX, chunkZ), new FixtureChunk(partyId, chunkX, chunkZ));
        }

        private static String coordinate(int chunkX, int chunkZ) {
            return chunkX + "," + chunkZ;
        }
    }

    /** Lookup-capable fixture intentionally lacking extent and damage methods. */
    public static final class LookupOnlyClaimManager {
        private static LookupOnlyClaimManager instance = new LookupOnlyClaimManager();
        private FixtureChunk chunk;
        private FixtureParty party;
        private int getAmountOfClaimsCalls;

        public static LookupOnlyClaimManager getInstance() {
            return instance;
        }

        static void reset() {
            instance = new LookupOnlyClaimManager();
        }

        public FixtureChunk getChunkRawCoords(String worldName, int blockX, int blockZ) {
            return chunk;
        }

        public FixtureParty getPartyById(UUID partyId) {
            return party != null && party.getId().equals(partyId) ? party : null;
        }

        public int getAmountOfClaims(FixtureParty ignored) {
            getAmountOfClaimsCalls++;
            return 99;
        }
    }

    public static final class MalformedClaimManager {
        private static MalformedClaimManager instance = new MalformedClaimManager();

        public static MalformedClaimManager getInstance() {
            return instance;
        }

        static void reset() {
            instance = new MalformedClaimManager();
        }

        public MalformedChunk getChunkRawCoords(String worldName, int blockX, int blockZ) {
            return new MalformedChunk();
        }

        public Object getPartyById(UUID partyId) {
            return new Object();
        }
    }

    public static final class ThrowingClaimManager {
        private static ThrowingClaimManager instance = new ThrowingClaimManager();

        public static ThrowingClaimManager getInstance() {
            return instance;
        }

        static void reset() {
            instance = new ThrowingClaimManager();
        }

        public FixtureChunk getChunkRawCoords(String worldName, int blockX, int blockZ) {
            throw new IllegalStateException("fixture-lookup-failure");
        }

        public Object getPartyById(UUID partyId) {
            return new Object();
        }
    }

    public static final class FixtureChunk {
        private final UUID partyOwner;
        private final int chunkX;
        private final int chunkZ;

        FixtureChunk(UUID partyOwner, int chunkX, int chunkZ) {
            this.partyOwner = partyOwner;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

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

    public static final class MalformedChunk {
        public String getPartyOwner() {
            return "not-a-uuid";
        }
    }

    public static final class FixtureParty {
        private final UUID id;
        private final UUID owner;
        private final Set<UUID> members = new HashSet<>();
        private final Set<UUID> directPlayerAllies = new HashSet<>();
        private final Set<UUID> partyAllies = new HashSet<>();
        private final Map<UUID, Boolean> playerPermissions = new HashMap<>();
        private final Map<UUID, Boolean> partyPermissions = new HashMap<>();
        private boolean tamedDamageEnabled;
        private UUID lastPartyPermissionSubject;
        private String lastPermissionKey;

        FixtureParty(UUID id, UUID owner) {
            this.id = id;
            this.owner = owner;
        }

        public UUID getId() {
            return id;
        }

        public boolean isOwnerOrMember(UUID playerId) {
            return owner.equals(playerId) || members.contains(playerId);
        }

        public boolean isPlayerAllied(UUID playerId) {
            return directPlayerAllies.contains(playerId);
        }

        public boolean isPartyAllied(UUID partyId) {
            return partyAllies.contains(partyId);
        }

        public boolean hasPermission(UUID playerId, String permissionKey) {
            lastPermissionKey = permissionKey;
            return playerPermissions.getOrDefault(playerId, false);
        }

        public boolean hasPartyPermission(UUID partyId, String permissionKey) {
            lastPartyPermissionSubject = partyId;
            lastPermissionKey = permissionKey;
            return partyPermissions.getOrDefault(partyId, false);
        }

        public boolean isTamedDamageEnabled() {
            return tamedDamageEnabled;
        }
    }
}
