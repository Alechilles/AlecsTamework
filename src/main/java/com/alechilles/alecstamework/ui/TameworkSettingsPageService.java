package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.commands.TameworkConfigPermission;
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
            return "Unable to open settings right now.";
        }
        Store<EntityStore> store = ref.getStore();
        if (store == null || store.getExternalData() == null) {
            return "Unable to open settings right now.";
        }
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return "Unable to open settings right now.";
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return "Unable to open settings right now.";
        }
        if (!hasAccess(playerRef, player)) {
            return "You do not have permission to use /tw settings.";
        }
        return openSettingsPage(player, ref, store, world);
    }

    @Nullable
    public static String openSettingsPage(@Nonnull Ref<EntityStore> ref,
                                          @Nonnull Store<EntityStore> store) {
        if (store.getExternalData() == null) {
            return "Unable to open settings right now.";
        }
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return "Unable to open settings right now.";
        }
        return openSettingsPage(ref, store, world);
    }

    @Nullable
    public static String openSettingsPage(@Nonnull Ref<EntityStore> ref,
                                          @Nonnull Store<EntityStore> store,
                                          @Nonnull World world) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return "Unable to open settings right now.";
        }
        return openSettingsPage(player, ref, store, world);
    }

    @Nullable
    private static String openSettingsPage(@Nonnull Player player,
                                           @Nonnull Ref<EntityStore> ref,
                                           @Nonnull Store<EntityStore> store,
                                           @Nonnull World world) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null) {
            return "Tamework settings are not available.";
        }
        if (player.getPageManager() == null) {
            return "Unable to open settings right now.";
        }
        PlayerRef uiPlayerRef = player.getPlayerRef();
        if (uiPlayerRef == null || !uiPlayerRef.isValid()) {
            return "Unable to open settings right now.";
        }
        if (!hasAccess(uiPlayerRef, player)) {
            return "You do not have permission to use /tw settings.";
        }
        TameworkSettingsPage page = new TameworkSettingsPage(uiPlayerRef, plugin, world);
        player.getPageManager().openCustomPage(ref, store, page);
        plugin.getTelemetryEvents().recordUsage("settings_page_opened", "Opened via /tw settings.");
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
