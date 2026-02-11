package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.UUIDBinaryCodec;
import com.hypixel.hytale.codec.codecs.simple.BooleanCodec;
import com.hypixel.hytale.codec.codecs.simple.LongCodec;
import com.hypixel.hytale.codec.codecs.simple.StringCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;

/**
 * Component that stores the latest Tamework hook signal for NPC instruction bridges.
 */
public final class TameworkHookComponent implements Component<EntityStore> {
    public static final String HOOK_ID_TAG = "HookId";
    public static final String PLAYER_ID_TAG = "PlayerId";
    public static final String PLAYER_NAME_TAG = "PlayerName";
    public static final String HELD_ITEM_TAG = "HeldItemId";
    public static final String TIMESTAMP_TAG = "TimestampMs";
    public static final String CONSUME_TAG = "ConsumeOnMatch";

    public static final BuilderCodec<TameworkHookComponent> CODEC = BuilderCodec.builder(
            TameworkHookComponent.class,
            TameworkHookComponent::new
    )
        .append(
            new KeyedCodec<>(HOOK_ID_TAG, new StringCodec()),
            TameworkHookComponent::setHookId,
            TameworkHookComponent::getHookId
        )
        .add()
        .append(
            new KeyedCodec<>(PLAYER_ID_TAG, new UUIDBinaryCodec()),
            TameworkHookComponent::setPlayerId,
            TameworkHookComponent::getPlayerId
        )
        .add()
        .append(
            new KeyedCodec<>(PLAYER_NAME_TAG, new StringCodec()),
            TameworkHookComponent::setPlayerName,
            TameworkHookComponent::getPlayerName
        )
        .add()
        .append(
            new KeyedCodec<>(HELD_ITEM_TAG, new StringCodec()),
            TameworkHookComponent::setHeldItemId,
            TameworkHookComponent::getHeldItemId
        )
        .add()
        .append(
            new KeyedCodec<>(TIMESTAMP_TAG, new LongCodec()),
            TameworkHookComponent::setTimestampMs,
            TameworkHookComponent::getTimestampMs
        )
        .add()
        .append(
            new KeyedCodec<>(CONSUME_TAG, new BooleanCodec()),
            TameworkHookComponent::setConsumeOnMatch,
            TameworkHookComponent::isConsumeOnMatch
        )
        .add()
        .build();

    private String hookId;
    private UUID playerId;
    private String playerName;
    private String heldItemId;
    private long timestampMs;
    private boolean consumeOnMatch;

    public TameworkHookComponent() {
    }

    public TameworkHookComponent(String hookId,
                                 UUID playerId,
                                 String playerName,
                                 String heldItemId,
                                 long timestampMs,
                                 boolean consumeOnMatch) {
        this.hookId = hookId;
        this.playerId = playerId;
        this.playerName = playerName;
        this.heldItemId = heldItemId;
        this.timestampMs = timestampMs;
        this.consumeOnMatch = consumeOnMatch;
    }

    public static ComponentType<EntityStore, TameworkHookComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getHookComponentType() : null;
    }

    public String getHookId() {
        return hookId;
    }

    public void setHookId(String hookId) {
        this.hookId = hookId;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public String getHeldItemId() {
        return heldItemId;
    }

    public void setHeldItemId(String heldItemId) {
        this.heldItemId = heldItemId;
    }

    public long getTimestampMs() {
        return timestampMs;
    }

    public void setTimestampMs(long timestampMs) {
        this.timestampMs = timestampMs;
    }

    public boolean isConsumeOnMatch() {
        return consumeOnMatch;
    }

    public void setConsumeOnMatch(boolean consumeOnMatch) {
        this.consumeOnMatch = consumeOnMatch;
    }

    public boolean matchesHook(String hookId) {
        if (hookId == null || hookId.isBlank()) {
            return false;
        }
        if (this.hookId == null || this.hookId.isBlank()) {
            return false;
        }
        return this.hookId.equalsIgnoreCase(hookId);
    }

    @Override
    public TameworkHookComponent clone() {
        return new TameworkHookComponent(hookId, playerId, playerName, heldItemId, timestampMs, consumeOnMatch);
    }
}
