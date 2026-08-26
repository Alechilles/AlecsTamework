package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudChangeSet;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudController;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSnapshot;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudUpdate;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudView;
import com.alechilles.alecstamework.api.commandhud.CommandHudCloseReason;
import com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext;
import com.alechilles.alecstamework.api.commandhud.CommandHudSurface;
import com.alechilles.alecstamework.api.internal.CommandHudRegistry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.ui.CommandHotswapHudHost;
import com.alechilles.alecstamework.ui.TameworkCommandHotswapHud;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.HudManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
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

/** Selects and owns one player's standard or custom hotswap HUD. */
final class CommandHotswapHudPresentationCoordinator {
    private final Object lock = new Object();
    private final Map<UUID, CommandHotswapHudPresentation> presentations = new HashMap<>();
    private final Map<UUID, CommandHotswapHudPresentationSupport.FailedTool> failedTools =
            new HashMap<>();
    @Nullable
    private final CommandHudCompositionResolver resolver;
    private final CommandHotswapHudSnapshotFactory snapshotFactory =
            new CommandHotswapHudSnapshotFactory();
    private final AtomicLong nextSessionGeneration = new AtomicLong();
    private final BiConsumer<Store<EntityStore>, UUID> invalidationSink;

    CommandHotswapHudPresentationCoordinator(
            @Nullable CommandHudRegistry registry,
            @Nonnull Consumer<UUID> invalidationSink
    ) {
        this(registry == null ? null : new CommandHudCompositionResolver(registry),
                (store, playerUuid) -> invalidationSink.accept(playerUuid));
    }

    CommandHotswapHudPresentationCoordinator(
            @Nullable CommandHudRegistry registry,
            @Nonnull BiConsumer<Store<EntityStore>, UUID> invalidationSink
    ) {
        this(registry == null ? null : new CommandHudCompositionResolver(registry),
                invalidationSink);
    }

    CommandHotswapHudPresentationCoordinator(
            @Nullable CommandHudCompositionResolver resolver,
            @Nonnull BiConsumer<Store<EntityStore>, UUID> invalidationSink
    ) {
        this.resolver = resolver;
        this.invalidationSink = Objects.requireNonNull(invalidationSink, "invalidationSink");
    }

    /** Presents the hotswap HUD for the current active command stack. */
    @Nullable
    CommandHotswapHudPresentation present(
            @Nullable Store<EntityStore> store,
            @Nonnull Player player,
            @Nonnull TwCommandItemConfig config,
            @Nonnull CommandHotswapHudToolIdentity toolIdentity,
            @Nonnull CommandHotswapHudViewModel model
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(toolIdentity, "toolIdentity");
        Objects.requireNonNull(model, "model");
        PlayerRef playerRef = player.getPlayerRef();
        UUID playerUuid = player.getUuid();
        if (playerRef == null || playerUuid == null || player.getHudManager() == null) {
            if (playerUuid != null) closePlayer(playerUuid);
            return null;
        }
        return present(store, playerRef, playerUuid, player.getHudManager(), config,
                toolIdentity, model);
    }
    /** Presents a hotswap HUD for an already resolved player connection. */
    @Nullable
    CommandHotswapHudPresentation present(
            @Nullable Store<EntityStore> store,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID playerUuid,
            @Nonnull HudManager hudManager,
            @Nonnull TwCommandItemConfig config,
            @Nonnull CommandHotswapHudToolIdentity toolIdentity,
            @Nonnull CommandHotswapHudViewModel model
    ) {
        Objects.requireNonNull(playerRef, "playerRef");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(hudManager, "hudManager");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(toolIdentity, "toolIdentity");
        Objects.requireNonNull(model, "model");
        synchronized (lock) {
            CommandHotswapHudPresentation previous = presentations.get(playerUuid);
            if (previous != null && !previous.matches(
                    store, toolIdentity, config)) {
                presentations.remove(playerUuid, previous);
                failedTools.remove(playerUuid);
                closePresentation(previous, hudManager,
                        CommandHotswapHudPresentationSupport.closeReason(previous, store,
                        toolIdentity));
                previous = null;
            }
            if (previous != null) {
                if (previous.custom() && !previous.usable()) {
                    failedTools.put(playerUuid,
                            new CommandHotswapHudPresentationSupport.FailedTool(previous.store(),
                            previous.toolIdentity(), previous.selection()));
                    presentations.remove(playerUuid, previous);
                    closePresentation(previous, hudManager,
                            CommandHudCloseReason.RENDERER_FAILED);
                    previous = null;
                } else {
                    if (previous.custom()) {
                        updateCustom(store, previous, config, toolIdentity, model);
                    } else if (!previous.model().equals(model)) {
                        previous.refreshStandard(model);
                    }
                    return previous;
                }
            }
            CommandHotswapHudPresentationSelection selection =
                    CommandHotswapHudPresentationSelection.from(config, toolIdentity);
            if (CommandHotswapHudPresentationSupport.matchesFailedTool(
                    failedTools.get(playerUuid), store,
                    toolIdentity, selection)) {
                return openStandard(store, playerUuid, playerRef, hudManager, config,
                        toolIdentity, model);
            }
            return openSelected(store, playerUuid, playerRef, hudManager, config,
                    toolIdentity, model);
        }
    }
    /** Hides the current HUD when the active command item leaves the hand. */
    void hide(@Nullable Player player) {
        if (player == null || player.getUuid() == null) return;
        hide(player.getUuid(), player.getHudManager());
    }
    /** Hides a hotswap session using an already resolved HUD manager. */
    void hide(@Nonnull UUID playerUuid, @Nullable HudManager hudManager) {
        synchronized (lock) {
            CommandHotswapHudPresentation presentation = presentations.remove(playerUuid);
            failedTools.remove(playerUuid);
            if (presentation != null) {
                closePresentation(presentation, hudManager, CommandHudCloseReason.TOOL_CHANGED);
            }
        }
    }
    /** Closes a player session after its ECS entity is removed. */
    void closePlayer(@Nonnull UUID playerUuid) {
        synchronized (lock) {
            CommandHotswapHudPresentation presentation = presentations.remove(playerUuid);
            failedTools.remove(playerUuid);
            if (presentation != null) {
                closePresentation(presentation, null, CommandHudCloseReason.PLAYER_UNLOADED);
            }
        }
    }

    /** Closes all sessions owned by one world store. */
    void closeStore(@Nonnull Store<EntityStore> store) {
        synchronized (lock) {
            for (CommandHotswapHudPresentation presentation :
                    new ArrayList<>(presentations.values())) {
                if (presentation.store() != store) continue;
                presentations.remove(presentation.playerUuid(), presentation);
                failedTools.remove(presentation.playerUuid());
                closePresentation(presentation, null, CommandHudCloseReason.STORE_REMOVED);
            }
        }
    }

    /** Closes every presentation owned by this coordinator. */
    void closeAll() {
        synchronized (lock) {
            for (CommandHotswapHudPresentation presentation : presentations.values()) {
                closePresentation(presentation, null, CommandHudCloseReason.SHUTDOWN);
            }
            presentations.clear();
            failedTools.clear();
        }
    }

    @Nullable
    CommandHotswapHudPresentation presentation(@Nonnull UUID playerUuid) {
        synchronized (lock) {
            return presentations.get(playerUuid);
        }
    }

    boolean needsRefresh(@Nonnull UUID playerUuid) {
        synchronized (lock) {
            CommandHotswapHudPresentation presentation = presentations.get(playerUuid);
            return presentation != null && presentation.custom()
                    && (!presentation.usable() || presentation.session() != null
                    && presentation.session().hasDirty());
        }
    }

    @Nonnull
    private CommandHotswapHudPresentation openSelected(
            @Nullable Store<EntityStore> store,
            @Nonnull UUID playerUuid,
            @Nonnull PlayerRef playerRef,
            @Nonnull HudManager hudManager,
            @Nonnull TwCommandItemConfig config,
            @Nonnull CommandHotswapHudToolIdentity toolIdentity,
            @Nonnull CommandHotswapHudViewModel model
    ) {
        CommandHudCompositionResolver currentResolver = resolver;
        CommandHudHotswapResolution resolution = currentResolver == null
                ? CommandHudHotswapResolution.standard()
                : currentResolver.resolveHotswap(config);
        if (!resolution.custom()) {
            return openStandard(store, playerUuid, playerRef, hudManager, config,
                    toolIdentity, model);
        }
        CommandHudCompositionSession<CommandHotswapHudSnapshot,
                CommandHotswapHudView, CommandHotswapHudUpdate> session = null;
        try {
            String language = language(playerRef);
            CommandHotswapHudSnapshot snapshot = snapshotFactory.create(model);
            CommandHudOpenContext context = new CommandHudOpenContext(
                    playerRef.getUuid(), language, toolIdentity.itemId(),
                    toolIdentity.itemId(), config.getId(), CommandHudSurface.HOTSWAP,
                    resolution.rendererId(), null, null,
                    nextSessionGeneration.incrementAndGet());
            AtomicReference<CommandHotswapHudPresentation> reference = new AtomicReference<>();
            AtomicReference<CommandHotswapHudHost> hostReference = new AtomicReference<>();
            AtomicReference<CommandHotswapHudChangeSet> pendingBaseChanges =
                    new AtomicReference<>();
            session = CommandHudCompositionSession.hotswap(
                    context,
                    resolution,
                    currentResolver.diagnostics,
                    currentResolver.timingWarnings,
                    update -> {
                        CommandHotswapHudChangeSet baseChanges =
                                pendingBaseChanges.getAndSet(null);
                        CommandHotswapHudUpdate effective = baseChanges == null
                                ? update
                                : new CommandHotswapHudUpdate(update.view(),
                                update.previousView(), merge(baseChanges,
                                update.changeSet()));
                        CommandHotswapHudHost host = hostReference.get();
                        if (host != null) host.applyUpdate(effective);
                    },
                    () -> markCompositionDirty(store, playerUuid),
                    (contributorId, reason) -> onCompositionFailure(
                            reference.get(), store, contributorId, reason));
            CommandHotswapHudView view = session.compose(snapshot);
            CommandHotswapHudController controller = session.hotswapController();
            if (controller == null) {
                session.close();
                return failedStandard(store, playerUuid, playerRef, hudManager, config,
                        toolIdentity, model);
            }
            CommandHudCompositionSession<CommandHotswapHudSnapshot,
                    CommandHotswapHudView, CommandHotswapHudUpdate> lifecycleSession = session;
            CommandHotswapHudHost host = new CommandHotswapHudHost(
                    playerRef, context, controller, view,
                    (phase, failure) -> onHostFailure(reference.get(), store, failure),
                    lifecycleSession::runIfCurrent,
                    lifecycleSession::runInitialIfCurrent);
            hostReference.set(host);
            CommandHotswapHudPresentation presentation =
                    CommandHotswapHudPresentation.custom(
                            this, store, playerUuid, playerRef, toolIdentity,
                            CommandHotswapHudPresentationSelection.from(config, toolIdentity),
                            pendingBaseChanges, model, snapshot, view, session, host);
            reference.set(presentation);
            presentations.put(playerUuid, presentation);
            try {
                hudManager.addCustomHud(playerRef, host);
            } catch (RuntimeException | LinkageError failure) {
                presentations.remove(playerUuid, presentation);
                return failedCustomStandard(presentation, playerUuid, playerRef,
                        hudManager, config, toolIdentity, model);
            }
            if (!host.isOpen() || !session.isOpen()) {
                presentations.remove(playerUuid, presentation);
                return failedCustomStandard(presentation, playerUuid, playerRef,
                        hudManager, config, toolIdentity, model);
            }
            failedTools.remove(playerUuid);
            return presentation;
        } catch (RuntimeException | LinkageError failure) {
            if (session != null) session.close();
            return failedStandard(store, playerUuid, playerRef, hudManager, config,
                    toolIdentity, model);
        }
    }

    @Nonnull
    private CommandHotswapHudPresentation failedCustomStandard(
            @Nonnull CommandHotswapHudPresentation presentation,
            @Nonnull UUID playerUuid,
            @Nonnull PlayerRef playerRef,
            @Nonnull HudManager hudManager,
            @Nonnull TwCommandItemConfig config,
            @Nonnull CommandHotswapHudToolIdentity toolIdentity,
            @Nonnull CommandHotswapHudViewModel model
    ) {
        failedTools.put(playerUuid,
                new CommandHotswapHudPresentationSupport.FailedTool(presentation.store(),
                presentation.toolIdentity(), presentation.selection()));
        closePresentation(presentation, hudManager, CommandHudCloseReason.RENDERER_FAILED);
        return openStandard(presentation.store(), playerUuid, playerRef, hudManager,
                config, toolIdentity, model);
    }

    @Nonnull
    private CommandHotswapHudPresentation failedStandard(
            @Nullable Store<EntityStore> store,
            @Nonnull UUID playerUuid,
            @Nonnull PlayerRef playerRef,
            @Nonnull HudManager hudManager,
            @Nonnull TwCommandItemConfig config,
            @Nonnull CommandHotswapHudToolIdentity toolIdentity,
            @Nonnull CommandHotswapHudViewModel model
    ) {
        failedTools.put(playerUuid,
                new CommandHotswapHudPresentationSupport.FailedTool(store, toolIdentity,
                CommandHotswapHudPresentationSelection.from(config, toolIdentity)));
        return openStandard(store, playerUuid, playerRef, hudManager, config,
                toolIdentity, model);
    }

    @Nonnull
    private CommandHotswapHudPresentation openStandard(
            @Nullable Store<EntityStore> store,
            @Nonnull UUID playerUuid,
            @Nonnull PlayerRef playerRef,
            @Nonnull HudManager hudManager,
            @Nonnull TwCommandItemConfig config,
            @Nonnull CommandHotswapHudToolIdentity toolIdentity,
            @Nonnull CommandHotswapHudViewModel model
    ) {
        TameworkCommandHotswapHud hud = new TameworkCommandHotswapHud(playerRef, model);
        CommandHotswapHudPresentation presentation =
                CommandHotswapHudPresentation.standard(
                        this, store, playerUuid, playerRef, toolIdentity,
                        CommandHotswapHudPresentationSelection.from(config, toolIdentity),
                        model, hud);
        presentations.put(playerUuid, presentation);
        try {
            hudManager.addCustomHud(playerRef, hud);
        } catch (RuntimeException | LinkageError failure) {
            presentations.remove(playerUuid, presentation);
            presentation.close(hudManager, CommandHudCloseReason.RENDERER_FAILED);
        }
        return presentation;
    }

    private void updateCustom(
            @Nullable Store<EntityStore> store,
            @Nonnull CommandHotswapHudPresentation presentation,
            @Nonnull TwCommandItemConfig config,
            @Nonnull CommandHotswapHudToolIdentity toolIdentity,
            @Nonnull CommandHotswapHudViewModel model
    ) {
        CommandHudCompositionSession<CommandHotswapHudSnapshot,
                CommandHotswapHudView, CommandHotswapHudUpdate> session =
                presentation.session();
        CommandHotswapHudHost host = presentation.customHost();
        if (session == null || host == null) return;
        CommandHotswapHudSnapshot snapshot = snapshotFactory.create(model);
        CommandHotswapHudView previous = presentation.view();
        CommandHotswapHudChangeSet baseChanges =
                CommandHotswapHudSnapshotDiffer.diff(previous.snapshot(), snapshot);
        if (baseChanges.changedSlots().isEmpty()
                && !baseChanges.groupStatusChanged() && !session.hasDirty()) {
            presentation.updateModel(model, snapshot, previous);
            return;
        }
        AtomicReference<CommandHotswapHudChangeSet> pendingBaseChanges =
                presentation.pendingBaseChanges();
        pendingBaseChanges.set(baseChanges);
        try {
            CommandHotswapHudUpdate composed = session.updateBase(snapshot);
            if (!session.isOpen()) {
                onHostFailure(presentation, store,
                        new IllegalStateException("hotswap HUD composition session closed"));
                return;
            }
            CommandHotswapHudView current = session.view();
            presentation.updateModel(model, snapshot, current);
        } catch (RuntimeException | LinkageError failure) {
            onHostFailure(presentation, store, failure);
        } finally {
            pendingBaseChanges.compareAndSet(baseChanges, null);
        }
    }

    @Nonnull
    private static CommandHotswapHudChangeSet merge(
            @Nonnull CommandHotswapHudChangeSet base,
            @Nullable CommandHotswapHudChangeSet composition
    ) {
        if (composition == null) return base;
        if (base.fullRefresh() || composition.fullRefresh()) {
            return CommandHotswapHudChangeSet.full();
        }
        EnumSet<CommandHotswapHudChangeSet.Slot> slots =
                EnumSet.noneOf(CommandHotswapHudChangeSet.Slot.class);
        slots.addAll(base.changedSlots());
        slots.addAll(composition.changedSlots());
        return new CommandHotswapHudChangeSet(false, slots,
                base.groupStatusChanged() || composition.groupStatusChanged(),
                composition.contributorPaths(), composition.fullRefreshContributors());
    }

    private void onHostFailure(
            @Nullable CommandHotswapHudPresentation presentation,
            @Nullable Store<EntityStore> store,
            @Nonnull Throwable failure
    ) {
        if (presentation == null) return;
        synchronized (lock) {
            if (presentations.get(presentation.playerUuid()) != presentation
                    || presentation.closed()) return;
            failedTools.put(presentation.playerUuid(),
                    new CommandHotswapHudPresentationSupport.FailedTool(
                    presentation.store(), presentation.toolIdentity(), presentation.selection()));
            presentation.markFailed();
            if (presentation.session() != null) presentation.session().close();
        }
        markCompositionDirty(store, presentation.playerUuid());
    }

    private void onCompositionFailure(
            @Nullable CommandHotswapHudPresentation presentation,
            @Nullable Store<EntityStore> store,
            @Nonnull com.alechilles.alecstamework.api.commandhud.CommandHudContributorId id,
            @Nonnull String reason
    ) {
        onHostFailure(presentation, store,
                new IllegalStateException("Contributor " + id.value() + " failed: " + reason));
    }

    private void markCompositionDirty(
            @Nullable Store<EntityStore> store,
            @Nonnull UUID playerUuid
    ) {
        try {
            invalidationSink.accept(store, playerUuid);
        } catch (RuntimeException | LinkageError ignored) {
            // A presentation callback must not escape the hotswap tick.
        }
    }

    private void closePresentation(
            @Nonnull CommandHotswapHudPresentation presentation,
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
            @Nonnull CommandHotswapHudPresentation presentation,
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
