package com.alechilles.alecstamework.persistence.control;

import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** One asynchronous startup node action with an explicit evidence deferral result. */
@FunctionalInterface
public interface PersistenceStartupAction {
    enum Result {
        COMPLETE,
        DEFERRED
    }

    /** Runs or resumes the node without publishing readiness itself. */
    @Nonnull
    CompletionStage<Result> execute();
}
