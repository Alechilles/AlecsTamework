package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationRequest;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;

/** Contains unknown provisioning activation outcomes. */
final class SqliteProvisioningActivationContainment {
    private SqliteProvisioningActivationContainment() {
    }

    static CompletionStage<OperationWorkflowResult> contain(
            SqliteOperationEngine operations,
            LongSupplier clock,
            OperationWorkflowResult result,
            ProvisioningActivationRequest request
    ) {
        if (result.status() != OperationWorkflowResult.Status.LIVE_UNKNOWN
                || result.operation() == null) {
            return CompletableFuture.completedFuture(result);
        }
        OperationEnvelope operation = result.operation();
        return operations.containUnknown(
                operation,
                operation.failureCode() == null
                        ? "provisioning_activation_live_outcome_unknown"
                        : operation.failureCode(),
                "Provisioning activation could not prove the exact spawn receipt",
                containmentScopes(operation, request),
                clock.getAsLong()
        ).completion().thenApply(containment ->
                containment instanceof PersistenceTransactionResult.Committed<?>
                        ? result
                        : new OperationWorkflowResult(
                                OperationWorkflowResult.Status.LIVE_UNKNOWN,
                                operation,
                                List.of(),
                                new IllegalStateException(
                                        "provisioning_activation_unknown_"
                                                + "containment_failed",
                                        result.failure()
                                )
                        ));
    }

    static List<OperationScope> participants(
            ProvisioningActivationRequest request
    ) {
        TreeSet<OperationScope> scopes = new TreeSet<>();
        scopes.add(OperationScope.profile(request.origin().profileId()));
        scopes.add(OperationScope.owner(
                request.groupAdmission().before().ownerId()
        ));
        if (request.timedActivation() != null) {
            scopes.add(OperationScope.commandFamily(
                    request.timedActivation().familyKey()
            ));
        }
        return List.copyOf(scopes);
    }

    private static List<OperationScope> containmentScopes(
            OperationEnvelope operation,
            ProvisioningActivationRequest request
    ) {
        ArrayList<OperationScope> scopes = new ArrayList<>();
        scopes.add(OperationScope.operation(operation.operationId()));
        scopes.addAll(participants(request));
        return List.copyOf(scopes);
    }
}
