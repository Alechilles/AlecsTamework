package com.alechilles.alecstamework.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiV08BinaryCompatibilityTest {
    private static final String FIXTURE_CLASS =
            "com.alechilles.alecstamework.compat.v08.Pre09ApiBinaryFixture";

    @Test
    void frozenApiV08CallerAndImplementorsLinkAgainstApiV09() throws Exception {
        URL fixtureRoot = Objects.requireNonNull(
                getClass().getClassLoader().getResource("compat/v08/"),
                "API 0.8 binary fixture root"
        );
        try (URLClassLoader fixtureLoader = new URLClassLoader(
                new URL[] { fixtureRoot }, getClass().getClassLoader())) {
            runCompatibilityAssertions(Class.forName(FIXTURE_CLASS, true, fixtureLoader));
        }
    }

    private static void runCompatibilityAssertions(Class<?> fixture) throws Exception {
        TameworkApi legacyApi = (TameworkApi) fixture.getMethod("legacyApi").invoke(null);

        Method legacyCaller = fixture.getMethod("invokeLegacyCallSites", TameworkApi.class);
        assertEquals(Boolean.TRUE, legacyCaller.invoke(null, legacyApi));
        assertEquals("0.8.0", legacyApi.getApiVersion());

        // Added to TameworkApi in 0.9: old implementors must inherit fail-closed authorities.
        BondedVesselsApi bondedVessels = legacyApi.bondedVessels();
        assertInstanceOf(BondedVesselsApi.class, bondedVessels);
        assertEquals(
                BondedVesselReadinessView.Readiness.UNAVAILABLE,
                bondedVessels.readiness().readiness()
        );
        assertThrows(NullPointerException.class, () -> bondedVessels.prepareTransition(null));
        assertInstanceOf(CompanionProvisioningApi.class, legacyApi.companionProvisioning());

        // Added to existing API 0.8 interfaces: the methods themselves must remain defaults so a
        // frozen third-party implementor does not gain new abstract methods at link time.
        assertTrue(BondedVesselsApi.class.getMethod(
                "resolveHeldItemProjection", BondedVesselHeldItemProjectionRequest.class).isDefault());
        assertTrue(ProfileDataApi.class.getMethod(
                "getVersioned", String.class, String.class, String.class).isDefault());
        assertTrue(ProfileDataApi.class.getMethod(
                "compareAndSet", ProfileDataCompareAndSetRequest.class).isDefault());
        assertTrue(ProfileDataApi.class.getMethod(
                "findOperation", String.class, String.class).isDefault());

        // Added to PolicyApi in 0.9.
        assertInstanceOf(PopulationGroupApi.class, legacyApi.policies().populationGroups());

        // Added to InteractionExtensionApi in 0.9.
        InteractionExtensionApi extensions = legacyApi.interactionExtensions();
        assertEquals(Set.of(), extensions.listCaptureRequirementIds());
        UnsupportedOperationException unsupported = assertThrows(
                UnsupportedOperationException.class,
                () -> extensions.registerCaptureRequirement("fixture", (context, spec) -> null)
        );
        assertEquals("capture-policy-unavailable", unsupported.getMessage());

        // Added to PopulationAdmissionApi in 0.9. Null validates that the inherited default body,
        // rather than an InvocationHandler or a recompiled fixture, is actually executing.
        assertThrows(
                NullPointerException.class,
                () -> legacyApi.policies().populationAdmissions().tryAdmitV2(null)
        );

        // Added to TameworkConfigReadApi in 0.9.
        TameworkConfigReadApi configs = legacyApi.configs();
        assertEquals(Optional.empty(), configs.getSpawnerCaptureMechanicsById("fixture"));
        assertEquals(Optional.empty(), configs.resolveSpawnerCaptureMechanicsForItemId("fixture"));
        assertEquals(Optional.empty(), configs.getCapturePolicyById("fixture"));
        assertEquals(Optional.empty(), configs.resolveCapturePolicyForRole("fixture"));
        assertEquals(Optional.empty(), configs.getSpawnerVesselConfigById("fixture"));
        assertEquals(Optional.empty(), configs.resolveSpawnerVesselConfigForItemId("fixture"));
        assertEquals(Optional.empty(), configs.getPopulationGroupById("fixture"));
        assertEquals(List.of(), configs.resolvePopulationGroupsForRole("fixture"));
    }
}
