package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Regression coverage for versioned, role-scoped talent allocation repair. */
class CompanionTalentAllocationReconciliationTest {
    @Test
    void matchingRevisionAndAllocationKeepsPurchasedNodesAndSpentCost() throws Exception {
        TwTalentConfig config = config("talents", 7L,
                talent("base", 1),
                talent("advanced", 2, new String[] {"base"}));
        TameworkTalentsComponent existing = new TameworkTalentsComponent(
                "talents", 3, new String[] {"base", "advanced"}, 7L);

        TameworkTalentsComponent reconciled = CompanionTalentService.reconcileAllocation(existing, config);

        assertNotSame(existing, reconciled);
        assertEquals("talents", reconciled.getConfigId());
        assertEquals(7L, reconciled.getAllocationRevision());
        assertEquals(3, reconciled.getSpentPoints());
        assertArrayEquals(new String[] {"base", "advanced"}, reconciled.getPurchasedTalentIds());
    }

    @Test
    void revisionChangeResetsOtherwiseValidAllocation() throws Exception {
        TwTalentConfig config = config("talents", 8L, talent("base", 1));
        TameworkTalentsComponent existing = new TameworkTalentsComponent(
                "talents", 1, new String[] {"base"}, 7L);

        TameworkTalentsComponent reconciled = CompanionTalentService.reconcileAllocation(existing, config);

        assertEquals("talents", reconciled.getConfigId());
        assertEquals(8L, reconciled.getAllocationRevision());
        assertEquals(0, reconciled.getSpentPoints());
        assertArrayEquals(new String[0], reconciled.getPurchasedTalentIds());
    }

    @Test
    void invalidIdsPrerequisitesAndSpentCostResetAllocation() throws Exception {
        TwTalentConfig config = config("talents", 1L,
                talent("base", 1),
                talent("advanced", 2, new String[] {"base"}));

        assertEmpty(reconcile(new TameworkTalentsComponent(
                "talents", 1, new String[] {"missing"}, 1L), config));
        assertEmpty(reconcile(new TameworkTalentsComponent(
                "talents", 2, new String[] {"advanced"}, 1L), config));
        assertEmpty(reconcile(new TameworkTalentsComponent(
                "talents", 2, new String[] {"base"}, 1L), config));
    }

    @Test
    void missingEnabledConfigPreservesExistingAllocation() throws Exception {
        TameworkTalentsComponent existing = new TameworkTalentsComponent(
                "talents", 4, new String[] {"legacy"}, 9L);

        assertSame(existing, CompanionTalentService.reconcileAllocation(existing, null));

        TwTalentConfig disabled = config("talents", 10L, talent("legacy", 4));
        setField(disabled, "enabled", false);
        assertSame(existing, CompanionTalentService.reconcileAllocation(existing, disabled));
    }

    @Test
    void componentCloneAndRevisionConstructorPersistAllocationRevision() {
        TameworkTalentsComponent original = new TameworkTalentsComponent(
                "talents", 2, new String[] {"base"}, 11L);

        TameworkTalentsComponent clone = original.clone();

        assertEquals(11L, original.getAllocationRevision());
        assertEquals(11L, clone.getAllocationRevision());
        assertArrayEquals(original.getPurchasedTalentIds(), clone.getPurchasedTalentIds());
    }

    private static TameworkTalentsComponent reconcile(TameworkTalentsComponent component,
                                                       TwTalentConfig config) {
        return CompanionTalentService.reconcileAllocation(component, config);
    }

    private static void assertEmpty(TameworkTalentsComponent component) {
        assertEquals(0, component.getSpentPoints());
        assertArrayEquals(new String[0], component.getPurchasedTalentIds());
    }

    private static TwTalentConfig config(String id,
                                         long allocationRevision,
                                         TwTalentConfig.TalentDefinition... talents) throws Exception {
        Constructor<TwTalentConfig> constructor = TwTalentConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwTalentConfig config = constructor.newInstance();
        setField(config, "id", id);
        setField(config, "enabled", true);
        setField(config, "allocationRevision", allocationRevision);
        setField(config, "talents", talents);
        return config;
    }

    private static TwTalentConfig.TalentDefinition talent(String id,
                                                          int pointCost) throws Exception {
        return talent(id, pointCost, new String[0]);
    }

    private static TwTalentConfig.TalentDefinition talent(String id,
                                                          int pointCost,
                                                          String[] prerequisites) throws Exception {
        Constructor<TwTalentConfig.TalentDefinition> constructor =
                TwTalentConfig.TalentDefinition.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwTalentConfig.TalentDefinition talent = constructor.newInstance();
        setField(talent, "id", id);
        setField(talent, "pointCost", pointCost);
        setField(talent, "requiresTalentIds", prerequisites);
        return talent;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
