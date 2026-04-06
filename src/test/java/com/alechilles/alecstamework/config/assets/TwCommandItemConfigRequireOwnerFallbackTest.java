package com.alechilles.alecstamework.config.assets;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests nullable RequireOwner behavior for global fallback support. */
class TwCommandItemConfigRequireOwnerFallbackTest {

    @Test
    void defaultRequireOwnerOverrideIsUnsetAndCanUseFallback() {
        TwCommandItemConfig config = new TwCommandItemConfig();

        assertNull(config.getRequireOwnerOverride());
        assertTrue(config.isRequireOwner());
        assertTrue(config.resolveRequireOwner(true));
        assertFalse(config.resolveRequireOwner(false));
    }

    @Test
    void explicitRequireOwnerOverrideWinsWhenSet() throws Exception {
        TwCommandItemConfig config = new TwCommandItemConfig();
        setField(config, "requireOwner", Boolean.FALSE);

        assertEquals(Boolean.FALSE, config.getRequireOwnerOverride());
        assertFalse(config.isRequireOwner());
        assertFalse(config.resolveRequireOwner(true));
        assertFalse(config.resolveRequireOwner(false));
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
