package com.alechilles.alecstamework.companion.revival;

import com.alechilles.alecstamework.api.ItemCostComponentView;
import com.alechilles.alecstamework.api.PaidCommandRevivedEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Maps one self-contained durable paid-revival result to the public event.
 *
 * <p>Delivery context supplies the recovered flag because the outbox payload
 * cannot infer whether this consumer invocation belongs to startup recovery.
 * No canonical read or repository join is required.</p>
 */
public final class PaidRevivalPublishedEventMapper {
    private PaidRevivalPublishedEventMapper() {
    }

    @Nonnull
    public static PaidCommandRevivedEvent map(
            @Nonnull ProjectionEvent event,
            boolean recovered,
            long emittedAtMs
    ) {
        if (event == null || !PaidRevivalEventCodec.EVENT_TYPE.equals(
                event.eventType()
        )) {
            throw new IllegalArgumentException(
                    "Paid revival projection event is required"
            );
        }
        PaidRevivalOutcome outcome = PaidRevivalEventCodec.decode(
                event.payloadVersion(), event.payloadJson()
        );
        requireMatchingEnvelope(event, outcome);
        return new PaidCommandRevivedEvent(
                event.operationId().value(),
                outcome.callerNamespace(),
                outcome.callerIdempotencyKey(),
                outcome.ownerId().value(),
                outcome.profileId().toString(),
                outcome.commandFamilyId(),
                costs(outcome.exactCost()),
                recovered,
                outcome.revivedAtMs(),
                emittedAtMs
        );
    }

    private static void requireMatchingEnvelope(
            ProjectionEvent event,
            PaidRevivalOutcome outcome
    ) {
        if (!event.aggregateId().equals(
                "paid-revival-result:" + outcome.profileId()
        )
                || event.aggregateRevision()
                != outcome.lifecycleRevision().value()
                || event.createdAtMs() != outcome.revivedAtMs()) {
            throw new IllegalArgumentException(
                    "Paid revival projection envelope does not match payload"
            );
        }
    }

    private static List<ItemCostComponentView> costs(
            List<RevivalCostItem> exactCost
    ) {
        return exactCost.stream()
                .map(item -> new ItemCostComponentView(
                        item.itemId(), item.quantity()
                ))
                .toList();
    }
}
