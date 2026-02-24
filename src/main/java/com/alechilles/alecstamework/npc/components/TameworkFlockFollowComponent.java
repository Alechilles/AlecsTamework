package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.UUIDBinaryCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;

/**
 * Stores runtime flock-follow membership for breeding family groups.
 */
public final class TameworkFlockFollowComponent implements Component<EntityStore> {
    public static final BuilderCodec<TameworkFlockFollowComponent> CODEC = BuilderCodec.builder(
            TameworkFlockFollowComponent.class,
            TameworkFlockFollowComponent::new
    )
        .append(
            new KeyedCodec<>("FlockId", Codec.STRING),
            TameworkFlockFollowComponent::setFlockId,
            TameworkFlockFollowComponent::getFlockId
        )
        .add()
        .append(
            new KeyedCodec<>("LeaderUuid", new UUIDBinaryCodec()),
            TameworkFlockFollowComponent::setLeaderUuid,
            TameworkFlockFollowComponent::getLeaderUuid
        )
        .add()
        .append(
            new KeyedCodec<>("Leader", Codec.BOOLEAN),
            TameworkFlockFollowComponent::setLeader,
            TameworkFlockFollowComponent::isLeader
        )
        .add()
        .append(
            new KeyedCodec<>("FormedAtMs", Codec.LONG),
            TameworkFlockFollowComponent::setFormedAtMs,
            TameworkFlockFollowComponent::getFormedAtMs
        )
        .add()
        .build();

    private String flockId;
    private UUID leaderUuid;
    private boolean leader;
    private long formedAtMs;

    public TameworkFlockFollowComponent() {
    }

    public TameworkFlockFollowComponent(String flockId, UUID leaderUuid, boolean leader, long formedAtMs) {
        this.flockId = flockId;
        this.leaderUuid = leaderUuid;
        this.leader = leader;
        this.formedAtMs = formedAtMs;
    }

    public static ComponentType<EntityStore, TameworkFlockFollowComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getFlockFollowComponentType() : null;
    }

    public String getFlockId() {
        return flockId;
    }

    public void setFlockId(String flockId) {
        this.flockId = flockId;
    }

    public UUID getLeaderUuid() {
        return leaderUuid;
    }

    public void setLeaderUuid(UUID leaderUuid) {
        this.leaderUuid = leaderUuid;
    }

    public boolean isLeader() {
        return leader;
    }

    public void setLeader(boolean leader) {
        this.leader = leader;
    }

    public long getFormedAtMs() {
        return formedAtMs;
    }

    public void setFormedAtMs(long formedAtMs) {
        this.formedAtMs = formedAtMs;
    }

    @Override
    public TameworkFlockFollowComponent clone() {
        return new TameworkFlockFollowComponent(flockId, leaderUuid, leader, formedAtMs);
    }
}
