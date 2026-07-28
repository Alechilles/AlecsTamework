package com.alechilles.alecstamework.persistence.operation;

import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Adapter-neutral admission and completion handle for one public operation. */
public record PublicOperationSubmission(
        @Nonnull Admission admission,
        @Nonnull CompletionStage<OperationWorkflowResult> completion
) {
    public PublicOperationSubmission {
        if (admission == null || completion == null) {
            throw new IllegalArgumentException(
                    "Complete public operation submission is required"
            );
        }
    }

    public boolean accepted() {
        return admission == Admission.ACCEPTED;
    }

    public enum Admission {
        ACCEPTED,
        CANCELLED_BEFORE_ACCEPTANCE,
        REJECTED
    }
}
