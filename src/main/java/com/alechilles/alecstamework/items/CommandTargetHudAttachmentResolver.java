package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.attachments.AttachmentDisplayResolver;
import com.alechilles.alecstamework.npc.attachments.ResolvedAttachmentDisplay;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** Resolves compact attachment rows for the command target HUD. */
final class CommandTargetHudAttachmentResolver {
    private static final int DEFAULT_MAX_ROWS = 3;

    private final AttachmentDisplaySource resolver;

    CommandTargetHudAttachmentResolver() {
        this(AttachmentDisplayResolver.ASSET_BACKED::resolveAll);
    }

    CommandTargetHudAttachmentResolver(AttachmentDisplaySource resolver) {
        this.resolver = resolver != null ? resolver : AttachmentDisplayResolver.ASSET_BACKED::resolveAll;
    }

    List<CommandTargetHudViewModel.AttachmentRow> resolveRows(@Nullable String roleId,
                                                              @Nullable String modelId,
                                                              @Nullable Map<String, String> attachments) {
        return resolveRows(roleId, modelId, attachments, DEFAULT_MAX_ROWS);
    }

    List<CommandTargetHudViewModel.AttachmentRow> resolveRows(@Nullable String roleId,
                                                              @Nullable String modelId,
                                                              @Nullable Map<String, String> attachments,
                                                              int maxRows) {
        if (attachments == null || attachments.isEmpty() || maxRows <= 0) {
            return List.of();
        }
        List<ResolvedAttachmentDisplay> displays = resolver.resolveAll(roleId, modelId, attachments);
        if (displays == null || displays.isEmpty()) {
            return List.of();
        }
        ArrayList<CommandTargetHudViewModel.AttachmentRow> rows = new ArrayList<>();
        for (ResolvedAttachmentDisplay display : displays) {
            if (display == null || rows.size() >= maxRows) {
                break;
            }
            rows.add(new CommandTargetHudViewModel.AttachmentRow(
                    safe(display.setLabel(), display.setId()),
                    safe(display.valueLabel(), display.valueId())
            ));
        }
        return rows;
    }

    private String safe(@Nullable String primary, @Nullable String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback == null || fallback.isBlank() ? "Unknown" : fallback;
    }

    @FunctionalInterface
    interface AttachmentDisplaySource {
        List<ResolvedAttachmentDisplay> resolveAll(@Nullable String roleId,
                                                   @Nullable String modelId,
                                                   @Nullable Map<String, String> attachments);
    }
}
