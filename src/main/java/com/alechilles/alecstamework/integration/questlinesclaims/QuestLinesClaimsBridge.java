package com.alechilles.alecstamework.integration.questlinesclaims;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationBridge;
import com.alechilles.alecstamework.integration.claims.ClaimLookupResult;
import com.alechilles.alecstamework.integration.claims.ClaimPopulationKey;
import java.lang.reflect.Method;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reflection bridge to QuestLines Claims' public API.
 */
public final class QuestLinesClaimsBridge implements ClaimIntegrationBridge {
    private static final String PROVIDER_ID = "questlines-claims";

    @Nullable
    private static volatile QuestLinesClaimsBridge cachedBridge;

    private final boolean available;
    @Nullable
    private final String unavailableReason;
    @Nullable
    private final Object api;
    @Nullable
    private final Method getClaimAtBlock;

    private QuestLinesClaimsBridge(boolean available,
                                   @Nullable String unavailableReason,
                                   @Nullable Object api,
                                   @Nullable Method getClaimAtBlock) {
        this.available = available;
        this.unavailableReason = unavailableReason;
        this.api = api;
        this.getClaimAtBlock = getClaimAtBlock;
    }

    @Nonnull
    public static QuestLinesClaimsBridge initialize() {
        QuestLinesClaimsBridge bridge = cachedBridge;
        if (bridge != null) {
            return bridge;
        }
        synchronized (QuestLinesClaimsBridge.class) {
            bridge = cachedBridge;
            if (bridge == null) {
                bridge = createBridge();
                cachedBridge = bridge;
            }
            return bridge;
        }
    }

    @Nonnull
    static QuestLinesClaimsBridge forApiForTests(@Nonnull Object api) {
        Method lookup = findMethod(api.getClass(), "getClaimAtBlock", String.class, int.class, int.class);
        return lookup == null
                ? unavailable("QuestLines Claims API getClaimAtBlock(String,int,int) was not found.")
                : new QuestLinesClaimsBridge(true, null, api, lookup);
    }

    @Nonnull
    private static QuestLinesClaimsBridge createBridge() {
        try {
            Class.forName(
                    "net.evilcraft.questlinesclaims.api.QuestLinesClaimsAPI",
                    false,
                    QuestLinesClaimsBridge.class.getClassLoader()
            );
            Object claimsPlugin = resolveLoadedPlugin(Tamework.getInstance(), "QuestLinesClaims");
            if (claimsPlugin == null) {
                return unavailable("QuestLinesClaims plugin is not loaded.");
            }
            Method getApi = claimsPlugin.getClass().getMethod("getApi");
            Object api = getApi.invoke(claimsPlugin);
            if (api == null) {
                return unavailable("QuestLinesClaims API is null.");
            }
            Method lookup = findMethod(api.getClass(), "getClaimAtBlock", String.class, int.class, int.class);
            if (lookup == null) {
                return unavailable("QuestLinesClaims API getClaimAtBlock(String,int,int) was not found.");
            }
            return new QuestLinesClaimsBridge(true, null, api, lookup);
        } catch (Throwable throwable) {
            return unavailable(extractMessage(throwable));
        }
    }

    @Nullable
    private static Object resolveLoadedPlugin(@Nullable Tamework plugin, @Nonnull String pluginName) {
        if (plugin == null) {
            return null;
        }
        try {
            Method getServer = plugin.getClass().getMethod("getServer");
            Object server = getServer.invoke(plugin);
            if (server == null) {
                return null;
            }
            Method getPlugin = server.getClass().getMethod("getPlugin", String.class);
            return getPlugin.invoke(server, pluginName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nonnull
    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Nullable
    @Override
    public String getUnavailableReason() {
        return unavailableReason;
    }

    @Nonnull
    @Override
    public ClaimLookupResult lookupClaim(@Nullable String worldName, double blockX, double blockZ) {
        if (!available) {
            return ClaimLookupResult.unavailable(unavailableReason);
        }
        if (api == null || getClaimAtBlock == null) {
            return ClaimLookupResult.unavailable("QuestLinesClaims API is unavailable.");
        }
        if (worldName == null || worldName.isBlank()) {
            return ClaimLookupResult.error("World name is missing.");
        }
        if (!Double.isFinite(blockX) || !Double.isFinite(blockZ)) {
            return ClaimLookupResult.error("Position is not finite.");
        }
        try {
            Object claim = getClaimAtBlock.invoke(api, worldName, (int) Math.floor(blockX), (int) Math.floor(blockZ));
            if (claim == null) {
                return ClaimLookupResult.noClaim();
            }
            return mapClaim(worldName, claim);
        } catch (Throwable throwable) {
            return ClaimLookupResult.error(extractMessage(throwable));
        }
    }

    @Nonnull
    private ClaimLookupResult mapClaim(@Nonnull String worldName, @Nonnull Object claim) {
        UUID ownerId = readUuid(claim, "getOwnerUuid", "getOwnerId");
        if (ownerId == null) {
            return ClaimLookupResult.error("QuestLines claim owner UUID is missing.");
        }
        String ownerType = readString(claim, "getOwnerType");
        if (ownerType == null || ownerType.isBlank()) {
            ownerType = "PLAYER";
        }
        Object claimId = readValue(claim, "getId", "getClaimId");
        int chunkCount = Math.max(1, readInt(claim, 1, "getChunkCount", "getChunksCount"));
        return ClaimLookupResult.found(
                ClaimPopulationKey.questLines(worldName, ownerType, ownerId, claimId),
                chunkCount
        );
    }

    @Nullable
    private static Method findMethod(@Nonnull Class<?> type,
                                     @Nonnull String name,
                                     Class<?>... parameterTypes) {
        try {
            return type.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    @Nullable
    private static Object readValue(@Nonnull Object target, @Nonnull String... methodNames) {
        for (String methodName : methodNames) {
            Method method = findMethod(target.getClass(), methodName);
            if (method == null) {
                continue;
            }
            try {
                return method.invoke(target);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    @Nullable
    private static UUID readUuid(@Nonnull Object target, @Nonnull String... methodNames) {
        Object value = readValue(target, methodNames);
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String text) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    @Nullable
    private static String readString(@Nonnull Object target, @Nonnull String... methodNames) {
        Object value = readValue(target, methodNames);
        return value == null ? null : String.valueOf(value);
    }

    private static int readInt(@Nonnull Object target, int fallback, @Nonnull String... methodNames) {
        Object value = readValue(target, methodNames);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    @Nonnull
    private static QuestLinesClaimsBridge unavailable(@Nullable String reason) {
        return new QuestLinesClaimsBridge(false, reason, null, null);
    }

    @Nullable
    private static String extractMessage(@Nullable Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    static void clearCachedBridgeForTests() {
        synchronized (QuestLinesClaimsBridge.class) {
            cachedBridge = null;
        }
    }
}
