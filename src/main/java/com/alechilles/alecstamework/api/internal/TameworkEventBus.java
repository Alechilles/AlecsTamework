package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.ConfigReloadedEvent;
import com.alechilles.alecstamework.api.CommandFamilyRosterMembershipChangedEvent;
import com.alechilles.alecstamework.api.CaptureAttemptResolvedEvent;
import com.alechilles.alecstamework.api.CompanionXpAwardedEvent;
import com.alechilles.alecstamework.api.CompanionProvisionedEvent;
import com.alechilles.alecstamework.api.ProvisionedCompanionDeathRecordedEvent;
import com.alechilles.alecstamework.api.ProvisionedCompanionRevivedEvent;
import com.alechilles.alecstamework.api.NpcCapturedEvent;
import com.alechilles.alecstamework.api.NpcDeathRecordedEvent;
import com.alechilles.alecstamework.api.NpcLostRecordedEvent;
import com.alechilles.alecstamework.api.NpcProfileChangedEvent;
import com.alechilles.alecstamework.api.NpcProfileView;
import com.alechilles.alecstamework.api.PopulationGroupLimitChangedEvent;
import com.alechilles.alecstamework.api.PopulationGroupMembershipChangedEvent;
import com.alechilles.alecstamework.api.TameworkConfigFamily;
import com.alechilles.alecstamework.api.TameworkEvent;
import com.alechilles.alecstamework.api.TameworkEventsApi;
import com.alechilles.alecstamework.items.CommandLinkedNpcCaptureService;
import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.alechilles.alecstamework.items.CommandLinkedNpcLostService;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class TameworkEventBus
        implements TameworkEventsApi, AutoCloseable {
    @Nullable
    private final HytaleLogger logger;
    private final CopyOnWriteArrayList<Subscription<?>> subscriptions = new CopyOnWriteArrayList<>();
    private final LongAdder dispatchedEvents = new LongAdder();
    private final LongAdder deliveryAttempts = new LongAdder();
    private final LongAdder deliveredListeners = new LongAdder();
    private final LongAdder listenerFailures = new LongAdder();
    private final AtomicReference<String> lastFailedEventType = new AtomicReference<>();

    public TameworkEventBus(@Nullable HytaleLogger logger) {
        this.logger = logger;
    }

    /** Receives an already-mapped canonical profile projection after durable commit. */
    public void publishProfileChanged(@Nonnull NpcProfileChangedEvent event) {
        dispatch(Objects.requireNonNull(event));
    }

    /** Compatibility event seam over immutable profile values. */
    public void publishProfileChanged(
            @Nullable NpcProfileView before,
            @Nullable NpcProfileView after,
            long publishedAtMs
    ) {
        if (before == null && after == null) {
            return;
        }
        dispatch(new NpcProfileChangedEvent(
                after != null ? after.profileId() : before.profileId(),
                CompanionProfileApiMapper.diff(before, after),
                before,
                after,
                publishedAtMs
        ));
    }

    @Override
    public <E extends TameworkEvent> AutoCloseable subscribe(@Nonnull Class<E> type, @Nonnull Consumer<E> listener) {
        Subscription<E> subscription = new Subscription<>(Objects.requireNonNull(type), Objects.requireNonNull(listener));
        subscriptions.add(subscription);
        return () -> subscriptions.remove(subscription);
    }

    public void publishCaptureRecorded(
            @Nonnull CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot snapshot,
            @Nullable NpcProfileView profile
    ) {
        dispatch(new NpcCapturedEvent(
                profile,
                snapshot.npcUuid(),
                snapshot.ownerId(),
                toOrderedSet(snapshot.toolIds()),
                snapshot.roleId(),
                snapshot.displayName(),
                ApiMapper.mapVector(snapshot.lastKnownPosition()),
                ApiMapper.mapVector(snapshot.homePosition()),
                snapshot.capturedAtMs(),
                System.currentTimeMillis()
        ));
    }

    public void publishDeathRecorded(
            @Nonnull CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot,
            @Nullable NpcProfileView profile
    ) {
        dispatch(new NpcDeathRecordedEvent(
                profile,
                snapshot.npcUuid(),
                snapshot.ownerId(),
                snapshot.ownerName(),
                toOrderedSet(snapshot.toolIds()),
                snapshot.roleId(),
                snapshot.displayName(),
                snapshot.customName(),
                snapshot.tamed(),
                ApiMapper.mapVector(snapshot.lastKnownPosition()),
                ApiMapper.mapVector(snapshot.homePosition()),
                snapshot.diedAtMs(),
                snapshot.respawnAvailableAtMs(),
                System.currentTimeMillis()
        ));
    }

    public void publishLostRecorded(
            @Nonnull CommandLinkedNpcLostService.LostLinkedNpcSnapshot snapshot,
            @Nullable NpcProfileView profile
    ) {
        dispatch(new NpcLostRecordedEvent(
                profile,
                snapshot.npcUuid(),
                ApiMapper.mapVector(snapshot.lastKnownPosition()),
                ApiMapper.mapVector(snapshot.homePosition()),
                snapshot.lastRelocationQueuedAtMs(),
                snapshot.lostAtMs(),
                snapshot.relocationRetryAttempts(),
                System.currentTimeMillis()
        ));
    }

    public void emitConfigReload(@Nonnull TameworkConfigFamily family, @Nullable Collection<String> changedIds) {
        LinkedHashSet<String> normalizedIds = new LinkedHashSet<>();
        if (changedIds != null) {
            for (String changedId : changedIds) {
                if (changedId == null) {
                    continue;
                }
                String trimmed = changedId.trim();
                if (!trimmed.isEmpty()) {
                    normalizedIds.add(trimmed);
                }
            }
        }
        if (normalizedIds.isEmpty()) {
            return;
        }
        dispatch(new ConfigReloadedEvent(family, normalizedIds, System.currentTimeMillis()));
    }

    public void emitCompanionXpAwarded(@Nonnull CompanionXpAwardedEvent event) {
        dispatch(event);
    }

    public void emitCaptureAttemptResolved(@Nonnull CaptureAttemptResolvedEvent event) {
        dispatch(event);
    }

    public void emitCompanionProvisioned(@Nonnull CompanionProvisionedEvent event) {
        dispatch(Objects.requireNonNull(event, "event"));
    }

    /** Isolated delivery seam for command-link-independent canonical lifecycle events. */
    public void emitCanonicalCompanionLifecycleEvent(@Nonnull TameworkEvent event) {
        Objects.requireNonNull(event, "event");
        if (!(event instanceof ProvisionedCompanionDeathRecordedEvent)
                && !(event instanceof ProvisionedCompanionRevivedEvent)) {
            throw new IllegalArgumentException(
                    "Only canonical provisioned companion lifecycle events are accepted.");
        }
        dispatch(event);
    }

    /** Isolated post-commit delivery seam for canonical population-group events. */
    public void emitPopulationGroupEvent(@Nonnull TameworkEvent event) {
        Objects.requireNonNull(event, "event");
        if (!(event instanceof PopulationGroupMembershipChangedEvent)
                && !(event instanceof PopulationGroupLimitChangedEvent)) {
            throw new IllegalArgumentException("Only canonical population-group events are accepted.");
        }
        dispatch(event);
    }

    /** Isolated post-commit delivery seam for canonical command-family roster events. */
    public void emitCommandFamilyRosterEvent(@Nonnull CommandFamilyRosterMembershipChangedEvent event) {
        dispatch(Objects.requireNonNull(event, "event"));
    }

    @Override
    public void close() {
        subscriptions.clear();
    }

    private void dispatch(@Nonnull TameworkEvent event) {
        dispatchedEvents.increment();
        for (Subscription<?> subscription : List.copyOf(subscriptions)) {
            if (!subscription.accepts(event)) {
                continue;
            }
            deliveryAttempts.increment();
            if (subscription.invoke(event, logger)) {
                deliveredListeners.increment();
            } else {
                listenerFailures.increment();
                lastFailedEventType.set(event.getClass().getSimpleName());
            }
        }
    }

    /** Thread-safe, bounded counters only; listener identities and event payloads are never exposed. */
    @Nonnull
    public DeliveryDiagnostics deliveryDiagnostics() {
        return new DeliveryDiagnostics(
                dispatchedEvents.sum(), deliveryAttempts.sum(), deliveredListeners.sum(),
                listenerFailures.sum(), lastFailedEventType.get());
    }

    public record DeliveryDiagnostics(long dispatchedEvents,
                                      long deliveryAttempts,
                                      long deliveredListeners,
                                      long listenerFailuresSinceBoot,
                                      @Nullable String lastFailedEventType) {
        public DeliveryDiagnostics {
            if (dispatchedEvents < 0L || deliveryAttempts < 0L || deliveredListeners < 0L
                    || listenerFailuresSinceBoot < 0L
                    || deliveredListeners + listenerFailuresSinceBoot > deliveryAttempts) {
                throw new IllegalArgumentException("Invalid event delivery diagnostics");
            }
            lastFailedEventType = lastFailedEventType == null
                    || lastFailedEventType.isBlank() ? null : lastFailedEventType.trim();
        }
    }

    @Nonnull
    private LinkedHashSet<String> toOrderedSet(@Nullable String[] values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values == null) {
            return normalized;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return normalized;
    }

    private static final class Subscription<E extends TameworkEvent> {
        private final Class<E> type;
        private final Consumer<E> listener;

        private Subscription(@Nonnull Class<E> type, @Nonnull Consumer<E> listener) {
            this.type = type;
            this.listener = listener;
        }

        private boolean accepts(@Nonnull TameworkEvent event) {
            return type.isAssignableFrom(event.getClass());
        }

        private boolean invoke(@Nonnull TameworkEvent event, @Nullable HytaleLogger logger) {
            try {
                listener.accept(type.cast(event));
                return true;
            } catch (RuntimeException | LinkageError ex) {
                if (logger != null) {
                    logger.at(Level.SEVERE).log(
                            "Tamework API event listener failed for "
                                    + type.getSimpleName()
                                    + ": "
                                    + ex.getMessage()
                    );
                }
                return false;
            }
        }
    }
}

