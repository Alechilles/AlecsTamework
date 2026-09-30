package com.alechilles.alecstamework.companion.live;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.UUIDBinaryCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
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
            .append(new KeyedCodec<>("Generation", Codec.LONG),
                    TameworkCompanionComponent::setGeneration, TameworkCompanionComponent::getGeneration)
            .add()
            .build();

    private UUID profileId;
    private Long generation = 0L;

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

    public Long getGeneration() {
        return generation == null ? 0L : generation;
    }

    public void setGeneration(Long generation) {
        this.generation = generation == null ? 0L : generation;
    }

    @Override
    public TameworkCompanionComponent clone() {
        return new TameworkCompanionComponent(profileId, getGeneration());
    }
}
