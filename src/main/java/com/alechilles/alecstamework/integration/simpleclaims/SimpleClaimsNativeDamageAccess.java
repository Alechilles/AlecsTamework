package com.alechilles.alecstamework.integration.simpleclaims;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reflective access to SimpleClaims 1.0.38's native tamed-damage interaction decision.
 *
 * <p>Delegating to {@code isAllowedToInteract} preserves SimpleClaims' admin, full-world,
 * membership, player-ally, party-ally, permission, and outsider-fallback ordering.</p>
 */
final class SimpleClaimsNativeDamageAccess {
    static final String TAMED_DAMAGE_PERMISSION_KEY = "simpleclaims.party.protection.tamed_damage";

    enum Status {
        ALLOWED,
        DENIED,
        UNAVAILABLE,
        ERROR
    }

    record Result(@Nonnull Status status,
                  @Nullable UUID claimPartyId,
                  @Nullable String message) {
    }

    private final boolean available;
    @Nullable
    private final String unavailableReason;
    @Nullable
    private final Method getInstance;
    @Nullable
    private final Method isAllowedToInteract;
    @Nullable
    private final Method isTamedDamageEnabled;
    @Nullable
    private final Method getChunkRawCoords;
    @Nullable
    private final Method getPartyOwner;

    private SimpleClaimsNativeDamageAccess(boolean available,
                                           @Nullable String unavailableReason,
                                           @Nullable Method getInstance,
                                           @Nullable Method isAllowedToInteract,
                                           @Nullable Method isTamedDamageEnabled,
                                           @Nullable Method getChunkRawCoords,
                                           @Nullable Method getPartyOwner) {
        this.available = available;
        this.unavailableReason = unavailableReason;
        this.getInstance = getInstance;
        this.isAllowedToInteract = isAllowedToInteract;
        this.isTamedDamageEnabled = isTamedDamageEnabled;
        this.getChunkRawCoords = getChunkRawCoords;
        this.getPartyOwner = getPartyOwner;
    }

    @Nonnull
    static SimpleClaimsNativeDamageAccess probe(@Nonnull ClassLoader classLoader) {
        try {
            Class<?> managerType = SimpleClaimsReflection.load(
                    classLoader,
                    SimpleClaimsReflection.CLAIM_MANAGER_CLASS
            );
            Class<?> partyType = SimpleClaimsReflection.load(
                    classLoader,
                    SimpleClaimsReflection.PARTY_INFO_CLASS
            );
            Class<?> chunkType;
            try {
                chunkType = SimpleClaimsReflection.load(
                        classLoader,
                        SimpleClaimsReflection.CHUNK_INFO_CLASS
                );
            } catch (Throwable ignored) {
                chunkType = null;
            }
            return forTypes(managerType, chunkType, partyType);
        } catch (Throwable throwable) {
            return unavailable("SimpleClaims native damage contract is unavailable: "
                    + SimpleClaimsReflection.message(throwable));
        }
    }

    @Nonnull
    static SimpleClaimsNativeDamageAccess forTypes(@Nonnull Class<?> managerType,
                                                   @Nullable Class<?> chunkType,
                                                   @Nonnull Class<?> partyType) {
        try {
            Method getChunk = SimpleClaimsReflection.optionalMethod(
                    managerType,
                    "getChunkRawCoords",
                    String.class,
                    int.class,
                    int.class
            );
            Method getOwner = chunkType == null
                    ? null
                    : SimpleClaimsReflection.optionalMethod(chunkType, "getPartyOwner");
            return new SimpleClaimsNativeDamageAccess(
                    true,
                    null,
                    SimpleClaimsReflection.requiredMethod(managerType, "getInstance"),
                    SimpleClaimsReflection.requiredMethod(
                            managerType,
                            "isAllowedToInteract",
                            UUID.class,
                            String.class,
                            int.class,
                            int.class,
                            Predicate.class,
                            String.class
                    ),
                    SimpleClaimsReflection.requiredMethod(partyType, "isTamedDamageEnabled"),
                    getChunk,
                    getOwner
            );
        } catch (Throwable throwable) {
            return unavailable("SimpleClaims native damage contract is incompatible: "
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
    Result evaluate(@Nullable String worldName,
                    double blockX,
                    double blockZ,
                    @Nullable UUID attackerPlayerId) {
        if (!available) {
            return new Result(Status.UNAVAILABLE, null, unavailableReason);
        }
        String world = normalizeWorld(worldName);
        if (world == null) {
            return new Result(Status.ERROR, null, "World name is missing.");
        }
        if (!Double.isFinite(blockX) || !Double.isFinite(blockZ)) {
            return new Result(Status.ERROR, null, "Position is not finite.");
        }
        return invokeNativePolicy(
                world,
                (int) Math.floor(blockX),
                (int) Math.floor(blockZ),
                attackerPlayerId
        );
    }

    @Nonnull
    private Result invokeNativePolicy(@Nonnull String worldName,
                                      int blockX,
                                      int blockZ,
                                      @Nullable UUID attackerPlayerId) {
        try {
            Object manager = getInstance.invoke(null);
            if (manager == null) {
                return new Result(Status.ERROR, null, "SimpleClaims manager was null.");
            }
            Predicate<Object> outsiderFallback = this::isTamedDamageAllowedForOutsider;
            Object allowedValue = isAllowedToInteract.invoke(
                    manager,
                    attackerPlayerId,
                    worldName,
                    blockX,
                    blockZ,
                    outsiderFallback,
                    TAMED_DAMAGE_PERMISSION_KEY
            );
            if (!(allowedValue instanceof Boolean allowed)) {
                return new Result(Status.ERROR, null, "SimpleClaims native damage result was not boolean.");
            }
            UUID claimPartyId = resolveClaimPartyId(manager, worldName, blockX, blockZ);
            return new Result(allowed ? Status.ALLOWED : Status.DENIED, claimPartyId, null);
        } catch (Throwable throwable) {
            return new Result(
                    Status.ERROR,
                    null,
                    "SimpleClaims native damage policy failed: " + SimpleClaimsReflection.message(throwable)
            );
        }
    }

    private boolean isTamedDamageAllowedForOutsider(@Nullable Object party) {
        if (party == null) {
            throw new PredicateInvocationFailure("SimpleClaims outsider policy party was null.");
        }
        try {
            Object enabledValue = isTamedDamageEnabled.invoke(party);
            if (enabledValue instanceof Boolean enabled) {
                return enabled;
            }
            throw new PredicateInvocationFailure("SimpleClaims tamed-damage fallback was not boolean.");
        } catch (PredicateInvocationFailure failure) {
            throw failure;
        } catch (Throwable throwable) {
            throw new PredicateInvocationFailure(SimpleClaimsReflection.message(throwable));
        }
    }

    @Nullable
    private UUID resolveClaimPartyId(@Nonnull Object manager,
                                     @Nonnull String worldName,
                                     int blockX,
                                     int blockZ) {
        if (getChunkRawCoords == null || getPartyOwner == null) {
            return null;
        }
        try {
            Object chunk = getChunkRawCoords.invoke(manager, worldName, blockX, blockZ);
            if (chunk == null) {
                return null;
            }
            Object partyId = getPartyOwner.invoke(chunk);
            return partyId instanceof UUID uuid ? uuid : null;
        } catch (Throwable ignored) {
            return null;
        }
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
    private static SimpleClaimsNativeDamageAccess unavailable(@Nonnull String reason) {
        return new SimpleClaimsNativeDamageAccess(false, reason, null, null, null, null, null);
    }

    private static final class PredicateInvocationFailure extends RuntimeException {
        private PredicateInvocationFailure(@Nonnull String message) {
            super(message);
        }
    }
}
