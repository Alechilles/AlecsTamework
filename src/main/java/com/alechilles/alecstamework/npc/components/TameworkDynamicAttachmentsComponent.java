package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Persists reversible dynamic attachment overlays currently applied by WhileMatching rules.
 */
public final class TameworkDynamicAttachmentsComponent implements Component<EntityStore> {
    private static final ActiveSlot[] EMPTY_ACTIVE_SLOTS = new ActiveSlot[0];
    private static final ArrayCodec<ActiveSlot> ACTIVE_SLOT_ARRAY_CODEC =
            new ArrayCodec<>(ActiveSlot.CODEC, ActiveSlot[]::new);

    public static final BuilderCodec<TameworkDynamicAttachmentsComponent> CODEC = BuilderCodec.builder(
            TameworkDynamicAttachmentsComponent.class,
            TameworkDynamicAttachmentsComponent::new
    )
        .append(
            new KeyedCodec<>("ActiveSlots", ACTIVE_SLOT_ARRAY_CODEC),
            TameworkDynamicAttachmentsComponent::setActiveSlots,
            TameworkDynamicAttachmentsComponent::getActiveSlots
        )
        .add()
        .build();

    private ActiveSlot[] activeSlots = EMPTY_ACTIVE_SLOTS;

    public TameworkDynamicAttachmentsComponent() {
    }

    public TameworkDynamicAttachmentsComponent(@Nullable ActiveSlot[] activeSlots) {
        setActiveSlots(activeSlots);
    }

    @Nullable
    public static ComponentType<EntityStore, TameworkDynamicAttachmentsComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getDynamicAttachmentsComponentType() : null;
    }

    public ActiveSlot[] getActiveSlots() {
        return cloneSlots(activeSlots);
    }

    public void setActiveSlots(@Nullable ActiveSlot[] activeSlots) {
        this.activeSlots = sanitizeActiveSlots(activeSlots);
    }

    public boolean hasActiveSlots() {
        return activeSlots != null && activeSlots.length > 0;
    }

    @Override
    public TameworkDynamicAttachmentsComponent clone() {
        return new TameworkDynamicAttachmentsComponent(activeSlots);
    }

    private static ActiveSlot[] sanitizeActiveSlots(@Nullable ActiveSlot[] input) {
        if (input == null || input.length == 0) {
            return EMPTY_ACTIVE_SLOTS;
        }
        List<ActiveSlot> cleaned = new ArrayList<>(input.length);
        for (ActiveSlot slot : input) {
            if (!isValid(slot)) {
                continue;
            }
            cleaned.add(slot.clone());
        }
        return cleaned.isEmpty() ? EMPTY_ACTIVE_SLOTS : cleaned.toArray(new ActiveSlot[0]);
    }

    private static boolean isValid(@Nullable ActiveSlot slot) {
        return slot != null
                && slot.slot != null
                && !slot.slot.isBlank()
                && slot.appliedValue != null
                && !slot.appliedValue.isBlank()
                && slot.ruleKey != null
                && !slot.ruleKey.isBlank();
    }

    private static ActiveSlot[] cloneSlots(@Nullable ActiveSlot[] input) {
        if (input == null || input.length == 0) {
            return EMPTY_ACTIVE_SLOTS;
        }
        ActiveSlot[] cloned = new ActiveSlot[input.length];
        for (int i = 0; i < input.length; i++) {
            cloned[i] = input[i] == null ? null : input[i].clone();
        }
        return cloned;
    }

    /** Single reversible attachment overlay slot applied by a dynamic attachment rule. */
    public static final class ActiveSlot {
        public static final BuilderCodec<ActiveSlot> CODEC = BuilderCodec.builder(
                ActiveSlot.class,
                ActiveSlot::new
        )
            .append(
                new KeyedCodec<>("Slot", Codec.STRING),
                ActiveSlot::setSlot,
                ActiveSlot::getSlot
            )
            .add()
            .append(
                new KeyedCodec<>("PreviousValue", Codec.STRING),
                ActiveSlot::setPreviousValue,
                ActiveSlot::getPreviousValue
            )
            .add()
            .append(
                new KeyedCodec<>("HasPreviousValue", Codec.BOOLEAN),
                ActiveSlot::setHasPreviousValue,
                ActiveSlot::isHasPreviousValue
            )
            .add()
            .append(
                new KeyedCodec<>("AppliedValue", Codec.STRING),
                ActiveSlot::setAppliedValue,
                ActiveSlot::getAppliedValue
            )
            .add()
            .append(
                new KeyedCodec<>("RuleKey", Codec.STRING),
                ActiveSlot::setRuleKey,
                ActiveSlot::getRuleKey
            )
            .add()
            .build();

        private String slot;
        @Nullable
        private String previousValue;
        private boolean hasPreviousValue;
        private String appliedValue;
        private String ruleKey;

        public ActiveSlot() {
        }

        public ActiveSlot(String slot,
                          @Nullable String previousValue,
                          boolean hasPreviousValue,
                          String appliedValue,
                          String ruleKey) {
            this.slot = slot;
            this.hasPreviousValue = hasPreviousValue;
            this.previousValue = hasPreviousValue ? previousValue : null;
            this.appliedValue = appliedValue;
            this.ruleKey = ruleKey;
        }

        public String getSlot() {
            return slot;
        }

        public void setSlot(String slot) {
            this.slot = slot;
        }

        @Nullable
        public String getPreviousValue() {
            return previousValue;
        }

        public void setPreviousValue(@Nullable String previousValue) {
            this.previousValue = previousValue;
        }

        public boolean isHasPreviousValue() {
            return hasPreviousValue;
        }

        public boolean getHasPreviousValue() {
            return hasPreviousValue;
        }

        public void setHasPreviousValue(boolean hasPreviousValue) {
            this.hasPreviousValue = hasPreviousValue;
            if (!hasPreviousValue) {
                previousValue = null;
            }
        }

        public String getAppliedValue() {
            return appliedValue;
        }

        public void setAppliedValue(String appliedValue) {
            this.appliedValue = appliedValue;
        }

        public String getRuleKey() {
            return ruleKey;
        }

        public void setRuleKey(String ruleKey) {
            this.ruleKey = ruleKey;
        }

        @Override
        public ActiveSlot clone() {
            return new ActiveSlot(slot, previousValue, hasPreviousValue, appliedValue, ruleKey);
        }
    }
}
