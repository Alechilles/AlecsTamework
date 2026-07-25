package com.alechilles.alecstamework.persistence.authoring;

import com.alechilles.alecstamework.api.PaidCommandRevivalQuote;
import com.alechilles.alecstamework.api.PaidCommandRevivalQuoteRequest;
import com.alechilles.alecstamework.api.PaidCommandRevivalRequest;
import com.alechilles.alecstamework.persistence.facade.ReplacementPaidCommandRevivalApi;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Cohesive quote and exact request author for paid command revival. */
public final class PaidRevivalEvidenceAuthor
        implements ReplacementPaidCommandRevivalApi.RequestAuthor {
    private final PaidRevivalQuoteAuthor quotes;
    private final PaidRevivalRequestAuthor requests;

    PaidRevivalEvidenceAuthor(
            @Nonnull PaidRevivalQuoteAuthor quotes,
            @Nonnull PaidRevivalRequestAuthor requests
    ) {
        this.quotes = Objects.requireNonNull(quotes, "quotes");
        this.requests = Objects.requireNonNull(requests, "requests");
    }

    @Override
    public CompletionStage<PaidCommandRevivalQuote> quote(
            PaidCommandRevivalQuoteRequest request
    ) {
        return quotes.quote(request);
    }

    @Override
    public CompletionStage<ReplacementPaidCommandRevivalApi.PreparedRevival>
    prepare(PaidCommandRevivalRequest request) {
        return requests.prepare(request);
    }

    @Override
    public IdempotencyKey operationKey(
            String callerNamespace,
            String idempotencyKey
    ) {
        return requests.operationKey(callerNamespace, idempotencyKey);
    }
}
