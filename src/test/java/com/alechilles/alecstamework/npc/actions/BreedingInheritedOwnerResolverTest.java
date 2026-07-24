package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Verifies live offspring owner inheritance, including explicitly unowned children. */
class BreedingInheritedOwnerResolverTest {
    @Test
    void inheritedOwnerUsesFirstOwnedParent() {
        UUID ownerA = UUID.randomUUID();
        BreedingOffspringProgressionService.OwnerSnapshot inherited =
                BreedingInheritedOwnerResolver.resolve(
                        null,
                        "child",
                        new BreedingOffspringProgressionService.OwnerSnapshot(ownerA, "A"),
                        new BreedingOffspringProgressionService.OwnerSnapshot(UUID.randomUUID(), "B")
                );

        assertEquals(ownerA, inherited.ownerId());
        assertEquals("A", inherited.ownerName());
    }

    @Test
    void disabledOwnerInheritanceProducesUnownedChild() throws Exception {
        TwBreedingConfig config = newConfig();
        TwBreedingConfig.InheritanceSettings inheritance = new TwBreedingConfig.InheritanceSettings();
        setField(inheritance, "inheritOwner", false);
        setField(config, "inheritance", inheritance);

        BreedingOffspringProgressionService.OwnerSnapshot inherited =
                BreedingInheritedOwnerResolver.resolve(
                        config,
                        "child",
                        new BreedingOffspringProgressionService.OwnerSnapshot(UUID.randomUUID(), "A"),
                        new BreedingOffspringProgressionService.OwnerSnapshot(UUID.randomUUID(), "B")
                );

        assertNull(inherited.ownerId());
        assertNull(inherited.ownerName());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static TwBreedingConfig newConfig() throws Exception {
        Constructor<TwBreedingConfig> constructor = TwBreedingConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }
}
