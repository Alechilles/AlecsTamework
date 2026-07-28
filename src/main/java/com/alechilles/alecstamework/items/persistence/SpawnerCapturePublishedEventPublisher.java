package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.items.persistence.SpawnerCaptureEvidenceFreezer.FrozenCapture;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Publishes the released capture event from canonical post-publication profile state.
 *
 * <p>Event delivery is observational: failure never changes an already-published operation.</p>
 */
final class SpawnerCapturePublishedEventPublisher {
    private final ProfileReader profiles;
    private final SpawnerCapturePublishedEventSink events;

    SpawnerCapturePublishedEventPublisher(
            ProfileReader profiles,
            SpawnerCapturePublishedEventSink events
    ) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.events = Objects.requireNonNull(events, "events");
    }

    CompletionStage<SpawnerPersistenceAuthorResult> publishIfNeeded(
            SpawnerPersistenceAuthorResult outcome,
            SpawnerCaptureContext context,
            FrozenCapture frozen
    ) {
        if (!outcome.published()
                || !frozen.resolution().successful()
                || frozen.resolution().successDisposition()
                != CaptureSuccessDisposition.CAPTURED_ITEM) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    outcome
            );
        }
        return profiles.find(context.profileId()).thenApply(read -> {
            if (read instanceof PersistenceReadResult.Found<
                    CompanionProfileReadModel> found) {
                publish(found.value(), frozen);
            }
            return outcome;
        });
    }

    private void publish(
            CompanionProfileReadModel profile,
            FrozenCapture frozen
    ) {
        try {
            events.publish(profile, evidence(profile, frozen));
        } catch (RuntimeException | LinkageError ignored) {
            // Public event delivery cannot alter a published capture.
        }
    }

    private SpawnerCapturePublishedEvidence evidence(
            CompanionProfileReadModel profile,
            FrozenCapture frozen
    ) {
        LinkedHashSet<String> tools = new LinkedHashSet<>();
        profile.toolLinks().stream()
                .map(link -> link.toolId().toString())
                .sorted()
                .forEach(tools::add);
        SpawnerCaptureLiveFacts facts = frozen.liveFacts();
        return new SpawnerCapturePublishedEvidence(
                facts.npcUuid(),
                profile.lifecycle().ownerId() == null
                        ? null
                        : profile.lifecycle().ownerId().value(),
                tools,
                profile.identity().roleId(),
                profile.identity().displayName(),
                facts.homePosition(),
                frozen.requestedAt()
        );
    }

    @FunctionalInterface
    interface ProfileReader {
        CompletionStage<PersistenceReadResult<CompanionProfileReadModel>> find(
                ProfileId profileId
        );
    }
}
