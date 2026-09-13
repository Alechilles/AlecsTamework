package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.commands.TameworkConfigPermission;
import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.singleplayer.SingleplayerModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Shared helpers for opening the in-world Tamework settings page.
 */
public final class TameworkSettingsPageService {

    private TameworkSettingsPageService() {
    }

    public static boolean hasAccess(@Nullable Object permissionSource) {
        return TameworkConfigPermission.hasAccess(permissionSource);
    }

    public static boolean hasAccess(@Nullable PlayerRef playerRef, @Nullable Object permissionSource) {
        return hasAccess(permissionSource) || isLocalSingleplayerOwner(playerRef);
    }

    public static boolean hasAccess(@Nullable CommandSender sender) {
        return hasAccess(resolvePlayerRef(sender), sender);
    }

    @Nullable
    public static String openSettingsPage(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return LocalizedText.resolve(playerRef, "tamework.ui.settings.openUnavailable");
        }
        Store<EntityStore> store = ref.getStore();
        if (store == null || store.getExternalData() == null) {
            return LocalizedText.resolve(playerRef, "tamework.ui.settings.openUnavailable");
        }
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return LocalizedText.resolve(playerRef, "tamework.ui.settings.openUnavailable");
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return LocalizedText.resolve(playerRef, "tamework.ui.settings.openUnavailable");
        }
        if (!hasAccess(playerRef, playerRef)) {
            return LocalizedText.resolve(playerRef, "tamework.ui.settings.permissionDenied");
        }
        return openSettingsPage(player, ref, store, world, "api", "settings_api");
    }

    @Nullable
    public static String openSettingsPage(@Nonnull Ref<EntityStore> ref,
                                          @Nonnull Store<EntityStore> store) {
        return openSettingsPage(ref, store, "api", "settings_api");
    }

    @Nullable
    public static String openSettingsPage(@Nonnull Ref<EntityStore> ref,
                                          @Nonnull Store<EntityStore> store,
                                          @Nonnull String telemetrySource,
                                          @Nonnull String entryPoint) {
        if (store.getExternalData() == null) {
            return LocalizedText.resolve(ref.isValid() ? store.getComponent(ref, PlayerRef.getComponentType()) : (PlayerRef) null, "tamework.ui.settings.openUnavailable");
        }
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return LocalizedText.resolve(ref.isValid() ? store.getComponent(ref, PlayerRef.getComponentType()) : (PlayerRef) null, "tamework.ui.settings.openUnavailable");
        }
        return openSettingsPage(ref, store, world, telemetrySource, entryPoint);
    }

    @Nullable
    public static String openSettingsPage(@Nonnull Ref<EntityStore> ref,
                                          @Nonnull Store<EntityStore> store,
                                          @Nonnull World world) {
        return openSettingsPage(ref, store, world, "api", "settings_api");
    }

    @Nullable
    public static String openSettingsPage(@Nonnull Ref<EntityStore> ref,
                                          @Nonnull Store<EntityStore> store,
                                          @Nonnull World world,
                                          @Nonnull String telemetrySource,
                                          @Nonnull String entryPoint) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return LocalizedText.resolve(ref.isValid() ? store.getComponent(ref, PlayerRef.getComponentType()) : (PlayerRef) null, "tamework.ui.settings.openUnavailable");
        }
        return openSettingsPage(player, ref, store, world, telemetrySource, entryPoint);
    }

    @Nullable
    private static String openSettingsPage(@Nonnull Player player,
                                           @Nonnull Ref<EntityStore> ref,
                                           @Nonnull Store<EntityStore> store,
                                           @Nonnull World world,
                                           @Nonnull String telemetrySource,
                                           @Nonnull String entryPoint) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null) {
            return LocalizedText.resolve(player, "tamework.ui.settings.notAvailable");
        }
        if (player.getPageManager() == null) {
            return LocalizedText.resolve(player, "tamework.ui.settings.openUnavailable");
        }
        PlayerRef uiPlayerRef = player.getPlayerRef();
        if (uiPlayerRef == null || !uiPlayerRef.isValid()) {
            return LocalizedText.resolve(player, "tamework.ui.settings.openUnavailable");
        }
        if (!hasAccess(uiPlayerRef, uiPlayerRef)) {
            return LocalizedText.resolve(player, "tamework.ui.settings.permissionDenied");
        }
        try {
            TameworkSettingsPage page = new TameworkSettingsPage(uiPlayerRef, plugin, world);
            player.getPageManager().openCustomPage(ref, store, page);
        } catch (Throwable failure) {
            plugin.getLogger().at(java.util.logging.Level.WARNING).withCause(failure)
                    .log("Failed to open Tamework settings page from %s.", entryPoint);
            try {
                plugin.getTelemetryEvents().recordError("ui_page_open_failed", failure,
                        TameworkTelemetryContext.uiPage("TameworkSettingsPage", telemetrySource,
                                "open", "Failed to open Tamework settings page.").build());
            } catch (RuntimeException | LinkageError telemetryFailure) {
                // The local warning already preserves the original page failure.
            }
            return LocalizedText.resolve(player, "tamework.ui.settings.openUnavailable");
        }
        try {
            plugin.getTelemetryEvents().recordUsage(
                    "settings_page_opened",
                    TameworkTelemetryEvents.featureContext("settings", "settings_page", entryPoint)
                            .operation("open")
                            .detail("Opened Tamework settings page.")
                            .detail("source", telemetrySource)
                            .build());
        } catch (RuntimeException | LinkageError failure) {
            plugin.getLogger().at(java.util.logging.Level.WARNING).withCause(failure)
                    .log("Settings opened, but its telemetry event could not be recorded.");
        }
        return null;
    }

    private static boolean isLocalSingleplayerOwner(@Nullable PlayerRef playerRef) {
        return playerRef != null
                && playerRef.isValid()
                && SingleplayerModule.get() != null
                && SingleplayerModule.isOwner(playerRef);
    }

    @Nullable
    private static PlayerRef resolvePlayerRef(@Nullable CommandSender sender) {
        if (sender == null || sender.getUuid() == null) {
            return null;
        }
        Universe universe = Universe.get();
        if (universe == null) {
            return null;
        }
        return universe.getPlayer(sender.getUuid());
    }
}
