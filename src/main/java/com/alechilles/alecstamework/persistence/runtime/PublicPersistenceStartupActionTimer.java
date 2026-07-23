package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.persistence.control.PersistenceStartupAction;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/** Adds passive timing around the existing startup DAG actions. */
final class PublicPersistenceStartupActionTimer {
    private PublicPersistenceStartupActionTimer() {
    }

    static Map<PersistenceStartupNode, PersistenceStartupAction> wrap(
            Map<PersistenceStartupNode, PersistenceStartupAction> actions,
            PublicPersistenceControlPlane metrics
    ) {
        EnumMap<PersistenceStartupNode, PersistenceStartupAction> timed =
                new EnumMap<>(PersistenceStartupNode.class);
        actions.forEach((node, action) ->
                timed.put(node, () -> execute(node, action, metrics)));
        return Map.copyOf(timed);
    }

    private static CompletionStage<PersistenceStartupAction.Result> execute(
            PersistenceStartupNode node,
            PersistenceStartupAction action,
            PublicPersistenceControlPlane metrics
    ) {
        long started = System.nanoTime();
        try {
            CompletionStage<PersistenceStartupAction.Result> result =
                    action.execute();
            if (result == null) {
                metrics.startupNodeTimed(
                        node, elapsed(started)
                );
                return null;
            }
            return result.whenComplete((ignored, failure) ->
                    metrics.startupNodeTimed(
                            node, elapsed(started)
                    ));
        } catch (RuntimeException | Error failure) {
            metrics.startupNodeTimed(node, elapsed(started));
            throw failure;
        }
    }

    private static long elapsed(long started) {
        return Math.max(0, System.nanoTime() - started);
    }
}
