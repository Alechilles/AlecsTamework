package com.alechilles.alecstamework.integration.simpleclaims;

import com.alechilles.alecstamework.integration.claims.ClaimLookupResult;
import com.alechilles.alecstamework.integration.claims.ClaimModProvider;
import java.util.Objects;
import javax.annotation.Nonnull;
import org.joml.Vector3d;

/** Claim lookups through one live SimpleClaims generation's bridge. Claim keys are per party. */
public final class SimpleClaimsClaimModProvider implements ClaimModProvider {
    private final SimpleClaimsBreedingBridge bridge;

    public SimpleClaimsClaimModProvider(@Nonnull SimpleClaimsBreedingBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    @Nonnull
    @Override
    public ClaimLookupResult lookupClaim(@Nonnull String worldName, @Nonnull Vector3d position) {
        SimpleClaimsBreedingBridge.LookupResult lookup = bridge.lookupSimpleClaimsClaim(worldName, position);
        if (lookup == null) {
            return ClaimLookupResult.error("lookup-result-null");
        }
        return switch (lookup.status()) {
            case CLAIM_FOUND -> {
                SimpleClaimsBreedingBridge.ClaimInfo info = lookup.claimInfo();
                yield info == null || info.partyId() == null
                        ? ClaimLookupResult.error("claim-info-missing")
                        : ClaimLookupResult.found("simpleclaims:" + info.partyId(),
                                info.claimChunkCount());
            }
            case NO_CLAIM -> new ClaimLookupResult(ClaimLookupResult.Status.NO_CLAIM, null, 0, lookup.message());
            case UNAVAILABLE -> ClaimLookupResult.unavailable(lookup.message());
            case ERROR -> ClaimLookupResult.error(lookup.message());
        };
    }
}
