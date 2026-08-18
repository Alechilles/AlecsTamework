package com.alechilles.alecstamework.runtime.activation;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior coverage for immutable runtime activation planning and topology checks. */
class TameworkRuntimeActivationPlannerTest {
    @Test
    void emptyEvidenceLeavesEveryStandardModuleDormant() {
        TameworkRuntimeModuleCatalog catalog = TameworkRuntimeModuleCatalog.standard();
        TameworkRuntimeActivationPlan plan = new TameworkRuntimeActivationPlanner(catalog)
                .plan(TameworkActivationEvidence.empty());

        assertEquals(catalog.modules(), plan.dormantModules());
        assertTrue(plan.activeModules().isEmpty());
        assertTrue(plan.unavailableModules().isEmpty());
        assertTrue(plan.states().values().stream().allMatch(
                state -> state == TameworkRuntimeActivationPlan.ModuleState.DORMANT
        ));
    }

    @Test
    void hStatsActivatesWithoutCoreOwnershipDependency() {
        TameworkRuntimeModuleCatalog catalog = TameworkRuntimeModuleCatalog.standard();
        TameworkRuntimeActivationPlan plan = new TameworkRuntimeActivationPlanner(catalog)
                .plan(TameworkActivationEvidence.builder()
                        .content(TameworkRuntimeModule.HSTATS, "hstats-profile")
                        .build());

        assertEquals(Set.of(TameworkRuntimeModule.HSTATS), plan.activeModules());
    }

    @Test
    void bondedPersistenceDoesNotWakeGenericPersistence() {
        TameworkRuntimeModuleCatalog catalog =
                TameworkRuntimeModuleCatalog.standard();
        TameworkRuntimeActivationPlan plan =
                new TameworkRuntimeActivationPlanner(catalog).plan(
                        TameworkActivationEvidence.builder()
                                .content(TameworkRuntimeModule.BONDED_PERSISTENCE,
                                        "bonded-profile")
                                .build());

        assertEquals(
                Set.of(TameworkRuntimeModule.CORE_OWNERSHIP),
                catalog.directDependencies(
                        TameworkRuntimeModule.BONDED_PERSISTENCE)
        );
        assertTrue(plan.isActive(TameworkRuntimeModule.BONDED_PERSISTENCE));
        assertEquals(
                TameworkRuntimeActivationPlan.ModuleState.DORMANT,
                plan.state(TameworkRuntimeModule.GENERIC_PERSISTENCE)
        );
    }

    @Test
    void directEvidenceExpandsTheExactDependencyClosure() {
        TameworkRuntimeModule leaf = TameworkRuntimeModule.of("test-leaf");
        TameworkRuntimeModule dependency = TameworkRuntimeModule.of("test-dependency");
        TameworkRuntimeModule root = TameworkRuntimeModule.of("test-root");
        TameworkRuntimeModule unrelated = TameworkRuntimeModule.of("test-unrelated");
        TameworkRuntimeModuleCatalog catalog = new TameworkRuntimeModuleCatalog(List.of(
                TameworkRuntimeModuleDescriptor.of(leaf),
                TameworkRuntimeModuleDescriptor.of(dependency, leaf),
                TameworkRuntimeModuleDescriptor.of(root, dependency),
                TameworkRuntimeModuleDescriptor.of(unrelated)
        ));

        TameworkRuntimeActivationPlan plan = new TameworkRuntimeActivationPlanner(catalog)
                .plan(TameworkActivationEvidence.builder().content(root, "role-config").build());

        assertEquals(Set.of(root, dependency, leaf), plan.activeModules());
        assertEquals(Set.of(unrelated), plan.dormantModules());
        assertTrue(plan.reasonsFor(dependency).stream().anyMatch(
                reason -> reason.kind() == TameworkActivationReason.Kind.DEPENDENCY
        ));
        assertTrue(plan.reasonsFor(leaf).stream().anyMatch(
                reason -> reason.kind() == TameworkActivationReason.Kind.DEPENDENCY
        ));
    }

    @Test
    void combinedDirectReasonsRemainVisibleOnTheActiveModule() {
        TameworkRuntimeModule module = TameworkRuntimeModule.of("test-combined");
        TameworkRuntimeModuleCatalog catalog = new TameworkRuntimeModuleCatalog(List.of(
                TameworkRuntimeModuleDescriptor.of(module)
        ));

        TameworkActivationEvidence evidence = TameworkActivationEvidence.builder()
                .content(module, "role-config")
                .publicCapability(module, "TW_ACTIVITY_OUTBOX_V1", "husbandry-profile")
                .durableState(module, "pending-output")
                .availableCapability("TW_ACTIVITY_OUTBOX_V1")
                .build();
        TameworkRuntimeActivationPlan plan = new TameworkRuntimeActivationPlanner(catalog)
                .plan(evidence);

        assertEquals(TameworkRuntimeActivationPlan.ModuleState.ACTIVE, plan.state(module));
        assertEquals(
                Set.of(
                        TameworkActivationReason.Kind.CONTENT,
                        TameworkActivationReason.Kind.PUBLIC_CAPABILITY,
                        TameworkActivationReason.Kind.DURABLE_STATE
                ),
                plan.reasonsFor(module).stream()
                        .map(TameworkActivationReason::kind)
                        .collect(java.util.stream.Collectors.toSet())
        );
    }

    @Test
    void aRequiredMissingCapabilityMakesTheModuleUnavailable() {
        TameworkRuntimeModule module = TameworkRuntimeModule.of("test-provider-bound");
        TameworkRuntimeModuleCatalog catalog = new TameworkRuntimeModuleCatalog(List.of(
                new TameworkRuntimeModuleDescriptor(
                        module, List.of(), List.of("TW_EXTERNAL_ANIMAL_MODIFIERS_V1")
                )
        ));

        TameworkRuntimeActivationPlan plan = new TameworkRuntimeActivationPlanner(catalog)
                .plan(TameworkActivationEvidence.builder().content(module, "animal-profile").build());

        assertEquals(TameworkRuntimeActivationPlan.ModuleState.UNAVAILABLE, plan.state(module));
        assertTrue(plan.reasonsFor(module).stream().anyMatch(
                reason -> reason.kind() == TameworkActivationReason.Kind.PUBLIC_CAPABILITY
                        && reason.detail().contains("TW_EXTERNAL_ANIMAL_MODIFIERS_V1")
        ));
        assertFalse(plan.isActive(module));
    }

    @Test
    void aConditionalRequirementDoesNotWakeDormantPersistence() {
        TameworkRuntimeModule module = TameworkRuntimeModule.of("test-persistence");
        TameworkRuntimeModuleCatalog catalog = new TameworkRuntimeModuleCatalog(List.of(
                TameworkRuntimeModuleDescriptor.of(module)
        ));
        TameworkRuntimeActivationPlanner planner = new TameworkRuntimeActivationPlanner(catalog);

        TameworkRuntimeActivationPlan dormant = planner.plan(TameworkActivationEvidence.builder()
                .requiredCapability(module, "persistence-writable")
                .build());
        TameworkRuntimeActivationPlan readOnly = planner.plan(TameworkActivationEvidence.builder()
                .content(module, "animal-profile")
                .requiredCapability(module, "persistence-writable")
                .build());

        assertEquals(TameworkRuntimeActivationPlan.ModuleState.DORMANT, dormant.state(module));
        assertEquals(TameworkRuntimeActivationPlan.ModuleState.UNAVAILABLE, readOnly.state(module));
    }

    @Test
    void unavailableRootDoesNotKeepAnOrphanDependencyActive() {
        TameworkRuntimeModule orphan = TameworkRuntimeModule.of("test-orphan-dependency");
        TameworkRuntimeModule root = TameworkRuntimeModule.of("test-unavailable-root");
        TameworkRuntimeModuleCatalog catalog = new TameworkRuntimeModuleCatalog(List.of(
                TameworkRuntimeModuleDescriptor.of(orphan),
                new TameworkRuntimeModuleDescriptor(
                        root,
                        List.of(orphan),
                        List.of("test-provider")
                )
        ));

        TameworkRuntimeActivationPlan plan = new TameworkRuntimeActivationPlanner(catalog)
                .plan(TameworkActivationEvidence.builder().content(root, "broken-profile").build());

        assertEquals(TameworkRuntimeActivationPlan.ModuleState.UNAVAILABLE, plan.state(root));
        assertEquals(TameworkRuntimeActivationPlan.ModuleState.DORMANT, plan.state(orphan));
        assertTrue(plan.activeModules().isEmpty());
    }

    @Test
    void sharedDependencyRemainsActiveForAnIndependentRunnableRoot() {
        TameworkRuntimeModule shared = TameworkRuntimeModule.of("test-shared-dependency");
        TameworkRuntimeModule unavailableRoot = TameworkRuntimeModule.of("test-unavailable-root");
        TameworkRuntimeModule runnableRoot = TameworkRuntimeModule.of("test-runnable-root");
        TameworkRuntimeModuleCatalog catalog = new TameworkRuntimeModuleCatalog(List.of(
                TameworkRuntimeModuleDescriptor.of(shared),
                new TameworkRuntimeModuleDescriptor(
                        unavailableRoot,
                        List.of(shared),
                        List.of("test-provider")
                ),
                TameworkRuntimeModuleDescriptor.of(runnableRoot, shared)
        ));

        TameworkRuntimeActivationPlan plan = new TameworkRuntimeActivationPlanner(catalog)
                .plan(TameworkActivationEvidence.builder()
                        .content(unavailableRoot, "broken-profile")
                        .content(runnableRoot, "healthy-profile")
                        .build());

        assertEquals(TameworkRuntimeActivationPlan.ModuleState.UNAVAILABLE,
                plan.state(unavailableRoot));
        assertEquals(TameworkRuntimeActivationPlan.ModuleState.ACTIVE,
                plan.state(runnableRoot));
        assertEquals(TameworkRuntimeActivationPlan.ModuleState.ACTIVE, plan.state(shared));
        assertEquals(Set.of(runnableRoot, shared), plan.activeModules());
    }

    @Test
    void fingerprintsIgnoreEvidenceOrderButChangeWithTopologyState() {
        TameworkRuntimeModule module = TameworkRuntimeModule.of("test-fingerprint");
        TameworkRuntimeModule other = TameworkRuntimeModule.of("test-other");
        TameworkRuntimeModuleCatalog catalog = new TameworkRuntimeModuleCatalog(List.of(
                TameworkRuntimeModuleDescriptor.of(module),
                TameworkRuntimeModuleDescriptor.of(other)
        ));
        TameworkRuntimeActivationPlanner planner = new TameworkRuntimeActivationPlanner(catalog);

        TameworkRuntimeActivationPlan first = planner.plan(TameworkActivationEvidence.builder()
                .content(module, "first")
                .durableState(module, "second")
                .build());
        TameworkRuntimeActivationPlan sameTopology = planner.plan(TameworkActivationEvidence.builder()
                .durableState(module, "second")
                .content(module, "first")
                .build());
        TameworkRuntimeActivationPlan differentTopology = planner.plan(
                TameworkActivationEvidence.builder().content(other, "other").build()
        );

        assertEquals(first.topologyFingerprint(), sameTopology.topologyFingerprint());
        assertFalse(first.topologyFingerprint().isEmpty());
        assertFalse(first.topologyFingerprint().equals(differentTopology.topologyFingerprint()));
    }

    @Test
    void fingerprintsDistinguishGraphsWhenIdsContainDelimiters() {
        TameworkRuntimeModule root = TameworkRuntimeModule.of("test-root");
        TameworkRuntimeModule a = TameworkRuntimeModule.of("a");
        TameworkRuntimeModule bCommaC = TameworkRuntimeModule.of("b,c");
        TameworkRuntimeModule aCommaB = TameworkRuntimeModule.of("a,b");
        TameworkRuntimeModule c = TameworkRuntimeModule.of("c");

        TameworkRuntimeModuleCatalog firstCatalog = new TameworkRuntimeModuleCatalog(List.of(
                TameworkRuntimeModuleDescriptor.of(root, a, bCommaC),
                TameworkRuntimeModuleDescriptor.of(a),
                TameworkRuntimeModuleDescriptor.of(bCommaC),
                TameworkRuntimeModuleDescriptor.of(aCommaB),
                TameworkRuntimeModuleDescriptor.of(c)
        ));
        TameworkRuntimeModuleCatalog secondCatalog = new TameworkRuntimeModuleCatalog(List.of(
                TameworkRuntimeModuleDescriptor.of(root, aCommaB, c),
                TameworkRuntimeModuleDescriptor.of(a),
                TameworkRuntimeModuleDescriptor.of(bCommaC),
                TameworkRuntimeModuleDescriptor.of(aCommaB),
                TameworkRuntimeModuleDescriptor.of(c)
        ));
        TameworkActivationEvidence evidence = TameworkActivationEvidence.builder()
                .content(root, "root-content")
                .content(a, "a-content")
                .content(bCommaC, "b-c-content")
                .content(aCommaB, "a-b-content")
                .content(c, "c-content")
                .build();
        TameworkRuntimeActivationPlan first = new TameworkRuntimeActivationPlanner(firstCatalog)
                .plan(evidence);
        TameworkRuntimeActivationPlan second = new TameworkRuntimeActivationPlanner(secondCatalog)
                .plan(evidence);

        assertFalse(first.topologyFingerprint().equals(second.topologyFingerprint()));
        assertEquals(TameworkTopologyComparison.RESTART_REQUIRED,
                new TameworkRuntimeActivationPlanner(firstCatalog).compare(first, second));
    }

    @Test
    void distinctTrustedReasonsWithDelimiterCharactersAreBothRetained() {
        TameworkRuntimeModule module = TameworkRuntimeModule.of("test-reasons");
        TameworkRuntimeModuleCatalog catalog = new TameworkRuntimeModuleCatalog(List.of(
                TameworkRuntimeModuleDescriptor.of(module)
        ));
        TameworkRuntimeActivationPlan plan = new TameworkRuntimeActivationPlanner(catalog).plan(
                TameworkActivationEvidence.builder()
                        .publicCapability(module, "a|b", "c")
                        .publicCapability(module, "a", "b|c")
                        .availableCapability("a|b")
                        .availableCapability("a")
                        .build()
        );

        assertEquals(2, plan.reasonsFor(module).size());
    }

    @Test
    void aReloadThatChangesTopologyRequiresRestart() {
        TameworkRuntimeModule module = TameworkRuntimeModule.of("test-reload");
        TameworkRuntimeModuleCatalog catalog = new TameworkRuntimeModuleCatalog(List.of(
                TameworkRuntimeModuleDescriptor.of(module)
        ));
        TameworkRuntimeActivationPlanner planner = new TameworkRuntimeActivationPlanner(catalog);
        TameworkRuntimeActivationPlan startup = planner.plan(TameworkActivationEvidence.empty());
        TameworkRuntimeActivationPlan candidate = planner.plan(
                TameworkActivationEvidence.builder().content(module, "new-config").build()
        );
        TameworkRuntimeActivationPlan sameTopology = planner.plan(
                TameworkActivationEvidence.builder().content(module, "same-topology").build()
        );

        assertEquals(TameworkTopologyComparison.RESTART_REQUIRED,
                planner.compare(startup, candidate));
        assertEquals(TameworkTopologyComparison.UNCHANGED,
                planner.compare(candidate, sameTopology));
        assertEquals(Set.of(module), planner.changedModules(startup, candidate));
        assertTrue(planner.changedModules(candidate, sameTopology).isEmpty());
    }

    @Test
    void catalogRejectsDuplicateUnknownAndCyclicDependencies() {
        TameworkRuntimeModule first = TameworkRuntimeModule.of("test-first");
        TameworkRuntimeModule second = TameworkRuntimeModule.of("test-second");
        TameworkRuntimeModule unknown = TameworkRuntimeModule.of("test-unknown");

        assertThrows(IllegalArgumentException.class, () -> new TameworkRuntimeModuleCatalog(List.of(
                TameworkRuntimeModuleDescriptor.of(first),
                TameworkRuntimeModuleDescriptor.of(first)
        )));
        assertThrows(IllegalArgumentException.class, () -> new TameworkRuntimeModuleCatalog(List.of(
                TameworkRuntimeModuleDescriptor.of(first, unknown)
        )));
        assertThrows(IllegalArgumentException.class, () -> new TameworkRuntimeModuleCatalog(List.of(
                TameworkRuntimeModuleDescriptor.of(first, second),
                TameworkRuntimeModuleDescriptor.of(second, first)
        )));
    }
}
