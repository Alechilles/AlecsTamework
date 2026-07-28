package com.alechilles.alecstamework.api;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Generic server-authoritative quote, commit, and recovery API for paid command revival. */
public interface PaidCommandRevivalApi {
    @Nonnull CompletionStage<PaidCommandRevivalQuote> quote(@Nonnull PaidCommandRevivalQuoteRequest request);

    @Nonnull CompletionStage<PaidCommandRevivalResult> revive(@Nonnull PaidCommandRevivalRequest request);

    @Nonnull CompletionStage<Optional<PaidCommandRevivalOperationView>> findOperation(
            @Nonnull String callerNamespace,
            @Nonnull String idempotencyKey
    );

    static PaidCommandRevivalApi unavailable() {
        return UnavailableHolder.INSTANCE;
    }

    final class UnavailableHolder {
        private static final PaidCommandRevivalApi INSTANCE = new PaidCommandRevivalApi() {
            @Override
            public CompletionStage<PaidCommandRevivalQuote> quote(PaidCommandRevivalQuoteRequest request) {
                if (request == null) throw new NullPointerException("request");
                return CompletableFuture.completedFuture(new PaidCommandRevivalQuote(
                        request.ownerUuid(), request.profileId(), request.commandFamilyId(),
                        PaidCommandRevivalQuote.Status.UNAVAILABLE, 0L, java.util.List.of(),
                        "unavailable", null, "paid-command-revival-authority-unavailable"
                ));
            }

            @Override
            public CompletionStage<PaidCommandRevivalResult> revive(PaidCommandRevivalRequest request) {
                if (request == null) throw new NullPointerException("request");
                return CompletableFuture.completedFuture(PaidCommandRevivalResult.unavailable(
                        request.profileId(), "paid-command-revival-authority-unavailable"
                ));
            }

            @Override
            public CompletionStage<Optional<PaidCommandRevivalOperationView>> findOperation(
                    String callerNamespace, String idempotencyKey) {
                if (callerNamespace == null) throw new NullPointerException("callerNamespace");
                if (idempotencyKey == null) throw new NullPointerException("idempotencyKey");
                return CompletableFuture.completedFuture(Optional.empty());
            }
        };

        private UnavailableHolder() {
        }
    }
}
