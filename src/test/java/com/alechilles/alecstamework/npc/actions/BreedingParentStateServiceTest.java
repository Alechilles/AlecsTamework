package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.ownership.CompanionIdentityResolver;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Regression coverage for canonical breeding-parent identity across projection UUID changes. */
class BreedingParentStateServiceTest {

    @Test
    void canonicalAliasWinsForAnOrdinaryRemappedProjection() {
        UUID historical = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        CompanionIdentityResolver resolver = new CompanionIdentityResolver();
        resolver.remap("profile-canonical", historical, current);
        BreedingParentStateService service = new BreedingParentStateService(() -> resolver);

        assertEquals("profile-canonical", service.resolveProfileId(current, null));
        assertEquals("profile-canonical", service.resolveProfileId(historical, null));
    }

    @Test
    void matchingProjectionMarkerAndAliasRemainCanonical() {
        UUID current = UUID.randomUUID();
        CompanionIdentityResolver resolver = new CompanionIdentityResolver();
        resolver.remap("profile-canonical", null, current);
        BreedingParentStateService service = new BreedingParentStateService(() -> resolver);

        assertEquals(
                "profile-canonical",
                service.resolveProfileId(current, " profile-canonical ")
        );
    }

    @Test
    void projectionMarkerAliasConflictFailsClosed() {
        UUID current = UUID.randomUUID();
        CompanionIdentityResolver resolver = new CompanionIdentityResolver();
        resolver.remap("profile-canonical", null, current);
        BreedingParentStateService service = new BreedingParentStateService(() -> resolver);

        assertThrows(
                IllegalStateException.class,
                () -> service.resolveProfileId(current, "profile-conflict")
        );
    }

    @Test
    void unresolvedLegacyParentUsesEntityFallback() {
        UUID current = UUID.randomUUID();
        BreedingParentStateService service = new BreedingParentStateService(() -> null);

        assertEquals("entity:" + current, service.resolveProfileId(current, null));
    }
}
