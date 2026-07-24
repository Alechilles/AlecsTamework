package com.alechilles.alecstamework.integration.simpleclaims;

import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Minimal optional bridge for released SimpleClaims breeding and tamed-damage behavior.
 *
 * <p>Each initialization reflects the current classloader generation. No population authority,
 * provider registry, claim occupancy, or world-topology cache is retained here.</p>
 */
public final class SimpleClaimsBreedingBridge {
    private static final String PROVIDER_ID = "simpleclaims";

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

    public record LookupResult(@Nonnull LookupStatus status,
                               @Nullable ClaimInfo claimInfo,
                               @Nullable String message) {
    }

    public record DamageAccessResult(@Nonnull DamageAccessStatus status,
                                     @Nullable UUID claimPartyId,
                                     @Nullable String message) {
    }

    @Nonnull
    private final SimpleClaimsClaimLookup lookup;
    @Nonnull
    private final SimpleClaimsNativeDamageAccess damageAccess;
    @Nonnull
    private final SimpleClaimsNativeTamedDamagePolicy damagePolicy;

    private SimpleClaimsBreedingBridge(@Nonnull SimpleClaimsClaimLookup lookup,
                                       @Nonnull SimpleClaimsNativeDamageAccess damageAccess) {
        this.lookup = lookup;
        this.damageAccess = damageAccess;
        this.damagePolicy = new SimpleClaimsNativeTamedDamagePolicy(damageAccess);
    }

    /** Resolves the current classloader generation without retaining a process-wide bridge. */
    @Nonnull
    public static SimpleClaimsBreedingBridge initialize() {
        return forClassLoader(SimpleClaimsBreedingBridge.class.getClassLoader());
    }

    @Nonnull
    static SimpleClaimsBreedingBridge forClassLoader(@Nonnull ClassLoader classLoader) {
        return new SimpleClaimsBreedingBridge(
                SimpleClaimsClaimLookup.probe(classLoader),
                SimpleClaimsNativeDamageAccess.probe(classLoader)
        );
    }

    /** Reflects only lookup and native-damage contracts for one live plugin generation. */
    @Nonnull
    public static SimpleClaimsBreedingBridge forDamagePlugin(@Nonnull Object plugin) {
        ClassLoader classLoader = plugin.getClass().getClassLoader();
        return forClassLoader(classLoader == null
                ? SimpleClaimsBreedingBridge.class.getClassLoader()
                : classLoader);
    }

    @Nonnull
    static SimpleClaimsBreedingBridge forTypesForTests(@Nonnull Class<?> managerType,
                                                       @Nonnull Class<?> chunkType,
                                                       @Nonnull Class<?> partyType) {
        return new SimpleClaimsBreedingBridge(
                SimpleClaimsClaimLookup.forTypes(managerType, chunkType, partyType),
                SimpleClaimsNativeDamageAccess.forTypes(managerType, chunkType, partyType)
        );
    }

    @Nonnull
    static SimpleClaimsBreedingBridge forDamageTypesForTests(@Nonnull Class<?> managerType,
                                                             @Nonnull Class<?> chunkType,
                                                             @Nonnull Class<?> partyType) {
        return forTypesForTests(managerType, chunkType, partyType);
    }

    public boolean isAvailable() {
        return lookup.isAvailable();
    }

    @Nullable
    public String getUnavailableReason() {
        return lookup.unavailableReason();
    }

    public boolean isDamagePolicyAvailable() {
        return damageAccess.isAvailable();
    }

    @Nullable
    public String getDamagePolicyUnavailableReason() {
        return damageAccess.unavailableReason();
    }

    @Nonnull
    public String providerId() {
        return PROVIDER_ID;
    }

    @Nonnull
    public LookupResult lookupClaim(@Nullable String worldName, double blockX, double blockZ) {
        return mapLookup(lookup.lookup(worldName, blockX, blockZ));
    }

    @Nonnull
    public LookupResult lookupClaim(@Nullable String worldName, @Nullable Vector3d position) {
        if (position == null) {
            return new LookupResult(LookupStatus.ERROR, null, "Position is missing.");
        }
        return lookupClaim(worldName, position.x, position.z);
    }

    @Nonnull
    public LookupResult lookupClaimIdentity(@Nullable String worldName, double blockX, double blockZ) {
        return lookupClaim(worldName, blockX, blockZ);
    }

    @Nonnull
    public LookupResult lookupClaimIdentity(@Nullable String worldName, @Nullable Vector3d position) {
        return lookupClaim(worldName, position);
    }

    @Nonnull
    public LookupResult lookupSimpleClaimsClaim(@Nullable String worldName, @Nullable Vector3d position) {
        return lookupClaimIdentity(worldName, position);
    }

    @Nonnull
    public LookupResult lookupSimpleClaimsClaim(@Nullable String worldName, double blockX, double blockZ) {
        return lookupClaim(worldName, blockX, blockZ);
    }

    @Nonnull
    public DamageAccessResult evaluateDamageAccess(@Nullable String worldName,
                                                   @Nullable Vector3d position,
                                                   @Nullable UUID attackerPlayerUuid,
                                                   @Nullable String ignoredPermissionKey) {
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
    private static LookupResult mapLookup(@Nonnull SimpleClaimsClaimLookup.Result result) {
        return switch (result.status()) {
            case CLAIM_FOUND -> new LookupResult(
                    LookupStatus.CLAIM_FOUND,
                    new ClaimInfo(result.partyId(), result.claimChunkCount()),
                    result.message()
            );
            case NO_CLAIM -> new LookupResult(LookupStatus.NO_CLAIM, null, result.message());
            case UNAVAILABLE -> new LookupResult(LookupStatus.UNAVAILABLE, null, result.message());
            case ERROR -> new LookupResult(LookupStatus.ERROR, null, result.message());
        };
    }
}
