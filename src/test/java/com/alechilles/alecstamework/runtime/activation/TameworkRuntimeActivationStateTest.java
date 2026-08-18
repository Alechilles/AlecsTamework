package com.alechilles.alecstamework.runtime.activation;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Behavior coverage for effective evidence, publication, diagnostics, and reload reporting. */
class TameworkRuntimeActivationStateTest {
    @Test
    void collectorIgnoresDisabledAndEmptyFactsButUsesEnabledTargetsAndItems() {
        TameworkRuntimeModule targetModule = TameworkRuntimeModule.of("target-module");
        TameworkRuntimeModule itemModule = TameworkRuntimeModule.of("item-module");

        TameworkActivationEvidence evidence = new TameworkAssetActivationEvidenceCollector().collect(
                List.of(
                        TameworkEffectiveAssetFact.of(
                                targetModule, false, "disabled-example", Set.of("Wolf"), Set.of()
                        ),
                        TameworkEffectiveAssetFact.of(
                                targetModule, true, "empty-roles", Set.of(), Set.of()
                        ),
                        TameworkEffectiveAssetFact.of(
                                targetModule, true, "role-config", Set.of("Wolf"), Set.of()
                        ),
                        TameworkEffectiveAssetFact.of(
                                itemModule, true, "item-config", Set.of(), Set.of("Tamework:Command")
                        )
                )
        );

        assertEquals(Set.of(targetModule, itemModule), evidence.directModules());
        assertTrue(evidence.reasonsFor(targetModule).stream()
                .allMatch(reason -> reason.detail().equals("role-config")));
        assertTrue(evidence.reasonsFor(itemModule).stream()
                .allMatch(reason -> reason.detail().equals("item-config")));
    }

    @Test
    void statePublicationIsAtomicAndExposesTheFrozenPlan() {
        TameworkRuntimeModule module = TameworkRuntimeModule.of("published-module");
        TameworkRuntimeModuleCatalog catalog = new TameworkRuntimeModuleCatalog(
                List.of(TameworkRuntimeModuleDescriptor.of(module))
        );
        TameworkRuntimeActivationPlan plan = new TameworkRuntimeActivationPlanner(catalog).plan(
                TameworkActivationEvidence.builder().content(module, "role-config").build()
        );
        AtomicReference<TameworkRuntimeActivationState> published = new AtomicReference<>();

        TameworkRuntimeActivationState state = TameworkRuntimeActivationState.publish(published, plan);

        assertSame(state, published.get());
        assertSame(plan, state.plan());
        assertTrue(state.isActive(module));
        assertEquals(plan.state(module), state.state(module));
        assertEquals(plan.reasonsFor(module), state.reasonsFor(module));
        assertEquals(plan.topologyFingerprint(), state.topologyFingerprint());
        assertThrows(UnsupportedOperationException.class,
                () -> state.plan().states().put(module,
                        TameworkRuntimeActivationPlan.ModuleState.DORMANT));
    }

    @Test
    void countersRecordAttemptsAndUntouchedDormantModulesRemainAtZero() {
        TameworkRuntimeModule active = TameworkRuntimeModule.of("active-module");
        TameworkRuntimeModule attemptedDormant = TameworkRuntimeModule.of("attempted-dormant-module");
        TameworkRuntimeModule untouchedDormant = TameworkRuntimeModule.of("untouched-dormant-module");
        TameworkRuntimeModule unavailable = TameworkRuntimeModule.of("unavailable-module");
        TameworkRuntimeModuleCatalog catalog = new TameworkRuntimeModuleCatalog(List.of(
                TameworkRuntimeModuleDescriptor.of(active),
                TameworkRuntimeModuleDescriptor.of(attemptedDormant),
                TameworkRuntimeModuleDescriptor.of(untouchedDormant),
                new TameworkRuntimeModuleDescriptor(
                        unavailable, List.of(), List.of("missing-provider")
                )
        ));
        TameworkRuntimeActivationPlan plan = new TameworkRuntimeActivationPlanner(catalog).plan(
                TameworkActivationEvidence.builder()
                        .content(active, "active-config")
                        .content(unavailable, "unavailable-config")
                        .build()
        );
        TameworkRuntimeDiagnostics diagnostics = TameworkRuntimeActivationState.of(plan).diagnostics();

        diagnostics.recordCallback(active);
        diagnostics.recordWorkerStart(active);
        diagnostics.recordSubscription(active);
        diagnostics.recordDatabaseOpen(active);
        diagnostics.recordSystemRegistration(active);
        diagnostics.recordWorkCycle(active);
        TameworkRuntimeDiagnostics.CounterSnapshot first = diagnostics.countersFor(active);
        diagnostics.recordCallback(active);
        diagnostics.recordCallback(attemptedDormant);
        diagnostics.recordWorkerStart(attemptedDormant);
        diagnostics.recordSubscription(attemptedDormant);
        diagnostics.recordDatabaseOpen(attemptedDormant);
        diagnostics.recordCallback(unavailable);
        diagnostics.recordWorkerStart(unavailable);
        diagnostics.recordSubscription(unavailable);
        diagnostics.recordDatabaseOpen(unavailable);

        assertEquals(new TameworkRuntimeDiagnostics.CounterSnapshot(1, 1, 1, 1, 1, 1), first);
        assertEquals(2, diagnostics.countersFor(active).callbacks());
        TameworkRuntimeDiagnostics.CounterSnapshot attempt =
                new TameworkRuntimeDiagnostics.CounterSnapshot(0, 1, 0, 1, 1, 1);
        assertEquals(attempt, diagnostics.countersFor(attemptedDormant));
        assertEquals(attempt, diagnostics.countersFor(unavailable));
        assertEquals(TameworkRuntimeDiagnostics.CounterSnapshot.zero(),
                diagnostics.countersFor(untouchedDormant));
    }

    @Test
    void diagnosticsShowEveryModuleStateReasonsAndCounters() {
        TameworkRuntimeModule active = TameworkRuntimeModule.of("active-module");
        TameworkRuntimeModule dormant = TameworkRuntimeModule.of("dormant-module");
        TameworkRuntimeModule unavailable = TameworkRuntimeModule.of("unavailable-module");
        TameworkRuntimeModuleCatalog catalog = new TameworkRuntimeModuleCatalog(List.of(
                TameworkRuntimeModuleDescriptor.of(active),
                TameworkRuntimeModuleDescriptor.of(dormant),
                new TameworkRuntimeModuleDescriptor(
                        unavailable, List.of(), List.of("missing-provider")
                )
        ));
        TameworkRuntimeActivationPlan plan = new TameworkRuntimeActivationPlanner(catalog).plan(
                TameworkActivationEvidence.builder()
                        .content(active, "active-config")
                        .content(unavailable, "unavailable-config")
                        .build()
        );

        TameworkRuntimeDiagnostics diagnostics = TameworkRuntimeActivationState.of(plan).diagnostics();

        assertEquals(catalog.modules(), diagnostics.modules().keySet());
        assertEquals(TameworkRuntimeActivationPlan.ModuleState.ACTIVE,
                diagnostics.module(active).state());
        assertEquals(plan.reasonsFor(active), diagnostics.module(active).reasons());
        assertEquals(TameworkRuntimeActivationPlan.ModuleState.DORMANT,
                diagnostics.module(dormant).state());
        assertEquals(TameworkRuntimeDiagnostics.CounterSnapshot.zero(),
                diagnostics.module(dormant).counters());
        assertEquals(TameworkRuntimeActivationPlan.ModuleState.UNAVAILABLE,
                diagnostics.module(unavailable).state());
        assertTrue(diagnostics.module(unavailable).reasons().stream()
                .anyMatch(reason -> reason.detail().equals("missing-provider")));
        assertEquals(TameworkRuntimeDiagnostics.CounterSnapshot.zero(),
                diagnostics.module(unavailable).counters());
    }

    @Test
    void reloadReportListsChangedModuleIdsAndRequiresRestart() {
        TameworkRuntimeModule module = TameworkRuntimeModule.of("reload-module");
        TameworkRuntimeModuleCatalog catalog = new TameworkRuntimeModuleCatalog(
                List.of(TameworkRuntimeModuleDescriptor.of(module))
        );
        TameworkRuntimeActivationPlanner planner = new TameworkRuntimeActivationPlanner(catalog);
        TameworkRuntimeActivationPlan startup = planner.plan(TameworkActivationEvidence.empty());
        TameworkRuntimeActivationPlan candidate = planner.plan(
                TameworkActivationEvidence.builder().content(module, "new-config").build()
        );
        TameworkRuntimeActivationPlan sameTopology = planner.plan(
                TameworkActivationEvidence.builder().content(module, "different-source").build()
        );

        TameworkReloadTopologyReport changed = TameworkReloadTopologyReport.compare(startup, candidate);
        TameworkReloadTopologyReport unchanged = TameworkReloadTopologyReport.compare(
                candidate, sameTopology
        );

        assertTrue(changed.restartRequired());
        assertEquals(Set.of(module.id()), changed.changedModuleIds());
        assertEquals("restart required: reload-module", changed.summary());
        assertFalse(unchanged.restartRequired());
        assertTrue(unchanged.changedModuleIds().isEmpty());
    }
}
