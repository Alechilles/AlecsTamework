package com.alechilles.alecstamework.api;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Public provisioning inputs must fail before journal or world work can see oversized identity. */
class CompanionProvisioningDtoValidationTest {
    private static final UUID ACTOR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    void provisioningRequestTrimsBoundedIdentityFields() {
        CompanionProvisioningRequest request = new CompanionProvisioningRequest(
                " hydragon ", " soul-bond ", null, ACTOR, " miniwyvern ",
                CompanionProvisioningDisposition.ACTIVE, " default ",
                new PopulationAdmissionLocation("default", 0, 0), " Spark ", null,
                CompanionProvisioningRequest.CURRENT_POLICY_REVISION);

        assertEquals("hydragon", request.callerNamespace());
        assertEquals("soul-bond", request.idempotencyKey());
        assertEquals("miniwyvern", request.roleId());
        assertEquals("default", request.ownershipWorldName());
        assertEquals("Spark", request.displayName());
    }

    @Test
    void provisioningRequestRejectsEveryOversizedTextBoundary() {
        assertThrows(IllegalArgumentException.class, () -> activeRequest(
                over(CompanionProvisioningRequest.MAX_CALLER_NAMESPACE_LENGTH), "key", "role",
                "world", "display", "world"));
        assertThrows(IllegalArgumentException.class, () -> activeRequest(
                "namespace", over(CompanionProvisioningRequest.MAX_IDEMPOTENCY_KEY_LENGTH), "role",
                "world", "display", "world"));
        assertThrows(IllegalArgumentException.class, () -> activeRequest(
                "namespace", "key", over(CompanionProvisioningRequest.MAX_ROLE_ID_LENGTH),
                "world", "display", "world"));
        assertThrows(IllegalArgumentException.class, () -> activeRequest(
                "namespace", "key", "role",
                over(CompanionProvisioningRequest.MAX_WORLD_NAME_LENGTH), "display", "world"));
        assertThrows(IllegalArgumentException.class, () -> activeRequest(
                "namespace", "key", "role", "world",
                over(CompanionProvisioningRequest.MAX_DISPLAY_NAME_LENGTH), "world"));
        assertThrows(IllegalArgumentException.class, () -> activeRequest(
                "namespace", "key", "role", "world", "display",
                over(CompanionProvisioningRequest.MAX_WORLD_NAME_LENGTH)));
    }

    @Test
    void provisioningRequestRejectsBlankRequiredIdentityFields() {
        assertThrows(IllegalArgumentException.class, () -> activeRequest(
                " ", "key", "role", "world", "display", "world"));
        assertThrows(IllegalArgumentException.class, () -> activeRequest(
                "namespace", " ", "role", "world", "display", "world"));
        assertThrows(IllegalArgumentException.class, () -> activeRequest(
                "namespace", "key", " ", "world", "display", "world"));
        assertThrows(IllegalArgumentException.class, () -> activeRequest(
                "namespace", "key", "role", " ", "display", "world"));
        assertThrows(IllegalArgumentException.class, () -> activeRequest(
                "namespace", "key", "role", "world", "display", " "));
    }

    @Test
    void provisioningRequestRejectsInvalidRevisionPositionAndDispositionShape() {
        assertThrows(IllegalArgumentException.class, () -> provisioning(
                CompanionProvisioningDisposition.ACTIVE,
                new PopulationAdmissionLocation("world", 0, 0), null, -2L));
        assertThrows(IllegalArgumentException.class, () -> provisioning(
                CompanionProvisioningDisposition.ACTIVE,
                new PopulationAdmissionLocation("world", 0, 0),
                new Vector3View(Double.NaN, 0.0D, 0.0D), 0L));
        assertThrows(IllegalArgumentException.class, () -> provisioning(
                CompanionProvisioningDisposition.ACTIVE,
                new PopulationAdmissionLocation("world", 0, 0),
                new Vector3View(0.0D, Double.POSITIVE_INFINITY, 0.0D), 0L));
        assertThrows(IllegalArgumentException.class, () -> provisioning(
                CompanionProvisioningDisposition.ACTIVE,
                new PopulationAdmissionLocation("world", 0, 0),
                new Vector3View(0.0D, 0.0D, Double.NEGATIVE_INFINITY), 0L));
        assertThrows(IllegalArgumentException.class, () -> provisioning(
                CompanionProvisioningDisposition.ACTIVE, null, null, 0L));
        assertThrows(IllegalArgumentException.class, () -> provisioning(
                CompanionProvisioningDisposition.PROVISIONED_DORMANT,
                new PopulationAdmissionLocation("world", 0, 0), null, 0L));
    }

    @Test
    void transitionRequestRejectsEveryOversizedTextBoundary() {
        assertThrows(IllegalArgumentException.class, () -> transition(
                over(ProvisionedCompanionTransitionRequest.MAX_CALLER_NAMESPACE_LENGTH),
                "key", "profile", "world", "world"));
        assertThrows(IllegalArgumentException.class, () -> transition(
                "namespace", over(ProvisionedCompanionTransitionRequest.MAX_IDEMPOTENCY_KEY_LENGTH),
                "profile", "world", "world"));
        assertThrows(IllegalArgumentException.class, () -> transition(
                "namespace", "key",
                over(ProvisionedCompanionTransitionRequest.MAX_PROFILE_ID_LENGTH),
                "world", "world"));
        assertThrows(IllegalArgumentException.class, () -> transition(
                "namespace", "key", "profile",
                over(ProvisionedCompanionTransitionRequest.MAX_WORLD_NAME_LENGTH), "world"));
        assertThrows(IllegalArgumentException.class, () -> transition(
                "namespace", "key", "profile", "world",
                over(ProvisionedCompanionTransitionRequest.MAX_WORLD_NAME_LENGTH)));
    }

    @Test
    void transitionRequestRejectsBlankIdentityInvalidRevisionAndDestinationShape() {
        assertThrows(IllegalArgumentException.class, () -> transition(
                " ", "key", "profile", "world", "world"));
        assertThrows(IllegalArgumentException.class, () -> transition(
                "namespace", " ", "profile", "world", "world"));
        assertThrows(IllegalArgumentException.class, () -> transition(
                "namespace", "key", " ", "world", "world"));
        assertThrows(IllegalArgumentException.class, () -> transition(
                "namespace", "key", "profile", " ", "world"));
        assertThrows(IllegalArgumentException.class, () -> transition(
                "namespace", "key", "profile", "world", " "));
        assertThrows(IllegalArgumentException.class, () -> transition(
                ProvisionedCompanionTransition.ACTIVATE,
                -1L, new PopulationAdmissionLocation("world", 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> transition(
                ProvisionedCompanionTransition.ACTIVATE, 0L, null));
        assertThrows(IllegalArgumentException.class, () -> transition(
                ProvisionedCompanionTransition.REVIVE_ACTIVE, 0L, null));
        assertThrows(IllegalArgumentException.class, () -> transition(
                ProvisionedCompanionTransition.REVIVE_DORMANT,
                0L, new PopulationAdmissionLocation("world", 0, 0)));
    }

    private static CompanionProvisioningRequest activeRequest(
            String namespace, String key, String role, String ownershipWorld,
            String displayName, String destinationWorld) {
        return new CompanionProvisioningRequest(namespace, key, null, ACTOR, role,
                CompanionProvisioningDisposition.ACTIVE, ownershipWorld,
                new PopulationAdmissionLocation(destinationWorld, 0, 0), displayName, null,
                CompanionProvisioningRequest.CURRENT_POLICY_REVISION);
    }

    private static CompanionProvisioningRequest provisioning(
            CompanionProvisioningDisposition disposition,
            PopulationAdmissionLocation destination,
            Vector3View homePosition,
            long expectedPolicyRevision) {
        return new CompanionProvisioningRequest(
                "namespace", "key", null, ACTOR, "role", disposition, "world",
                destination, null, homePosition, expectedPolicyRevision);
    }

    private static ProvisionedCompanionTransitionRequest transition(
            String namespace, String key, String profileId, String ownershipWorld,
            String destinationWorld) {
        return new ProvisionedCompanionTransitionRequest(
                namespace, key, ACTOR, profileId, 1L, ProvisionedCompanionTransition.ACTIVATE,
                ownershipWorld, new PopulationAdmissionLocation(destinationWorld, 0, 0));
    }

    private static ProvisionedCompanionTransitionRequest transition(
            ProvisionedCompanionTransition transition,
            long expectedProfileRevision,
            PopulationAdmissionLocation destination) {
        return new ProvisionedCompanionTransitionRequest(
                "namespace", "key", ACTOR, "profile", expectedProfileRevision, transition,
                "world", destination);
    }

    private static String over(int maximum) {
        return "x".repeat(maximum + 1);
    }
}
