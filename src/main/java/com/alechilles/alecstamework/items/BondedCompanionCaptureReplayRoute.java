package com.alechilles.alecstamework.items;

import java.util.Objects;
import java.util.function.Function;
import javax.annotation.Nullable;

/** Routes positive durable capture evidence back through exact author replay. */
final class BondedCompanionCaptureReplayRoute {
    private final BondedCompanionCaptureAuthor author;

    BondedCompanionCaptureReplayRoute(BondedCompanionCaptureAuthor author) {
        this.author = Objects.requireNonNull(author, "author");
    }

    Result resume(
            BondedCompanionCaptureReplayGateway.Request request,
            Function<BondedCompanionCaptureReplayGateway.Evidence,
                    BondedCompanionCaptureIntent> intentFactory,
            @Nullable BondedCompanionCaptureFeedbackDispatcher.CompletionContext
                    completion
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(intentFactory, "intentFactory");
        var lookup = author.lookupReplay(request);
        return switch (lookup.status()) {
            case ABSENT -> new Result(false, null);
            case CONFLICT, FAILED -> handled(author.reject(
                    BondedCompanionCaptureAuthor.Status.DATABASE_FAILED,
                    completion));
            case MATCHED -> resumeMatched(
                    lookup.evidence(), intentFactory, completion);
        };
    }

    private Result resumeMatched(
            BondedCompanionCaptureReplayGateway.Evidence evidence,
            Function<BondedCompanionCaptureReplayGateway.Evidence,
                    BondedCompanionCaptureIntent> intentFactory,
            BondedCompanionCaptureFeedbackDispatcher.CompletionContext completion
    ) {
        BondedCompanionCaptureIntent intent;
        try {
            intent = intentFactory.apply(evidence);
        } catch (RuntimeException failure) {
            intent = null;
        }
        if (intent == null) {
            return handled(author.reject(
                    BondedCompanionCaptureAuthor.Status.SNAPSHOT_FAILED,
                    completion));
        }
        return handled(author.capture(intent, completion));
    }

    private Result handled(BondedCompanionCaptureAuthor.Result result) {
        return new Result(true, result);
    }

    record Result(
            boolean handled,
            @Nullable BondedCompanionCaptureAuthor.Result result
    ) {
        Result {
            if (handled != (result != null)) {
                throw new IllegalArgumentException("invalid replay route result");
            }
        }
    }
}
