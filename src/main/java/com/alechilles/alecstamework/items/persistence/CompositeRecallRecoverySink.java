package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.items.ImportedRecallRecoverySink;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Tries independent exact-evidence Recall recovery mechanisms in order. */
public final class CompositeRecallRecoverySink
        implements ImportedRecallRecoverySink {
    private final List<ImportedRecallRecoverySink> delegates;

    public CompositeRecallRecoverySink(
            @Nonnull List<ImportedRecallRecoverySink> delegates
    ) {
        this.delegates = List.copyOf(
                Objects.requireNonNull(delegates, "delegates")
        );
    }

    @Override
    public CompletionStage<RecoveryOutcome> recover(RecallFailure failure) {
        return recover(failure, 0);
    }

    private CompletionStage<RecoveryOutcome> recover(
            RecallFailure failure,
            int index
    ) {
        if (index >= delegates.size()) {
            return CompletableFuture.completedFuture(RecoveryOutcome.NONE);
        }
        return delegates.get(index).recover(failure).thenCompose(outcome ->
                outcome == RecoveryOutcome.NONE
                        ? recover(failure, index + 1)
                        : CompletableFuture.completedFuture(outcome)
        );
    }
}
