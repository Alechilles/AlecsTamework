package com.alechilles.alecstamework.selftest;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.UUIDBinaryCodec;
import com.hypixel.hytale.codec.codecs.simple.StringCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;

/**
 * Marks live NPC fixtures created by the in-game API self-test system.
 */
public final class ApiSelfTestFixtureMarkerComponent implements Component<EntityStore> {
    public static final BuilderCodec<ApiSelfTestFixtureMarkerComponent> CODEC = BuilderCodec.builder(
            ApiSelfTestFixtureMarkerComponent.class,
            ApiSelfTestFixtureMarkerComponent::new
    ).append(
            new KeyedCodec<>("FixtureSetId", new StringCodec()),
            ApiSelfTestFixtureMarkerComponent::setFixtureSetId,
            ApiSelfTestFixtureMarkerComponent::getFixtureSetId
    ).add().append(
            new KeyedCodec<>("FixtureKey", new StringCodec()),
            ApiSelfTestFixtureMarkerComponent::setFixtureKey,
            ApiSelfTestFixtureMarkerComponent::getFixtureKey
    ).add().append(
            new KeyedCodec<>("OwnerPlayerUuid", new UUIDBinaryCodec()),
            ApiSelfTestFixtureMarkerComponent::setOwnerPlayerUuid,
            ApiSelfTestFixtureMarkerComponent::getOwnerPlayerUuid
    ).add().append(
            new KeyedCodec<>("ToolId", new StringCodec()),
            ApiSelfTestFixtureMarkerComponent::setToolId,
            ApiSelfTestFixtureMarkerComponent::getToolId
    ).add().append(
            new KeyedCodec<>("CreatedAtMs", Codec.LONG),
            ApiSelfTestFixtureMarkerComponent::setCreatedAtMs,
            ApiSelfTestFixtureMarkerComponent::getCreatedAtMs
    ).add().build();

    private String fixtureSetId;
    private String fixtureKey;
    private UUID ownerPlayerUuid;
    private String toolId;
    private long createdAtMs;

    public ApiSelfTestFixtureMarkerComponent() {
    }

    public ApiSelfTestFixtureMarkerComponent(String fixtureSetId,
                                             String fixtureKey,
                                             UUID ownerPlayerUuid,
                                             String toolId,
                                             long createdAtMs) {
        this.fixtureSetId = fixtureSetId;
        this.fixtureKey = fixtureKey;
        this.ownerPlayerUuid = ownerPlayerUuid;
        this.toolId = toolId;
        this.createdAtMs = createdAtMs;
    }

    public static ComponentType<EntityStore, ApiSelfTestFixtureMarkerComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getApiSelfTestFixtureMarkerComponentType() : null;
    }

    public String getFixtureSetId() {
        return fixtureSetId;
    }

    public void setFixtureSetId(String fixtureSetId) {
        this.fixtureSetId = fixtureSetId;
    }

    public String getFixtureKey() {
        return fixtureKey;
    }

    public void setFixtureKey(String fixtureKey) {
        this.fixtureKey = fixtureKey;
    }

    public UUID getOwnerPlayerUuid() {
        return ownerPlayerUuid;
    }

    public void setOwnerPlayerUuid(UUID ownerPlayerUuid) {
        this.ownerPlayerUuid = ownerPlayerUuid;
    }

    public String getToolId() {
        return toolId;
    }

    public void setToolId(String toolId) {
        this.toolId = toolId;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }

    public void setCreatedAtMs(long createdAtMs) {
        this.createdAtMs = createdAtMs;
    }

    @Override
    public ApiSelfTestFixtureMarkerComponent clone() {
        return new ApiSelfTestFixtureMarkerComponent(
                fixtureSetId,
                fixtureKey,
                ownerPlayerUuid,
                toolId,
                createdAtMs
        );
    }
}
