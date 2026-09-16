package com.alechilles.alecstamework.runtime;

import com.alechilles.alecstamework.runtime.TameworkRuntimeRegistrationContext.Participant;
import com.alechilles.alecstamework.runtime.TameworkRuntimeRegistrationContext.RegistrationKind;
import com.alechilles.alecstamework.runtime.TameworkRuntimeRegistrationContext.RegistrationTarget;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeActivationPlan;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeModule;
import com.hypixel.hytale.component.system.EcsEvent;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Collects, preflights, and installs potential runtime resources.
 *
 * <p>Declarations stay dormant until an immutable activation plan selects
 * their module. Each pass validates and orders every declaration before it
 * constructs an active resource or mutates the registration target.</p>
 */
public final class TameworkRuntimeParticipantRegistry {
    private final List<Participant> participants = new ArrayList<>();
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
        participants.add(Participant.prepared(module, id, RegistrationKind.ECS_SYSTEM, factory::get));
    }

    /** Adds one chunk-store system factory. */
    public void chunkSystem(TameworkRuntimeModule module, String id, Supplier<?> factory) {
        participants.add(Participant.prepared(module, id, RegistrationKind.CHUNK_SYSTEM, factory::get));
    }

    /** Adds a best-effort entity system that may be absent on this server build. */
    public void optionalEntitySystem(
            TameworkRuntimeModule module,
            String id,
            Supplier<?> factory
    ) {
        participants.add(Participant.prepared(
                module,
                id,
                RegistrationKind.ECS_SYSTEM,
                () -> createOptional(id, factory),
                (target, prepared) -> {
                    Optional<?> system = (Optional<?>) prepared;
                    return system.isEmpty() ? () -> { } : target.register(
                            RegistrationKind.ECS_SYSTEM,
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
        action(module, id, RegistrationKind.SUBSCRIPTION, () -> {
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
        action(module, id, RegistrationKind.LISTENER, registration);
    }

    /** Adds one asset event subscription. */
    public void subscription(TameworkRuntimeModule module, String id, Runnable registration) {
        action(module, id, RegistrationKind.SUBSCRIPTION, registration);
    }

    /** Returns the declarations in insertion order. */
    public List<Participant> participants() {
        return List.copyOf(participants);
    }

    /** Constructs every active prepared resource without changing a host target. */
    public void preflight(TameworkRuntimeActivationPlan plan) {
        preflight(plan, participants);
    }

    /** Installs active declarations and reports each completed installation. */
    public TameworkRuntimeHandle register(
            TameworkRuntimeActivationPlan plan,
            RegistrationTarget target,
            Consumer<Participant> installed,
            Participant... additionalParticipants
    ) {
        Objects.requireNonNull(additionalParticipants, "Additional runtime participants are required");
        List<Participant> declarations = new ArrayList<>(participants);
        for (Participant participant : additionalParticipants) {
            declarations.add(Objects.requireNonNull(
                    participant, "Additional runtime participant is required"
            ));
        }
        return register(plan, target, declarations, installed);
    }

    static List<Participant> snapshot(
            TameworkRuntimeActivationPlan plan,
            List<Participant> source
    ) {
        TameworkRuntimeActivationPlan checkedPlan = Objects.requireNonNull(
                plan, "Activation plan is required"
        );
        Objects.requireNonNull(source, "Participants are required");
        Map<String, Participant> byId = new LinkedHashMap<>();
        for (Participant participant : source) {
            Participant checked = Objects.requireNonNull(
                    participant, "Participant cannot be null"
            );
            if (!checkedPlan.modules().contains(checked.module())) {
                throw new IllegalArgumentException(
                        "Participant " + checked.id() + " names a module outside the activation plan: "
                                + checked.module().id()
                );
            }
            if (byId.putIfAbsent(checked.id(), checked) != null) {
                throw new IllegalArgumentException("Duplicate runtime participant ID: " + checked.id());
            }
        }

        Map<TameworkRuntimeModule, Integer> moduleOrder = moduleOrder(checkedPlan);
        List<Participant> ordered = new ArrayList<>(byId.values());
        // Hytale validates referenced system types during registration.
        // List.sort preserves declaration order within each module.
        ordered.sort(Comparator.comparingInt(participant -> moduleOrder.get(participant.module())));
        return Collections.unmodifiableList(ordered);
    }

    static List<Participant> activeParticipants(
            TameworkRuntimeActivationPlan plan,
            List<Participant> declarations
    ) {
        List<Participant> active = new ArrayList<>();
        for (Participant participant : declarations) {
            if (plan.isActive(participant.module())) {
                active.add(participant);
            }
        }
        return Collections.unmodifiableList(active);
    }

    static void preflight(
            TameworkRuntimeActivationPlan plan,
            List<Participant> declarations
    ) {
        preflightActive(activeParticipants(plan, snapshot(plan, declarations)));
    }

    static TameworkRuntimeHandle register(
            TameworkRuntimeActivationPlan plan,
            RegistrationTarget target,
            List<Participant> declarations,
            Consumer<Participant> installed
    ) {
        Objects.requireNonNull(target, "Runtime registration target is required");
        Consumer<Participant> observer = Objects.requireNonNull(
                installed, "Registration observer is required"
        );
        List<Participant> active = activeParticipants(
                plan, snapshot(plan, declarations)
        );
        preflightActive(active);

        TameworkRuntimeHandle handle = new TameworkRuntimeHandle();
        try {
            for (Participant participant : active) {
                AutoCloseable resource = participant.register(target);
                handle.add(resource);
                observer.accept(participant);
            }
            return handle;
        } catch (Exception exception) {
            try {
                handle.close();
            } catch (RuntimeException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw new IllegalStateException("Runtime participant registration failed", exception);
        }
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
            RegistrationKind kind,
            Runnable registration
    ) {
        participants.add(Participant.of(
                module,
                id,
                kind,
                target -> {
                    registration.run();
                    return () -> { };
                }
        ));
    }

    private static void preflightActive(
            List<Participant> active
    ) {
        for (Participant participant : active) {
            try {
                participant.preflight();
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "Runtime participant preflight failed: " + participant.id(),
                        exception
                );
            }
        }
    }

    private static Map<TameworkRuntimeModule, Integer> moduleOrder(
            TameworkRuntimeActivationPlan plan
    ) {
        Map<TameworkRuntimeModule, Integer> order = new LinkedHashMap<>();
        Set<TameworkRuntimeModule> visiting = new TreeSet<>();
        Set<TameworkRuntimeModule> visited = new TreeSet<>();
        for (TameworkRuntimeModule module : new TreeSet<>(plan.modules())) {
            appendModule(module, plan, visiting, visited, order);
        }
        return order;
    }

    private static void appendModule(
            TameworkRuntimeModule module,
            TameworkRuntimeActivationPlan plan,
            Set<TameworkRuntimeModule> visiting,
            Set<TameworkRuntimeModule> visited,
            Map<TameworkRuntimeModule, Integer> order
    ) {
        if (visited.contains(module)) {
            return;
        }
        if (!visiting.add(module)) {
            throw new IllegalArgumentException("Runtime participant dependency cycle at " + module.id());
        }
        for (TameworkRuntimeModule dependency : new TreeSet<>(plan.dependenciesFor(module))) {
            appendModule(dependency, plan, visiting, visited, order);
        }
        visiting.remove(module);
        visited.add(module);
        order.put(module, order.size());
    }
}
