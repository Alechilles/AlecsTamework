package com.alechilles.alecstamework.persistence.projection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import com.alechilles.alecstamework.persistence.runtime.PersistenceThroughputMetrics;
import javax.annotation.Nonnull;

/** Serializes publication requests by consumer identity and publication origin. */
public final class ProjectionPublicationScheduler {
    private final ProjectionCoordinator coordinator;
    private final PersistenceThroughputMetrics throughputMetrics;
    private final ConcurrentHashMap<LaneKey, Lane> lanes =
            new ConcurrentHashMap<>();

    public ProjectionPublicationScheduler(
            @Nonnull ProjectionCoordinator coordinator
    ) {
        this(coordinator, PersistenceThroughputMetrics.NO_OP);
    }

    /** Builds a scheduler with passive publication-merge evidence. */
    public ProjectionPublicationScheduler(
            @Nonnull ProjectionCoordinator coordinator,
            @Nonnull PersistenceThroughputMetrics throughputMetrics
    ) {
        if (coordinator == null) {
            throw new IllegalArgumentException(
                    "Projection coordinator is required"
            );
        }
        if (throughputMetrics == null) {
            throw new IllegalArgumentException("Projection metrics are required");
        }
        this.coordinator = coordinator;
        this.throughputMetrics = throughputMetrics;
    }

    /** Publishes one consumer through its context-specific serial lane. */
    @Nonnull
    public CompletionStage<ProjectionCatchUpResult> publish(
            @Nonnull ProjectionConsumer consumer,
            @Nonnull ProjectionPublicationContext context,
            @Nonnull ProjectionSequence target,
            int batchSize
    ) {
        require(consumer, context, target, batchSize);
        CompletableFuture<ProjectionCatchUpResult> waiter =
                new CompletableFuture<>();
        LaneKey key = new LaneKey(consumer.consumerId(), context);
        AtomicReference<RunRequest> start = new AtomicReference<>();
        AtomicReference<Boolean> merged = new AtomicReference<>(false);
        lanes.compute(key, (ignored, existing) -> {
            Lane lane = existing;
            Request request = new Request(
                    consumer, context, target, batchSize
            );
            if (lane == null) {
                lane = new Lane();
                lane.running = true;
                lane.runningTarget = target;
                lane.waiters.add(new Waiter(target, waiter, request));
                start.set(new RunRequest(key, lane, request));
                return lane;
            }
            lane.waiters.add(new Waiter(target, waiter, request));
            if (!lane.running) {
                lane.running = true;
                lane.runningTarget = target;
                start.set(new RunRequest(key, lane, request));
            } else {
                merged.set(true);
                if (target.compareTo(lane.runningTarget) > 0
                        && (lane.pendingTarget == null
                        || target.compareTo(lane.pendingTarget) > 0)) {
                    lane.pendingTarget = target;
                    lane.pendingRequest = request;
                }
            }
            return lane;
        });
        if (Boolean.TRUE.equals(merged.get())) {
            safe(() -> throughputMetrics.projectionPublicationMerged());
        }
        start(start.get());
        return waiter;
    }

    /**
     * Publishes all required consumers concurrently and returns the first
     * concrete failure in required-consumer order, or {@code null}.
     */
    @Nonnull
    public CompletionStage<Throwable> publishRequired(
            @Nonnull List<? extends ProjectionConsumer> consumers,
            @Nonnull ProjectionPublicationContext context,
            @Nonnull ProjectionSequence target,
            int batchSize
    ) {
        if (consumers == null || context == null || target == null
                || batchSize <= 0 || batchSize > 10_000) {
            throw new IllegalArgumentException(
                    "Complete projection publication request is required"
            );
        }
        for (ProjectionConsumer consumer : consumers) {
            require(consumer, context, target, batchSize);
        }
        List<CompletableFuture<ProjectionCatchUpResult>> publications =
                new ArrayList<>();
        for (ProjectionConsumer consumer : consumers) {
            publications.add(publish(
                    consumer, context, target, batchSize
            ).toCompletableFuture());
        }
        CompletableFuture<Void> all = CompletableFuture.allOf(
                publications.toArray(CompletableFuture[]::new)
        );
        return all.thenApply(ignored -> firstFailure(publications));
    }

    /** Returns the number of active or pending consumer/context lanes. */
    public int activeLaneCount() {
        return lanes.size();
    }

    private void start(RunRequest request) {
        if (request == null) {
            return;
        }
        CompletionStage<ProjectionCatchUpResult> completion;
        try {
            completion = coordinator.afterCommit(
                    request.request.consumer,
                    request.request.target,
                    request.request.batchSize,
                    request.request.context
            );
        } catch (Throwable failure) {
            finish(request, failed(failure), null);
            return;
        }
        completion.whenComplete((result, failure) -> finish(
                request, result, failure
        ));
    }

    private void finish(
            RunRequest request,
            ProjectionCatchUpResult result,
            Throwable failure
    ) {
        ProjectionCatchUpResult resolved = result == null
                ? failed(failure == null
                        ? new IllegalStateException(
                        "projection_publication_returned_null"
                ) : failure)
                : result;
        AtomicReference<CompletionPlan> plan = new AtomicReference<>(
                CompletionPlan.EMPTY
        );
        lanes.compute(request.key, (ignored, current) -> {
            if (current != request.lane) {
                return current;
            }
            if (resolved.status() == ProjectionCatchUpResult.Status.CAUGHT_UP) {
                plan.set(successPlan(request.key, current, resolved));
            } else {
                plan.set(failurePlan(request.key, current, resolved));
            }
            return plan.get().removeLane ? null : current;
        });
        complete(plan.get().completions);
        start(plan.get().next);
    }

    private CompletionPlan successPlan(
            LaneKey key,
            Lane lane,
            ProjectionCatchUpResult result
    ) {
        List<Completion> completions = new ArrayList<>();
        ProjectionSequence acknowledged = result.acknowledged();
        lane.waiters.removeIf(waiter -> {
            if (waiter.target.compareTo(acknowledged) <= 0) {
                completions.add(new Completion(waiter.future, result));
                return true;
            }
            return false;
        });
        ProjectionSequence pending = lane.pendingTarget;
        if (pending == null || pending.compareTo(acknowledged) <= 0) {
            for (Waiter waiter : lane.waiters) {
                completions.add(new Completion(waiter.future, result));
            }
            lane.waiters.clear();
            lane.running = false;
            return new CompletionPlan(completions, null, true);
        }
        Request nextRequest = lane.pendingRequest;
        if (nextRequest == null) {
            nextRequest = highestWaiter(lane.waiters).request;
        }
        lane.pendingTarget = null;
        lane.pendingRequest = null;
        lane.runningTarget = pending;
        return new CompletionPlan(
                completions,
                new RunRequest(key, lane, nextRequest),
                false
        );
    }

    private CompletionPlan failurePlan(
            LaneKey key,
            Lane lane,
            ProjectionCatchUpResult result
    ) {
        List<Completion> completions = new ArrayList<>();
        ProjectionSequence attempted = lane.runningTarget;
        lane.waiters.removeIf(waiter -> {
            if (waiter.target.compareTo(attempted) <= 0) {
                completions.add(new Completion(waiter.future, result));
                return true;
            }
            return false;
        });
        ProjectionSequence pending = lane.pendingTarget;
        if (pending == null || lane.waiters.isEmpty()) {
            for (Waiter waiter : lane.waiters) {
                completions.add(new Completion(waiter.future, result));
            }
            lane.waiters.clear();
            lane.running = false;
            return new CompletionPlan(completions, null, true);
        }
        Request nextRequest = lane.pendingRequest;
        if (nextRequest == null) {
            nextRequest = highestWaiter(lane.waiters).request;
        }
        lane.pendingTarget = null;
        lane.pendingRequest = null;
        lane.runningTarget = pending;
        return new CompletionPlan(
                completions,
                new RunRequest(key, lane, nextRequest),
                false
        );
    }

    private Waiter highestWaiter(List<Waiter> waiters) {
        Waiter highest = waiters.getFirst();
        for (int index = 1; index < waiters.size(); index++) {
            Waiter candidate = waiters.get(index);
            if (candidate.target.compareTo(highest.target) > 0) {
                highest = candidate;
            }
        }
        return highest;
    }

    private void complete(List<Completion> completions) {
        for (Completion completion : completions) {
            completion.future.complete(completion.result);
        }
    }

    private void safe(Runnable hook) {
        try {
            hook.run();
        } catch (Throwable ignored) {
            // Throughput measurements cannot change publication outcomes.
        }
    }

    private Throwable firstFailure(
            List<CompletableFuture<ProjectionCatchUpResult>> publications
    ) {
        for (CompletableFuture<ProjectionCatchUpResult> publication
                : publications) {
            ProjectionCatchUpResult result = publication.join();
            if (result.status() != ProjectionCatchUpResult.Status.CAUGHT_UP) {
                return result.failure() == null
                        ? new IllegalStateException(
                        "projection_"
                                + result.status().name().toLowerCase()
                ) : result.failure();
            }
        }
        return null;
    }

    private ProjectionCatchUpResult failed(Throwable failure) {
        return new ProjectionCatchUpResult(
                ProjectionCatchUpResult.Status.READ_FAILED,
                ProjectionSequence.ORIGIN,
                0,
                0,
                failure
        );
    }

    private void require(
            ProjectionConsumer consumer,
            ProjectionPublicationContext context,
            ProjectionSequence target,
            int batchSize
    ) {
        if (consumer == null || consumer.consumerId() == null
                || context == null || target == null || batchSize <= 0
                || batchSize > 10_000) {
            throw new IllegalArgumentException(
                    "Complete projection publication request is required"
            );
        }
    }

    private record LaneKey(
            ProjectionConsumerId consumerId,
            ProjectionPublicationContext context
    ) {
    }

    private record Request(
            ProjectionConsumer consumer,
            ProjectionPublicationContext context,
            ProjectionSequence target,
            int batchSize
    ) {
    }

    private record Waiter(
            ProjectionSequence target,
            CompletableFuture<ProjectionCatchUpResult> future,
            Request request
    ) {
    }

    private static final class Lane {
        private ProjectionSequence runningTarget;
        private ProjectionSequence pendingTarget;
        private Request pendingRequest;
        private boolean running;
        private final List<Waiter> waiters = new ArrayList<>();
    }

    private record RunRequest(
            LaneKey key,
            Lane lane,
            Request request
    ) {
    }

    private record Completion(
            CompletableFuture<ProjectionCatchUpResult> future,
            ProjectionCatchUpResult result
    ) {
    }

    private record CompletionPlan(
            List<Completion> completions,
            RunRequest next,
            boolean removeLane
    ) {
        private static final CompletionPlan EMPTY =
                new CompletionPlan(List.of(), null, false);
    }
}
