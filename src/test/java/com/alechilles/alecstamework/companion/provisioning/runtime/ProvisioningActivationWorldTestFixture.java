package com.alechilles.alecstamework.companion.provisioning.runtime;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationDefinition;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationRequest;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningOrigin;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import java.util.List;

/** Deterministic activation request and live-operation envelope fixtures. */
final class ProvisioningActivationWorldTestFixture {
    static final long TARGET_CHUNK = 91L;
    private static final long NOW = -4_000;
    private static final ProvisioningOrigin ORIGIN =
            new ProvisioningOrigin("test:activation-world", "mini-wyvern");
    private static final OwnerId OWNER = OwnerId.parse(
            "20000000-0000-0000-0000-000000000291"
    );
    private static final NpcAlias ALIAS = NpcAlias.parse(
            "30000000-0000-0000-0000-000000000291"
    );
    private static final OperationId OPERATION = OperationId.parse(
            "60000000-0000-0000-0000-000000000291"
    );

    private ProvisioningActivationWorldTestFixture() {
    }

    static ProvisioningActivationRequest request() {
        return request("spawn-receipt");
    }

    static ProvisioningActivationRequest request(String receiptKey) {
        CompanionLifecycle before = new CompanionLifecycle(
                ORIGIN.profileId(),
                OWNER,
                LifecycleState.PROVISIONED_DORMANT,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.PROVISIONING,
                        ORIGIN.stableKey()
                ),
                new LifecycleRevision(5),
                null,
                -5_000,
                ReconciliationGeneration.INITIAL,
                null,
                "world-target"
        );
        CompanionLifecycle after = new CompanionLifecycle(
                ORIGIN.profileId(),
                OWNER,
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(
                        ALIAS.toString(), "world-target"
                ),
                new LifecycleRevision(6),
                null,
                NOW,
                ReconciliationGeneration.INITIAL,
                null,
                "world-target"
        );
        return new ProvisioningActivationRequest(
                ORIGIN,
                new PopulationGroupTransitionAdmissionRequest(
                        before, after, 1, 0, List.of(), NOW
                ),
                ALIAS,
                new CompanionSpawnPlacement(
                        "world-target",
                        -12.5,
                        -63.05,
                        -4.5,
                        -0.25f,
                        -1.5f,
                        -0.5f
                ),
                receiptKey,
                null,
                NOW
        );
    }

    static OperationEnvelope operation(
            ProvisioningActivationRequest request
    ) {
        return new OperationEnvelope(
                OPERATION,
                request.origin().activationKey(request.spawnReceiptKey()),
                ProvisioningActivationDefinition.KIND,
                ProvisioningActivationDefinition.INSTANCE.payloadVersion(),
                ProvisioningActivationDefinition.INSTANCE.encode(request),
                OperationPhase.LIVE_APPLYING,
                "provisioning",
                request.groupAdmission().before().revision(),
                null,
                0,
                1,
                null,
                null,
                NOW,
                NOW,
                null,
                null,
                null,
                List.of(
                        OperationScope.operation(OPERATION),
                        OperationScope.profile(request.origin().profileId()),
                        OperationScope.owner(OWNER)
                )
        );
    }
}
