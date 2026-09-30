package com.alechilles.alecstamework.companion.live;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.UUIDBinaryCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Stamp on every companion body: its permanent profile id and the generation of the
 * holder it belongs to (spec 6.1). Codec id "TameworkCompanion"; saved with the chunk.
 * A generation of -1 marks a migrated body that is known to be stale.
 */
public final class TameworkCompanionComponent implements Component<EntityStore> {
    public static final String CODEC_ID = "TameworkCompanion";

    public static final BuilderCodec<TameworkCompanionComponent> CODEC = BuilderCodec.builder(
                    TameworkCompanionComponent.class, TameworkCompanionComponent::new)
            .append(new KeyedCodec<>("ProfileId", new UUIDBinaryCodec()),
                    TameworkCompanionComponent::setProfileId, TameworkCompanionComponent::getProfileId)
            .add()
            .<Long>append(new KeyedCodec<>("Generation", Codec.LONG),
                    (component, value) -> component.setGeneration(value == null ? 0L : value),
                    TameworkCompanionComponent::getGeneration)
            .add()
            .build();

    @Nullable private static ComponentType<EntityStore, TameworkCompanionComponent> type;

    private UUID profileId;
    private long generation;

    public static void register(@Nonnull Tamework plugin) {
        type = plugin.getEntityStoreRegistry().registerComponent(TameworkCompanionComponent.class, CODEC_ID, CODEC);
    }

    /** The registered type, or {@code null} before {@link #register} runs. */
    @Nullable
    public static ComponentType<EntityStore, TameworkCompanionComponent> getComponentType() {
        return type;
    }

    public TameworkCompanionComponent() {
    }

    public TameworkCompanionComponent(UUID profileId, long generation) {
        this.profileId = profileId;
        this.generation = generation;
    }

    @Nullable
    public UUID getProfileId() {
        return profileId;
    }

    public void setProfileId(UUID profileId) {
        this.profileId = profileId;
    }

    public long getGeneration() {
        return generation;
    }

    public void setGeneration(long generation) {
        this.generation = generation;
    }

    @Override
    public TameworkCompanionComponent clone() {
        return new TameworkCompanionComponent(profileId, generation);
    }
}
