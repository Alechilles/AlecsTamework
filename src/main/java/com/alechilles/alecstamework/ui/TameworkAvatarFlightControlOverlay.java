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
    static final String UI_PATH = "Hud/TameworkAvatarFlightControls.ui";

    private TameworkAvatarFlightControlOverlay() {
    }

    public static void show(@Nonnull PlayerRef playerRef) {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        commandBuilder.append(UI_PATH);
        playerRef.getPacketHandler().writeNoCache(new UpdateAnchorUI(
                ANCHOR_ID,
                true,
                commandBuilder.getCommands(),
                null
        ));
    }

    public static void remove(@Nonnull PlayerRef playerRef) {
        playerRef.getPacketHandler().writeNoCache(new UpdateAnchorUI(
                ANCHOR_ID,
                true,
                null,
                null
        ));
    }
}
