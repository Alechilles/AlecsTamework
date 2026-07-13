package com.alechilles.alecstamework.npc.actions;

import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Describes one validated attachment equip, replacement, or removal transaction. */
record AttachmentExchangePlan(@Nonnull String slotId,
                              @Nonnull String currentValue,
                              @Nonnull String targetValue,
                              @Nullable String consumedItemId,
                              @Nullable String refundedItemId,
                              int heldQuantity) {
    static final String NONE_VALUE = "None";

    /** Resolves an exchange only when the live hand, current value, and model options are refundable. */
    @Nullable
    static AttachmentExchangePlan resolve(@Nonnull HeldItemAttachmentMapping mapping,
                                          @Nullable String currentValue,
                                          @Nullable String liveHeldItemId,
                                          int liveHeldQuantity,
                                          @Nullable String capturedHeldItemId,
                                          @Nullable Set<String> supportedValues) {
        if (supportedValues == null || supportedValues.isEmpty()) {
            return null;
        }
        String normalizedCurrent = normalizeCurrent(currentValue, supportedValues);
        if (normalizedCurrent == null) {
            return null;
        }
        if (liveHeldItemId == null) {
            return resolveRemoval(mapping, normalizedCurrent, liveHeldQuantity, capturedHeldItemId, supportedValues);
        }
        return resolveEquip(
                mapping,
                normalizedCurrent,
                liveHeldItemId,
                liveHeldQuantity,
                capturedHeldItemId,
                supportedValues
        );
    }

    private static AttachmentExchangePlan resolveRemoval(@Nonnull HeldItemAttachmentMapping mapping,
                                                         @Nonnull String currentValue,
                                                         int liveHeldQuantity,
                                                         @Nullable String capturedHeldItemId,
                                                         @Nonnull Set<String> supportedValues) {
        if (liveHeldQuantity > 0
                || capturedHeldItemId != null
                || NONE_VALUE.equals(currentValue)
                || !supportedValues.contains(NONE_VALUE)) {
            return null;
        }
        String refundedItemId = mapping.resolveItemId(currentValue);
        return refundedItemId == null
                ? null
                : new AttachmentExchangePlan(
                        mapping.slotId(),
                        currentValue,
                        NONE_VALUE,
                        null,
                        refundedItemId,
                        0
                );
    }

    private static AttachmentExchangePlan resolveEquip(@Nonnull HeldItemAttachmentMapping mapping,
                                                       @Nonnull String currentValue,
                                                       @Nonnull String liveHeldItemId,
                                                       int liveHeldQuantity,
                                                       @Nullable String capturedHeldItemId,
                                                       @Nonnull Set<String> supportedValues) {
        if (liveHeldQuantity < 1 || !liveHeldItemId.equals(capturedHeldItemId)) {
            return null;
        }
        String targetValue = mapping.resolve(liveHeldItemId);
        if (targetValue == null
                || targetValue.equals(currentValue)
                || !supportedValues.contains(targetValue)) {
            return null;
        }
        String refundedItemId = NONE_VALUE.equals(currentValue)
                ? null
                : mapping.resolveItemId(currentValue);
        if (!NONE_VALUE.equals(currentValue) && refundedItemId == null) {
            return null;
        }
        return new AttachmentExchangePlan(
                mapping.slotId(),
                currentValue,
                targetValue,
                liveHeldItemId,
                refundedItemId,
                liveHeldQuantity
        );
    }

    @Nullable
    private static String normalizeCurrent(@Nullable String currentValue,
                                           @Nonnull Set<String> supportedValues) {
        if (currentValue != null && !currentValue.isBlank()) {
            return supportedValues.contains(currentValue) ? currentValue : null;
        }
        return supportedValues.contains(NONE_VALUE) ? NONE_VALUE : null;
    }

    boolean removesAttachment() {
        return consumedItemId == null;
    }

    boolean needsSeparateRefundInsertion() {
        return refundedItemId != null && heldQuantity > 1;
    }
}
