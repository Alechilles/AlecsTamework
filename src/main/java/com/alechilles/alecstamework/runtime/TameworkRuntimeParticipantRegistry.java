package com.alechilles.alecstamework.runtime;

import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeModule;
import com.hypixel.hytale.component.system.EcsEvent;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/** Collects potential runtime resources without installing inactive work. */
public final class TameworkRuntimeParticipantRegistry {
    private final List<TameworkRuntimeRegistrationContext.Participant> participants =
            new ArrayList<>();
    private final Consumer<Class<? super EcsEvent>> eventTypeRegistrar;
    private final HytaleLogger logger;

    /** Creates a registry for deferred Tamework runtime resources. */
    public TameworkRuntimeParticipantRegistry(
            Consumer<Class<? super EcsEvent>> eventTypeRegistrar,
            HytaleLogger logger
    ) {
        this.eventTypeRegistrar = eventTypeRegistrar;
        this.logger = logger;
    }

    /** Adds one entity-store system factory. */
    public void entitySystem(TameworkRuntimeModule module, String id, Supplier<?> factory) {
        participants.add(TameworkRuntimeRegistrationContext.Participant.prepared(
                module, id, TameworkRuntimeRegistrationContext.RegistrationKind.ECS_SYSTEM,
                factory::get
        ));
    }

    /** Adds one chunk-store system factory. */
    public void chunkSystem(TameworkRuntimeModule module, String id, Supplier<?> factory) {
        participants.add(TameworkRuntimeRegistrationContext.Participant.prepared(
                module, id, TameworkRuntimeRegistrationContext.RegistrationKind.CHUNK_SYSTEM,
                factory::get
        ));
    }

    /** Adds a best-effort entity system that may be absent on this server build. */
    public void optionalEntitySystem(
            TameworkRuntimeModule module,
            String id,
            Supplier<?> factory
    ) {
        participants.add(TameworkRuntimeRegistrationContext.Participant.prepared(
                module,
                id,
                TameworkRuntimeRegistrationContext.RegistrationKind.ECS_SYSTEM,
                () -> createOptional(id, factory),
                (target, prepared) -> {
                    Optional<?> system = (Optional<?>) prepared;
                    return system.isEmpty() ? () -> { } : target.register(
                            TameworkRuntimeRegistrationContext.RegistrationKind.ECS_SYSTEM,
                            id,
                            system.get()
                    );
                }
        ));
    }

    /** Adds an entity event type owned by one module. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public <T extends EcsEvent> void entityEventType(
            TameworkRuntimeModule module,
            String id,
            Class<? super T> eventType
    ) {
        action(module, id, TameworkRuntimeRegistrationContext.RegistrationKind.SUBSCRIPTION, () -> {
            try {
                eventTypeRegistrar.accept((Class) eventType);
            } catch (IllegalArgumentException exception) {
                logger.at(Level.FINE).log(
                        "Entity event type already registered; skipping "
                                + eventType.getSimpleName() + "."
                );
            }
        });
    }

    /** Adds one global listener registration. */
    public void listener(TameworkRuntimeModule module, String id, Runnable registration) {
        action(module, id, TameworkRuntimeRegistrationContext.RegistrationKind.LISTENER, registration);
    }

    /** Adds one asset event subscription. */
    public void subscription(TameworkRuntimeModule module, String id, Runnable registration) {
        action(module, id, TameworkRuntimeRegistrationContext.RegistrationKind.SUBSCRIPTION, registration);
    }

    /** Returns the declarations in insertion order. */
    public List<TameworkRuntimeRegistrationContext.Participant> participants() {
        return List.copyOf(participants);
    }

    private Optional<?> createOptional(String id, Supplier<?> factory) {
        try {
            return Optional.ofNullable(factory.get());
        } catch (RuntimeException | LinkageError error) {
            logger.at(Level.WARNING).withCause(error).log(
                    "Skipping optional runtime participant " + id + "."
            );
            return Optional.empty();
        }
    }

    private void action(
            TameworkRuntimeModule module,
            String id,
            TameworkRuntimeRegistrationContext.RegistrationKind kind,
            Runnable registration
    ) {
        participants.add(TameworkRuntimeRegistrationContext.Participant.of(
                module,
                id,
                kind,
                target -> {
                    registration.run();
                    return () -> { };
                }
        ));
    }
}
