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
                TameworkRuntimeModuleDescriptor.withRequiredCapabilities(
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

        assertEquals(TameworkTopologyComparison.RESTART_REQUIRED,
                planner.compare(startup, candidate));
        assertEquals(TameworkTopologyComparison.UNCHANGED,
                planner.compare(candidate, planner.plan(
                        TameworkActivationEvidence.builder().content(module, "same-topology").build()
                )));
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
