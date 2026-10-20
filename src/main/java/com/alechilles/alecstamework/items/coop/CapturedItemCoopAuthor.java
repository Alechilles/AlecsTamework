package com.alechilles.alecstamework.items.coop;

import com.alechilles.alecstamework.companion.coop.CoopCapturedItemInventoryPosition;
import javax.annotation.Nonnull;

/**
 * Holds the captured-item coop intake evidence. The intake itself runs through the companion
 * index coop flow.
 */
public final class CapturedItemCoopAuthor {
    private CapturedItemCoopAuthor() {
    }

    /**
     * Exact player-inventory evidence frozen on the owning world thread.
     *
     * <p>The position is relative to its named HOTBAR, STORAGE, or BACKPACK section. Combined
     * inventory offsets are deliberately not accepted.</p>
     */
    public record Source(
            @Nonnull java.util.UUID actorUuid,
            @Nonnull String sourceWorldKey,
            @Nonnull CoopCapturedItemInventoryPosition inventoryPosition,
            @Nonnull com.alechilles.alecstamework.companion.capture
                    .CapturedArtifact sourceArtifact
    ) {
        public Source {
            if (actorUuid == null || inventoryPosition == null
                    || sourceArtifact == null || sourceWorldKey == null
                    || sourceWorldKey.isBlank()) {
                throw new IllegalArgumentException(
                        "Complete captured-item coop source is required"
                );
            }
            sourceWorldKey = sourceWorldKey.trim();
        }
    }
}
