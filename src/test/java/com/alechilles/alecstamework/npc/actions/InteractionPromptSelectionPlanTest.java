package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** Tests prompt interaction pre-classification. */
class InteractionPromptSelectionPlanTest {

    @Test
    void promptPlanSeparatesContextualConditionalAndGenericEntries() {
        InteractionSelector selector = new InteractionSelector(null, null, null, null, "Harvest_Ready");
        TwInteractionConfig.CustomInteraction generic = new TwInteractionConfig.CustomInteraction();
        TwInteractionConfig.FeedInteraction conditional = new TwInteractionConfig.FeedInteraction();
        TwInteractionConfig.HarvestInteraction contextual = new TwInteractionConfig.HarvestInteraction();

        InteractionPromptPlan plan = selector.buildPromptPlan(new TwInteractionConfig.InteractionEntry[] {
                generic,
                conditional,
                contextual
        });

        assertArrayEquals(new int[] { 2 }, plan.contextualIndexes());
        assertArrayEquals(new int[] { 1 }, plan.conditionalIndexes());
        assertArrayEquals(new int[] { 0 }, plan.genericIndexes());
    }

    @Test
    void promptPlanSkipsDisabledEntries() throws Exception {
        InteractionSelector selector = new InteractionSelector(null, null, null, null, "Harvest_Ready");
        TwInteractionConfig.CustomInteraction disabledGeneric = new TwInteractionConfig.CustomInteraction();
        setField(TwInteractionConfig.InteractionEntry.class, disabledGeneric, "enabled", false);
        TwInteractionConfig.CustomInteraction enabledGeneric = new TwInteractionConfig.CustomInteraction();

        InteractionPromptPlan plan = selector.buildPromptPlan(new TwInteractionConfig.InteractionEntry[] {
                disabledGeneric,
                enabledGeneric
        });

        assertArrayEquals(new int[0], plan.contextualIndexes());
        assertArrayEquals(new int[0], plan.conditionalIndexes());
        assertArrayEquals(new int[] { 1 }, plan.genericIndexes());
    }

    private static void setField(Class<?> owner, Object target, String fieldName, Object value) throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
