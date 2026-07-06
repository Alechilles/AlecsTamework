package com.alechilles.alecstamework.npc.dynamicattachments;

import com.alechilles.alecstamework.config.assets.TwDynamicAttachmentsConfig;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicAttachmentConditionEvaluatorTest {
    @Test
    void displayNameEqualsUsesDefaultIgnoreCase() throws Exception {
        TwDynamicAttachmentsConfig.Condition condition = condition("DisplayNameEquals");
        setField(condition, "value", "mittens");
        DynamicAttachmentNpcSnapshot snapshot = DynamicAttachmentNpcSnapshot.builder()
                .displayName("Mittens")
                .build();

        assertTrue(DynamicAttachmentConditionEvaluator.matches(condition, snapshot));
    }

    @Test
    void displayNameEqualsHonorsCaseSensitiveMismatch() throws Exception {
        TwDynamicAttachmentsConfig.Condition condition = condition("DisplayNameEquals");
        setField(condition, "value", "mittens");
        setField(condition, "ignoreCase", false);
        DynamicAttachmentNpcSnapshot snapshot = DynamicAttachmentNpcSnapshot.builder()
                .displayName("Mittens")
                .build();

        assertFalse(DynamicAttachmentConditionEvaluator.matches(condition, snapshot));
    }

    @Test
    void ownerPresentUsesExpectedFalse() throws Exception {
        TwDynamicAttachmentsConfig.Condition condition = condition("Owner Present");
        setField(condition, "expected", Boolean.FALSE);
        DynamicAttachmentNpcSnapshot snapshot = DynamicAttachmentNpcSnapshot.builder()
                .ownerPresent(false)
                .build();

        assertTrue(DynamicAttachmentConditionEvaluator.matches(condition, snapshot));
    }

    @Test
    void comparesNeedThresholdsByNormalizedNeedId() throws Exception {
        DynamicAttachmentNpcSnapshot snapshot = DynamicAttachmentNpcSnapshot.builder()
                .need("HUNGER", 0.25)
                .need("water", 0.75)
                .build();

        assertTrue(DynamicAttachmentConditionEvaluator.matches(numberedNeed("Need-Below", "hunger", 0.5), snapshot));
        assertTrue(DynamicAttachmentConditionEvaluator.matches(numberedNeed("NeedAtLeast", "WATER", 0.5), snapshot));
    }

    @Test
    void evaluatesTraitPresenceAndExactValue() throws Exception {
        DynamicAttachmentNpcSnapshot snapshot = DynamicAttachmentNpcSnapshot.builder()
                .trait("Brave", 2.0)
                .build();

        TwDynamicAttachmentsConfig.Condition present = condition("TraitPresent");
        setField(present, "traitId", "brave");
        TwDynamicAttachmentsConfig.Condition exact = condition("Trait_Value");
        setField(exact, "traitId", "BRAVE");
        setField(exact, "number", 2.0);
        TwDynamicAttachmentsConfig.Condition mismatch = condition("TraitValue");
        setField(mismatch, "traitId", "brave");
        setField(mismatch, "number", 2.5);

        assertTrue(DynamicAttachmentConditionEvaluator.matches(present, snapshot));
        assertTrue(DynamicAttachmentConditionEvaluator.matches(exact, snapshot));
        assertFalse(DynamicAttachmentConditionEvaluator.matches(mismatch, snapshot));
    }

    @Test
    void comparesHappinessThresholds() throws Exception {
        DynamicAttachmentNpcSnapshot snapshot = DynamicAttachmentNpcSnapshot.builder()
                .happiness(0.6)
                .build();

        assertTrue(DynamicAttachmentConditionEvaluator.matches(numbered("HappinessAtLeast", 0.6), snapshot));
        assertTrue(DynamicAttachmentConditionEvaluator.matches(numbered("Happiness Below", 0.7), snapshot));
        assertFalse(DynamicAttachmentConditionEvaluator.matches(numbered("HappinessBelow", 0.6), snapshot));
    }

    @Test
    void commandStateEqualsUsesStateKeyAndExpectedValue() throws Exception {
        TwDynamicAttachmentsConfig.Condition condition = condition("CommandStateEquals");
        setField(condition, "state", "mode");
        setField(condition, "value", "follow");
        DynamicAttachmentNpcSnapshot snapshot = DynamicAttachmentNpcSnapshot.builder()
                .commandState("MODE", "Follow")
                .build();

        assertTrue(DynamicAttachmentConditionEvaluator.matches(condition, snapshot));
    }

    @Test
    void genderLifeStageAndTamedStateUseConfiguredValues() throws Exception {
        DynamicAttachmentNpcSnapshot snapshot = DynamicAttachmentNpcSnapshot.builder()
                .gender("Female")
                .lifeStage("Adult")
                .tamed(true)
                .build();

        assertTrue(DynamicAttachmentConditionEvaluator.matches(valued("gender", "female"), snapshot));
        assertTrue(DynamicAttachmentConditionEvaluator.matches(valued("life-stage", "adult"), snapshot));
        assertTrue(DynamicAttachmentConditionEvaluator.matches(expected("tamed_state", true), snapshot));
    }

    @Test
    void missingValuesReturnFalse() throws Exception {
        DynamicAttachmentNpcSnapshot snapshot = DynamicAttachmentNpcSnapshot.builder().build();

        assertFalse(DynamicAttachmentConditionEvaluator.matches(valued("DisplayNameEquals", "Mittens"), snapshot));
        assertFalse(DynamicAttachmentConditionEvaluator.matches(numberedNeed("NeedAtLeast", "hunger", 0.5), snapshot));
        assertFalse(DynamicAttachmentConditionEvaluator.matches(numbered("TraitValue", 1.0), snapshot));
    }

    @Test
    void unknownOrBlankTypeReturnsFalse() throws Exception {
        DynamicAttachmentNpcSnapshot snapshot = DynamicAttachmentNpcSnapshot.builder()
                .displayName("Mittens")
                .build();

        assertFalse(DynamicAttachmentConditionEvaluator.matches(condition("NotACondition"), snapshot));
        assertFalse(DynamicAttachmentConditionEvaluator.matches(condition("   "), snapshot));
    }

    private static TwDynamicAttachmentsConfig.Condition valued(String type, String value) throws Exception {
        TwDynamicAttachmentsConfig.Condition condition = condition(type);
        setField(condition, "value", value);
        return condition;
    }

    private static TwDynamicAttachmentsConfig.Condition expected(String type, boolean expected) throws Exception {
        TwDynamicAttachmentsConfig.Condition condition = condition(type);
        setField(condition, "expected", expected);
        return condition;
    }

    private static TwDynamicAttachmentsConfig.Condition numbered(String type, double number) throws Exception {
        TwDynamicAttachmentsConfig.Condition condition = condition(type);
        setField(condition, "number", number);
        return condition;
    }

    private static TwDynamicAttachmentsConfig.Condition numberedNeed(String type, String need, double number)
            throws Exception {
        TwDynamicAttachmentsConfig.Condition condition = numbered(type, number);
        setField(condition, "need", need);
        return condition;
    }

    private static TwDynamicAttachmentsConfig.Condition condition(String type) throws Exception {
        TwDynamicAttachmentsConfig.Condition condition = new TwDynamicAttachmentsConfig.Condition();
        setField(condition, "type", type);
        return condition;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
