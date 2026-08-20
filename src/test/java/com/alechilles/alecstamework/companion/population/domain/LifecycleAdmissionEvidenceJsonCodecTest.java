package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlan;
import com.alechilles.alecstamework.companion.population.OwnerPopulationScope;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Protects the durable admission evidence used by operation replay. */
class LifecycleAdmissionEvidenceJsonCodecTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "80000000-0000-0000-0000-000000000801"
    );
    private static final OwnerId OWNER = OwnerId.parse(
            "80000000-0000-0000-0000-000000000802"
    );

    @Test
    void managedPayloadAndOwnerCompositionRoundTripExactly() {
        PopulationDomainAdmissionOperation.Payload payload =
                new PopulationDomainAdmissionOperation.Payload(
                        UUID.fromString(
                                "80000000-0000-0000-0000-000000000803"
                        ),
                        PROFILE,
                        OWNER,
                        new LifecycleRevision(4),
                        "husbandry-world",
                        OWNER,
                        "husbandry-world",
                        LifecycleState.CAPTURED,
                        LifecycleState.ACTIVE,
                        "livestock",
                        "test-provider",
                        1,
                        "generation-7",
                        11,
                        13,
                        9999,
                        1,
                        List.of(new PopulationDomainAdmissionOperation.DomainInput(
                                "cattle",
                                PopulationDomainScope.PER_WORLD,
                                "husbandry-world",
                                1,
                                1,
                                2,
                                12,
                                12,
                                5
                        )),
                        List.of(),
                        100
                );
        OwnerPopulationAdmissionPlan ownerPlan =
                new OwnerPopulationAdmissionPlan(
                        PROFILE,
                        new LifecycleRevision(4),
                        List.of(
                                new OwnerPopulationAdmissionPlan.LimitIncrease(
                                        OwnerPopulationScope.global(OWNER),
                                        1,
                                        20
                                ),
                                new OwnerPopulationAdmissionPlan.LimitIncrease(
                                        OwnerPopulationScope.perWorld(
                                                OWNER,
                                                "husbandry-world"
                                        ),
                                        1,
                                        10
                                )
                        )
                );
        OperationId operationId = new OperationId(UUID.fromString(
                "80000000-0000-0000-0000-000000000804"
        ));
        PopulationDomainReservation source = new PopulationDomainReservation(
                new OperationId(UUID.fromString(
                        "80000000-0000-0000-0000-000000000805"
                )),
                PROFILE,
                new LifecycleRevision(4),
                new PopulationDomainBucket(
                        OWNER,
                        "cattle",
                        PopulationDomainScope.PER_WORLD,
                        "husbandry-world"
                ),
                1,
                0,
                2,
                12,
                12,
                11,
                13,
                5,
                100
        );
        PopulationDomainConvergencePlan plan =
                new PopulationDomainConvergencePlan(
                        PROFILE,
                        new LifecycleRevision(4),
                        OWNER,
                        "husbandry-world",
                        LifecycleState.CAPTURED,
                        OWNER,
                        "husbandry-world",
                        LifecycleState.ACTIVE,
                        List.of(new PopulationDomainConvergencePlan.SourceRow(
                                source, 1, 0
                        )),
                        payload.reservations(operationId)
                );
        LifecycleAdmissionEvidence evidence =
                LifecycleAdmissionEvidence.managed(
                        payload,
                        new PopulationAdmissionComposition(ownerPlan, null),
                        plan
                );

        assertEquals(
                evidence,
                LifecycleAdmissionEvidenceJsonCodec.decode(
                        LifecycleAdmissionEvidenceJsonCodec.encode(evidence)
                )
        );

        var oldJson = LifecycleAdmissionEvidenceJsonCodec.encode(evidence);
        oldJson.remove("convergencePlan");
        assertNull(
                LifecycleAdmissionEvidenceJsonCodec.decode(oldJson)
                        .convergencePlan()
        );
    }
}
