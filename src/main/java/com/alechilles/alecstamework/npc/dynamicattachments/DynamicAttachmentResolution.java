package com.alechilles.alecstamework.npc.dynamicattachments;

import java.util.Map;
import javax.annotation.Nonnull;

/** Immutable result of resolving dynamic attachment rules for one NPC snapshot. */
public record DynamicAttachmentResolution(
        @Nonnull Map<String, String> permanentAttachments,
        @Nonnull Map<String, TemporaryAttachment> temporaryAttachments) {
    public DynamicAttachmentResolution {
        permanentAttachments = permanentAttachments == null ? Map.of() : Map.copyOf(permanentAttachments);
        temporaryAttachments = temporaryAttachments == null ? Map.of() : Map.copyOf(temporaryAttachments);
    }

    public boolean isEmpty() {
        return permanentAttachments.isEmpty() && temporaryAttachments.isEmpty();
    }

    /** Temporary attachment value plus the stable rule key that selected it. */
    public record TemporaryAttachment(@Nonnull String value, @Nonnull String ruleKey) {
    }
}
