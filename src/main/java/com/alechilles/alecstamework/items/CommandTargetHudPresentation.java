package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudCloseReason;
import com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudChangeSet;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudController;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudSnapshot;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudUpdate;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudView;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorRequirement;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.ui.CommandTargetHudHost;
import com.alechilles.alecstamework.ui.TameworkCommandTargetHud;
import com.hypixel.hytale.server.core.entity.entities.player.hud.HudManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Owns the standard or custom HUD resources for one exact target selection. */
final class CommandTargetHudPresentation {
    private final CommandTargetHudPresentationCoordinator owner;
    private final UUID playerUuid;
    private final PlayerRef playerRef;
    private final String targetKey;
    @Nullable
    private final String activeItemId;
    private final CommandTargetHudPresentationSelection selection;
    @Nonnull
    private final AtomicReference<CommandTargetHudChangeSet> pendingBaseChanges;
    private final boolean custom;
    @Nullable
    private final TameworkCommandTargetHud standardHud;
    @Nullable
    private final CommandTargetHudHost customHost;
    @Nullable
    private final CommandHudCompositionSession<CommandTargetHudSnapshot,
            CommandTargetHudView, CommandTargetHudUpdate> session;
    @Nullable
    private CommandTargetHudViewModel model;
    @Nullable
    private CommandTargetHudSnapshot snapshot;
    @Nullable
    private CommandTargetHudView view;
    private boolean failed;
    private boolean closed;

    private CommandTargetHudPresentation(
            @Nonnull CommandTargetHudPresentationCoordinator owner,
            @Nonnull UUID playerUuid,
            @Nonnull PlayerRef playerRef,
            @Nonnull String targetKey,
            @Nullable String activeItemId,
            @Nonnull CommandTargetHudPresentationSelection selection,
            @Nonnull AtomicReference<CommandTargetHudChangeSet> pendingBaseChanges,
            @Nonnull CommandTargetHudViewModel model,
            boolean custom,
            @Nullable TameworkCommandTargetHud standardHud,
            @Nullable CommandTargetHudHost customHost,
            @Nullable CommandHudCompositionSession<CommandTargetHudSnapshot,
                    CommandTargetHudView, CommandTargetHudUpdate> session,
            @Nullable CommandTargetHudSnapshot snapshot,
            @Nullable CommandTargetHudView view
    ) {
        this.owner = owner;
        this.playerUuid = playerUuid;
        this.playerRef = playerRef;
        this.targetKey = targetKey;
        this.activeItemId = activeItemId;
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
    static CommandTargetHudPresentation standard(
            @Nonnull CommandTargetHudPresentationCoordinator owner,
            @Nonnull UUID playerUuid,
            @Nonnull PlayerRef playerRef,
            @Nonnull String targetKey,
            @Nullable String activeItemId,
            @Nonnull CommandTargetHudPresentationSelection selection,
            @Nonnull CommandTargetHudViewModel model,
            @Nonnull TameworkCommandTargetHud hud
    ) {
        return new CommandTargetHudPresentation(owner, playerUuid, playerRef, targetKey,
                activeItemId, selection, new AtomicReference<>(), model, false, hud,
                null, null, null, null);
    }

    @Nonnull
    static CommandTargetHudPresentation custom(
            @Nonnull CommandTargetHudPresentationCoordinator owner,
            @Nonnull UUID playerUuid,
            @Nonnull PlayerRef playerRef,
            @Nonnull String targetKey,
            @Nullable String activeItemId,
            @Nonnull CommandTargetHudPresentationSelection selection,
            @Nonnull AtomicReference<CommandTargetHudChangeSet> pendingBaseChanges,
            @Nonnull CommandTargetHudViewModel model,
            @Nonnull CommandTargetHudSnapshot snapshot,
            @Nonnull CommandTargetHudView view,
            @Nonnull CommandHudCompositionSession<CommandTargetHudSnapshot,
                    CommandTargetHudView, CommandTargetHudUpdate> session,
            @Nonnull CommandTargetHudHost host
    ) {
        return new CommandTargetHudPresentation(owner, playerUuid, playerRef, targetKey,
                activeItemId, selection, pendingBaseChanges, model, true, null, host,
                session, snapshot, view);
    }

    UUID playerUuid() {
        return playerUuid;
    }

    String targetKey() {
        return targetKey;
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
    CommandHudCompositionSession<CommandTargetHudSnapshot,
            CommandTargetHudView, CommandTargetHudUpdate> session() {
        return session;
    }

    @Nullable
    CommandTargetHudHost customHost() {
        return customHost;
    }

    @Nonnull
    AtomicReference<CommandTargetHudChangeSet> pendingBaseChanges() {
        return pendingBaseChanges;
    }

    @Nonnull
    CommandTargetHudView view() {
        return Objects.requireNonNull(view, "custom target view");
    }

    boolean matches(
            @Nonnull String key,
            @Nonnull TwCommandItemConfig config,
            @Nullable String activeItemId
    ) {
        return targetKey.equals(key)
                && selection.equals(CommandTargetHudPresentationSelection.from(
                        config, activeItemId));
    }

    void refreshStandard(@Nonnull CommandTargetHudViewModel updated, @Nullable String language) {
        if (standardHud != null) {
            standardHud.refresh(updated, language);
        }
        model = updated;
    }

    void updateModel(
            @Nonnull CommandTargetHudViewModel updated,
            @Nonnull CommandTargetHudSnapshot updatedSnapshot,
            @Nonnull CommandTargetHudView updatedView
    ) {
        model = updated;
        snapshot = updatedSnapshot;
        view = updatedView;
    }

    void markFailed() {
        failed = true;
    }

    void close(@Nullable HudManager hudManager, @Nonnull CommandHudCloseReason reason) {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (hudManager != null) {
                hudManager.removeCustomHud(playerRef, TameworkCommandTargetHud.HUD_KEY);
            } else {
                if (customHost != null) {
                    customHost.hideNow();
                }
                if (standardHud != null) {
                    standardHud.hideNow();
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Cleanup below must still release renderer and contributor state.
        } finally {
            if (customHost != null) {
                customHost.close();
            }
            if (session != null) {
                session.close();
            }
        }
    }

    void closeFromStateStore() {
        owner.close(this, null, CommandHudCloseReason.PLAYER_UNLOADED);
    }
}
