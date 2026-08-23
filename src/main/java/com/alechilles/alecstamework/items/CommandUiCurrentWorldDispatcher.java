package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ui.CommandUiHostPage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Dispatches deferred page work after resolving the player's current world. */
final class CommandUiCurrentWorldDispatcher
        implements CommandUiHostPage.WorldDispatcher {
    private static final int MAX_WORLD_HANDOFFS = 3;
    private final Resolver resolver;

    CommandUiCurrentWorldDispatcher(@Nonnull Resolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Nonnull
    static CommandUiCurrentWorldDispatcher production() {
        return new CommandUiCurrentWorldDispatcher(
                CommandUiCurrentWorldDispatcher::resolveProduction);
    }

    @Override
    public boolean dispatch(
            @Nonnull UUID playerUuid,
            @Nonnull CommandUiHostPage.WorldOperation operation
    ) {
        ResolvedWorld current = resolve(playerUuid);
        return current != null && current.executor().execute(() ->
                executeCurrent(playerUuid, current, operation, 0));
    }

    private void executeCurrent(
            UUID playerUuid,
            ResolvedWorld scheduled,
            CommandUiHostPage.WorldOperation operation,
            int handoffs
    ) {
        ResolvedWorld current = resolve(playerUuid);
        if (current == null) {
            operation.unavailable();
            return;
        }
        if (!Objects.equals(scheduled.worldIdentity(),
                current.worldIdentity())) {
            if (handoffs >= MAX_WORLD_HANDOFFS
                    || !current.executor().execute(() -> executeCurrent(
                    playerUuid, current, operation, handoffs + 1))) {
                operation.unavailable();
            }
            return;
        }
        operation.run(current.playerRef(), current.store());
    }

    @Nullable
    private ResolvedWorld resolve(UUID playerUuid) {
        try {
            return resolver.resolve(playerUuid);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    private static ResolvedWorld resolveProduction(UUID playerUuid) {
        Universe universe = Universe.get();
        PlayerRef player = universe == null ? null : universe.getPlayer(playerUuid);
        Ref<EntityStore> ref = player == null ? null : player.getReference();
        if (ref == null || !ref.isValid()) return null;
        Store<EntityStore> store = ref.getStore();
        World world = store == null || store.getExternalData() == null
                ? null : store.getExternalData().getWorld();
        if (world == null || !world.isAlive()) return null;
        return new ResolvedWorld(ref, store, callback -> {
            try {
                world.execute(callback);
                return true;
            } catch (RuntimeException | LinkageError ignored) {
                return false;
            }
        }, world);
    }

    @FunctionalInterface
    interface Resolver {
        @Nullable ResolvedWorld resolve(UUID playerUuid);
    }

    @FunctionalInterface
    interface WorldExecutor {
        boolean execute(Runnable callback);
    }

    record ResolvedWorld(
            @Nullable Ref<EntityStore> playerRef,
            @Nullable Store<EntityStore> store,
            @Nonnull WorldExecutor executor,
            @Nonnull Object worldIdentity
    ) {
        ResolvedWorld(Ref<EntityStore> playerRef,
                      Store<EntityStore> store,
                      WorldExecutor executor) {
            this(playerRef, store, executor, executor);
        }

        ResolvedWorld {
            Objects.requireNonNull(executor, "executor");
            Objects.requireNonNull(worldIdentity, "worldIdentity");
        }
    }
}
