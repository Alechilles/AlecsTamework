package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwAttachmentMigrationConfig;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Applies configured attachment migrations. Backfill rules only fill missing target slots; linked rules
 * always set the target slot from the source slot so paired attachments (such as Fluff and Wool) match.
 */
public final class CompanionAttachmentMigrationService {
    private CompanionAttachmentMigrationService() {
    }

    public static Map<String, String> applyConfiguredMigrations(
            @Nullable TwAttachmentMigrationConfig config,
            @Nullable Map<String, String> selections,
            @Nullable Map<String, Set<String>> attachmentOptions) {
        Map<String, String> baseSelections = CompanionModelAttachmentService.sanitizeAttachmentSelections(selections);
        if (baseSelections.isEmpty()
                || config == null
                || !config.isEnabled()
                || attachmentOptions == null
                || attachmentOptions.isEmpty()) {
            return baseSelections;
        }

        HashMap<String, String> migrated = null;
        for (TwAttachmentMigrationConfig.Rule rule : config.getRules()) {
            if (rule == null) {
                continue;
            }
            String sourceSlot = rule.getSourceSlot();
            String targetSlot = rule.getTargetSlot();
            boolean linked = rule.isLinked();
            if (sourceSlot == null || targetSlot == null || (!linked && baseSelections.containsKey(targetSlot))) {
                continue;
            }
            // Linked rules read earlier results so a chain of links resolves in declaration order.
            Map<String, String> sourceSelections = linked && migrated != null ? migrated : baseSelections;
            String sourceValue = sourceSelections.get(sourceSlot);
            if (sourceValue == null || sourceValue.isBlank()) {
                continue;
            }
            Set<String> sourceOptions = attachmentOptions.get(sourceSlot);
            if (sourceOptions == null || !sourceOptions.contains(sourceValue)) {
                continue;
            }
            String targetValue = rule.resolveTargetValue(sourceValue);
            if (targetValue == null || targetValue.isBlank()) {
                continue;
            }
            Set<String> targetOptions = attachmentOptions.get(targetSlot);
            if (targetOptions == null || !targetOptions.contains(targetValue)) {
                continue;
            }
            Map<String, String> currentSelections = migrated != null ? migrated : baseSelections;
            if (targetValue.equals(currentSelections.get(targetSlot))) {
                continue;
            }
            if (migrated == null) {
                migrated = new HashMap<>(baseSelections);
            }
            migrated.put(targetSlot, targetValue);
        }

        return migrated == null ? baseSelections : Collections.unmodifiableMap(migrated);
    }
}
