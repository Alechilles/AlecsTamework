package com.alechilles.alecstamework.api;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkApiV09ContractTest {
    @Test
    void legacyImplementationsReceiveFailClosedRootFacades() {
        TameworkApi legacy = new LegacyApiImplementation();

        assertEquals(
                CompanionProvisioningResult.Status.UNAVAILABLE,
                legacy.companionProvisioning().provision(request()).toCompletableFuture().join().status()
        );
    }

    @Test
    void provisionedTransitionSuccessRequiresDurableOriginAndIdentity() {
        CompanionProvisioningResult transitioned = new CompanionProvisioningResult(
                CompanionProvisioningResult.Status.TRANSITIONED,
                "activated",
                "Alechilles:HyDragon",
                "soul-bond:player",
                UUID.randomUUID(),
                "profile-1",
                UUID.randomUUID(),
                "Tamed_Wyvern_Mini",
                PopulationCompanionLifecycle.ACTIVE,
                CompanionProvisioningProjectionStatus.ACTIVE,
                "active",
                null,
                3L
        );

        assertTrue(transitioned.accepted());
        assertThrows(IllegalArgumentException.class, () -> new CompanionProvisioningResult(
                CompanionProvisioningResult.Status.TRANSITIONED,
                "activated",
                null,
                null,
                UUID.randomUUID(),
                "profile-1",
                UUID.randomUUID(),
                "Tamed_Wyvern_Mini",
                PopulationCompanionLifecycle.ACTIVE,
                CompanionProvisioningProjectionStatus.ACTIVE,
                "active",
                null,
                3L
        ));
    }

    @Test
    void dormantProvisioningDoesNotOccupyAClaimAndCannotUseV1Admission() {
        assertFalse(PopulationCompanionLifecycle.PROVISIONED_DORMANT.occupiesPhysicalClaim());
        assertThrows(IllegalArgumentException.class, () -> new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(null, UUID.randomUUID().toString(), "provision-test"),
                null,
                PopulationAdmissionRequest.NEW_PROFILE_REVISION,
                null,
                UUID.randomUUID(),
                null,
                null,
                PopulationAdmissionOperation.PROVISION_DORMANT,
                1,
                PopulationAdmissionForcePolicy.ENFORCE,
                PopulationCompanionLifecycle.PROVISIONED_DORMANT
        ));
    }

    private static CompanionProvisioningRequest request() {
        return new CompanionProvisioningRequest(
                "Alechilles:HyDragon",
                "soul-bond:player",
                null,
                UUID.randomUUID(),
                "Tamed_Wyvern_Mini",
                CompanionProvisioningDisposition.PROVISIONED_DORMANT,
                "default",
                null,
                null,
                null,
                CompanionProvisioningRequest.CURRENT_POLICY_REVISION
        );
    }

    private static final class LegacyApiImplementation implements TameworkApi {
        @Override public String getApiVersion() { return "0.8.0"; }
        @Override public EnumSet<TameworkApiCapability> getCapabilities() { return EnumSet.noneOf(TameworkApiCapability.class); }
        @Override public NpcProfilesApi profiles() { return null; }
        @Override public CommandLinksApi commandLinks() { return null; }
        @Override public ProgressionApi progression() { return null; }
        @Override public PolicyApi policies() { return null; }
        @Override public InteractionExtensionApi interactionExtensions() { return null; }
        @Override public TraitEffectApi traitEffects() { return null; }
        @Override public ProfileDataApi profileData() { return null; }
        @Override public TameworkEventsApi events() { return null; }
        @Override public TameworkConfigReadApi configs() { return null; }
        @Override public DiagnosticsApi diagnostics() { return null; }
    }
}
