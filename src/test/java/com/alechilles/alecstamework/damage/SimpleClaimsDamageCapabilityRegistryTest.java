package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.integration.simpleclaims.SimpleClaimsBreedingBridge;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleClaimsDamageCapabilityRegistryTest {
    /** Regression: repeated policy resolution must not reflect the same live plugin generation. */
    @Test
    void sharedRuntimeReflectsOnlyOncePerReadyPluginGeneration() {
        FakeLocator locator = new FakeLocator(unavailable(SimpleClaimsPluginState.ABSENT, "not installed"));
        AtomicInteger reflections = new AtomicInteger();
        SimpleClaimsDamageCapabilityRegistry registry = new SimpleClaimsDamageCapabilityRegistry(
                locator,
                plugin -> {
                    reflections.incrementAndGet();
                    return generation(SimpleClaimsBreedingBridge.DamageAccessStatus.ALLOWED, true);
                }
        );
        SimpleClaimsCapabilityRuntime runtime = new SimpleClaimsCapabilityRuntime(registry);

        assertFalse(runtime.resolveBridge().available());
        assertEquals(0, reflections.get(), "An absent optional plugin must not trigger reflection.");

        locator.location.set(ready("plugin-a", "1.0.38"));
        SimpleClaimsCapabilityRuntime.BridgeResolution first = runtime.resolveBridge();
        SimpleClaimsCapabilityRuntime.BridgeResolution repeated = runtime.resolveBridge();
        assertTrue(first.available());
        assertSame(first.bridge(), repeated.bridge());
        assertEquals(1, reflections.get());

        locator.location.set(unavailable(SimpleClaimsPluginState.DISABLED, "stopped"));
        assertFalse(runtime.resolveBridge().available());

        locator.location.set(ready("plugin-a", "1.0.38"));
        SimpleClaimsCapabilityRuntime.BridgeResolution restarted = runtime.resolveBridge();
        assertTrue(restarted.available());
        assertNotSame(first.bridge(), restarted.bridge());
        assertEquals(2, reflections.get());
    }

    @Test
    void absentReadyStopAndReplacementNeverUseStickyNegativeOrStalePositiveState() {
        FakeLocator locator = new FakeLocator(unavailable(SimpleClaimsPluginState.ABSENT, "not installed"));
        AtomicInteger reflections = new AtomicInteger();
        SimpleClaimsDamageCapabilityRegistry registry = new SimpleClaimsDamageCapabilityRegistry(
                locator,
                plugin -> {
                    reflections.incrementAndGet();
                    return generation(SimpleClaimsBreedingBridge.DamageAccessStatus.ALLOWED, true);
                }
        );

        assertEquals(SimpleClaimsPluginState.ABSENT, registry.resolve().state());
        assertEquals(0, reflections.get());

        locator.location.set(ready("plugin-a", "1.0.38"));
        SimpleClaimsDamageCapabilityResolver.Resolution first = registry.resolve();
        SimpleClaimsDamageCapabilityResolver.Resolution repeated = registry.resolve();
        assertEquals(SimpleClaimsPluginState.READY, first.state());
        assertSame(first, repeated);
        assertEquals(1, reflections.get());

        locator.location.set(unavailable(SimpleClaimsPluginState.DISABLED, "stopped"));
        assertEquals(SimpleClaimsPluginState.DISABLED, registry.resolve().state());

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

        assertEquals(SimpleClaimsPluginState.READY, resolution.state());
        assertEquals(true, resolution.capability().nativeDamageAvailable());
        assertEquals(false, resolution.capability().claimIdentityAvailable());
    }

    @Test
    void damageRangeAllowsBuildMetadataButRejectsUnverifiedPrereleases() {
        AtomicInteger reflections = new AtomicInteger();
        SimpleClaimsDamageCapabilityRegistry buildRegistry = new SimpleClaimsDamageCapabilityRegistry(
                new FakeLocator(ready("plugin-build", "1.0.38+vendor.7")),
                plugin -> {
                    reflections.incrementAndGet();
                    return generation(SimpleClaimsBreedingBridge.DamageAccessStatus.ALLOWED, true);
                }
        );

        assertEquals(SimpleClaimsPluginState.READY, buildRegistry.resolve().state());
        assertEquals(1, reflections.get());
        for (String version : new String[]{
                "1.0.37+vendor.7",
                "1.0.38-rc.1",
                "1.0.99-beta+vendor.7",
                "1.1.0+vendor.7"
        }) {
            SimpleClaimsDamageCapabilityRegistry prereleaseRegistry =
                    new SimpleClaimsDamageCapabilityRegistry(
                            new FakeLocator(ready("plugin-prerelease", version)),
                            plugin -> {
                                reflections.incrementAndGet();
                                return generation(
                                        SimpleClaimsBreedingBridge.DamageAccessStatus.ALLOWED,
                                        true
                                );
                            }
                    );
            assertEquals(
                    SimpleClaimsPluginState.INCOMPATIBLE,
                    prereleaseRegistry.resolve().state(),
                    () -> "Unsupported damage-contract release should be rejected: " + version
            );
        }
        assertEquals(1, reflections.get(), "Rejected versions must not reach reflection.");
    }

    @Test
    void closeIsIdempotentAndPreventsFurtherPluginLocationAccess() {
        FakeLocator locator = new FakeLocator(ready("plugin-a", "1.0.38"));
        SimpleClaimsDamageCapabilityRegistry registry = new SimpleClaimsDamageCapabilityRegistry(
                locator,
                plugin -> generation(SimpleClaimsBreedingBridge.DamageAccessStatus.ALLOWED, true)
        );

        assertEquals(SimpleClaimsPluginState.READY, registry.resolve().state());
        assertEquals(1, locator.locateCalls.get());

        registry.close();
        registry.close();

        assertEquals(SimpleClaimsPluginState.ERROR, registry.resolve().state());
        assertEquals(1, locator.locateCalls.get());
        assertEquals(1, locator.closeCalls.get());
    }

    private static SimpleClaimsDamageGeneration generation(
            SimpleClaimsBreedingBridge.DamageAccessStatus status,
            boolean identityAvailable) {
        UUID partyId = UUID.randomUUID();
        return new SimpleClaimsDamageGeneration(
                SimpleClaimsBreedingBridge.initialize(),
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

    private static SimpleClaimsPluginLocation ready(String token, String version) {
        return new SimpleClaimsPluginLocation(
                SimpleClaimsPluginState.READY,
                version,
                null,
                new SimpleClaimsPluginGeneration(token, token + "-loader", 0L),
                new Object()
        );
    }

    private static SimpleClaimsPluginLocation unavailable(SimpleClaimsPluginState state, String reason) {
        return new SimpleClaimsPluginLocation(
                state,
                null,
                reason,
                SimpleClaimsPluginGeneration.NONE,
                null
        );
    }

    private static final class FakeLocator implements SimpleClaimsPluginLocator {
        private final AtomicReference<SimpleClaimsPluginLocation> location;
        private final AtomicInteger locateCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();

        private FakeLocator(SimpleClaimsPluginLocation location) {
            this.location = new AtomicReference<>(location);
        }

        @Override
        public SimpleClaimsPluginLocation locate() {
            locateCalls.incrementAndGet();
            return location.get();
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }
}
