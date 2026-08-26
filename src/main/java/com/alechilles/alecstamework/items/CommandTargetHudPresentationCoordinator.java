package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudCloseReason;
import com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext;
import com.alechilles.alecstamework.api.commandhud.CommandHudSurface;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudChangeSet;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudController;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudSnapshot;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudUpdate;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudView;
import com.alechilles.alecstamework.api.internal.CommandHudRegistry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.ui.CommandTargetHudHost;
import com.alechilles.alecstamework.ui.TameworkCommandTargetHud;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.HudManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Selects, owns, and replaces one player's standard or custom target HUD. */
final class CommandTargetHudPresentationCoordinator {
    private final Object lock = new Object();
    private final Map<UUID, CommandTargetHudPresentation> presentations = new HashMap<>();
    private final Map<UUID, String> failedTargetKeys = new HashMap<>();
    private final CommandHudCompositionResolver resolver;
    private final CommandTargetHudSnapshotFactory snapshotFactory =
            new CommandTargetHudSnapshotFactory();
    private final AtomicLong nextSessionGeneration = new AtomicLong();
    @Nonnull
    private final CommandTargetHudDirtyTracker dirtyTracker;

    CommandTargetHudPresentationCoordinator(
            @Nullable CommandHudRegistry registry,
            @Nonnull Consumer<UUID> invalidationSink
    ) {
        this(registry == null ? null : new CommandHudCompositionResolver(registry),
                (store, playerUuid) -> invalidationSink.accept(playerUuid));
    }

    CommandTargetHudPresentationCoordinator(
            @Nullable CommandHudRegistry registry,
            @Nonnull BiConsumer<Store<EntityStore>, UUID> invalidationSink
    ) {
        this(registry == null ? null : new CommandHudCompositionResolver(registry),
                invalidationSink);
    }

    CommandTargetHudPresentationCoordinator(
            @Nullable CommandHudCompositionResolver resolver,
            @Nonnull BiConsumer<Store<EntityStore>, UUID> invalidationSink
    ) {
        this.resolver = resolver;
        this.dirtyTracker = new CommandTargetHudDirtyTracker(
                Objects.requireNonNull(invalidationSink, "invalidationSink"));
    }

    /** Presents the target HUD using the active item as its tool identity. */
    @Nullable
    CommandTargetHudPresentation present(
            @Nullable Store<EntityStore> store,
            @Nonnull Player player,
            @Nonnull TwCommandItemConfig config,
            @Nonnull String targetKey,
            @Nonnull CommandTargetHudViewModel model,
            @Nullable String activeItemId
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(targetKey, "targetKey");
        Objects.requireNonNull(model, "model");
        PlayerRef playerRef = player.getPlayerRef();
        UUID playerUuid = player.getUuid();
        if (playerRef == null || playerUuid == null || player.getHudManager() == null) {
            if (playerUuid != null) {
                closePlayer(playerUuid);
            }
            return null;
        }
        return present(store, playerRef, playerUuid, player.getHudManager(), config,
                targetKey, model, activeItemId);
    }

    /**
     * Presents a target HUD for an already resolved player connection.
     *
     * <p>The service normally calls the Player overload. Keeping the HUD manager and
     * player reference as the boundary also lets lifecycle tests exercise the real
     * Hytale HUD host without constructing a full ECS Player.</p>
     */
    @Nullable
    CommandTargetHudPresentation present(
            @Nullable Store<EntityStore> store,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID playerUuid,
            @Nonnull HudManager hudManager,
            @Nonnull TwCommandItemConfig config,
            @Nonnull String targetKey,
            @Nonnull CommandTargetHudViewModel model,
            @Nullable String activeItemId
    ) {
        Objects.requireNonNull(playerRef, "playerRef");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(hudManager, "hudManager");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(targetKey, "targetKey");
        Objects.requireNonNull(model, "model");
        synchronized (lock) {
            CommandTargetHudPresentation previous = presentations.get(playerUuid);
            if (previous != null && !previous.matches(targetKey, config, activeItemId)) {
                presentations.remove(playerUuid, previous);
                if (!targetKey.equals(previous.targetKey())) {
                    failedTargetKeys.remove(playerUuid);
                }
                closePresentation(previous, hudManager, CommandHudCloseReason.TARGET_CHANGED);
                previous = null;
            }
            if (previous != null) {
                if (previous.custom() && !previous.usable()) {
                    failedTargetKeys.put(playerUuid, targetKey);
                    presentations.remove(playerUuid, previous);
                    closePresentation(previous, hudManager, CommandHudCloseReason.RENDERER_FAILED);
                    previous = null;
                } else {
                    long dirtyVersion = dirtyTracker.version(playerUuid);
                    if (previous.custom()) {
                        updateCustom(store, previous, config, targetKey, model);
                        dirtyTracker.markPresented(playerUuid, dirtyVersion);
                    } else {
                        previous.refreshStandard(model, language(playerRef));
                        dirtyTracker.clear(playerUuid);
                    }
                    return previous;
                }
            }
            if (targetKey.equals(failedTargetKeys.get(playerUuid))) {
                dirtyTracker.clear(playerUuid);
                return openStandard(playerUuid, playerRef, hudManager, config,
                        targetKey, model, activeItemId);
            }
            return openSelected(store, playerUuid, playerRef, hudManager, config,
                    targetKey, model, activeItemId);
        }
    }

    /** Hides and closes the current target session for a live player. */
    void hide(@Nullable Player player) {
        if (player == null || player.getUuid() == null) {
            return;
        }
        hide(player.getUuid(), player.getHudManager());
    }

    /** Hides a target session using an already resolved HUD manager. */
    void hide(@Nonnull UUID playerUuid, @Nullable HudManager hudManager) {
        synchronized (lock) {
            CommandTargetHudPresentation presentation = presentations.remove(playerUuid);
            failedTargetKeys.remove(playerUuid);
            dirtyTracker.clear(playerUuid);
            if (presentation != null) {
                closePresentation(presentation, hudManager, CommandHudCloseReason.TARGET_LOST);
            }
        }
    }

    /** Closes a player session after the ECS player has already been removed. */
    void closePlayer(@Nonnull UUID playerUuid) {
        synchronized (lock) {
            CommandTargetHudPresentation presentation = presentations.remove(playerUuid);
            failedTargetKeys.remove(playerUuid);
            dirtyTracker.clear(playerUuid);
            if (presentation != null) {
                closePresentation(presentation, null, CommandHudCloseReason.PLAYER_UNLOADED);
            }
        }
    }

    /** Closes every presentation owned by this coordinator. */
    void closeAll() {
        synchronized (lock) {
            for (CommandTargetHudPresentation presentation : presentations.values()) {
                closePresentation(presentation, null, CommandHudCloseReason.SHUTDOWN);
            }
            presentations.clear();
            failedTargetKeys.clear();
            dirtyTracker.clearAll();
        }
    }

    @Nullable
    String activeTargetKey(@Nonnull UUID playerUuid) {
        synchronized (lock) {
            CommandTargetHudPresentation presentation = presentations.get(playerUuid);
            return presentation == null || presentation.closed()
                    ? null : presentation.targetKey();
        }
    }

    boolean needsRefresh(@Nonnull UUID playerUuid) {
        synchronized (lock) {
            CommandTargetHudPresentation presentation = presentations.get(playerUuid);
            return presentation != null && presentation.custom()
                    && (!presentation.usable() || dirtyTracker.pending(playerUuid));
        }
    }

    @Nullable
    CommandTargetHudPresentation presentation(@Nonnull UUID playerUuid) {
        synchronized (lock) {
            return presentations.get(playerUuid);
        }
    }

    @Nonnull
    private CommandTargetHudPresentation openSelected(
            @Nullable Store<EntityStore> store,
            @Nonnull UUID playerUuid,
            @Nonnull PlayerRef playerRef,
            @Nonnull HudManager hudManager,
            @Nonnull TwCommandItemConfig config,
            @Nonnull String targetKey,
            @Nonnull CommandTargetHudViewModel model,
            @Nullable String activeItemId
    ) {
        CommandHudCompositionResolver currentResolver = resolver;
        CommandHudTargetResolution resolution = currentResolver == null
                ? CommandHudTargetResolution.standard()
                : currentResolver.resolveTarget(config);
        if (!resolution.custom()) {
            dirtyTracker.clear(playerUuid);
            return openStandard(playerUuid, playerRef, hudManager, config,
                    targetKey, model, activeItemId);
        }
        CommandHudCompositionSession<CommandTargetHudSnapshot,
                CommandTargetHudView, CommandTargetHudUpdate> session = null;
        try {
            String language = language(playerRef);
            CommandTargetHudSnapshot snapshot = snapshotFactory.create(model, targetKey);
            long dirtyVersionAtStart = dirtyTracker.version(playerUuid);
            CommandHudOpenContext context = new CommandHudOpenContext(
                    playerRef.getUuid(),
                    language,
                    activeItemId,
                    activeItemId,
                    config.getId(),
                    CommandHudSurface.TARGET,
                    resolution.rendererId(),
                    snapshot.targetUuid(),
                    targetKey,
                    nextSessionGeneration.incrementAndGet()
            );
            AtomicReference<CommandTargetHudPresentation> reference = new AtomicReference<>();
            session = CommandHudCompositionSession.target(
                    context,
                    resolution,
                    currentResolver.diagnostics,
                    currentResolver.timingWarnings,
                    ignored -> { },
                    () -> markCompositionDirty(store, playerUuid),
                    (contributorId, reason) -> onCompositionFailure(
                            reference.get(), store, contributorId, reason));
            CommandTargetHudView view = session.compose(snapshot);
            CommandTargetHudController controller = session.targetController();
            if (controller == null) {
                session.close();
                failedTargetKeys.put(playerUuid, targetKey);
                return openStandard(playerUuid, playerRef, hudManager, config,
                        targetKey, model, activeItemId);
            }
            CommandTargetHudHost host = new CommandTargetHudHost(
                    playerRef, context, controller, view,
                    (phase, failure) -> onHostFailure(reference.get(), store, failure));
            CommandTargetHudPresentation presentation = CommandTargetHudPresentation.custom(
                    this, playerUuid, playerRef, targetKey, activeItemId,
                    CommandTargetHudPresentationSelection.from(config, activeItemId),
                    model, snapshot, view,
                    session, host);
            reference.set(presentation);
            presentations.put(playerUuid, presentation);
            try {
                hudManager.addCustomHud(playerRef, host);
            } catch (RuntimeException | LinkageError failure) {
                presentations.remove(playerUuid, presentation);
                failedTargetKeys.put(playerUuid, targetKey);
                closePresentation(presentation, hudManager, CommandHudCloseReason.RENDERER_FAILED);
                return openStandard(playerUuid, playerRef, hudManager, config,
                        targetKey, model, activeItemId);
            }
            if (!host.isOpen()) {
                presentations.remove(playerUuid, presentation);
                failedTargetKeys.put(playerUuid, targetKey);
                closePresentation(presentation, hudManager, CommandHudCloseReason.RENDERER_FAILED);
                return openStandard(playerUuid, playerRef, hudManager, config,
                        targetKey, model, activeItemId);
            }
            dirtyTracker.markPresented(playerUuid, dirtyVersionAtStart);
            return presentation;
        } catch (RuntimeException | LinkageError failure) {
            if (session != null) {
                session.close();
            }
            failedTargetKeys.put(playerUuid, targetKey);
            return openStandard(playerUuid, playerRef, hudManager, config,
                    targetKey, model, activeItemId);
        }
    }

    @Nonnull
    private CommandTargetHudPresentation openStandard(
            @Nonnull UUID playerUuid,
            @Nonnull PlayerRef playerRef,
            @Nonnull HudManager hudManager,
            @Nonnull TwCommandItemConfig config,
            @Nonnull String targetKey,
            @Nonnull CommandTargetHudViewModel model,
            @Nullable String activeItemId
    ) {
        TameworkCommandTargetHud hud = new TameworkCommandTargetHud(
                playerRef, model, language(playerRef));
        CommandTargetHudPresentation presentation = CommandTargetHudPresentation.standard(
                this, playerUuid, playerRef, targetKey, activeItemId,
                CommandTargetHudPresentationSelection.from(config, activeItemId), model, hud);
        presentations.put(playerUuid, presentation);
        try {
            hudManager.addCustomHud(playerRef, hud);
        } catch (RuntimeException | LinkageError failure) {
            presentations.remove(playerUuid, presentation);
            closePresentation(presentation, hudManager, CommandHudCloseReason.RENDERER_FAILED);
        }
        return presentation;
    }

    private void updateCustom(
            @Nullable Store<EntityStore> store,
            @Nonnull CommandTargetHudPresentation presentation,
            @Nonnull TwCommandItemConfig config,
            @Nonnull String targetKey,
            @Nonnull CommandTargetHudViewModel model
    ) {
        CommandHudCompositionSession<CommandTargetHudSnapshot,
                CommandTargetHudView, CommandTargetHudUpdate> session = presentation.session();
        CommandTargetHudHost host = presentation.customHost();
        if (session == null || host == null) {
            return;
        }
        CommandTargetHudSnapshot snapshot = snapshotFactory.create(model, targetKey);
        CommandTargetHudView previous = presentation.view();
        try {
            CommandTargetHudView rebased = session.rebase(snapshot);
            if (!session.isOpen()) {
                onHostFailure(presentation, store,
                        new IllegalStateException("target HUD composition session closed"));
                return;
            }
            CommandTargetHudView current = session.view();
            if (!Objects.equals(current.snapshot(), snapshot)) {
                current = new CommandTargetHudView(snapshot, current.contributions());
            }
            CommandTargetHudUpdate composed = session.lastUpdate();
            CommandTargetHudChangeSet baseChanges =
                    CommandTargetHudSnapshotDiffer.diff(previous.snapshot(), snapshot);
            CommandTargetHudChangeSet changes = merge(baseChanges,
                    composed == null || !Objects.equals(composed.view(), rebased)
                            ? null : composed.changeSet());
            if (changes.changedSections().isEmpty()
                    && Objects.equals(previous, current)) {
                presentation.updateModel(model, snapshot, current);
                return;
            }
            CommandTargetHudUpdate update = new CommandTargetHudUpdate(
                    current, previous, changes);
            presentation.updateModel(model, snapshot, current);
            host.applyUpdate(update);
        } catch (RuntimeException | LinkageError failure) {
            onHostFailure(presentation, store, failure);
        }
    }

    @Nonnull
    private static CommandTargetHudChangeSet merge(
            @Nonnull CommandTargetHudChangeSet base,
            @Nullable CommandTargetHudChangeSet composition
    ) {
        if (composition == null) {
            return base;
        }
        if (base.fullRefresh() || composition.fullRefresh()) {
            return CommandTargetHudChangeSet.full();
        }
        EnumSet<CommandTargetHudChangeSet.Section> sections =
                EnumSet.noneOf(CommandTargetHudChangeSet.Section.class);
        sections.addAll(base.changedSections());
        sections.addAll(composition.changedSections());
        return new CommandTargetHudChangeSet(
                false,
                sections,
                composition.contributorPaths(),
                composition.fullRefreshContributors());
    }

    private void onHostFailure(
            @Nullable CommandTargetHudPresentation presentation,
            @Nullable Store<EntityStore> store,
            @Nonnull Throwable failure
    ) {
        if (presentation == null) {
            return;
        }
        synchronized (lock) {
            if (presentations.get(presentation.playerUuid()) != presentation
                    || presentation.closed()) {
                return;
            }
            failedTargetKeys.put(presentation.playerUuid(), presentation.targetKey());
            presentation.markFailed();
            CommandHudCompositionSession<?, ?, ?> session = presentation.session();
            if (session != null) {
                session.close();
            }
        }
        dirtyTracker.invalidate(store, presentation.playerUuid());
    }

    private void onCompositionFailure(
            @Nullable CommandTargetHudPresentation presentation,
            @Nullable Store<EntityStore> store,
            @Nonnull com.alechilles.alecstamework.api.commandhud.CommandHudContributorId contributorId,
            @Nonnull String reason
    ) {
        onHostFailure(presentation, store,
                new IllegalStateException("Contributor " + contributorId.value()
                        + " failed: " + reason));
    }

    private void markCompositionDirty(
            @Nullable Store<EntityStore> store,
            @Nonnull UUID playerUuid
    ) {
        dirtyTracker.markDirty(store, playerUuid);
    }

    private void closePresentation(
            @Nonnull CommandTargetHudPresentation presentation,
            @Nullable HudManager hudManager,
            @Nonnull CommandHudCloseReason reason
    ) {
        presentation.close(hudManager, reason);
    }

    @Nullable
    private static String language(@Nonnull PlayerRef playerRef) {
        String value = playerRef.getLanguage();
        return value == null || value.isBlank() ? null : value;
    }

    void close(
            @Nonnull CommandTargetHudPresentation presentation,
            @Nullable HudManager hudManager,
            @Nonnull CommandHudCloseReason reason
    ) {
        synchronized (lock) {
            if (presentations.get(presentation.playerUuid()) == presentation) {
                presentations.remove(presentation.playerUuid(), presentation);
            }
            presentation.close(hudManager, reason);
        }
    }
}
