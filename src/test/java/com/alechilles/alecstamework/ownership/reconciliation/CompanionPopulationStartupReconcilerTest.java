package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.ownership.CompanionPopulationBootstrapService;
import com.alechilles.alecstamework.ownership.OwnerPopulationReadiness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionPopulationStartupReconcilerTest {
    @Test
    void finalStatusRequiresEveryReconciliationAndIndexLayerToBeReady() {
        assertEquals(
                CompanionPopulationReconciliationProgress.Status.READY,
                status(
                        CompanionPopulationReconciliationService.Status.READY,
                        OwnerPopulationReadiness.READY,
                        OwnerPopulationReadiness.READY
                )
        );
        assertEquals(
                CompanionPopulationReconciliationProgress.Status.RECONCILING,
                status(
                        CompanionPopulationReconciliationService.Status.RECONCILING,
                        OwnerPopulationReadiness.READY,
                        OwnerPopulationReadiness.READY
                )
        );
        assertEquals(
                CompanionPopulationReconciliationProgress.Status.RECONCILING,
                status(
                        CompanionPopulationReconciliationService.Status.READY,
                        OwnerPopulationReadiness.RECONCILING,
                        OwnerPopulationReadiness.READY
                )
        );
        assertEquals(
                CompanionPopulationReconciliationProgress.Status.RECONCILING,
                status(
                        CompanionPopulationReconciliationService.Status.READY,
                        OwnerPopulationReadiness.READY,
                        OwnerPopulationReadiness.LOADING
                )
        );
    }

    @Test
    void finalStatusPropagatesAnyDegradedLayer() {
        assertEquals(
                CompanionPopulationReconciliationProgress.Status.DEGRADED,
                status(
                        CompanionPopulationReconciliationService.Status.DEGRADED,
                        OwnerPopulationReadiness.READY,
                        OwnerPopulationReadiness.READY
                )
        );
        assertEquals(
                CompanionPopulationReconciliationProgress.Status.DEGRADED,
                status(
                        CompanionPopulationReconciliationService.Status.READY,
                        OwnerPopulationReadiness.DEGRADED,
                        OwnerPopulationReadiness.READY
                )
        );
        assertEquals(
                CompanionPopulationReconciliationProgress.Status.DEGRADED,
                status(
                        CompanionPopulationReconciliationService.Status.READY,
                        OwnerPopulationReadiness.READY,
                        OwnerPopulationReadiness.DEGRADED
                )
        );
    }

    private static CompanionPopulationReconciliationProgress.Status status(
            CompanionPopulationReconciliationService.Status resultStatus,
            OwnerPopulationReadiness global,
            OwnerPopulationReadiness perWorld
    ) {
        CompanionPopulationReconciliationService.Result result =
                new CompanionPopulationReconciliationService.Result(resultStatus, "test", 0, 0, 0, 0);
        CompanionPopulationBootstrapService.BootstrapResult bootstrap =
                new CompanionPopulationBootstrapService.BootstrapResult(
                        global, perWorld, 0, 0, 0, "test"
                );
        return CompanionPopulationStartupReconciler.finalStatus(result, bootstrap);
    }
}
