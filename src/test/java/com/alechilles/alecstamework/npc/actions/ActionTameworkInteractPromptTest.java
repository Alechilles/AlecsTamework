package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests prompt visibility vs interactable state for contextual interactions. */
class ActionTameworkInteractPromptTest {

    @Test
    void hiddenPromptEntriesRemainInteractable() throws Exception {
        ActionTameworkInteractPrompt action = newPrompt();
        TwInteractionConfig.HarvestInteraction entry = new TwInteractionConfig.HarvestInteraction();
        setField(TwInteractionConfig.InteractionEntry.class, entry, "showPrompt", Boolean.FALSE);

        Object promptState = resolvePromptState(
                action,
                new ActionTameworkInteract.ResolvedInteraction(entry, 0, 0, null)
        );

        assertTrue((Boolean) readField(promptState, "interactable"));
        assertFalse((Boolean) readField(promptState, "showPrompt"));
    }

    @Test
    void missingResolvedInteractionIsNotInteractable() throws Exception {
        ActionTameworkInteractPrompt action = newPrompt();

        Object promptState = resolvePromptState(action, null);

        assertFalse((Boolean) readField(promptState, "interactable"));
        assertFalse((Boolean) readField(promptState, "showPrompt"));
    }

    private static ActionTameworkInteractPrompt newPrompt() throws Exception {
        Unsafe unsafe = getUnsafe();
        return (ActionTameworkInteractPrompt) unsafe.allocateInstance(ActionTameworkInteractPrompt.class);
    }

    private static Object resolvePromptState(ActionTameworkInteractPrompt action,
                                             ActionTameworkInteract.ResolvedInteraction resolved) throws Exception {
        Method method = ActionTameworkInteractPrompt.class.getDeclaredMethod(
                "resolvePromptState",
                ActionTameworkInteract.ResolvedInteraction.class
        );
        method.setAccessible(true);
        return method.invoke(action, resolved);
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Class<?> owner, Object target, String fieldName, Object value) throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Unsafe getUnsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
