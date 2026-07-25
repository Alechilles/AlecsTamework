package com.alechilles.alecstamework.companion.coop;

import javax.annotation.Nonnull;

/** Exact engine-neutral player-inventory coordinate frozen for captured-item intake. */
public record CoopCapturedItemInventoryPosition(
        @Nonnull Section section,
        int slot
) {
    public enum Section {
        HOTBAR,
        STORAGE,
        BACKPACK
    }

    public CoopCapturedItemInventoryPosition {
        if (section == null || slot < 0) {
            throw new IllegalArgumentException(
                    "Valid captured-item inventory position is required"
            );
        }
    }
}
