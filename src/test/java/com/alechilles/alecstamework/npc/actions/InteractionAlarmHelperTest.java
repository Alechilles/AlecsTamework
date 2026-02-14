package com.alechilles.alecstamework.npc.actions;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for Interaction alarm helper logic. */
class InteractionAlarmHelperTest {

    @Test
    void missingNpcRefCountsAsUnset() throws Exception {
        ActionTameworkInteract owner = newInteract();
        InteractionAlarmHelper helper = new InteractionAlarmHelper(owner);

        assertTrue(helper.matchesAlarmState(null, null, "TestAlarm", "unset"));
        assertFalse(helper.matchesAlarmState(null, null, "TestAlarm", "active"));
    }

    @Test
    void blankAlarmNameFails() throws Exception {
        ActionTameworkInteract owner = newInteract();
        InteractionAlarmHelper helper = new InteractionAlarmHelper(owner);

        assertFalse(helper.matchesAlarmState(null, null, "  ", "unset"));
        assertFalse(helper.matchesAlarmState(null, null, null, "unset"));
    }

    private static ActionTameworkInteract newInteract() throws Exception {
        Unsafe unsafe = getUnsafe();
        return (ActionTameworkInteract) unsafe.allocateInstance(ActionTameworkInteract.class);
    }

    private static Unsafe getUnsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
