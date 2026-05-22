package com.alechilles.alecstamework.npc.attachments;

import javax.annotation.Nullable;

/**
 * Player-facing display data for one raw model attachment selection.
 */
public record ResolvedAttachmentDisplay(
        String setId,
        String setLabel,
        @Nullable String valueId,
        @Nullable String valueLabel
) {
    public String toTooltipLine() {
        if (valueLabel == null || valueLabel.isBlank()) {
            return setLabel;
        }
        return setLabel + ": " + valueLabel;
    }
}
