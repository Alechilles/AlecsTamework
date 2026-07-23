package com.alechilles.alecstamework.companion.provisioning;

import com.alechilles.alecstamework.persistence.operation.LiveOperationBoundary;

/** Idempotent receipt resolver for initial provisioned entity insertion. */
@FunctionalInterface
public interface ProvisioningActivationLiveBoundary
        extends LiveOperationBoundary<ProvisioningActivationRequest> {
}
