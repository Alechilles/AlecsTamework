package com.alechilles.alecstamework.integration.questlinesclaims;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimFootprint;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationBridge;
import com.alechilles.alecstamework.integration.claims.ClaimLookupResult;
import com.alechilles.alecstamework.integration.claims.ClaimPopulationKey;
import com.alechilles.alecstamework.integration.claims.ClaimResolution;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reflection bridge to QuestLines Claims' public API.
 */
public final class QuestLinesClaimsBridge implements ClaimIntegrationBridge {
    private static final String PROVIDER_ID = "questlines-claims";
    private static final String FOOTPRINT_ID_PREFIX = "footprint:";

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
        return resolveClaim(worldName, blockX, blockZ).toLookupResult();
    }

    @Nonnull
    @Override
    public ClaimResolution resolveClaim(@Nullable String worldName, double blockX, double blockZ) {
        if (!available) {
            return ClaimResolution.unavailable(unavailableReason);
        }
        if (api == null || getClaimAtBlock == null) {
            return ClaimResolution.unavailable("QuestLinesClaims API is unavailable.");
        }
        if (worldName == null || worldName.isBlank()) {
            return ClaimResolution.error("World name is missing.");
        }
        if (!Double.isFinite(blockX) || !Double.isFinite(blockZ)) {
            return ClaimResolution.error("Position is not finite.");
        }
        String requestedWorld = worldName.trim();
        try {
            Object claim = getClaimAtBlock.invoke(
                    api,
                    requestedWorld,
                    (int) Math.floor(blockX),
                    (int) Math.floor(blockZ)
            );
            return claim == null ? ClaimResolution.noClaim() : mapClaim(requestedWorld, claim);
        } catch (Throwable throwable) {
            return ClaimResolution.error("QuestLines claim lookup failed: " + extractMessage(throwable));
        }
    }

    @Nonnull
    private ClaimResolution mapClaim(@Nonnull String requestedWorld, @Nonnull Object claim) {
        ReflectedValue ownerValue = readValue(claim, "getOwnerUuid", "getOwnerId");
        if (ownerValue.failure() != null) {
            return reflectionError("claim owner", ownerValue);
        }
        UUID ownerId = parseUuid(ownerValue.value());
        if (ownerId == null) {
            return ClaimResolution.error("QuestLines claim owner UUID is missing.");
        }

        ReflectedValue ownerTypeValue = readValue(claim, "getOwnerType");
        if (ownerTypeValue.failure() != null) {
            return reflectionError("claim owner type", ownerTypeValue);
        }
        String ownerType = normalizeOwnerType(ownerTypeValue.value());

        ClaimResolution worldError = validateClaimWorld(requestedWorld, claim);
        if (worldError != null) {
            return worldError;
        }

        FootprintRead footprintRead = readFootprint(requestedWorld, claim);
        if (footprintRead.error() != null) {
            return ClaimResolution.error(footprintRead.error());
        }

        ReflectedValue claimIdValue = readValue(claim, "getClaimId", "getId");
        if (claimIdValue.failure() != null) {
            return reflectionError("claim id", claimIdValue);
        }
        String claimId = normalizeClaimId(claimIdValue.value());
        if (claimId == null && footprintRead.footprint() != null) {
            claimId = FOOTPRINT_ID_PREFIX + footprintRead.footprint().digest();
        }
        if (claimId == null) {
            return ClaimResolution.error("QuestLines claim ID is missing and no complete footprint is available.");
        }

        ClaimPopulationKey key = ClaimPopulationKey.questLines(
                requestedWorld,
                ownerType,
                ownerId,
                claimId
        );
        return footprintRead.footprint() != null
                ? ClaimResolution.found(key, footprintRead.footprint())
                : ClaimResolution.foundWithoutFootprint(key, footprintRead.chunkCount());
    }

    @Nullable
    private static ClaimResolution validateClaimWorld(@Nonnull String requestedWorld, @Nonnull Object claim) {
        ReflectedValue worldValue = readValue(claim, "getWorldName");
        if (worldValue.failure() != null) {
            return reflectionError("claim world", worldValue);
        }
        String claimWorld = normalizeText(worldValue.value());
        if (claimWorld != null && !requestedWorld.equals(claimWorld)) {
            return ClaimResolution.error(
                    "QuestLines claim world '" + claimWorld + "' did not match requested world '" + requestedWorld + "'."
            );
        }
        return null;
    }

    @Nonnull
    private static FootprintRead readFootprint(@Nonnull String requestedWorld, @Nonnull Object claim) {
        ReflectedValue chunksValue = readValue(claim, "getChunks");
        if (chunksValue.failure() != null) {
            return FootprintRead.error("QuestLines getChunks failed: " + extractMessage(chunksValue.failure()));
        }
        if (chunksValue.methodFound()) {
            return mapChunkElements(requestedWorld, chunksValue.value());
        }

        ReflectedValue numericValue = readValue(claim, "getChunkCount", "getChunksCount");
        if (numericValue.failure() != null) {
            return FootprintRead.error("QuestLines numeric chunk count failed: " + extractMessage(numericValue.failure()));
        }
        Integer numericCount = parseInteger(numericValue.value());
        if (!numericValue.methodFound() || numericCount == null || numericCount <= 0) {
            return FootprintRead.error("QuestLines claim chunk extent is missing or empty.");
        }
        return FootprintRead.scalar(numericCount);
    }

    @Nonnull
    private static FootprintRead mapChunkElements(@Nonnull String requestedWorld, @Nullable Object rawChunks) {
        ChunkSnapshot snapshot = snapshotChunks(rawChunks);
        if (snapshot.error() != null) {
            return FootprintRead.error(snapshot.error());
        }
        if (snapshot.elements().isEmpty()) {
            return FootprintRead.error("QuestLines claim chunk extent is empty.");
        }

        ArrayList<ClaimChunkCoordinate> coordinates = new ArrayList<>(snapshot.elements().size());
        for (Object element : snapshot.elements()) {
            CoordinateRead coordinate = readCoordinate(element);
            if (coordinate.error() != null) {
                return FootprintRead.error(coordinate.error());
            }
            if (!requestedWorld.equals(coordinate.coordinate().worldName())) {
                return FootprintRead.error(
                        "QuestLines claim chunk world '"
                                + coordinate.coordinate().worldName()
                                + "' did not match requested world '"
                                + requestedWorld
                                + "'."
                );
            }
            coordinates.add(coordinate.coordinate());
        }

        ClaimFootprint footprint = new ClaimFootprint(coordinates);
        return footprint.chunks().isEmpty()
                ? FootprintRead.error("QuestLines claim chunk extent is empty.")
                : FootprintRead.complete(footprint);
    }

    @Nonnull
    private static ChunkSnapshot snapshotChunks(@Nullable Object rawChunks) {
        if (rawChunks == null) {
            return ChunkSnapshot.error("QuestLines getChunks returned null.");
        }
        try {
            if (rawChunks instanceof Collection<?> collection) {
                return ChunkSnapshot.success(new ArrayList<>(collection));
            }
            if (rawChunks instanceof Map<?, ?> map) {
                ArrayList<Object> elements = new ArrayList<>(map.size());
                for (Map.Entry<?, ?> entry : new ArrayList<>(map.entrySet())) {
                    Object candidate = coordinateLike(entry.getValue()) ? entry.getValue() : entry.getKey();
                    elements.add(candidate);
                }
                return ChunkSnapshot.success(elements);
            }
            if (rawChunks.getClass().isArray()) {
                int length = Array.getLength(rawChunks);
                ArrayList<Object> elements = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    elements.add(Array.get(rawChunks, i));
                }
                return ChunkSnapshot.success(elements);
            }
            return ChunkSnapshot.error(
                    "QuestLines getChunks returned unsupported type " + rawChunks.getClass().getName() + "."
            );
        } catch (Throwable throwable) {
            return ChunkSnapshot.error("QuestLines claim chunks could not be snapshotted: " + extractMessage(throwable));
        }
    }

    private static boolean coordinateLike(@Nullable Object value) {
        return value != null
                && (findMethod(value.getClass(), "getChunkX") != null || findMethod(value.getClass(), "getX") != null)
                && (findMethod(value.getClass(), "getChunkZ") != null || findMethod(value.getClass(), "getZ") != null);
    }

    @Nonnull
    private static CoordinateRead readCoordinate(@Nullable Object element) {
        if (element == null) {
            return CoordinateRead.error("QuestLines claim chunks contained a null element.");
        }
        ReflectedValue xValue = readValue(element, "getChunkX", "getX");
        ReflectedValue zValue = readValue(element, "getChunkZ", "getZ");
        ReflectedValue worldValue = readValue(element, "getWorldName", "getWorld");
        if (xValue.failure() != null || zValue.failure() != null || worldValue.failure() != null) {
            Throwable failure = firstFailure(xValue, zValue, worldValue);
            return CoordinateRead.error("QuestLines claim chunk accessor failed: " + extractMessage(failure));
        }
        Integer chunkX = parseInteger(xValue.value());
        Integer chunkZ = parseInteger(zValue.value());
        String worldName = normalizeText(worldValue.value());
        if (!xValue.methodFound() || !zValue.methodFound() || chunkX == null || chunkZ == null || worldName == null) {
            return CoordinateRead.error(
                    "QuestLines claim chunk " + element.getClass().getName() + " was missing X, Z, or world data."
            );
        }
        return CoordinateRead.success(new ClaimChunkCoordinate(worldName, chunkX, chunkZ));
    }

    @Nullable
    private static Throwable firstFailure(@Nonnull ReflectedValue... values) {
        for (ReflectedValue value : values) {
            if (value.failure() != null) {
                return value.failure();
            }
        }
        return null;
    }

    @Nonnull
    private static ClaimResolution reflectionError(@Nonnull String field, @Nonnull ReflectedValue value) {
        return ClaimResolution.error(
                "QuestLines " + field + " accessor " + value.methodName() + " failed: " + extractMessage(value.failure())
        );
    }

    @Nonnull
    private static String normalizeOwnerType(@Nullable Object value) {
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        String normalized = normalizeText(value);
        return normalized == null ? "PLAYER" : normalized;
    }

    @Nullable
    private static String normalizeClaimId(@Nullable Object value) {
        return normalizeText(value);
    }

    @Nullable
    private static String normalizeText(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    @Nullable
    private static UUID parseUuid(@Nullable Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        String text = normalizeText(value);
        if (text == null) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nullable
    private static Integer parseInteger(@Nullable Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = normalizeText(value);
        if (text == null) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Nonnull
    private static ReflectedValue readValue(@Nonnull Object target, @Nonnull String... methodNames) {
        for (String methodName : methodNames) {
            Method method = findMethod(target.getClass(), methodName);
            if (method == null) {
                continue;
            }
            try {
                return ReflectedValue.success(methodName, method.invoke(target));
            } catch (Throwable throwable) {
                return ReflectedValue.failure(methodName, unwrapInvocation(throwable));
            }
        }
        return ReflectedValue.missing();
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

    @Nonnull
    private static QuestLinesClaimsBridge unavailable(@Nullable String reason) {
        return new QuestLinesClaimsBridge(false, reason, null, null);
    }

    @Nullable
    private static Throwable unwrapInvocation(@Nullable Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            current = invocation.getCause();
        }
        return current;
    }

    @Nonnull
    private static String extractMessage(@Nullable Throwable throwable) {
        Throwable unwrapped = unwrapInvocation(throwable);
        if (unwrapped == null) {
            return "unknown error";
        }
        String message = unwrapped.getMessage();
        return message == null || message.isBlank() ? unwrapped.getClass().getSimpleName() : message;
    }

    static void clearCachedBridgeForTests() {
        synchronized (QuestLinesClaimsBridge.class) {
            cachedBridge = null;
        }
    }

    private record ReflectedValue(boolean methodFound,
                                  @Nullable String methodName,
                                  @Nullable Object value,
                                  @Nullable Throwable failure) {
        @Nonnull
        static ReflectedValue success(@Nonnull String methodName, @Nullable Object value) {
            return new ReflectedValue(true, methodName, value, null);
        }

        @Nonnull
        static ReflectedValue failure(@Nonnull String methodName, @Nullable Throwable failure) {
            return new ReflectedValue(true, methodName, null, failure);
        }

        @Nonnull
        static ReflectedValue missing() {
            return new ReflectedValue(false, null, null, null);
        }
    }

    private record FootprintRead(@Nullable ClaimFootprint footprint,
                                 int chunkCount,
                                 @Nullable String error) {
        @Nonnull
        static FootprintRead complete(@Nonnull ClaimFootprint footprint) {
            return new FootprintRead(footprint, footprint.chunkCount(), null);
        }

        @Nonnull
        static FootprintRead scalar(int count) {
            return new FootprintRead(null, Math.max(0, count), null);
        }

        @Nonnull
        static FootprintRead error(@Nonnull String message) {
            return new FootprintRead(null, 0, message);
        }
    }

    private record ChunkSnapshot(@Nonnull List<Object> elements, @Nullable String error) {
        @Nonnull
        static ChunkSnapshot success(@Nonnull List<Object> elements) {
            return new ChunkSnapshot(List.copyOf(elements), null);
        }

        @Nonnull
        static ChunkSnapshot error(@Nonnull String message) {
            return new ChunkSnapshot(List.of(), message);
        }
    }

    private record CoordinateRead(@Nullable ClaimChunkCoordinate coordinate, @Nullable String error) {
        @Nonnull
        static CoordinateRead success(@Nonnull ClaimChunkCoordinate coordinate) {
            return new CoordinateRead(coordinate, null);
        }

        @Nonnull
        static CoordinateRead error(@Nonnull String message) {
            return new CoordinateRead(null, message);
        }
    }
}
