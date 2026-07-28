package com.alechilles.alecstamework.companion.provisioning.runtime;

import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationRequest;
import com.alechilles.alecstamework.persistence.runtime.HytaleAsyncWorldOperationGateway;

/** Current-world asynchronous durability gateway for initial provisioning. */
@FunctionalInterface
public interface ProvisioningActivationWorldGateway
        extends HytaleAsyncWorldOperationGateway<
        ProvisioningActivationRequest> {
}
