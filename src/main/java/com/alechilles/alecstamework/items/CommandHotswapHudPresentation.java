package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudChangeSet;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSnapshot;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudUpdate;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudView;
import com.alechilles.alecstamework.api.commandhud.CommandHudCloseReason;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudController;
import com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.ui.CommandHotswapHudHost;
import com.alechilles.alecstamework.ui.TameworkCommandHotswapHud;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.player.hud.HudManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Owns the standard or custom HUD resources for one exact equipped stack. */
final class CommandHotswapHudPresentation {
    private final CommandHotswapHudPresentationCoordinator owner;
    @Nullable
    private final Store<EntityStore> store;
    private final UUID playerUuid;
    private final PlayerRef playerRef;
    private final HudManager hudManager;
    private final CommandHotswapHudToolIdentity toolIdentity;
    private final CommandHotswapHudPresentationSelection selection;
    @Nonnull
    private final AtomicReference<CommandHotswapHudChangeSet> pendingBaseChanges;
    private final boolean custom;
    @Nullable
    private final TameworkCommandHotswapHud standardHud;
    @Nullable
    private final CommandHotswapHudHost customHost;
    @Nullable
    private final CommandHudCompositionSession<CommandHotswapHudSnapshot,
            CommandHotswapHudView, CommandHotswapHudUpdate> session;
    @Nullable
    private CommandHotswapHudViewModel model;
    @Nullable
    private CommandHotswapHudSnapshot snapshot;
    @Nullable
    private CommandHotswapHudView view;
    private boolean failed;
    private boolean closed;

    private CommandHotswapHudPresentation(
            @Nonnull CommandHotswapHudPresentationCoordinator owner,
            @Nullable Store<EntityStore> store,
            @Nonnull UUID playerUuid,
            @Nonnull PlayerRef playerRef,
            @Nonnull HudManager hudManager,
            @Nonnull CommandHotswapHudToolIdentity toolIdentity,
            @Nonnull CommandHotswapHudPresentationSelection selection,
            @Nonnull AtomicReference<CommandHotswapHudChangeSet> pendingBaseChanges,
            @Nonnull CommandHotswapHudViewModel model,
            boolean custom,
            @Nullable TameworkCommandHotswapHud standardHud,
            @Nullable CommandHotswapHudHost customHost,
            @Nullable CommandHudCompositionSession<CommandHotswapHudSnapshot,
                    CommandHotswapHudView, CommandHotswapHudUpdate> session,
            @Nullable CommandHotswapHudSnapshot snapshot,
            @Nullable CommandHotswapHudView view
    ) {
        this.owner = owner;
        this.store = store;
        this.playerUuid = playerUuid;
        this.playerRef = playerRef;
        this.hudManager = hudManager;
        this.toolIdentity = toolIdentity;
        this.selection = selection;
        this.pendingBaseChanges = pendingBaseChanges;
        this.model = model;
        this.custom = custom;
        this.standardHud = standardHud;
        this.customHost = customHost;
        this.session = session;
        this.snapshot = snapshot;
        this.view = view;
    }

    @Nonnull
    static CommandHotswapHudPresentation standard(
            @Nonnull CommandHotswapHudPresentationCoordinator owner,
            @Nullable Store<EntityStore> store,
            @Nonnull UUID playerUuid,
            @Nonnull PlayerRef playerRef,
            @Nonnull HudManager hudManager,
            @Nonnull CommandHotswapHudToolIdentity toolIdentity,
            @Nonnull CommandHotswapHudPresentationSelection selection,
            @Nonnull CommandHotswapHudViewModel model,
            @Nonnull TameworkCommandHotswapHud hud
    ) {
        return new CommandHotswapHudPresentation(owner, store, playerUuid, playerRef,
                hudManager,
                toolIdentity, selection, new AtomicReference<>(), model, false, hud,
                null, null, null, null);
    }

    @Nonnull
    static CommandHotswapHudPresentation custom(
            @Nonnull CommandHotswapHudPresentationCoordinator owner,
            @Nullable Store<EntityStore> store,
            @Nonnull UUID playerUuid,
            @Nonnull PlayerRef playerRef,
            @Nonnull HudManager hudManager,
            @Nonnull CommandHotswapHudToolIdentity toolIdentity,
            @Nonnull CommandHotswapHudPresentationSelection selection,
            @Nonnull AtomicReference<CommandHotswapHudChangeSet> pendingBaseChanges,
            @Nonnull CommandHotswapHudViewModel model,
            @Nonnull CommandHotswapHudSnapshot snapshot,
            @Nonnull CommandHotswapHudView view,
            @Nonnull CommandHudCompositionSession<CommandHotswapHudSnapshot,
                    CommandHotswapHudView, CommandHotswapHudUpdate> session,
            @Nonnull CommandHotswapHudHost host
    ) {
        return new CommandHotswapHudPresentation(owner, store, playerUuid, playerRef,
                hudManager,
                toolIdentity, selection, pendingBaseChanges, model, true, null, host,
                session, snapshot, view);
    }

    @Nullable
    Store<EntityStore> store() {
        return store;
    }

    @Nonnull
    UUID playerUuid() {
        return playerUuid;
    }

    @Nonnull
    PlayerRef playerRef() {
        return playerRef;
    }

    @Nonnull
    HudManager hudManager() {
        return hudManager;
    }

    @Nonnull
    CommandHotswapHudToolIdentity toolIdentity() {
        return toolIdentity;
    }

    @Nonnull
    CommandHotswapHudPresentationSelection selection() {
        return selection;
    }

    @Nonnull
    CommandHotswapHudViewModel model() {
        return Objects.requireNonNull(model, "hotswap model");
    }

    boolean custom() {
        return custom;
    }

    boolean closed() {
        return closed;
    }

    boolean usable() {
        return !closed && !failed
                && (!custom || customHost != null && customHost.isOpen()
                && session != null && session.isOpen());
    }

    @Nullable
    CommandHudCompositionSession<CommandHotswapHudSnapshot,
            CommandHotswapHudView, CommandHotswapHudUpdate> session() {
        return session;
    }

    @Nullable
    CommandHotswapHudHost customHost() {
        return customHost;
    }

    @Nonnull
    AtomicReference<CommandHotswapHudChangeSet> pendingBaseChanges() {
        return pendingBaseChanges;
    }

    @Nonnull
    CommandHotswapHudView view() {
        return Objects.requireNonNull(view, "custom hotswap view");
    }

    boolean matches(
            @Nullable Store<EntityStore> currentStore,
            @Nonnull PlayerRef currentPlayerRef,
            @Nonnull HudManager currentHudManager,
            @Nonnull CommandHotswapHudToolIdentity currentTool,
            @Nonnull TwCommandItemConfig config
    ) {
        return store == currentStore
                && playerRef == currentPlayerRef
                && hudManager == currentHudManager
                && toolIdentity.same(currentTool)
                && selection.equals(CommandHotswapHudPresentationSelection.from(
                        config, currentTool));
    }

    void refreshStandard(@Nonnull CommandHotswapHudViewModel updated) {
        if (standardHud != null) standardHud.refresh(updated);
        model = updated;
    }

    void updateModel(
            @Nonnull CommandHotswapHudViewModel updated,
            @Nonnull CommandHotswapHudSnapshot updatedSnapshot,
            @Nonnull CommandHotswapHudView updatedView
    ) {
        model = updated;
        snapshot = updatedSnapshot;
        view = updatedView;
    }

    void markFailed() {
        failed = true;
    }

    void close(@Nullable HudManager ignoredHudManager, @Nonnull CommandHudCloseReason reason) {
        if (closed) return;
        closed = true;
        try {
            hudManager.removeCustomHud(playerRef, TameworkCommandHotswapHud.HUD_KEY);
        } catch (RuntimeException | LinkageError ignored) {
            // Cleanup below must still release renderer and contributor state.
        } finally {
            if (customHost != null) customHost.close();
            if (session != null) session.close();
        }
    }

    void closeFromStateStore() {
        owner.close(this, null, CommandHudCloseReason.PLAYER_UNLOADED);
    }
}
