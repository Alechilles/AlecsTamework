package com.alechilles.alecstamework.api;

import com.hypixel.hytale.server.core.Message;
import javax.annotation.Nullable;

/** Optional presentation fields for an item containing a captured NPC. */
public record CapturedItemDisplayContribution(
        @Nullable Message descriptionPrefix,
        @Nullable String qualityId
) {
    /** Returns a contribution that leaves the capture item's normal display unchanged. */
    public static CapturedItemDisplayContribution none() {
        return new CapturedItemDisplayContribution(null, null);
    }
}
