package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.api.ProvisionedCompanionTransition;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.CompanionSpawnAdmissionRequest;
import com.alechilles.alecstamework.ownership.CompanionSpawnPopulationAdmissionService;
import com.alechilles.alecstamework.provisioning.ProvisioningPopulationBackend;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HytaleProvisionedCompanionProjectionPortTest {
    private static final UUID OWNER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OPERATION = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID DEAD_NPC = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Test
    void activeProjectionUsesCanonicalNullNpcRestoreAndStablePlannedIdentity() {
        PopulationAdmissionLocation destination = new PopulationAdmissionLocation("default", 3, -2);
        CompanionSpawnAdmissionRequest first = HytaleProvisionedCompanionProjectionPort.spawnRequest(
                "profile", null, CompanionLifecycleState.PROVISIONED_DORMANT,
                OWNER, destination, OPERATION);
        CompanionSpawnAdmissionRequest retry = HytaleProvisionedCompanionProjectionPort.spawnRequest(
                "profile", null, CompanionLifecycleState.PROVISIONED_DORMANT,
                OWNER, destination, OPERATION);

        assertTrue(first.canonicalNullNpcRestore());
        assertEquals(CompanionLifecycleState.PROVISIONED_DORMANT,
                first.requiredSourceLifecycle());
        assertEquals(CompanionSpawnPopulationAdmissionService.plannedNpcUuid(first),
                CompanionSpawnPopulationAdmissionService.plannedNpcUuid(retry));
    }

    @Test
    void dormantReviveIsAZeroProjectionLifecycleAdmission() {
        ProvisioningPopulationBackend.TransitionRequest transition =
                new ProvisioningPopulationBackend.TransitionRequest(
                        OPERATION, "hydragon", "revive", OWNER, "profile", 7L,
                        ProvisionedCompanionTransition.REVIVE_DORMANT, "default", null);

        var admission = HytaleProvisionedCompanionProjectionPort
                .dormantReviveAdmissionRequest(transition, DEAD_NPC);

        assertEquals(PopulationAdmissionOperation.LIFECYCLE_CHANGE, admission.operation());
        assertEquals(PopulationCompanionLifecycle.PROVISIONED_DORMANT,
                admission.targetLifecycle());
        assertEquals(PopulationAdmissionForcePolicy.ENFORCE, admission.forcePolicy());
        assertEquals(DEAD_NPC, admission.currentNpcUuid());
        assertNull(admission.destination());
        assertEquals(OWNER, admission.oldOwnerUuid());
        assertEquals(OWNER, admission.newOwnerUuid());
    }
}
