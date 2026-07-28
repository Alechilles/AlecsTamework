package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.api.NpcDeathRecordedEvent;
import com.alechilles.alecstamework.api.NpcLostRecordedEvent;
import com.alechilles.alecstamework.api.NpcProfileView;
import com.alechilles.alecstamework.api.Vector3View;
import com.alechilles.alecstamework.api.internal.CompanionProfileApiMapper;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import java.util.Objects;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Maps immutable replacement dormant evidence to the two released API events. */
public final class TameworkDormantCompanionEventSink
        implements DormantCompanionEventSink {
    private final Consumer<NpcDeathRecordedEvent> deaths;
    private final Consumer<NpcLostRecordedEvent> losses;

    public TameworkDormantCompanionEventSink(
            @Nonnull Consumer<NpcDeathRecordedEvent> deaths,
            @Nonnull Consumer<NpcLostRecordedEvent> losses
    ) {
        this.deaths = Objects.requireNonNull(deaths, "death event consumer");
        this.losses = Objects.requireNonNull(losses, "lost event consumer");
    }

    @Override
    public void publish(@Nonnull Published event) {
        Objects.requireNonNull(event, "Published dormant event is required");
        if (event.observation().evidence()
                == DormantCompanionObservation.Evidence.SAVED_DEATH_COMPONENT) {
            deaths.accept(death(event));
            return;
        }
        losses.accept(lost(event));
    }

    private NpcDeathRecordedEvent death(Published event) {
        DormantCompanionObservation observation = event.observation();
        DormantCompanionObservation.DeathObservation death =
                Objects.requireNonNull(observation.death(), "death");
        DormantCompanionEventFacts facts = event.facts();
        return new NpcDeathRecordedEvent(
                profile(event),
                facts.npcUuid(),
                facts.ownerUuid(),
                facts.ownerName(),
                facts.toolIds(),
                role(event),
                event.canonicalProfile().identity().displayName(),
                facts.customName(),
                facts.tamed(),
                vector(observation.lastKnownPosition()),
                vector(facts.homePosition()),
                death.diedAtMs(),
                death.restorationAvailableAtMs(),
                event.emittedAtMs()
        );
    }

    private NpcLostRecordedEvent lost(Published event) {
        DormantCompanionObservation observation = event.observation();
        DormantCompanionObservation.LostObservation lost =
                Objects.requireNonNull(observation.lost(), "lost");
        return new NpcLostRecordedEvent(
                profile(event),
                event.facts().npcUuid(),
                vector(observation.lastKnownPosition()),
                vector(event.facts().homePosition()),
                lost.lastRelocationQueuedAtMs(),
                observation.observedAtMs(),
                lost.relocationRetryAttempts(),
                event.emittedAtMs()
        );
    }

    private NpcProfileView profile(Published event) {
        var model = event.canonicalProfile();
        return CompanionProfileApiMapper.map(
                CompanionProfileProjectionState.compose(
                        model.identity(),
                        model.currentAlias(),
                        model.lifecycle(),
                        model.toolLinks(),
                        model.currentSnapshots(),
                        model.currentCoopSlot()
                )
        );
    }

    @Nullable
    private String role(Published event) {
        String canonical = event.canonicalProfile().identity().roleId();
        return canonical != null
                ? canonical
                : event.facts().snapshotRoleId();
    }

    @Nullable
    private Vector3View vector(
            @Nullable DormantCompanionObservation.PositionObservation value
    ) {
        return value == null
                ? null
                : new Vector3View(value.x(), value.y(), value.z());
    }
}
