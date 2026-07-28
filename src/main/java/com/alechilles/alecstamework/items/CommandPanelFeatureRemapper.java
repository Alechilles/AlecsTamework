package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;

/** Maps durable roster feature cards to the UUID rendered by the panel. */
final class CommandPanelFeatureRemapper {
    private CommandPanelFeatureRemapper() { }

    static Map<UUID, CommandPanelFeaturePresentation> remap(
            Map<UUID, CommandPanelFeaturePresentation> features,
            @Nullable CommandLinkedPanelEntryService.ResolvedEntries entries) {
        if (features == null || features.isEmpty() || entries == null
                || entries.renderedIds().isEmpty()) return features == null ? Map.of() : features;
        LinkedHashMap<UUID, CommandPanelFeaturePresentation> result =
                new LinkedHashMap<>(features);
        for (Map.Entry<UUID, UUID> identity : entries.renderedIds().entrySet()) {
            CommandPanelFeaturePresentation feature = features.get(identity.getKey());
            if (feature != null && identity.getValue() != null) {
                result.put(identity.getValue(), feature);
            }
        }
        return Map.copyOf(result);
    }
}
