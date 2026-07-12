package com.alechilles.alecstamework.integration.simpleclaims;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Native-shaped SimpleClaims fixture shared by runtime and public-API damage adapter tests. */
public final class SimpleClaimsDamageBridgeFixture implements AutoCloseable {
    public static final UUID CLAIM_PARTY_ID =
            UUID.fromString("00000000-0000-0000-0000-00000000c101");
    public static final UUID CLAIM_OWNER_ID =
            UUID.fromString("00000000-0000-0000-0000-00000000c102");
    public static final UUID ATTACKER_PARTY_ID =
            UUID.fromString("00000000-0000-0000-0000-00000000c103");

    /** Native topology/permission states represented in the cross-adapter scenario matrix. */
    public enum Mode {
        NO_CLAIM,
        FULL_WORLD_PROTECTED,
        ADMIN,
        MEMBER_ALLOWED,
        MEMBER_DENIED,
        PLAYER_ALLY_ALLOWED,
        PARTY_ALLY_ALLOWED,
        OUTSIDER_ALLOWED,
        OUTSIDER_DENIED,
        PROVIDER_FAILURE
    }

    private final FixtureClaimManager manager;
    private final SimpleClaimsBreedingBridge bridge;

    private SimpleClaimsDamageBridgeFixture(@Nonnull FixtureClaimManager manager) {
        this.manager = manager;
        FixtureClaimManager.instance = manager;
        this.bridge = SimpleClaimsBreedingBridge.forDamageTypesForTests(
                FixtureClaimManager.class,
                FixtureChunk.class,
                FixtureParty.class
        );
    }

    @Nonnull
    public static SimpleClaimsDamageBridgeFixture open(@Nonnull Mode mode,
                                                        @Nonnull UUID attackerUuid) {
        return new SimpleClaimsDamageBridgeFixture(FixtureClaimManager.forMode(mode, attackerUuid));
    }

    @Nonnull
    public SimpleClaimsBreedingBridge bridge() {
        return bridge;
    }

    public int nativeCalls() {
        return manager.nativeCalls;
    }

    @Nullable
    public UUID lastPartyPermissionSubject() {
        return manager.claimParty == null ? null : manager.claimParty.lastPartyPermissionSubject;
    }

    @Nullable
    public UUID lastPlayerPermissionSubject() {
        return manager.claimParty == null ? null : manager.claimParty.lastPlayerPermissionSubject;
    }

    @Override
    public void close() {
        FixtureClaimManager.instance = new FixtureClaimManager();
    }

    /** Deterministic subset of SimpleClaims 1.0.38's native manager contract. */
    public static final class FixtureClaimManager {
        private static FixtureClaimManager instance = new FixtureClaimManager();

        private final Map<UUID, FixtureParty> parties = new HashMap<>();
        private final Map<String, FixtureChunk> chunks = new HashMap<>();
        private final Set<UUID> adminOverrides = new HashSet<>();
        private final Set<String> fullWorldProtection = new HashSet<>();
        private final Map<UUID, UUID> playerToParty = new HashMap<>();
        @Nullable
        private FixtureParty claimParty;
        private boolean throwNativePolicy;
        private int nativeCalls;

        public static FixtureClaimManager getInstance() {
            return instance;
        }

        @Nullable
        public FixtureChunk getChunkRawCoords(String worldName, int blockX, int blockZ) {
            return chunks.get(worldName + ":" + Math.floorDiv(blockX, 32) + ":" + Math.floorDiv(blockZ, 32));
        }

        @Nullable
        public FixtureParty getPartyById(UUID partyId) {
            return parties.get(partyId);
        }

        public boolean isAllowedToInteract(UUID attackerId,
                                           String worldName,
                                           int blockX,
                                           int blockZ,
                                           Predicate<FixtureParty> outsiderFallback,
                                           String permissionKey) {
            nativeCalls++;
            if (throwNativePolicy) {
                throw new IllegalStateException("fixture-native-failure");
            }
            if (attackerId != null && adminOverrides.contains(attackerId)) {
                return true;
            }
            FixtureChunk chunk = getChunkRawCoords(worldName, blockX, blockZ);
            if (chunk == null) {
                return !fullWorldProtection.contains(worldName);
            }
            FixtureParty party = getPartyById(chunk.getPartyOwner());
            if (party == null) {
                return true;
            }
            if (attackerId == null) {
                return false;
            }
            if (party.isOwnerOrMember(attackerId) || party.isPlayerAllied(attackerId)) {
                return party.hasPermission(attackerId, permissionKey);
            }
            UUID attackerPartyId = playerToParty.get(attackerId);
            if (attackerPartyId != null && party.isPartyAllied(attackerPartyId)) {
                return party.hasPartyPermission(attackerPartyId, permissionKey);
            }
            return outsiderFallback.test(party);
        }

        @Nonnull
        private static FixtureClaimManager forMode(@Nonnull Mode mode, @Nonnull UUID attackerUuid) {
            FixtureClaimManager manager = new FixtureClaimManager();
            if (mode == Mode.NO_CLAIM || mode == Mode.FULL_WORLD_PROTECTED) {
                if (mode == Mode.FULL_WORLD_PROTECTED) {
                    manager.fullWorldProtection.add("damage-adapter-world");
                }
                return manager;
            }

            FixtureParty party = new FixtureParty(CLAIM_PARTY_ID, CLAIM_OWNER_ID);
            manager.claimParty = party;
            manager.parties.put(CLAIM_PARTY_ID, party);
            manager.chunks.put(
                    "damage-adapter-world:0:0",
                    new FixtureChunk(CLAIM_PARTY_ID)
            );
            switch (mode) {
                case ADMIN -> manager.adminOverrides.add(attackerUuid);
                case MEMBER_ALLOWED -> {
                    party.members.add(attackerUuid);
                    party.playerPermissions.put(attackerUuid, true);
                }
                case MEMBER_DENIED -> party.members.add(attackerUuid);
                case PLAYER_ALLY_ALLOWED -> {
                    party.directPlayerAllies.add(attackerUuid);
                    party.playerPermissions.put(attackerUuid, true);
                }
                case PARTY_ALLY_ALLOWED -> {
                    manager.playerToParty.put(attackerUuid, ATTACKER_PARTY_ID);
                    party.partyAllies.add(ATTACKER_PARTY_ID);
                    party.partyPermissions.put(ATTACKER_PARTY_ID, true);
                }
                case OUTSIDER_ALLOWED -> party.tamedDamageEnabled = true;
                case OUTSIDER_DENIED -> party.tamedDamageEnabled = false;
                case PROVIDER_FAILURE -> manager.throwNativePolicy = true;
                default -> throw new IllegalArgumentException("Unsupported claimed mode: " + mode);
            }
            return manager;
        }
    }

    /** Minimal claimed-chunk identity used by both native access and result attribution. */
    public static final class FixtureChunk {
        private final UUID partyOwner;

        private FixtureChunk(UUID partyOwner) {
            this.partyOwner = partyOwner;
        }

        public UUID getPartyOwner() {
            return partyOwner;
        }
    }

    /** Permission-bearing party shape consumed reflectively by the native damage capability. */
    public static final class FixtureParty {
        private final UUID id;
        private final UUID owner;
        private final Set<UUID> members = new HashSet<>();
        private final Set<UUID> directPlayerAllies = new HashSet<>();
        private final Set<UUID> partyAllies = new HashSet<>();
        private final Map<UUID, Boolean> playerPermissions = new HashMap<>();
        private final Map<UUID, Boolean> partyPermissions = new HashMap<>();
        private boolean tamedDamageEnabled;
        @Nullable
        private UUID lastPlayerPermissionSubject;
        @Nullable
        private UUID lastPartyPermissionSubject;

        private FixtureParty(UUID id, UUID owner) {
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
            lastPlayerPermissionSubject = playerId;
            return playerPermissions.getOrDefault(playerId, false);
        }

        public boolean hasPartyPermission(UUID partyId, String permissionKey) {
            lastPartyPermissionSubject = partyId;
            return partyPermissions.getOrDefault(partyId, false);
        }

        public boolean isTamedDamageEnabled() {
            return tamedDamageEnabled;
        }
    }
}
