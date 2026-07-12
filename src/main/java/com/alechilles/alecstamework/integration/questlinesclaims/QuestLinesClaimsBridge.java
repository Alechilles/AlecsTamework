package com.alechilles.alecstamework.integration.questlinesclaims;

import static com.alechilles.alecstamework.integration.questlinesclaims.QuestLinesReflectionAccess.extractMessage;
import static com.alechilles.alecstamework.integration.questlinesclaims.QuestLinesReflectionAccess.firstFailure;
import static com.alechilles.alecstamework.integration.questlinesclaims.QuestLinesReflectionAccess.normalizeText;
import static com.alechilles.alecstamework.integration.questlinesclaims.QuestLinesReflectionAccess.parseInteger;
import static com.alechilles.alecstamework.integration.questlinesclaims.QuestLinesReflectionAccess.parseUuid;
import static com.alechilles.alecstamework.integration.questlinesclaims.QuestLinesReflectionAccess.resolveAccessor;

import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimFootprint;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationBridge;
import com.alechilles.alecstamework.integration.claims.ClaimLookupResult;
import com.alechilles.alecstamework.integration.claims.ClaimPluginLocation;
import com.alechilles.alecstamework.integration.claims.ClaimPopulationKey;
import com.alechilles.alecstamework.integration.claims.ClaimProviderState;
import com.alechilles.alecstamework.integration.claims.ClaimResolution;
import com.alechilles.alecstamework.integration.claims.HytaleClaimPluginLocator;
import com.alechilles.alecstamework.integration.questlinesclaims.QuestLinesReflectionAccess.Accessor;
import com.alechilles.alecstamework.integration.questlinesclaims.QuestLinesReflectionAccess.MethodFinder;
import com.alechilles.alecstamework.integration.questlinesclaims.QuestLinesReflectionAccess.ReflectedValue;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reflection bridge to QuestLines Claims' public API.
 *
 * <p>Each instance belongs to one observed plugin generation. Claim and coordinate accessor
 * bundles, including missing accessors, are cached by runtime class for that generation.</p>
 */
public final class QuestLinesClaimsBridge implements ClaimIntegrationBridge {
    private static final String PROVIDER_ID = "questlines-claims";
    private static final String FOOTPRINT_ID_PREFIX = "footprint:";

    private final boolean available;
    @Nullable
    private final String unavailableReason;
    @Nullable
    private final Object api;
    @Nullable
    private final Method getClaimAtBlock;
    private final MethodFinder methodFinder;
    private final ConcurrentMap<Class<?>, ClaimAccessorBundle> claimAccessors = new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<?>, CoordinateAccessorBundle> coordinateAccessors = new ConcurrentHashMap<>();

    private QuestLinesClaimsBridge(boolean available,
                                   @Nullable String unavailableReason,
                                   @Nullable Object api,
                                   @Nullable Method getClaimAtBlock) {
        this(available, unavailableReason, api, getClaimAtBlock, QuestLinesReflectionAccess.PUBLIC_METHODS);
    }

    private QuestLinesClaimsBridge(boolean available,
                                   @Nullable String unavailableReason,
                                   @Nullable Object api,
                                   @Nullable Method getClaimAtBlock,
                                   @Nonnull MethodFinder methodFinder) {
        this.available = available;
        this.unavailableReason = unavailableReason;
        this.api = api;
        this.getClaimAtBlock = getClaimAtBlock;
        this.methodFinder = Objects.requireNonNull(methodFinder, "methodFinder");
    }

    /** Resolves the current plugin generation without retaining a process-wide bridge. */
    @Nonnull
    public static QuestLinesClaimsBridge initialize() {
        return createBridge();
    }

    @Nonnull
    static QuestLinesClaimsBridge forApiForTests(@Nonnull Object api) {
        return forApiForTests(api, QuestLinesReflectionAccess.PUBLIC_METHODS);
    }

    @Nonnull
    static QuestLinesClaimsBridge forApiForTests(@Nonnull Object api,
                                                 @Nonnull MethodFinder methodFinder) {
        Method lookup = methodFinder.find(
                api.getClass(), "getClaimAtBlock", String.class, int.class, int.class
        );
        return lookup == null
                ? unavailable("QuestLines Claims API getClaimAtBlock(String,int,int) was not found.")
                : new QuestLinesClaimsBridge(true, null, api, lookup, methodFinder);
    }

    @Nonnull
    private static QuestLinesClaimsBridge createBridge() {
        ClaimPluginLocation location = new HytaleClaimPluginLocator(
                PROVIDER_ID,
                HytaleClaimPluginLocator.QUESTLINES_PLUGIN_IDENTIFIER
        ).locate();
        if (location.state() != ClaimProviderState.READY || location.pluginInstance() == null) {
            return unavailable(location.reason());
        }
        return forPlugin(location.pluginInstance());
    }

    @Nonnull
    static QuestLinesClaimsBridge forPlugin(@Nonnull Object claimsPlugin) {
        try {
            Method getApi = claimsPlugin.getClass().getMethod("getApi");
            Object api = getApi.invoke(claimsPlugin);
            if (api == null) {
                return unavailable("QuestLinesClaims API is null.");
            }
            Method lookup = QuestLinesReflectionAccess.findMethod(
                    api.getClass(), "getClaimAtBlock", String.class, int.class, int.class
            );
            if (lookup == null) {
                return unavailable("QuestLinesClaims API getClaimAtBlock(String,int,int) was not found.");
            }
            return new QuestLinesClaimsBridge(true, null, api, lookup);
        } catch (Throwable throwable) {
            return unavailable(extractMessage(throwable));
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
        ClaimAccessorBundle accessors = claimAccessors.computeIfAbsent(
                claim.getClass(), this::discoverClaimAccessors
        );
        ReflectedValue ownerValue = accessors.owner().read(claim);
        if (ownerValue.failure() != null) {
            return reflectionError("claim owner", ownerValue);
        }
        UUID ownerId = parseUuid(ownerValue.value());
        if (ownerId == null) {
            return ClaimResolution.error("QuestLines claim owner UUID is missing.");
        }

        ReflectedValue ownerTypeValue = accessors.ownerType().read(claim);
        if (ownerTypeValue.failure() != null) {
            return reflectionError("claim owner type", ownerTypeValue);
        }
        String ownerType = normalizeOwnerType(ownerTypeValue.value());

        ClaimResolution worldError = validateClaimWorld(requestedWorld, claim, accessors);
        if (worldError != null) {
            return worldError;
        }

        FootprintRead footprintRead = readFootprint(requestedWorld, claim, accessors);
        if (footprintRead.error() != null) {
            return ClaimResolution.error(footprintRead.error());
        }
        ClaimFootprint footprint = footprintRead.footprint();
        if (footprint == null) {
            return ClaimResolution.error("QuestLines claim extent did not produce a complete footprint.");
        }

        ReflectedValue claimIdValue = accessors.claimId().read(claim);
        if (claimIdValue.failure() != null) {
            return reflectionError("claim id", claimIdValue);
        }
        String claimId = normalizeClaimId(claimIdValue.value());
        if (claimId == null) {
            claimId = FOOTPRINT_ID_PREFIX + footprint.digest();
        }

        ClaimPopulationKey key = ClaimPopulationKey.questLines(
                requestedWorld,
                ownerType,
                ownerId,
                claimId
        );
        return ClaimResolution.found(key, footprint);
    }

    @Nonnull
    private ClaimAccessorBundle discoverClaimAccessors(@Nonnull Class<?> claimType) {
        return new ClaimAccessorBundle(
                resolveAccessor(claimType, methodFinder, "getOwnerUuid", "getOwnerId"),
                resolveAccessor(claimType, methodFinder, "getOwnerType"),
                resolveAccessor(claimType, methodFinder, "getWorldName"),
                resolveAccessor(claimType, methodFinder, "getChunks"),
                resolveAccessor(claimType, methodFinder, "getClaimId", "getId")
        );
    }

    @Nullable
    private static ClaimResolution validateClaimWorld(@Nonnull String requestedWorld,
                                                      @Nonnull Object claim,
                                                      @Nonnull ClaimAccessorBundle accessors) {
        ReflectedValue worldValue = accessors.world().read(claim);
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
    private FootprintRead readFootprint(@Nonnull String requestedWorld,
                                        @Nonnull Object claim,
                                        @Nonnull ClaimAccessorBundle accessors) {
        ReflectedValue chunksValue = accessors.chunks().read(claim);
        if (chunksValue.failure() != null) {
            return FootprintRead.error("QuestLines getChunks failed: " + extractMessage(chunksValue.failure()));
        }
        if (!chunksValue.methodFound()) {
            return FootprintRead.error(
                    "QuestLines claim getChunks() accessor is missing; verified 1.3.1 extent is required."
            );
        }
        return mapChunkElements(requestedWorld, chunksValue.value());
    }

    @Nonnull
    private FootprintRead mapChunkElements(@Nonnull String requestedWorld, @Nullable Object rawChunks) {
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
    private ChunkSnapshot snapshotChunks(@Nullable Object rawChunks) {
        if (rawChunks == null) {
            return ChunkSnapshot.error("QuestLines getChunks returned null.");
        }
        try {
            if (rawChunks instanceof Collection<?> collection) {
                return ChunkSnapshot.success(new ArrayList<>(collection));
            }
            if (rawChunks instanceof Map<?, ?> map) {
                ArrayList<Map.Entry<?, ?>> entries = new ArrayList<>(map.size());
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    entries.add(new AbstractMap.SimpleImmutableEntry<>(entry));
                }
                ArrayList<Object> elements = new ArrayList<>(entries.size());
                for (Map.Entry<?, ?> entry : entries) {
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

    @Nonnull
    private CoordinateRead readCoordinate(@Nullable Object element) {
        if (element == null) {
            return CoordinateRead.error("QuestLines claim chunks contained a null element.");
        }
        CoordinateAccessorBundle accessors = coordinateAccessors.computeIfAbsent(
                element.getClass(), this::discoverCoordinateAccessors
        );
        ReflectedValue xValue = accessors.x().read(element);
        ReflectedValue zValue = accessors.z().read(element);
        ReflectedValue worldValue = accessors.world().read(element);
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

    private boolean coordinateLike(@Nullable Object value) {
        if (value == null) {
            return false;
        }
        return coordinateAccessors.computeIfAbsent(
                value.getClass(), this::discoverCoordinateAccessors
        ).hasCoordinates();
    }

    @Nonnull
    private CoordinateAccessorBundle discoverCoordinateAccessors(@Nonnull Class<?> coordinateType) {
        return new CoordinateAccessorBundle(
                resolveAccessor(coordinateType, methodFinder, "getChunkX", "getX"),
                resolveAccessor(coordinateType, methodFinder, "getChunkZ", "getZ"),
                resolveAccessor(coordinateType, methodFinder, "getWorldName", "getWorld")
        );
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

    @Nonnull
    private static QuestLinesClaimsBridge unavailable(@Nullable String reason) {
        return new QuestLinesClaimsBridge(false, reason, null, null);
    }

    private record FootprintRead(@Nullable ClaimFootprint footprint,
                                 @Nullable String error) {
        @Nonnull
        static FootprintRead complete(@Nonnull ClaimFootprint footprint) {
            return new FootprintRead(footprint, null);
        }

        @Nonnull
        static FootprintRead error(@Nonnull String message) {
            return new FootprintRead(null, message);
        }
    }

    private record ClaimAccessorBundle(@Nonnull Accessor owner,
                                       @Nonnull Accessor ownerType,
                                       @Nonnull Accessor world,
                                       @Nonnull Accessor chunks,
                                       @Nonnull Accessor claimId) {
    }

    private record CoordinateAccessorBundle(@Nonnull Accessor x,
                                            @Nonnull Accessor z,
                                            @Nonnull Accessor world) {
        boolean hasCoordinates() {
            return x.methodFound() && z.methodFound();
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
