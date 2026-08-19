package com.alechilles.alecstamework.items;

import java.util.Arrays;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Receives player UUID dirty signals for command HUD trackers. */
@FunctionalInterface
public interface CommandHudDirtySink {
    void markDirty(@Nullable UUID playerUuid);

    @Nonnull
    static CommandHudDirtySink fanOut(@Nonnull CommandHudDirtySink... sinks) {
        CommandHudDirtySink[] copy = Arrays.copyOf(sinks, sinks.length);
        return playerUuid -> {
            for (CommandHudDirtySink sink : copy) {
                if (sink != null) {
                    sink.markDirty(playerUuid);
                }
            }
        };
    }
}
