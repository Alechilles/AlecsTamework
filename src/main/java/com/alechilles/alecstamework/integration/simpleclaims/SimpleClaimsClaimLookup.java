package com.alechilles.alecstamework.integration.simpleclaims;

import java.lang.reflect.Method;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Minimal SimpleClaims 1.0.38 claim lookup and count used by released breeding limits.
 *
 * <p>This probe deliberately does not require claim extent or damage-policy methods.</p>
 */
final class SimpleClaimsClaimLookup {
    enum Status {
        CLAIM_FOUND,
        NO_CLAIM,
        UNAVAILABLE,
        ERROR
    }

    record Result(@Nonnull Status status,
                  @Nullable UUID partyId,
                  int claimChunkCount,
                  @Nullable String message) {
        Result {
            claimChunkCount = Math.max(0, claimChunkCount);
        }
    }

    private final boolean available;
    @Nullable
    private final String unavailableReason;
    @Nullable
    private final Method getInstance;
    @Nullable
    private final Method getChunkRawCoords;
    @Nullable
    private final Method getPartyById;
    @Nullable
    private final Method getPartyOwner;
    @Nullable
    private final Method getAmountOfClaims;

    private SimpleClaimsClaimLookup(boolean available,
                                    @Nullable String unavailableReason,
                                    @Nullable Method getInstance,
                                    @Nullable Method getChunkRawCoords,
                                    @Nullable Method getPartyById,
                                    @Nullable Method getPartyOwner,
                                    @Nullable Method getAmountOfClaims) {
        this.available = available;
        this.unavailableReason = unavailableReason;
        this.getInstance = getInstance;
        this.getChunkRawCoords = getChunkRawCoords;
        this.getPartyById = getPartyById;
        this.getPartyOwner = getPartyOwner;
        this.getAmountOfClaims = getAmountOfClaims;
    }

    @Nonnull
    static SimpleClaimsClaimLookup probe(@Nonnull ClassLoader classLoader) {
        try {
            Class<?> managerType = SimpleClaimsReflection.load(
                    classLoader,
                    SimpleClaimsReflection.CLAIM_MANAGER_CLASS
            );
            Class<?> chunkType = SimpleClaimsReflection.load(
                    classLoader,
                    SimpleClaimsReflection.CHUNK_INFO_CLASS
            );
            Class<?> partyType = SimpleClaimsReflection.load(
                    classLoader,
                    SimpleClaimsReflection.PARTY_INFO_CLASS
            );
            return forTypes(managerType, chunkType, partyType);
        } catch (Throwable throwable) {
            return unavailable("SimpleClaims lookup contract is unavailable: "
                    + SimpleClaimsReflection.message(throwable));
        }
    }

    @Nonnull
    static SimpleClaimsClaimLookup forTypes(@Nonnull Class<?> managerType,
                                            @Nonnull Class<?> chunkType,
                                            @Nonnull Class<?> partyType) {
        try {
            return new SimpleClaimsClaimLookup(
                    true,
                    null,
                    SimpleClaimsReflection.requiredMethod(managerType, "getInstance"),
                    SimpleClaimsReflection.requiredMethod(
                            managerType,
                            "getChunkRawCoords",
                            String.class,
                            int.class,
                            int.class
                    ),
                    SimpleClaimsReflection.requiredMethod(managerType, "getPartyById", UUID.class),
                    SimpleClaimsReflection.requiredMethod(chunkType, "getPartyOwner"),
                    SimpleClaimsReflection.optionalMethod(managerType, "getAmountOfClaims", partyType)
            );
        } catch (Throwable throwable) {
            return unavailable("SimpleClaims lookup contract is incompatible: "
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
    Result lookup(@Nullable String worldName, double blockX, double blockZ) {
        if (!available) {
            return new Result(Status.UNAVAILABLE, null, 0, unavailableReason);
        }
        String world = normalizeWorld(worldName);
        if (world == null) {
            return new Result(Status.ERROR, null, 0, "World name is missing.");
        }
        if (!Double.isFinite(blockX) || !Double.isFinite(blockZ)) {
            return new Result(Status.ERROR, null, 0, "Position is not finite.");
        }
        return invokeLookup(world, (int) Math.floor(blockX), (int) Math.floor(blockZ));
    }

    @Nonnull
    private Result invokeLookup(@Nonnull String worldName, int blockX, int blockZ) {
        try {
            Object manager = getInstance.invoke(null);
            if (manager == null) {
                return new Result(Status.ERROR, null, 0, "SimpleClaims manager was null.");
            }
            Object chunk = getChunkRawCoords.invoke(manager, worldName, blockX, blockZ);
            if (chunk == null) {
                return new Result(Status.NO_CLAIM, null, 0, null);
            }
            Object partyIdValue = getPartyOwner.invoke(chunk);
            if (!(partyIdValue instanceof UUID partyId)) {
                return new Result(Status.ERROR, null, 0, "SimpleClaims chunk owner was not a UUID.");
            }
            Object party = getPartyById.invoke(manager, partyId);
            if (party == null) {
                return new Result(
                        Status.ERROR,
                        partyId,
                        0,
                        "SimpleClaims claim owner party could not be resolved for " + partyId + "."
                );
            }
            int claimChunkCount = resolveClaimCount(manager, party);
            return new Result(Status.CLAIM_FOUND, partyId, claimChunkCount, null);
        } catch (Throwable throwable) {
            return new Result(
                    Status.ERROR,
                    null,
                    0,
                    "SimpleClaims claim lookup failed: " + SimpleClaimsReflection.message(throwable)
            );
        }
    }

    private int resolveClaimCount(@Nonnull Object manager, @Nonnull Object party)
            throws ReflectiveOperationException {
        if (getAmountOfClaims == null) {
            return 0;
        }
        Object countValue = getAmountOfClaims.invoke(manager, party);
        if (!(countValue instanceof Number count)) {
            throw new IllegalStateException("SimpleClaims claim count was not numeric.");
        }
        return Math.max(0, count.intValue());
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
    private static SimpleClaimsClaimLookup unavailable(@Nonnull String reason) {
        return new SimpleClaimsClaimLookup(false, reason, null, null, null, null, null);
    }
}
