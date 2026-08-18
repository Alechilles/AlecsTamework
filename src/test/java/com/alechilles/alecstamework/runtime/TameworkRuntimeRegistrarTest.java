package com.alechilles.alecstamework.runtime;

import com.alechilles.alecstamework.runtime.TameworkRuntimeRegistrationContext.Participant;
import com.alechilles.alecstamework.runtime.TameworkRuntimeRegistrationContext.RegistrationKind;
import com.alechilles.alecstamework.runtime.activation.TameworkActivationEvidence;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeActivationPlan;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeActivationPlanner;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeDiagnostics;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeModule;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeModuleCatalog;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeModuleDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Behavior tests for dormant boundaries, preflight, and resource ownership. */
class TameworkRuntimeRegistrarTest {
    @Test
    void dormantPlanInstallsNoSystemsListenersSubscriptionsOrWorkers() {
        TameworkRuntimeModule module = TameworkRuntimeModule.of("dormant");
        TameworkRuntimeActivationPlan plan = plan(List.of(module), TameworkActivationEvidence.empty());
        RecordingTarget target = new RecordingTarget();
        List<String> observed = new ArrayList<>();

        TameworkRuntimeHandle handle = new TameworkRuntimeRegistrar().register(
                TameworkRuntimeRegistrationContext.builder(plan, target)
                        .participant(participant(module, "system", RegistrationKind.ECS_SYSTEM, target))
                        .participant(participant(module, "listener", RegistrationKind.LISTENER, target))
                        .participant(participant(module, "subscription", RegistrationKind.SUBSCRIPTION, target))
                        .participant(participant(module, "worker", RegistrationKind.WORKER, target))
                        .build(),
                participant -> observed.add(participant.id())
        );

        assertEquals(List.of(), target.registered);
        assertEquals(List.of(), observed);
        assertEquals(0, handle.size());
    }

    @Test
    void activePlanInstallsOnlyItsDependencyClosureInDependencyOrder() {
        TameworkRuntimeModule dependency = TameworkRuntimeModule.of("dependency");
        TameworkRuntimeModule active = TameworkRuntimeModule.of("active");
        TameworkRuntimeModule dormant = TameworkRuntimeModule.of("dormant");
        TameworkRuntimeActivationPlan plan = plan(
                List.of(
                        TameworkRuntimeModuleDescriptor.of(dependency),
                        TameworkRuntimeModuleDescriptor.of(active, dependency),
                        TameworkRuntimeModuleDescriptor.of(dormant)
                ),
                TameworkActivationEvidence.builder().content(active, "profile").build()
        );
        RecordingTarget target = new RecordingTarget();
        List<String> observed = new ArrayList<>();

        new TameworkRuntimeRegistrar().register(
                TameworkRuntimeRegistrationContext.builder(plan, target)
                        .participant(participant(active, "active", RegistrationKind.LISTENER, target))
                        .participant(participant(dormant, "dormant", RegistrationKind.WORKER, target))
                        .participant(participant(dependency, "dependency", RegistrationKind.ECS_SYSTEM, target))
                        .build(),
                participant -> observed.add(participant.id())
        );

        assertEquals(List.of("dependency", "active"), target.registered);
        assertEquals(List.of("dependency", "active"), observed);
    }

    @Test
    void telemetryDistinguishesInactiveActiveIdleAndLoadedAnimalStates() {
        TameworkRuntimeModule active = TameworkRuntimeModule.of("animal-runtime");
        TameworkRuntimeModule dormant = TameworkRuntimeModule.of("unused-runtime");
        TameworkRuntimeActivationPlan plan = plan(
                List.of(
                        TameworkRuntimeModuleDescriptor.of(active),
                        TameworkRuntimeModuleDescriptor.of(dormant)
                ),
                TameworkActivationEvidence.builder().content(active, "husbandry-profile").build()
        );
        TameworkRuntimeDiagnostics diagnostics = new TameworkRuntimeDiagnostics(plan);
        RecordingTarget target = new RecordingTarget();

        new TameworkRuntimeRegistrar().register(
                TameworkRuntimeRegistrationContext.builder(plan, target)
                        .participant(participant(active, "animal-system", RegistrationKind.ECS_SYSTEM, target))
                        .participant(participant(dormant, "unused-system", RegistrationKind.ECS_SYSTEM, target))
                        .build(),
                participant -> TameworkRuntimeRegistrationTelemetry.record(diagnostics, participant)
        );

        assertEquals(TameworkRuntimeDiagnostics.CounterSnapshot.zero(), diagnostics.countersFor(dormant));
        assertEquals(1, diagnostics.countersFor(active).systemRegistrations());
        assertEquals(0, diagnostics.countersFor(active).callbacks());
        assertEquals(0, diagnostics.countersFor(active).workCycles());

        diagnostics.recordCallback(active);
        diagnostics.recordWorkCycle(active);

        assertEquals(1, diagnostics.countersFor(active).callbacks());
        assertEquals(1, diagnostics.countersFor(active).workCycles());
        assertEquals(TameworkRuntimeDiagnostics.CounterSnapshot.zero(), diagnostics.countersFor(dormant));
    }

    @Test
    void preflightFailureLeavesTheTargetUnchanged() {
        TameworkRuntimeModule module = TameworkRuntimeModule.of("preflight");
        TameworkRuntimeActivationPlan plan = plan(
                List.of(module),
                TameworkActivationEvidence.builder().content(module, "profile").build()
        );
        RecordingTarget target = new RecordingTarget();
        AtomicInteger preflightCalls = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> new TameworkRuntimeRegistrar().register(
                TameworkRuntimeRegistrationContext.builder(plan, target)
                        .participant(Participant.of(
                                module,
                                "failing",
                                RegistrationKind.WORKER,
                                () -> {
                                    preflightCalls.incrementAndGet();
                                    throw new IllegalStateException("missing dependency");
                                },
                                ignored -> target.resource("failing")
                        ))
                        .build()
        ));

        assertEquals(1, preflightCalls.get());
        assertEquals(List.of(), target.registered);
    }

    @Test
    void earlyPreflightResourceIsReusedDuringRegistration() {
        TameworkRuntimeModule module = TameworkRuntimeModule.of("preflight-once");
        TameworkRuntimeActivationPlan plan = plan(
                List.of(module),
                TameworkActivationEvidence.builder().content(module, "profile").build()
        );
        RecordingTarget target = new RecordingTarget();
        AtomicInteger factoryCalls = new AtomicInteger();
        Participant participant = Participant.prepared(
                module, "prepared-system", RegistrationKind.ECS_SYSTEM,
                () -> {
                    factoryCalls.incrementAndGet();
                    return new Object();
                }
        );
        TameworkRuntimeRegistrationContext context =
                TameworkRuntimeRegistrationContext.builder(plan, target)
                        .participant(participant)
                        .build();
        TameworkRuntimeRegistrar registrar = new TameworkRuntimeRegistrar();

        registrar.preflight(context);
        registrar.register(context);

        assertEquals(1, factoryCalls.get());
        assertEquals(List.of("prepared-system"), target.registered);
    }

    @Test
    void handleClosesResourcesInReverseRegistrationOrder() {
        TameworkRuntimeModule first = TameworkRuntimeModule.of("first");
        TameworkRuntimeModule second = TameworkRuntimeModule.of("second");
        TameworkRuntimeActivationPlan plan = plan(
                List.of(
                        TameworkRuntimeModuleDescriptor.of(first),
                        TameworkRuntimeModuleDescriptor.of(second)
                ),
                TameworkActivationEvidence.builder()
                        .content(first, "first-profile")
                        .content(second, "second-profile")
                        .build()
        );
        RecordingTarget target = new RecordingTarget();

        TameworkRuntimeHandle handle = new TameworkRuntimeRegistrar().register(
                TameworkRuntimeRegistrationContext.builder(plan, target)
                        .participant(participant(first, "first", RegistrationKind.ECS_SYSTEM, target))
                        .participant(participant(second, "second", RegistrationKind.WORKER, target))
                        .build()
        );

        handle.close();
        assertEquals(List.of("first", "second", "second", "first"), target.lifecycle);
        assertEquals(0, handle.size());
    }

    private static Participant participant(
            TameworkRuntimeModule module,
            String id,
            RegistrationKind kind,
            RecordingTarget target
    ) {
        return Participant.of(module, id, kind, ignored -> target.resource(id));
    }

    private static TameworkRuntimeActivationPlan plan(
            List<?> descriptors,
            TameworkActivationEvidence evidence
    ) {
        TameworkRuntimeModuleCatalog catalog;
        if (descriptors.isEmpty() || descriptors.get(0) instanceof TameworkRuntimeModule) {
            @SuppressWarnings("unchecked")
            List<TameworkRuntimeModule> modules = (List<TameworkRuntimeModule>) descriptors;
            catalog = new TameworkRuntimeModuleCatalog(
                    modules.stream().map(TameworkRuntimeModuleDescriptor::of).toList()
            );
        } else {
            @SuppressWarnings("unchecked")
            List<TameworkRuntimeModuleDescriptor> values =
                    (List<TameworkRuntimeModuleDescriptor>) descriptors;
            catalog = new TameworkRuntimeModuleCatalog(values);
        }
        return new TameworkRuntimeActivationPlanner(catalog).plan(evidence);
    }

    private static final class RecordingTarget
            implements TameworkRuntimeRegistrationContext.RegistrationTarget {
        private final List<String> registered = new ArrayList<>();
        private final List<String> lifecycle = new ArrayList<>();

        @Override
        public AutoCloseable register(RegistrationKind kind, String participantId) {
            registered.add(participantId);
            lifecycle.add(participantId);
            return () -> lifecycle.add(participantId);
        }

        private AutoCloseable resource(String participantId) {
            return register(RegistrationKind.WORKER, participantId);
        }
    }
}
