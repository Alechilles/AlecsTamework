package com.alechilles.alecstamework.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.PopulationGroupReconciliationView;
import com.alechilles.alecstamework.config.assets.TwPopulationGroupConfig;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupRegistry;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PopulationGroupApiDelegateTest {
    @TempDir Path tempDir;

    @Test
    void exposesWinningDefinitionZeroCountsAndReadiness() throws Exception {
        try (TameworkPersistenceRuntime persistence =
                     TameworkPersistenceRuntime.initialize(tempDir, null)) {
            PopulationGroupRegistry registry = new PopulationGroupRegistry();
            assertTrue(registry.replace(List.of(group()), 7L).applied());
            PopulationGroupApiDelegate api = new PopulationGroupApiDelegate(
                    registry, persistence.getPopulationGroupRepository(), () -> true);

            assertEquals("hydragon:test", api.getDefinition("hydragon:test").orElseThrow().groupId());
            assertEquals(1, api.resolveForRole("Test_Role").size());
            var counts = api.getCounts(UUID.randomUUID(), "hydragon:test", null).orElseThrow();
            assertEquals(0, counts.committedOwned());
            assertEquals(0, counts.pendingActive());
            assertEquals(7L, counts.classificationRevision());
            assertEquals(PopulationGroupReconciliationView.Readiness.READY,
                    api.getReconciliationStatus().readiness());
        }
    }

    @Test
    void refusesCountsWhileReconciliationIsPending() throws Exception {
        try (TameworkPersistenceRuntime persistence =
                     TameworkPersistenceRuntime.initialize(tempDir, null)) {
            PopulationGroupRegistry registry = new PopulationGroupRegistry();
            registry.replace(List.of(group()), 1L);
            PopulationGroupApiDelegate api = new PopulationGroupApiDelegate(
                    registry, persistence.getPopulationGroupRepository(), () -> false);
            assertTrue(api.getCounts(UUID.randomUUID(), "hydragon:test", null).isEmpty());
            assertEquals(PopulationGroupReconciliationView.Readiness.RECONCILING,
                    api.getReconciliationStatus().readiness());
        }
    }

    private static TwPopulationGroupConfig group() throws Exception {
        var constructor = TwPopulationGroupConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwPopulationGroupConfig config = constructor.newInstance();
        set(config, "id", "TestGroup");
        set(config, "groupId", "hydragon:test");
        set(config, "roleIds", new String[] { "Test_Role" });
        Object limits = field(config, "limits");
        set(limits, "maxOwnedPerOwner", 2);
        set(limits, "maxActivePerOwner", 1);
        return config;
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
