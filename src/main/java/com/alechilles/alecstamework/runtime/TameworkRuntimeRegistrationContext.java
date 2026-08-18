package com.alechilles.alecstamework.runtime;

import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeActivationPlan;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeModule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Immutable input for one Tamework runtime registration pass.
 *
 * <p>The context contains only a frozen activation plan, a target supplied by
 * the host, and declared participants. It does not retain a mutable registry
 * or start work while it is being built.</p>
 */
public final class TameworkRuntimeRegistrationContext {
    /** The kinds of runtime work that a participant may install. */
    public enum RegistrationKind {
        ECS_SYSTEM,
        CHUNK_SYSTEM,
        LISTENER,
        SUBSCRIPTION,
        WORKER
    }

    /** Target used by participants to install one active runtime resource. */
    @FunctionalInterface
    public interface RegistrationTarget {
        /** Installs one resource and returns its shutdown action. */
        AutoCloseable register(RegistrationKind kind, String participantId);

        /** Installs one resource with its host object when the target needs it. */
        default AutoCloseable register(
                RegistrationKind kind,
                String participantId,
                Object resource
        ) {
            return register(kind, participantId);
        }
    }

    /** Checked operation used by participant preflight and registration. */
    @FunctionalInterface
    public interface CheckedAction {
        /** Runs the operation. */
        void run() throws Exception;
    }

    /** Checked registration operation for one participant. */
    @FunctionalInterface
    public interface RegistrationAction {
        /** Registers the participant on the supplied target. */
        AutoCloseable register(RegistrationTarget target) throws Exception;
    }

    /** Checked factory used to construct a participant during preflight. */
    @FunctionalInterface
    public interface ResourceFactory {
        /** Constructs the resource without mutating the host target. */
        Object create() throws Exception;
    }

    /** Registers a resource that was constructed during preflight. */
    @FunctionalInterface
    public interface PreparedRegistrationAction {
        /** Installs the prepared resource on the supplied target. */
        AutoCloseable register(RegistrationTarget target, Object resource) throws Exception;
    }

    /** Immutable declaration of one potential runtime participant. */
    public static final class Participant {
        private final TameworkRuntimeModule module;
        private final String id;
        private final RegistrationKind kind;
        private final CheckedAction preflight;
        private final RegistrationAction registration;

        private Participant(
                TameworkRuntimeModule module,
                String id,
                RegistrationKind kind,
                CheckedAction preflight,
                RegistrationAction registration
        ) {
            this.module = Objects.requireNonNull(module, "Participant module is required");
            this.id = requireId(id);
            this.kind = Objects.requireNonNull(kind, "Participant registration kind is required");
            this.preflight = Objects.requireNonNull(preflight, "Participant preflight is required");
            this.registration = Objects.requireNonNull(registration, "Participant registration is required");
        }

        /** Creates a participant declaration. */
        public static Participant of(
                TameworkRuntimeModule module,
                String id,
                RegistrationKind kind,
                CheckedAction preflight,
                RegistrationAction registration
        ) {
            return new Participant(module, id, kind, preflight, registration);
        }

        /** Creates a participant whose preflight has no extra work. */
        public static Participant of(
                TameworkRuntimeModule module,
                String id,
                RegistrationKind kind,
                RegistrationAction registration
        ) {
            return of(module, id, kind, () -> { }, registration);
        }

        /**
         * Creates a participant whose resource is built during preflight and
         * reused during registration.
         *
         * <p>This is the required path for ECS systems and workers. It keeps
         * constructor failures ahead of the first host mutation.</p>
         */
        public static Participant prepared(
                TameworkRuntimeModule module,
                String id,
                RegistrationKind kind,
                ResourceFactory factory
        ) {
            return prepared(
                    module,
                    id,
                    kind,
                    factory,
                    (target, resource) -> target.register(kind, id, resource)
            );
        }

        /**
         * Creates a prepared participant with a registration step that may
         * initialize the cached resource before returning its owner.
         */
        public static Participant prepared(
                TameworkRuntimeModule module,
                String id,
                RegistrationKind kind,
                ResourceFactory factory,
                PreparedRegistrationAction registration
        ) {
            Objects.requireNonNull(factory, "Participant resource factory is required");
            Objects.requireNonNull(registration, "Prepared participant registration is required");
            AtomicReference<Object> prepared = new AtomicReference<>();
            return of(
                    module,
                    id,
                    kind,
                    () -> {
                        if (prepared.get() != null) {
                            return;
                        }
                        synchronized (prepared) {
                            if (prepared.get() != null) {
                                return;
                            }
                            Object resource = factory.create();
                            if (resource == null) {
                                throw new IllegalStateException(
                                        "Runtime participant factory returned null: " + id
                                );
                            }
                            prepared.set(resource);
                        }
                    },
                    target -> registration.register(target, prepared.get())
            );
        }

        public TameworkRuntimeModule module() {
            return module;
        }

        public String id() {
            return id;
        }

        public RegistrationKind kind() {
            return kind;
        }

        void preflight() throws Exception {
            preflight.run();
        }

        AutoCloseable register(RegistrationTarget target) throws Exception {
            return registration.register(target);
        }

        private static String requireId(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Participant ID is required");
            }
            return value.trim();
        }
    }

    private final TameworkRuntimeActivationPlan plan;
    private final RegistrationTarget target;
    private final List<Participant> participants;

    private TameworkRuntimeRegistrationContext(
            TameworkRuntimeActivationPlan plan,
            RegistrationTarget target,
            List<Participant> participants
    ) {
        this.plan = Objects.requireNonNull(plan, "Activation plan is required");
        this.target = Objects.requireNonNull(target, "Registration target is required");
        this.participants = orderedParticipants(participants, plan);
    }

    /** Starts a builder for one registration pass. */
    public static Builder builder(
            TameworkRuntimeActivationPlan plan,
            RegistrationTarget target
    ) {
        return new Builder(plan, target);
    }

    public TameworkRuntimeActivationPlan plan() {
        return plan;
    }

    RegistrationTarget target() {
        return target;
    }

    /** Returns all declared participants in dependency registration order. */
    public List<Participant> participants() {
        return participants;
    }

    /** Returns only participants whose owning module is active. */
    public List<Participant> activeParticipants() {
        List<Participant> active = new ArrayList<>();
        for (Participant participant : participants) {
            if (plan.isActive(participant.module())) {
                active.add(participant);
            }
        }
        return Collections.unmodifiableList(active);
    }

    /** Mutable builder that freezes all declarations at build time. */
    public static final class Builder {
        private final TameworkRuntimeActivationPlan plan;
        private final RegistrationTarget target;
        private final List<Participant> participants = new ArrayList<>();

        private Builder(TameworkRuntimeActivationPlan plan, RegistrationTarget target) {
            this.plan = Objects.requireNonNull(plan, "Activation plan is required");
            this.target = Objects.requireNonNull(target, "Registration target is required");
        }

        /** Adds one potential participant. Inactive modules remain dormant. */
        public Builder participant(Participant participant) {
            participants.add(Objects.requireNonNull(participant, "Participant is required"));
            return this;
        }

        /** Freezes this context. */
        public TameworkRuntimeRegistrationContext build() {
            return new TameworkRuntimeRegistrationContext(plan, target, participants);
        }
    }

    private static List<Participant> orderedParticipants(
            List<Participant> source,
            TameworkRuntimeActivationPlan plan
    ) {
        Objects.requireNonNull(source, "Participants are required");
        Map<String, Participant> byId = new LinkedHashMap<>();
        for (Participant participant : source) {
            Participant checked = Objects.requireNonNull(participant, "Participant cannot be null");
            if (!plan.modules().contains(checked.module())) {
                throw new IllegalArgumentException(
                        "Participant " + checked.id() + " names a module outside the activation plan: "
                                + checked.module().id()
                );
            }
            if (byId.putIfAbsent(checked.id(), checked) != null) {
                throw new IllegalArgumentException("Duplicate runtime participant ID: " + checked.id());
            }
        }

        Map<TameworkRuntimeModule, Integer> moduleOrder = moduleOrder(plan);
        List<Participant> ordered = new ArrayList<>(byId.values());
        ordered.sort(Comparator
                .comparingInt((Participant participant) -> moduleOrder.get(participant.module()))
                .thenComparing(Participant::kind)
                .thenComparing(Participant::id));
        return Collections.unmodifiableList(ordered);
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
