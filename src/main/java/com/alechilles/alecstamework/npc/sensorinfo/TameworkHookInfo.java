package com.alechilles.alecstamework.npc.sensorinfo;

import com.alechilles.alecstamework.npc.components.TameworkHookComponent;
import com.hypixel.hytale.server.npc.sensorinfo.ExtraInfoProvider;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Extra info payload for Tamework hook sensors.
 */
public final class TameworkHookInfo implements ExtraInfoProvider {
    public static final String PARAM_HOOK_ID = "HookId";
    public static final String PARAM_HOOK_PLAYER_ID = "HookPlayerId";
    public static final String PARAM_HOOK_PLAYER_NAME = "HookPlayerName";
    public static final String PARAM_HOOK_HELD_ITEM_ID = "HookHeldItemId";
    public static final String PARAM_HOOK_TIMESTAMP_MS = "HookTimestampMs";
    public static final String PARAM_HOOK_HAS_TARGET_POSITION = "HookHasTargetPosition";
    public static final String PARAM_HOOK_TARGET_X = "HookTargetX";
    public static final String PARAM_HOOK_TARGET_Y = "HookTargetY";
    public static final String PARAM_HOOK_TARGET_Z = "HookTargetZ";

    private String hookId;
    private UUID playerId;
    private String playerName;
    private String heldItemId;
    private long timestampMs;
    private boolean hasTargetPosition;
    private double targetX;
    private double targetY;
    private double targetZ;

    @Override
    public Class<? extends ExtraInfoProvider> getType() {
        return TameworkHookInfo.class;
    }

    public void updateFrom(@Nullable TameworkHookComponent component) {
        if (component == null) {
            clear();
            return;
        }
        this.hookId = component.getHookId();
        this.playerId = component.getPlayerId();
        this.playerName = component.getPlayerName();
        this.heldItemId = component.getHeldItemId();
        this.timestampMs = component.getTimestampMs();
        this.hasTargetPosition = component.hasTargetPosition();
        this.targetX = component.getTargetX();
        this.targetY = component.getTargetY();
        this.targetZ = component.getTargetZ();
    }

    public void clear() {
        this.hookId = null;
        this.playerId = null;
        this.playerName = null;
        this.heldItemId = null;
        this.timestampMs = 0L;
        this.hasTargetPosition = false;
        this.targetX = 0.0;
        this.targetY = 0.0;
        this.targetZ = 0.0;
    }

    public String getHookId() {
        return hookId;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getHeldItemId() {
        return heldItemId;
    }

    public long getTimestampMs() {
        return timestampMs;
    }

    public boolean hasTargetPosition() {
        return hasTargetPosition;
    }

    public double getTargetX() {
        return targetX;
    }

    public double getTargetY() {
        return targetY;
    }

    public double getTargetZ() {
        return targetZ;
    }
}
