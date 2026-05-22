package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwAttachmentMigrationConfig;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Applies configured legacy attachment migrations without overwriting already selected target slots.
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
            if (sourceSlot == null || targetSlot == null || baseSelections.containsKey(targetSlot)) {
                continue;
            }
            String sourceValue = baseSelections.get(sourceSlot);
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
            if (migrated == null) {
                migrated = new HashMap<>(baseSelections);
            }
            migrated.put(targetSlot, targetValue);
        }

        return migrated == null ? baseSelections : Collections.unmodifiableMap(migrated);
    }
}
