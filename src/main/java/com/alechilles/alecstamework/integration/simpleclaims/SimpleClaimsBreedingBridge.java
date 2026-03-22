package com.alechilles.alecstamework.integration.simpleclaims;

import com.hypixel.hytale.math.vector.Vector3d;
import java.lang.reflect.Method;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Reflective bridge to SimpleClaims for optional Tamework claim integrations.
 */
public final class SimpleClaimsBreedingBridge {

    public enum LookupStatus {
        CLAIM_FOUND,
        NO_CLAIM,
        UNAVAILABLE,
        ERROR
    }

    public enum DamageAccessStatus {
        ALLOWED,
        DENIED,
        LOOKUP_ERROR,
        UNAVAILABLE
    }

    public record ClaimInfo(UUID partyId, int claimChunkCount) {
    }

    public record LookupResult(LookupStatus status,
                               @Nullable ClaimInfo claimInfo,
                               @Nullable String message) {
    }

    public record DamageAccessResult(DamageAccessStatus status,
                                     @Nullable UUID claimPartyId,
                                     @Nullable String message) {
    }

    private final boolean available;
    @Nullable
    private final String unavailableReason;
    @Nullable
    private final Method claimManagerGetInstance;
    @Nullable
    private final Method claimManagerGetChunkRawCoords;
    @Nullable
    private final Method claimManagerGetPartyById;
    @Nullable
    private final Method claimManagerGetAmountOfClaims;
    @Nullable
    private final Method claimManagerGetPartyFromPlayer;
    @Nullable
    private final Method chunkInfoGetPartyOwner;
    @Nullable
    private final Method partyInfoIsOwnerOrMember;
    @Nullable
    private final Method partyInfoHasPartyPermission;
    @Nullable
    private final Method partyInfoGetId;

    private SimpleClaimsBreedingBridge(boolean available,
                                       @Nullable String unavailableReason,
                                       @Nullable Method claimManagerGetInstance,
                                       @Nullable Method claimManagerGetChunkRawCoords,
                                       @Nullable Method claimManagerGetPartyById,
                                       @Nullable Method claimManagerGetAmountOfClaims,
                                       @Nullable Method claimManagerGetPartyFromPlayer,
                                       @Nullable Method chunkInfoGetPartyOwner,
                                       @Nullable Method partyInfoIsOwnerOrMember,
                                       @Nullable Method partyInfoHasPartyPermission,
                                       @Nullable Method partyInfoGetId) {
        this.available = available;
        this.unavailableReason = unavailableReason;
        this.claimManagerGetInstance = claimManagerGetInstance;
        this.claimManagerGetChunkRawCoords = claimManagerGetChunkRawCoords;
        this.claimManagerGetPartyById = claimManagerGetPartyById;
        this.claimManagerGetAmountOfClaims = claimManagerGetAmountOfClaims;
        this.claimManagerGetPartyFromPlayer = claimManagerGetPartyFromPlayer;
        this.chunkInfoGetPartyOwner = chunkInfoGetPartyOwner;
        this.partyInfoIsOwnerOrMember = partyInfoIsOwnerOrMember;
        this.partyInfoHasPartyPermission = partyInfoHasPartyPermission;
        this.partyInfoGetId = partyInfoGetId;
    }

    public static SimpleClaimsBreedingBridge initialize() {
        try {
            Class<?> claimManagerClass = Class.forName("com.buuz135.simpleclaims.claim.ClaimManager");
            Class<?> chunkInfoClass = Class.forName("com.buuz135.simpleclaims.claim.chunk.ChunkInfo");
            Class<?> partyInfoClass = Class.forName("com.buuz135.simpleclaims.claim.party.PartyInfo");
            Method getInstance = claimManagerClass.getMethod("getInstance");
            Method getChunkRawCoords = claimManagerClass.getMethod(
                    "getChunkRawCoords",
                    String.class,
                    int.class,
                    int.class
            );
            Method getPartyById = claimManagerClass.getMethod("getPartyById", UUID.class);
            Method getAmountOfClaims = claimManagerClass.getMethod("getAmountOfClaims", partyInfoClass);
            Method getPartyFromPlayer = claimManagerClass.getMethod("getPartyFromPlayer", UUID.class);
            Method getPartyOwner = chunkInfoClass.getMethod("getPartyOwner");
            Method isOwnerOrMember = partyInfoClass.getMethod("isOwnerOrMember", UUID.class);
            Method hasPartyPermission = partyInfoClass.getMethod("hasPartyPermission", UUID.class, String.class);
            Method getPartyId = partyInfoClass.getMethod("getId");
            return new SimpleClaimsBreedingBridge(
                    true,
                    null,
                    getInstance,
                    getChunkRawCoords,
                    getPartyById,
                    getAmountOfClaims,
                    getPartyFromPlayer,
                    getPartyOwner,
                    isOwnerOrMember,
                    hasPartyPermission,
                    getPartyId
            );
        } catch (Throwable throwable) {
            return unavailable(extractMessage(throwable));
        }
    }

    public boolean isAvailable() {
        return available;
    }

    @Nullable
    public String getUnavailableReason() {
        return unavailableReason;
    }

    public LookupResult lookupClaim(@Nullable String worldName, @Nullable Vector3d position) {
        if (position == null) {
            return lookupClaim(worldName, 0.0, 0.0);
        }
        return lookupClaim(worldName, position.x, position.z);
    }

    public LookupResult lookupClaim(@Nullable String worldName, double blockX, double blockZ) {
        if (!available) {
            return new LookupResult(LookupStatus.UNAVAILABLE, null, unavailableReason);
        }
        if (worldName == null || worldName.isBlank()) {
            return new LookupResult(LookupStatus.ERROR, null, "World name is missing.");
        }
        if (!Double.isFinite(blockX) || !Double.isFinite(blockZ)) {
            return new LookupResult(LookupStatus.ERROR, null, "Position is not finite.");
        }
        Object claimManager = resolveClaimManagerForLookup();
        if (claimManager == null) {
            return new LookupResult(LookupStatus.UNAVAILABLE, null, unavailableReason);
        }
        return lookupClaimInternal(claimManager, worldName, blockX, blockZ);
    }

    public DamageAccessResult evaluateDamageAccess(@Nullable String worldName,
                                                   @Nullable Vector3d position,
                                                   @Nullable UUID attackerPlayerUuid,
                                                   @Nullable String allowDamagePermissionKey) {
        if (!available) {
            return new DamageAccessResult(DamageAccessStatus.UNAVAILABLE, null, unavailableReason);
        }
        if (attackerPlayerUuid == null) {
            return new DamageAccessResult(DamageAccessStatus.ALLOWED, null, "attacker-missing");
        }
        if (worldName == null || worldName.isBlank()) {
            return new DamageAccessResult(DamageAccessStatus.LOOKUP_ERROR, null, "World name is missing.");
        }
        if (position == null || !Double.isFinite(position.x) || !Double.isFinite(position.z)) {
            return new DamageAccessResult(DamageAccessStatus.LOOKUP_ERROR, null, "Position is not finite.");
        }
        Object claimManager = resolveClaimManagerForDamage();
        if (claimManager == null) {
            return new DamageAccessResult(DamageAccessStatus.UNAVAILABLE, null, unavailableReason);
        }

        LookupResult lookup = lookupClaimInternal(claimManager, worldName, position.x, position.z);
        if (lookup.status() == LookupStatus.NO_CLAIM) {
            return new DamageAccessResult(DamageAccessStatus.ALLOWED, null, null);
        }
        if (lookup.status() == LookupStatus.UNAVAILABLE) {
            return new DamageAccessResult(DamageAccessStatus.UNAVAILABLE, null, lookup.message());
        }
        if (lookup.status() == LookupStatus.ERROR) {
            return new DamageAccessResult(DamageAccessStatus.LOOKUP_ERROR, null, lookup.message());
        }
        ClaimInfo claimInfo = lookup.claimInfo();
        if (claimInfo == null || claimInfo.partyId() == null) {
            return new DamageAccessResult(DamageAccessStatus.LOOKUP_ERROR, null, "Claim info was missing.");
        }
        try {
            Object claimParty = claimManagerGetPartyById.invoke(claimManager, claimInfo.partyId());
            if (claimParty == null) {
                return new DamageAccessResult(
                        DamageAccessStatus.LOOKUP_ERROR,
                        claimInfo.partyId(),
                        "SimpleClaims claim party could not be resolved."
                );
            }
            if (claimManagerGetPartyFromPlayer != null && partyInfoGetId != null) {
                Object attackerParty = claimManagerGetPartyFromPlayer.invoke(claimManager, attackerPlayerUuid);
                if (attackerParty != null) {
                    Object attackerPartyId = partyInfoGetId.invoke(attackerParty);
                    if (attackerPartyId instanceof UUID attackerPartyUuid
                            && attackerPartyUuid.equals(claimInfo.partyId())) {
                        return new DamageAccessResult(DamageAccessStatus.ALLOWED, claimInfo.partyId(), "attacker-party-match");
                    }
                }
            }
            if (!(partyInfoIsOwnerOrMember.invoke(claimParty, attackerPlayerUuid) instanceof Boolean isOwnerOrMember)) {
                return new DamageAccessResult(
                        DamageAccessStatus.LOOKUP_ERROR,
                        claimInfo.partyId(),
                        "SimpleClaims membership check did not return a boolean."
                );
            }
            if (isOwnerOrMember) {
                return new DamageAccessResult(DamageAccessStatus.ALLOWED, claimInfo.partyId(), "attacker-member");
            }
            String permissionKey = allowDamagePermissionKey == null ? "" : allowDamagePermissionKey.trim();
            if (!permissionKey.isBlank()) {
                Object hasPermissionValue = partyInfoHasPartyPermission.invoke(claimParty, attackerPlayerUuid, permissionKey);
                if (!(hasPermissionValue instanceof Boolean hasPermission)) {
                    return new DamageAccessResult(
                            DamageAccessStatus.LOOKUP_ERROR,
                            claimInfo.partyId(),
                            "SimpleClaims permission check did not return a boolean."
                    );
                }
                if (hasPermission) {
                    return new DamageAccessResult(
                            DamageAccessStatus.ALLOWED,
                            claimInfo.partyId(),
                            "attacker-permission"
                    );
                }
            }
            return new DamageAccessResult(DamageAccessStatus.DENIED, claimInfo.partyId(), "attacker-not-allowed");
        } catch (Throwable throwable) {
            return new DamageAccessResult(
                    DamageAccessStatus.LOOKUP_ERROR,
                    claimInfo.partyId(),
                    extractMessage(throwable)
            );
        }
    }

    @Nullable
    private Object resolveClaimManagerForLookup() {
        if (claimManagerGetInstance == null
                || claimManagerGetChunkRawCoords == null
                || claimManagerGetPartyById == null
                || claimManagerGetAmountOfClaims == null
                || chunkInfoGetPartyOwner == null) {
            return null;
        }
        try {
            return claimManagerGetInstance.invoke(null);
        } catch (Throwable throwable) {
            return null;
        }
    }

    @Nullable
    private Object resolveClaimManagerForDamage() {
        if (claimManagerGetInstance == null
                || claimManagerGetChunkRawCoords == null
                || claimManagerGetPartyById == null
                || chunkInfoGetPartyOwner == null
                || claimManagerGetPartyFromPlayer == null
                || partyInfoIsOwnerOrMember == null
                || partyInfoHasPartyPermission == null) {
            return null;
        }
        try {
            return claimManagerGetInstance.invoke(null);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private LookupResult lookupClaimInternal(@Nullable Object claimManager,
                                             @Nullable String worldName,
                                             double blockX,
                                             double blockZ) {
        if (claimManager == null || worldName == null || worldName.isBlank()) {
            return new LookupResult(LookupStatus.ERROR, null, "Claim manager/world context is missing.");
        }
        try {
            int rawX = (int) Math.floor(blockX);
            int rawZ = (int) Math.floor(blockZ);
            Object chunkInfo = claimManagerGetChunkRawCoords.invoke(claimManager, worldName, rawX, rawZ);
            if (chunkInfo == null) {
                return new LookupResult(LookupStatus.NO_CLAIM, null, null);
            }
            Object partyIdValue = chunkInfoGetPartyOwner.invoke(chunkInfo);
            if (!(partyIdValue instanceof UUID partyId)) {
                return new LookupResult(LookupStatus.ERROR, null, "SimpleClaims chunk owner was not a UUID.");
            }
            Object party = claimManagerGetPartyById.invoke(claimManager, partyId);
            if (party == null) {
                return new LookupResult(
                        LookupStatus.ERROR,
                        null,
                        "SimpleClaims claim owner party could not be resolved for " + partyId + "."
                );
            }
            Object countValue = claimManagerGetAmountOfClaims != null
                    ? claimManagerGetAmountOfClaims.invoke(claimManager, party)
                    : Integer.valueOf(0);
            if (!(countValue instanceof Number countNumber)) {
                return new LookupResult(LookupStatus.ERROR, null, "SimpleClaims claim count was not numeric.");
            }
            int claimChunkCount = Math.max(0, countNumber.intValue());
            return new LookupResult(LookupStatus.CLAIM_FOUND, new ClaimInfo(partyId, claimChunkCount), null);
        } catch (Throwable throwable) {
            return new LookupResult(LookupStatus.ERROR, null, extractMessage(throwable));
        }
    }

    private static SimpleClaimsBreedingBridge unavailable(@Nullable String reason) {
        return new SimpleClaimsBreedingBridge(false, reason, null, null, null, null, null, null, null, null, null);
    }

    @Nullable
    private static String extractMessage(@Nullable Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message;
    }
}
