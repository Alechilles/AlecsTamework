package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Arrays;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Receives player UUID dirty signals for command HUD trackers. */
@FunctionalInterface
public interface CommandHudDirtySink {
    void markDirty(@Nullable UUID playerUuid);

    /** Marks a player dirty in the current store while retaining UUID-only compatibility callers. */
    default void markDirty(@Nullable Store<EntityStore> store, @Nullable UUID playerUuid) {
        markDirty(playerUuid);
    }

    /** Marks a known player for bounded low-priority recovery inspection. */
    default void markRecovery(@Nullable Store<EntityStore> store, @Nullable UUID playerUuid) {
    }

    /** Removes one player from store-scoped HUD activation state. */
    default void remove(@Nullable Store<EntityStore> store, @Nullable UUID playerUuid) {
    }

    /** Removes all HUD activation state owned by one store. */
    default void removeStore(@Nullable Store<EntityStore> store) {
    }

    @Nonnull
    static CommandHudDirtySink fanOut(@Nonnull CommandHudDirtySink... sinks) {
        CommandHudDirtySink[] copy = Arrays.copyOf(sinks, sinks.length);
        return new CommandHudDirtySink() {
            @Override
            public void markDirty(@Nullable UUID playerUuid) {
                for (CommandHudDirtySink sink : copy) {
                    if (sink != null) {
                        sink.markDirty(playerUuid);
                    }
                }
            }

            @Override
            public void markDirty(@Nullable Store<EntityStore> store,
                                  @Nullable UUID playerUuid) {
                for (CommandHudDirtySink sink : copy) {
                    if (sink != null) {
                        sink.markDirty(store, playerUuid);
                    }
                }
            }

            @Override
            public void markRecovery(@Nullable Store<EntityStore> store,
                                     @Nullable UUID playerUuid) {
                for (CommandHudDirtySink sink : copy) {
                    if (sink != null) {
                        sink.markRecovery(store, playerUuid);
                    }
                }
            }

            @Override
            public void remove(@Nullable Store<EntityStore> store,
                               @Nullable UUID playerUuid) {
                for (CommandHudDirtySink sink : copy) {
                    if (sink != null) {
                        sink.remove(store, playerUuid);
                    }
                }
            }

            @Override
            public void removeStore(@Nullable Store<EntityStore> store) {
                for (CommandHudDirtySink sink : copy) {
                    if (sink != null) {
                        sink.removeStore(store);
                    }
                }
            }
        };
    }
}
