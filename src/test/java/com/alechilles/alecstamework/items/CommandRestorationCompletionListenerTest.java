package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.items.persistence.CompanionLifecycleAuthorResult;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import org.junit.jupiter.api.Test;

class CommandRestorationCompletionListenerTest {
    @Test
    void scopePolicyFailureExplainsPersistenceSetupConflict() {
        CompanionLifecycleAuthorResult result = new CompanionLifecycleAuthorResult(
                CompanionLifecycleAuthorResult.Kind.RESTORATION,
                CompanionLifecycleAuthorResult.Status.WORKFLOW_FAILED,
                null,
                OperationWorkflowResult.Status.PREPARE_FAILED,
                "restoration_workflow_not_published",
                new IllegalArgumentException(
                        "operation_scope_policy_mismatch:companion_restoration"
                )
        );

        assertEquals(
                "Companion revival is unavailable because its persistence setup is incompatible.",
                CommandRestorationCompletionListener.workflowFailureMessage(result)
        );
    }

    @Test
    void prepareFailureExplainsThatSavedStateCouldNotBeValidated() {
        CompanionLifecycleAuthorResult result = new CompanionLifecycleAuthorResult(
                CompanionLifecycleAuthorResult.Kind.RESTORATION,
                CompanionLifecycleAuthorResult.Status.WORKFLOW_FAILED,
                null,
                OperationWorkflowResult.Status.PREPARE_FAILED,
                "free_restoration_workflow_not_published",
                new IllegalStateException("population_domain_source_state_mismatch")
        );

        assertEquals(
                "Companion revival could not validate its saved state.",
                CommandRestorationCompletionListener.workflowFailureMessage(result)
        );
    }
}
