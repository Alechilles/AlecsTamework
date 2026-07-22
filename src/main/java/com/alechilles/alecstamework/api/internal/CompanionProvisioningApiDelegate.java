package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.CompanionProvisioningApi;
import com.alechilles.alecstamework.api.CompanionProvisioningOperationView;
import com.alechilles.alecstamework.api.CompanionProvisioningRequest;
import com.alechilles.alecstamework.api.CompanionProvisioningResult;
import com.alechilles.alecstamework.api.CompanionProvisioningLinkRequest;
import com.alechilles.alecstamework.api.CompanionProvisioningLinkResult;
import com.alechilles.alecstamework.api.ProvisionedCompanionTransitionRequest;
import com.alechilles.alecstamework.api.ProvisionedCompanionView;
import com.alechilles.alecstamework.provisioning.CompanionProvisioningCoordinator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Thin public facade over Tamework's internal journal-backed provisioning coordinator. */
public final class CompanionProvisioningApiDelegate implements CompanionProvisioningApi {
    private final CompanionProvisioningCoordinator coordinator;

    public CompanionProvisioningApiDelegate(@Nonnull CompanionProvisioningCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public Optional<ProvisionedCompanionView> getByProfileId(String profileId) {
        return coordinator.getByProfileId(profileId);
    }

    @Override
    public Optional<ProvisionedCompanionView> getByOrigin(String callerNamespace, String idempotencyKey) {
        return coordinator.getByOrigin(callerNamespace, idempotencyKey);
    }

    @Override
    public CompletionStage<CompanionProvisioningResult> provision(CompanionProvisioningRequest request) {
        return coordinator.provision(request);
    }

    @Override
    public CompletionStage<CompanionProvisioningLinkResult> provisionAndLink(
            CompanionProvisioningLinkRequest request) {
        return coordinator.provisionAndLink(request);
    }

    /** Runtime composition seam; not part of the downstream public API. */
    public void installInitialProjectionHook(
            CompanionProvisioningCoordinator.InitialProjectionHook hook) {
        coordinator.installInitialProjectionHook(hook);
    }

    @Override
    public CompletionStage<CompanionProvisioningResult> transition(
            ProvisionedCompanionTransitionRequest request) {
        return coordinator.transition(request);
    }

    @Override
    public CompletionStage<Optional<CompanionProvisioningOperationView>> findOperation(
            String callerNamespace, String idempotencyKey) {
        return coordinator.findOperation(callerNamespace, idempotencyKey);
    }
}
