package com.alechilles.alecstamework.config.assets;

import java.lang.reflect.Field;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests MaxActive defaults and inheritance behavior for command-item configs. */
class TwCommandItemConfigMaxActiveTest {

    @Test
    void maxActiveDefaultsToZeroWhenUnset() {
        TwCommandItemConfig config = new TwCommandItemConfig();

        assertEquals(0, config.getMaxActive());
    }

    @Test
    void maxActiveInheritsFromParentWhenNotExplicit() throws Exception {
        TwCommandItemConfig parent = new TwCommandItemConfig();
        TwCommandItemConfig child = new TwCommandItemConfig();
        setField(parent, "maxActive", 3);
        setField(child, "maxActive", 0);

        child.inheritMissingTopLevelFrom(parent, Set.of());

        assertEquals(3, child.getMaxActive());
    }

    @Test
    void maxActiveDoesNotInheritWhenExplicitlyAuthored() throws Exception {
        TwCommandItemConfig parent = new TwCommandItemConfig();
        TwCommandItemConfig child = new TwCommandItemConfig();
        setField(parent, "maxActive", 3);
        setField(child, "maxActive", 2);

        child.inheritMissingTopLevelFrom(parent, Set.of("MaxActive"));

        assertEquals(2, child.getMaxActive());
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
