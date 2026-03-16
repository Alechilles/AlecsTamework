package com.alechilles.alecstamework.npc.actions;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActionTameworkInteractTriggerSourceTest {

    @Test
    void describeTriggerSourceUsesConfiguredLabel() throws Exception {
        ActionTameworkInteract action = newInteract();
        setField(action, "triggerSource", "InteractionContext");

        assertEquals("InteractionContext", action.describeTriggerSource());
    }

    @Test
    void describeTriggerSourceFallsBackWhenUnset() throws Exception {
        ActionTameworkInteract action = newInteract();

        assertEquals("<unspecified>", action.describeTriggerSource());
    }

    private static ActionTameworkInteract newInteract() throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        return (ActionTameworkInteract) unsafe.allocateInstance(ActionTameworkInteract.class);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = ActionTameworkInteract.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
