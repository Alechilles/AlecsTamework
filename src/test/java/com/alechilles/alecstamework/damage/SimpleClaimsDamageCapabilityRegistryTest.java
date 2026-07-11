package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.integration.claims.ClaimPluginLocation;
import com.alechilles.alecstamework.integration.claims.ClaimPluginLocator;
import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.integration.claims.ClaimProviderState;
import com.alechilles.alecstamework.integration.simpleclaims.SimpleClaimsBreedingBridge;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class SimpleClaimsDamageCapabilityRegistryTest {
    @Test
    void absentReadyStopAndReplacementNeverUseStickyNegativeOrStalePositiveState() {
        FakeLocator locator = new FakeLocator(unavailable(ClaimProviderState.ABSENT, "not installed"));
        AtomicInteger reflections = new AtomicInteger();
        SimpleClaimsDamageCapabilityRegistry registry = new SimpleClaimsDamageCapabilityRegistry(
                locator,
                plugin -> {
                    reflections.incrementAndGet();
                    return generation(SimpleClaimsBreedingBridge.DamageAccessStatus.ALLOWED, true);
                }
        );

        assertEquals(ClaimProviderState.ABSENT, registry.resolve().state());
        assertEquals(0, reflections.get());

        locator.location.set(ready("plugin-a", "1.0.38"));
        SimpleClaimsDamageCapabilityResolver.Resolution first = registry.resolve();
        SimpleClaimsDamageCapabilityResolver.Resolution repeated = registry.resolve();
        assertEquals(ClaimProviderState.READY, first.state());
        assertSame(first, repeated);
        assertEquals(1, reflections.get());

        locator.location.set(unavailable(ClaimProviderState.DISABLED, "stopped"));
        assertEquals(ClaimProviderState.DISABLED, registry.resolve().state());

        locator.location.set(ready("plugin-a", "1.0.38"));
        SimpleClaimsDamageCapabilityResolver.Resolution restarted = registry.resolve();
        assertNotSame(first, restarted, "A stopped generation must be reflected again even with the same token.");
        assertEquals(2L, restarted.generation().reflectedContractGeneration());

        locator.location.set(ready("plugin-b", "1.0.39"));
        SimpleClaimsDamageCapabilityResolver.Resolution replacement = registry.resolve();
        assertNotSame(restarted, replacement);
        assertEquals(3L, replacement.generation().reflectedContractGeneration());
        assertEquals(3, reflections.get());
    }

    @Test
    void nativeDamageCapabilityIsReadyWithoutClaimIdentityOrPopulationTopology() {
        SimpleClaimsDamageCapabilityRegistry registry = new SimpleClaimsDamageCapabilityRegistry(
                new FakeLocator(ready("plugin-a", "1.0.38")),
                plugin -> generation(SimpleClaimsBreedingBridge.DamageAccessStatus.DENIED, false)
        );

        SimpleClaimsDamageCapabilityResolver.Resolution resolution = registry.resolve();

        assertEquals(ClaimProviderState.READY, resolution.state());
        assertEquals(true, resolution.capability().nativeDamageAvailable());
        assertEquals(false, resolution.capability().claimIdentityAvailable());
    }

    private static SimpleClaimsDamageGeneration generation(
            SimpleClaimsBreedingBridge.DamageAccessStatus status,
            boolean identityAvailable) {
        UUID partyId = UUID.randomUUID();
        return new SimpleClaimsDamageGeneration(
                (world, position, attacker, permission) ->
                        new SimpleClaimsBreedingBridge.DamageAccessResult(status, partyId, null),
                (world, position, attacker, permission) ->
                        LegacySimpleClaimsPartyPermissionBypass.Result.notGranted(),
                (world, position) -> new SimpleClaimsClaimIdentityAccess.Result(
                        identityAvailable
                                ? SimpleClaimsClaimIdentityAccess.Status.CLAIM_FOUND
                                : SimpleClaimsClaimIdentityAccess.Status.UNAVAILABLE,
                        identityAvailable ? partyId : null,
                        identityAvailable ? null : "identity unavailable"
                ),
                true,
                identityAvailable,
                identityAvailable ? null : "identity unavailable"
        );
    }

    private static ClaimPluginLocation ready(String token, String version) {
        return new ClaimPluginLocation(
                "simpleclaims-damage",
                ClaimProviderState.READY,
                version,
                null,
                new ClaimProviderGeneration(token, token + "-loader", 0L),
                new Object()
        );
    }

    private static ClaimPluginLocation unavailable(ClaimProviderState state, String reason) {
        return new ClaimPluginLocation(
                "simpleclaims-damage",
                state,
                null,
                reason,
                ClaimProviderGeneration.NONE,
                null
        );
    }

    private static final class FakeLocator implements ClaimPluginLocator {
        private final AtomicReference<ClaimPluginLocation> location;

        private FakeLocator(ClaimPluginLocation location) {
            this.location = new AtomicReference<>(location);
        }

        @Override
        public ClaimPluginLocation locate() {
            return location.get();
        }
    }
}
