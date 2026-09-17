package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class BreedingLitterLifecycleFamilyTest {
    @Test
    void queuedBirthRetainsUnnamedFamilyGrowthSettings() throws Exception {
        TwBreedingConfig config = config(null);
        TwBreedingConfig.RoleFamily family = BreedingLitterWorldExecutor.family(config, child());

        assertNotNull(family);
        var lifecycle = config.resolveOffspringLifecycle("lamb");
        assertEquals(0.8, lifecycle.resolveBabyStartScale(family), 0.000001);
        assertEquals(1.2, lifecycle.resolveAdultSwitchScale(family), 0.000001);
        assertEquals(6000, lifecycle.resolveTimeToFullGrownSeconds(family));
    }

    @Test
    void unnamedPlanDoesNotAdoptNewNamedFamily() throws Exception {
        assertNull(BreedingLitterWorldExecutor.family(config("replacement-family"), child()));
    }

    @Test
    void sharedBabyRoleUsesThePlannedAdultFamily() throws Exception {
        TwBreedingConfig config = config(null);
        TwBreedingConfig.RoleFamily sheep = config.resolveLifecycleFamilyForRole("lamb");
        TwBreedingConfig.RoleFamily ram = new TwBreedingConfig.RoleFamily();
        set(ram, "babyRoleId", "lamb");
        set(ram, "adultRoleId", "ram");
        set(ram, "babyStartScale", 0.7);
        set(ram, "timeToFullGrownSeconds", 5400);
        set(config.getOffspringLifecycle(), "families", new TwBreedingConfig.RoleFamily[] { sheep, ram });

        var plan = new BreedingLitterOperation.ChildPlan(
                UUID.randomUUID(), "lamb", "ram", "Male", null, null);
        var family = BreedingLitterWorldExecutor.family(config, plan);

        assertNotNull(family);
        var lifecycle = config.resolveOffspringLifecycle("lamb");
        assertEquals(0.7, lifecycle.resolveBabyStartScale(family), 0.000001);
        assertEquals(5400, lifecycle.resolveTimeToFullGrownSeconds(family));
    }

    @Test
    void queuedBirthRetainsSelectedLineWithinUnnamedFamily() throws Exception {
        TwBreedingConfig config = config(null);
        TwBreedingConfig.RoleFamily root = config.resolveLifecycleFamilyForRole("lamb");
        TwBreedingConfig.RoleLine line = new TwBreedingConfig.RoleLine();
        set(line, "id", "selected-line");
        set(line, "adultRoleId", "sheep");
        set(line, "babyRoleId", "lamb");
        set(line, "babyStartScale", 0.9);
        set(line, "timeToFullGrownSeconds", 7200);
        set(root, "lines", new TwBreedingConfig.RoleLine[] { line });

        var plan = new BreedingLitterOperation.ChildPlan(
                UUID.randomUUID(), "lamb", "sheep", "Male", null, "selected-line");
        var family = BreedingLitterWorldExecutor.family(config, plan);

        assertNotNull(family);
        var lifecycle = config.resolveOffspringLifecycle("lamb");
        assertEquals(0.9, lifecycle.resolveBabyStartScale(family), 0.000001);
        assertEquals(7200, lifecycle.resolveTimeToFullGrownSeconds(family));
    }

    private static BreedingLitterOperation.ChildPlan child() {
        return new BreedingLitterOperation.ChildPlan(
                UUID.randomUUID(), "lamb", "sheep", "Male", null, null);
    }

    private static TwBreedingConfig config(String id) throws Exception {
        var constructor = TwBreedingConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwBreedingConfig config = constructor.newInstance();
        TwBreedingConfig.RoleFamily family = new TwBreedingConfig.RoleFamily();
        set(family, "id", id);
        set(family, "adultRoleId", "sheep");
        set(family, "babyRoleId", "lamb");
        set(family, "babyStartScale", 0.8);
        set(family, "adultSwitchScale", 1.2);
        set(family, "timeToFullGrownSeconds", 6000);
        set(config.getOffspringLifecycle(), "families", new TwBreedingConfig.RoleFamily[] { family });
        return config;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
