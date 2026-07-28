package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;

/** Performs best-effort released event publication after canonical dormant publication. */
final class PublishedDormantEventPublisher {
    private final ProfileReader profiles;
    private final LongSupplier clock;
    private final DormantCompanionEventSink events;
    private final DormantCompanionEventWarningSink warnings;

    PublishedDormantEventPublisher(
            ProfileReader profiles,
            LongSupplier clock,
            DormantCompanionEventSink events,
            DormantCompanionEventWarningSink warnings
    ) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
        this.warnings = Objects.requireNonNull(warnings, "warnings");
    }

    CompletionStage<CompanionLifecycleAuthorResult> publish(
            DormantCompanionObservation observation,
            DormantCompanionEventFacts facts,
            CompanionLifecycleAuthorResult published
    ) {
        final CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
                read;
        try {
            read = profiles.find(observation.profileId());
        } catch (RuntimeException | LinkageError failure) {
            warn("dormant_event_profile_read_failed", observation);
            return CompletableFuture.completedFuture(published);
        }
        return read.handle((result, failure) -> {
            publish(observation, facts, result, failure);
            return published;
        });
    }

    private void publish(
            DormantCompanionObservation observation,
            DormantCompanionEventFacts facts,
            PersistenceReadResult<CompanionProfileReadModel> read,
            Throwable readFailure
    ) {
        if (readFailure != null || !(read instanceof
                PersistenceReadResult.Found<CompanionProfileReadModel> found)
                || found.value().lifecycle().state()
                != targetState(observation)) {
            warn("dormant_event_profile_read_failed", observation);
            return;
        }
        try {
            events.publish(new DormantCompanionEventSink.Published(
                    observation, facts, found.value(), clock.getAsLong()
            ));
        } catch (RuntimeException | LinkageError failure) {
            warn("dormant_event_publish_failed", observation);
        }
    }

    private LifecycleState targetState(
            DormantCompanionObservation observation
    ) {
        return observation.evidence()
                == DormantCompanionObservation.Evidence.SAVED_DEATH_COMPONENT
                ? LifecycleState.DEAD_REVIVABLE
                : LifecycleState.LOST;
    }

    private void warn(
            String code,
            DormantCompanionObservation observation
    ) {
        try {
            warnings.warn(new DormantCompanionEventWarningSink.Warning(
                    code,
                    observation.profileId(),
                    "Released dormant API event was not emitted"
            ));
        } catch (RuntimeException | LinkageError ignored) {
            // Warning delivery cannot alter an already-published transition.
        }
    }

    @FunctionalInterface
    interface ProfileReader {
        CompletionStage<PersistenceReadResult<CompanionProfileReadModel>> find(
                ProfileId profileId
        );
    }
}
