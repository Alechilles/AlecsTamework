package com.alechilles.alecstamework.companion.provisioning.runtime;

import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.runtime.HytaleWorldOperationDispatcher;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** World lookup failure never reaches provisioning ECS work. */
class HytaleProvisioningActivationBoundaryTest {
    @Test
    void unavailableCurrentWorldIsRetryableWithoutEcsAccess()
            throws Exception {
        HytaleProvisioningActivationBoundary boundary =
                new HytaleProvisioningActivationBoundary(
                        (world, store, request, operation) -> {
                            throw new AssertionError(
                                    "Unavailable world cannot reach ECS"
                            );
                        },
                        new HytaleWorldOperationDispatcher(
                                ignored -> null
                        )
                );

        var request = ProvisioningActivationWorldTestFixture.request();
        LiveOperationResult result = boundary.applyOrResolve(
                request,
                ProvisioningActivationWorldTestFixture.operation(request)
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(
                LiveOperationResult.Status.RETRYABLE,
                result.status()
        );
        assertEquals(
                "provisioning_activation_world_unavailable",
                result.code()
        );
    }
}
