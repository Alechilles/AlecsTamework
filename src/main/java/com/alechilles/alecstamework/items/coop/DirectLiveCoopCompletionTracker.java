package com.alechilles.alecstamework.items.coop;

import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Tracks direct-live coop facade completions without retaining Hytale runtime objects.
 *
 * <p>Every continuation closes only over immutable slot keys, strings, primitive position
 * evidence, and the thread-safe collections owned by this tracker. World, store, entity
 * reference, component, asset, and container instances remain on the world-thread side of the
 * system.</p>
 */
public final class DirectLiveCoopCompletionTracker {
    private final Set<String> registrationInFlight =
            ConcurrentHashMap.newKeySet();
    private final Set<CoopSlotKey> registeredSlots =
            ConcurrentHashMap.newKeySet();
    private final Set<String> captureInFlight = ConcurrentHashMap.newKeySet();
    private final Set<String> releaseInFlight = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<PublishedEffect> publishedEffects =
            new ConcurrentLinkedQueue<>();

    /** Returns whether this exact canonical slot has completed registration. */
    public boolean isRegistered(@Nonnull CoopSlotKey slot) {
        return registeredSlots.contains(slot);
    }

    /** Claims one deterministic registration batch for submission. */
    public boolean beginRegistration(@Nonnull String batchKey) {
        return registrationInFlight.add(batchKey);
    }

    /** Observes registration publication using only copied slot value evidence. */
    public void trackRegistration(
            @Nonnull String batchKey,
            @Nonnull List<CoopSlotKey> slots,
            @Nonnull CompletionStage<List<DirectLiveCoopAuthor.Outcome>> stage
    ) {
        List<CoopSlotKey> frozenSlots = List.copyOf(slots);
        stage.whenComplete((outcomes, failure) -> {
            registrationInFlight.remove(batchKey);
            if (failure != null || outcomes == null
                    || outcomes.size() != frozenSlots.size()) {
                return;
            }
            for (int index = 0; index < frozenSlots.size(); index++) {
                DirectLiveCoopAuthor.Outcome outcome = outcomes.get(index);
                if (outcome == DirectLiveCoopAuthor.Outcome.REGISTERED
                        || outcome == DirectLiveCoopAuthor.Outcome
                        .ALREADY_REGISTERED
                        || outcome == DirectLiveCoopAuthor.Outcome
                        .OCCUPIED_PRESERVED) {
                    registeredSlots.add(frozenSlots.get(index));
                }
            }
        });
    }

    /** Claims one live capture value-evidence submission. */
    public boolean beginCapture(@Nonnull String key) {
        return captureInFlight.add(key);
    }

    /** Observes capture publication and queues immutable presentation evidence. */
    public void trackCapture(
            @Nonnull String key,
            @Nonnull PublishedEffect effect,
            @Nonnull CompletionStage<DirectLiveCoopAuthor.Outcome> stage
    ) {
        stage.whenComplete((outcome, failure) -> {
            captureInFlight.remove(key);
            if (failure == null
                    && outcome == DirectLiveCoopAuthor.Outcome
                    .CAPTURE_SUBMITTED) {
                publishedEffects.add(effect);
            }
        });
    }

    /** Claims one canonical release value-evidence submission. */
    public boolean beginRelease(@Nonnull String key) {
        return releaseInFlight.add(key);
    }

    /** Observes release publication and queues immutable presentation evidence. */
    public void trackRelease(
            @Nonnull String key,
            @Nonnull PublishedEffect effect,
            @Nonnull CompletionStage<DirectLiveCoopAuthor.Outcome> stage
    ) {
        stage.whenComplete((outcome, failure) -> {
            releaseInFlight.remove(key);
            if (failure == null
                    && outcome == DirectLiveCoopAuthor.Outcome
                    .RELEASE_SUBMITTED) {
                publishedEffects.add(effect);
            }
        });
    }

    /** Returns the current bounded drain count for one world-thread sweep. */
    public int pendingEffectCount() {
        return publishedEffects.size();
    }

    /** Polls one immutable successful-publication presentation request. */
    @Nullable
    public PublishedEffect pollEffect() {
        return publishedEffects.poll();
    }

    /** Defers an effect until its owning world receives a later sweep. */
    public void requeueEffect(@Nonnull PublishedEffect effect) {
        publishedEffects.add(effect);
    }

    /** Immutable evidence sufficient to resolve optional presentation on a later world sweep. */
    public record PublishedEffect(
            @Nonnull String worldKey,
            @Nonnull String physicalCoopKey,
            double x,
            double y,
            double z
    ) {
        public PublishedEffect {
            if (worldKey == null || worldKey.isBlank()) {
                throw new IllegalArgumentException("World key is required");
            }
            if (physicalCoopKey == null || physicalCoopKey.isBlank()) {
                throw new IllegalArgumentException(
                        "Physical coop key is required"
                );
            }
        }
    }
}
