package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.protocol.packets.interface_.UpdateAnchorUI;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import javax.annotation.Nonnull;

/**
 * Mounts avatar-flight controls in Hytale's native server-content HUD anchor.
 */
public final class TameworkAvatarFlightControlOverlay {
    static final String ANCHOR_ID = "MapServerContent";
    static final String ROOT_SELECTOR = "#TameworkAvatarFlightControls";
    static final String UI_PATH = "Hud/TameworkAvatarFlightControls.ui";

    private TameworkAvatarFlightControlOverlay() {
    }

    public static void show(@Nonnull PlayerRef playerRef) {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        commandBuilder.remove(ROOT_SELECTOR);
        commandBuilder.append(UI_PATH);
        send(playerRef, commandBuilder);
    }

    public static void remove(@Nonnull PlayerRef playerRef) {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        commandBuilder.remove(ROOT_SELECTOR);
        send(playerRef, commandBuilder);
    }

    private static void send(@Nonnull PlayerRef playerRef,
                             @Nonnull UICommandBuilder commandBuilder) {
        playerRef.getPacketHandler().writeNoCache(new UpdateAnchorUI(
                ANCHOR_ID,
                false,
                commandBuilder.getCommands(),
                null
        ));
    }
}
