package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.EnumCodec;
import com.hypixel.hytale.codec.codecs.UUIDBinaryCodec;
import com.hypixel.hytale.codec.codecs.simple.LongCodec;
import com.hypixel.hytale.codec.codecs.simple.StringCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;

/**
 * Component that stores custom NPC name metadata.
 */
public final class TameworkNpcNameComponent implements Component<EntityStore> {
    public enum NameSource {
        Player,
        Config,
        System
    }

    public static final String NAME_TAG = "Name";
    public static final String OWNER_ID_TAG = "OwnerId";
    public static final String UPDATED_TAG = "LastUpdatedMs";
    public static final String SOURCE_TAG = "Source";

    public static final BuilderCodec<TameworkNpcNameComponent> CODEC = BuilderCodec.builder(
            TameworkNpcNameComponent.class,
            TameworkNpcNameComponent::new
    )
        .append(
            new KeyedCodec<>(NAME_TAG, new StringCodec()),
            TameworkNpcNameComponent::setName,
            TameworkNpcNameComponent::getName
        )
        .add()
        .append(
            new KeyedCodec<>(OWNER_ID_TAG, new UUIDBinaryCodec()),
            TameworkNpcNameComponent::setOwnerId,
            TameworkNpcNameComponent::getOwnerId
        )
        .add()
        .append(
            new KeyedCodec<>(UPDATED_TAG, new LongCodec()),
            TameworkNpcNameComponent::setLastUpdatedMs,
            TameworkNpcNameComponent::getLastUpdatedMs
        )
        .add()
        .append(
            new KeyedCodec<>(SOURCE_TAG, new EnumCodec<>(NameSource.class)),
            TameworkNpcNameComponent::setSource,
            TameworkNpcNameComponent::getSource
        )
        .add()
        .build();

    private String name;
    private UUID ownerId;
    private long lastUpdatedMs;
    private NameSource source = NameSource.Player;

    public TameworkNpcNameComponent() {
    }

    public TameworkNpcNameComponent(String name, UUID ownerId, long lastUpdatedMs, NameSource source) {
        this.name = name;
        this.ownerId = ownerId;
        this.lastUpdatedMs = lastUpdatedMs;
        this.source = source != null ? source : NameSource.Player;
    }

    public static ComponentType<EntityStore, TameworkNpcNameComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getNpcNameComponentType() : null;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public long getLastUpdatedMs() {
        return lastUpdatedMs;
    }

    public void setLastUpdatedMs(long lastUpdatedMs) {
        this.lastUpdatedMs = lastUpdatedMs;
    }

    public NameSource getSource() {
        return source;
    }

    public void setSource(NameSource source) {
        this.source = source != null ? source : NameSource.Player;
    }

    @Override
    public TameworkNpcNameComponent clone() {
        return new TameworkNpcNameComponent(name, ownerId, lastUpdatedMs, source);
    }
}
