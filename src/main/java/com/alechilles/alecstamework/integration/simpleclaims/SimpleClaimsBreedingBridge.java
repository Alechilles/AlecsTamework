package com.alechilles.alecstamework.integration.simpleclaims;

import com.alechilles.alecstamework.integration.claims.ClaimFootprint;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationBridge;
import com.alechilles.alecstamework.integration.claims.ClaimLookupResult;
import com.alechilles.alecstamework.integration.claims.ClaimPopulationKey;
import com.alechilles.alecstamework.integration.claims.ClaimResolution;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Compatibility facade over independently probed SimpleClaims 1.0.38 capabilities.
 *
 * <p>Claim lookup remains usable when world extent or native damage methods are absent. Extent
 * failures are explicit on the rich resolution path so per-chunk consumers fail closed, while
 * total-only and lookup-only consumers can use {@link #lookupClaimIdentity} independently.</p>
 */
public final class SimpleClaimsBreedingBridge implements ClaimIntegrationBridge {
    private static final String PROVIDER_ID = "simpleclaims";

    @Nullable
    private static volatile SimpleClaimsBreedingBridge cachedBridge;

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
        public ClaimInfo {
            claimChunkCount = Math.max(0, claimChunkCount);
        }
    }

    public record LookupResult(LookupStatus status,
                               @Nullable ClaimInfo claimInfo,
                               @Nullable String message) {
    }

    public record DamageAccessResult(DamageAccessStatus status,
                                     @Nullable UUID claimPartyId,
                                     @Nullable String message) {
    }

    @Nonnull
    private final SimpleClaimsClaimLookup lookup;
    @Nonnull
    private final SimpleClaimsWorldExtent extent;
    @Nonnull
    private final SimpleClaimsNativeTamedDamagePolicy damagePolicy;
    @Nonnull
    private final SimpleClaimsNativeDamageAccess damageAccess;

    private SimpleClaimsBreedingBridge(@Nonnull SimpleClaimsClaimLookup lookup,
                                       @Nonnull SimpleClaimsWorldExtent extent,
                                       @Nonnull SimpleClaimsNativeDamageAccess damageAccess) {
        this.lookup = lookup;
        this.extent = extent;
        this.damageAccess = damageAccess;
        this.damagePolicy = new SimpleClaimsNativeTamedDamagePolicy(damageAccess);
    }

    @Nonnull
    public static SimpleClaimsBreedingBridge initialize() {
        SimpleClaimsBreedingBridge bridge = cachedBridge;
        if (bridge != null) {
            return bridge;
        }
        synchronized (SimpleClaimsBreedingBridge.class) {
            bridge = cachedBridge;
            if (bridge == null) {
                bridge = createBridge();
                cachedBridge = bridge;
            }
            return bridge;
        }
    }

    @Nonnull
    private static SimpleClaimsBreedingBridge createBridge() {
        ClassLoader classLoader = SimpleClaimsBreedingBridge.class.getClassLoader();
        return new SimpleClaimsBreedingBridge(
                SimpleClaimsClaimLookup.probe(classLoader),
                SimpleClaimsWorldExtent.probe(classLoader),
                SimpleClaimsNativeDamageAccess.probe(classLoader)
        );
    }

    @Nonnull
    static SimpleClaimsBreedingBridge forTypesForTests(@Nonnull Class<?> managerType,
                                                       @Nonnull Class<?> chunkType,
                                                       @Nonnull Class<?> partyType) {
        return new SimpleClaimsBreedingBridge(
                SimpleClaimsClaimLookup.forTypes(managerType, chunkType),
                SimpleClaimsWorldExtent.forTypes(managerType, chunkType),
                SimpleClaimsNativeDamageAccess.forTypes(managerType, chunkType, partyType)
        );
    }

    @Override
    public boolean isAvailable() {
        return lookup.isAvailable();
    }

    @Nullable
    @Override
    public String getUnavailableReason() {
        return lookup.unavailableReason();
    }

    public boolean isExtentAvailable() {
        return extent.isAvailable();
    }

    @Nullable
    public String getExtentUnavailableReason() {
        return extent.unavailableReason();
    }

    public boolean isDamagePolicyAvailable() {
        return damageAccess.isAvailable();
    }

    @Nullable
    public String getDamagePolicyUnavailableReason() {
        return damageAccess.unavailableReason();
    }

    @Nonnull
    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Nonnull
    @Override
    public ClaimLookupResult lookupClaim(@Nullable String worldName, double blockX, double blockZ) {
        ClaimResolution resolution = resolveClaim(worldName, blockX, blockZ);
        return switch (resolution.status()) {
            case CLAIM_FOUND -> resolution.key() == null
                    ? ClaimLookupResult.error("SimpleClaims claim key was missing.")
                    : new ClaimLookupResult(
                            ClaimLookupResult.Status.CLAIM_FOUND,
                            resolution.key(),
                            resolution.claimChunkCount(),
                            resolution.message()
                    );
            case NO_CLAIM -> ClaimLookupResult.noClaim();
            case UNAVAILABLE -> ClaimLookupResult.unavailable(resolution.message());
            case ERROR -> ClaimLookupResult.error(resolution.message());
        };
    }

    /**
     * Resolves only claim identity. Lookup-only and total-only policies use this path so they never
     * touch provider topology or a provider-global claim-count method.
     */
    @Nonnull
    public ClaimLookupResult lookupClaimIdentity(@Nullable String worldName, double blockX, double blockZ) {
        SimpleClaimsClaimLookup.Result result = lookup.lookup(worldName, blockX, blockZ);
        if (result.status() != SimpleClaimsClaimLookup.Status.CLAIM_FOUND) {
            return mapLookupFailure(result).toLookupResult();
        }
        String world = normalizeWorld(worldName);
        UUID partyId = result.partyId();
        if (world == null || partyId == null) {
            return ClaimLookupResult.error("SimpleClaims claim identity was incomplete.");
        }
        return ClaimLookupResult.found(ClaimPopulationKey.simpleClaims(world, partyId), 0);
    }

    @Nonnull
    public ClaimLookupResult lookupClaimIdentity(@Nullable String worldName, @Nullable Vector3d position) {
        if (position == null) {
            return ClaimLookupResult.error("Position is missing.");
        }
        return lookupClaimIdentity(worldName, position.x, position.z);
    }

    @Nonnull
    @Override
    public ClaimResolution resolveClaim(@Nullable String worldName, double blockX, double blockZ) {
        SimpleClaimsClaimLookup.Result lookupResult = lookup.lookup(worldName, blockX, blockZ);
        if (lookupResult.status() != SimpleClaimsClaimLookup.Status.CLAIM_FOUND) {
            return mapLookupFailure(lookupResult);
        }
        String world = normalizeWorld(worldName);
        UUID partyId = lookupResult.partyId();
        if (world == null || partyId == null) {
            return ClaimResolution.error("SimpleClaims claim identity was incomplete.");
        }
        ClaimPopulationKey key = ClaimPopulationKey.simpleClaims(world, partyId);
        SimpleClaimsWorldExtent.Result extentResult = extent.resolve(world, partyId);
        if (extentResult.status() == SimpleClaimsWorldExtent.Status.AVAILABLE) {
            ClaimFootprint footprint = extentResult.footprint();
            return footprint == null
                    ? ClaimResolution.error("SimpleClaims world extent was missing its footprint.")
                    : ClaimResolution.found(key, footprint);
        }
        String message = "SimpleClaims world extent "
                + extentResult.status().name().toLowerCase()
                + ": "
                + safeMessage(extentResult.message());
        return extentResult.status() == SimpleClaimsWorldExtent.Status.UNAVAILABLE
                ? ClaimResolution.unavailable(message)
                : ClaimResolution.error(message);
    }

    @Nonnull
    public LookupResult lookupSimpleClaimsClaim(@Nullable String worldName, @Nullable Vector3d position) {
        if (position == null) {
            return new LookupResult(LookupStatus.ERROR, null, "Position is missing.");
        }
        return lookupSimpleClaimsClaim(worldName, position.x, position.z);
    }

    @Nonnull
    public LookupResult lookupSimpleClaimsClaim(@Nullable String worldName, double blockX, double blockZ) {
        ClaimResolution resolution = resolveClaim(worldName, blockX, blockZ);
        return switch (resolution.status()) {
            case CLAIM_FOUND -> {
                ClaimPopulationKey key = resolution.key();
                if (key == null) {
                    yield new LookupResult(LookupStatus.ERROR, null, "SimpleClaims claim key was missing.");
                }
                yield new LookupResult(
                        LookupStatus.CLAIM_FOUND,
                        new ClaimInfo(key.ownerId(), resolution.claimChunkCount()),
                        resolution.message()
                );
            }
            case NO_CLAIM -> new LookupResult(LookupStatus.NO_CLAIM, null, null);
            case UNAVAILABLE -> new LookupResult(LookupStatus.UNAVAILABLE, null, resolution.message());
            case ERROR -> new LookupResult(LookupStatus.ERROR, null, resolution.message());
        };
    }

    @Nonnull
    public DamageAccessResult evaluateDamageAccess(@Nullable String worldName,
                                                   @Nullable Vector3d position,
                                                   @Nullable UUID attackerPlayerUuid,
                                                   @Nullable String allowDamagePermissionKey) {
        SimpleClaimsNativeTamedDamagePolicy.Decision decision = damagePolicy.evaluate(
                worldName,
                position,
                attackerPlayerUuid
        );
        return switch (decision.status()) {
            case ALLOWED, SKIPPED -> new DamageAccessResult(
                    DamageAccessStatus.ALLOWED,
                    decision.claimPartyId(),
                    decision.message()
            );
            case DENIED -> new DamageAccessResult(
                    DamageAccessStatus.DENIED,
                    decision.claimPartyId(),
                    decision.message()
            );
            case ALLOW_FAIL_OPEN -> new DamageAccessResult(
                    decision.accessStatus() == SimpleClaimsNativeDamageAccess.Status.UNAVAILABLE
                            ? DamageAccessStatus.UNAVAILABLE
                            : DamageAccessStatus.LOOKUP_ERROR,
                    decision.claimPartyId(),
                    decision.message()
            );
        };
    }

    @Nonnull
    SimpleClaimsWorldExtent.Result resolveWorldExtent(@Nullable String worldName, @Nullable UUID partyId) {
        return extent.resolve(worldName, partyId);
    }

    @Nonnull
    private static ClaimResolution mapLookupFailure(@Nonnull SimpleClaimsClaimLookup.Result result) {
        return switch (result.status()) {
            case CLAIM_FOUND -> ClaimResolution.error("SimpleClaims claim mapping was incomplete.");
            case NO_CLAIM -> ClaimResolution.noClaim();
            case UNAVAILABLE -> ClaimResolution.unavailable(result.message());
            case ERROR -> ClaimResolution.error(result.message());
        };
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
    private static String safeMessage(@Nullable String message) {
        return message == null || message.isBlank() ? "unknown error" : message;
    }

    static void clearCachedBridgeForTests() {
        synchronized (SimpleClaimsBreedingBridge.class) {
            cachedBridge = null;
        }
    }
}
