package com.alechilles.alecstamework.npc.dynamicattachments;

import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkDynamicAttachmentsComponent;
import com.alechilles.alecstamework.npc.dynamicattachments.DynamicAttachmentResolution.TemporaryAttachment;
import com.alechilles.alecstamework.npc.progression.CompanionModelAttachmentService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Pure attachment-state helpers for applying persistent and reversible dynamic attachment selections.
 */
public final class DynamicAttachmentApplicationService {
    private DynamicAttachmentApplicationService() {
    }

    public static Map<String, String> mergePermanent(@Nullable Map<String, String> current,
                                                     @Nullable Map<String, String> permanent) {
        HashMap<String, String> merged = mutableSanitized(current);
        putCleanEntries(merged, permanent);
        return immutable(merged);
    }

    public static OverlayMerge mergeTemporary(@Nullable Map<String, String> current,
                                              @Nullable TameworkDynamicAttachmentsComponent overlay,
                                              @Nullable Map<String, TemporaryAttachment> temporary) {
        HashMap<String, String> attachments = mutableSanitized(current);
        Map<String, TemporaryAttachment> activeTemporary = sanitizedTemporary(temporary);
        HashMap<String, TameworkDynamicAttachmentsComponent.ActiveSlot> existingSlots = activeSlotsBySlot(overlay);
        List<String> sortedSlots = sortedKeys(activeTemporary);
        List<TameworkDynamicAttachmentsComponent.ActiveSlot> activeSlots = new ArrayList<>(sortedSlots.size());

        for (String slot : sortedSlots) {
            TemporaryAttachment selected = activeTemporary.get(slot);
            TameworkDynamicAttachmentsComponent.ActiveSlot existing = existingSlots.get(slot);
            TameworkDynamicAttachmentsComponent.ActiveSlot activeSlot;
            if (matches(existing, selected)) {
                activeSlot = existing.clone();
            } else {
                String previousValue = attachments.get(slot);
                activeSlot = new TameworkDynamicAttachmentsComponent.ActiveSlot(
                        slot,
                        previousValue,
                        previousValue != null,
                        selected.value(),
                        selected.ruleKey()
                );
            }
            attachments.put(slot, selected.value());
            activeSlots.add(activeSlot);
        }

        return new OverlayMerge(
                immutable(attachments),
                new TameworkDynamicAttachmentsComponent(activeSlots.toArray(new TameworkDynamicAttachmentsComponent.ActiveSlot[0]))
        );
    }

    public static OverlayMerge restoreInactiveTemporarySlots(@Nullable Map<String, String> current,
                                                            @Nullable TameworkDynamicAttachmentsComponent overlay,
                                                            @Nullable Map<String, TemporaryAttachment> activeTemporary) {
        HashMap<String, String> attachments = mutableSanitized(current);
        Map<String, TemporaryAttachment> active = sanitizedTemporary(activeTemporary);
        TameworkDynamicAttachmentsComponent.ActiveSlot[] slots = activeSlots(overlay);
        List<TameworkDynamicAttachmentsComponent.ActiveSlot> retained = new ArrayList<>(slots.length);

        for (TameworkDynamicAttachmentsComponent.ActiveSlot slot : slots) {
            if (!isValid(slot)) {
                continue;
            }
            TemporaryAttachment selected = active.get(slot.getSlot());
            if (matches(slot, selected)) {
                retained.add(slot.clone());
                continue;
            }
            restoreStaleSlot(attachments, slot);
        }

        return new OverlayMerge(
                immutable(attachments),
                new TameworkDynamicAttachmentsComponent(retained.toArray(new TameworkDynamicAttachmentsComponent.ActiveSlot[0]))
        );
    }

    public static ApplyResult applyResolution(@Nullable TameworkAttachmentsComponent stored,
                                              @Nullable TameworkDynamicAttachmentsComponent overlay,
                                              @Nullable DynamicAttachmentResolution resolution) {
        Map<String, String> storedAttachments = stored == null ? Collections.emptyMap() : stored.getAttachmentIds();
        Map<String, String> permanent = resolution == null
                ? Collections.emptyMap()
                : resolution.permanentAttachments();
        Map<String, TemporaryAttachment> temporary = resolution == null
                ? Collections.emptyMap()
                : resolution.temporaryAttachments();

        OverlayMerge restored = restoreInactiveTemporarySlots(storedAttachments, overlay, temporary);
        Map<String, String> withPermanent = mergePermanent(restored.attachments(), permanent);
        OverlayMerge applied = mergeTemporary(withPermanent, restored.overlay(), temporary);

        TameworkAttachmentsComponent nextAttachments =
                new TameworkAttachmentsComponent(stored == null ? null : stored.getConfigId(), applied.attachments());
        TameworkDynamicAttachmentsComponent nextOverlay = applied.overlay();
        boolean changed = !sameAttachments(storedAttachments, nextAttachments.getAttachmentIds())
                || !sameSlots(activeSlots(overlay), nextOverlay.getActiveSlots());

        return new ApplyResult(nextAttachments, nextOverlay, changed);
    }

    public static Map<String, String> filterSupportedSelections(
            @Nullable Map<String, String> selections,
            @Nullable Map<String, Set<String>> attachmentOptions) {
        return CompanionModelAttachmentService.filterAttachmentSelections(selections, attachmentOptions);
    }

    private static void restoreStaleSlot(HashMap<String, String> attachments,
                                         TameworkDynamicAttachmentsComponent.ActiveSlot slot) {
        String currentValue = attachments.get(slot.getSlot());
        if (!Objects.equals(currentValue, slot.getAppliedValue())) {
            return;
        }
        if (slot.isHasPreviousValue() && isNonBlank(slot.getPreviousValue())) {
            attachments.put(slot.getSlot(), slot.getPreviousValue());
        } else {
            attachments.remove(slot.getSlot());
        }
    }

    private static HashMap<String, TameworkDynamicAttachmentsComponent.ActiveSlot> activeSlotsBySlot(
            @Nullable TameworkDynamicAttachmentsComponent overlay) {
        HashMap<String, TameworkDynamicAttachmentsComponent.ActiveSlot> slotsBySlot = new HashMap<>();
        TameworkDynamicAttachmentsComponent.ActiveSlot[] slots = activeSlots(overlay);
        for (TameworkDynamicAttachmentsComponent.ActiveSlot slot : slots) {
            if (isValid(slot)) {
                slotsBySlot.put(slot.getSlot(), slot.clone());
            }
        }
        return slotsBySlot;
    }

    private static TameworkDynamicAttachmentsComponent.ActiveSlot[] activeSlots(
            @Nullable TameworkDynamicAttachmentsComponent overlay) {
        return overlay == null
                ? new TameworkDynamicAttachmentsComponent.ActiveSlot[0]
                : overlay.getActiveSlots();
    }

    private static boolean sameAttachments(@Nullable Map<String, String> left,
                                           @Nullable Map<String, String> right) {
        return mutableSanitized(left).equals(mutableSanitized(right));
    }

    private static boolean sameSlots(TameworkDynamicAttachmentsComponent.ActiveSlot[] left,
                                     TameworkDynamicAttachmentsComponent.ActiveSlot[] right) {
        if (left.length != right.length) {
            return false;
        }
        for (int i = 0; i < left.length; i++) {
            if (!sameSlot(left[i], right[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameSlot(@Nullable TameworkDynamicAttachmentsComponent.ActiveSlot left,
                                    @Nullable TameworkDynamicAttachmentsComponent.ActiveSlot right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return Objects.equals(left.getSlot(), right.getSlot())
                && Objects.equals(left.getPreviousValue(), right.getPreviousValue())
                && left.isHasPreviousValue() == right.isHasPreviousValue()
                && Objects.equals(left.getAppliedValue(), right.getAppliedValue())
                && Objects.equals(left.getRuleKey(), right.getRuleKey());
    }

    private static boolean matches(@Nullable TameworkDynamicAttachmentsComponent.ActiveSlot slot,
                                   @Nullable TemporaryAttachment selected) {
        return slot != null
                && selected != null
                && Objects.equals(slot.getAppliedValue(), selected.value())
                && Objects.equals(slot.getRuleKey(), selected.ruleKey());
    }

    private static boolean isValid(@Nullable TameworkDynamicAttachmentsComponent.ActiveSlot slot) {
        return slot != null
                && isNonBlank(slot.getSlot())
                && isNonBlank(slot.getAppliedValue())
                && isNonBlank(slot.getRuleKey());
    }

    private static Map<String, TemporaryAttachment> sanitizedTemporary(
            @Nullable Map<String, TemporaryAttachment> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyMap();
        }
        HashMap<String, TemporaryAttachment> cleaned = new HashMap<>();
        for (Map.Entry<String, TemporaryAttachment> entry : raw.entrySet()) {
            if (entry == null || !isNonBlank(entry.getKey())) {
                continue;
            }
            TemporaryAttachment attachment = entry.getValue();
            if (attachment == null || !isNonBlank(attachment.value()) || !isNonBlank(attachment.ruleKey())) {
                continue;
            }
            cleaned.put(entry.getKey(), attachment);
        }
        return cleaned.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(cleaned);
    }

    private static HashMap<String, String> mutableSanitized(@Nullable Map<String, String> raw) {
        HashMap<String, String> cleaned = new HashMap<>();
        putCleanEntries(cleaned, raw);
        return cleaned;
    }

    private static void putCleanEntries(HashMap<String, String> target,
                                        @Nullable Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (entry == null) {
                continue;
            }
            String key = entry.getKey();
            String value = entry.getValue();
            if (isNonBlank(key) && isNonBlank(value)) {
                target.put(key, value);
            }
        }
    }

    private static List<String> sortedKeys(Map<String, TemporaryAttachment> values) {
        if (values.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<String> keys = new ArrayList<>(values.keySet());
        Collections.sort(keys);
        return keys;
    }

    private static Map<String, String> immutable(HashMap<String, String> values) {
        if (values.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new HashMap<>(values));
    }

    private static boolean isNonBlank(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    public record OverlayMerge(
            Map<String, String> attachments,
            TameworkDynamicAttachmentsComponent overlay) {
        public OverlayMerge {
            attachments = immutable(mutableSanitized(attachments));
            overlay = overlay == null ? new TameworkDynamicAttachmentsComponent(null) : overlay.clone();
        }
    }

    public record ApplyResult(
            TameworkAttachmentsComponent attachments,
            TameworkDynamicAttachmentsComponent overlay,
            boolean changed) {
        public ApplyResult {
            attachments = attachments == null
                    ? new TameworkAttachmentsComponent(null, null)
                    : new TameworkAttachmentsComponent(attachments.getConfigId(), attachments.getAttachmentIds());
            overlay = overlay == null ? new TameworkDynamicAttachmentsComponent(null) : overlay.clone();
        }
    }
}
