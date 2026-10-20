package com.alechilles.alecstamework.npc.components;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Exact positive evidence that persistence intentionally retired a live entity.
 *
 * <p>Removal observers may suppress dormant/death authoring only when all three fields match the
 * canonical operation they are resolving. This generic marker is shared by capture mechanisms;
 * it is not itself a completion receipt.</p>
 */
public final class TameworkPersistenceRetirementComponent
        implements Component<EntityStore> {
    public static final BuilderCodec<TameworkPersistenceRetirementComponent> CODEC =
            BuilderCodec.builder(
                    TameworkPersistenceRetirementComponent.class,
                    TameworkPersistenceRetirementComponent::new
            ).<String>append(
                    new KeyedCodec<>("OperationId", Codec.STRING),
                    TameworkPersistenceRetirementComponent::setOperationId,
                    TameworkPersistenceRetirementComponent::getOperationId
            ).add()
                    .<String>append(
                            new KeyedCodec<>("ProfileId", Codec.STRING),
                            TameworkPersistenceRetirementComponent::setProfileId,
                            TameworkPersistenceRetirementComponent::getProfileId
                    ).add()
                    .<String>append(
                            new KeyedCodec<>("OperationKind", Codec.STRING),
                            TameworkPersistenceRetirementComponent::setOperationKind,
                            TameworkPersistenceRetirementComponent::getOperationKind
                    ).add()
                    .build();

    private String operationId;
    private String profileId;
    private String operationKind;

    public TameworkPersistenceRetirementComponent() {
    }

    public TameworkPersistenceRetirementComponent(
            @Nonnull String operationId,
            @Nonnull String profileId,
            @Nonnull String operationKind
    ) {
        setOperationId(operationId);
        setProfileId(profileId);
        setOperationKind(operationKind);
    }

    public String getOperationId() {
        return operationId;
    }

    public void setOperationId(String operationId) {
        this.operationId = requireText(operationId, "Retirement operation ID");
    }

    public String getProfileId() {
        return profileId;
    }

    public void setProfileId(String profileId) {
        this.profileId = requireText(profileId, "Retirement profile ID");
    }

    public String getOperationKind() {
        return operationKind;
    }

    public void setOperationKind(String operationKind) {
        this.operationKind = requireText(
                operationKind, "Retirement operation kind"
        );
    }

    @Override
    @Nonnull
    public TameworkPersistenceRetirementComponent clone() {
        return new TameworkPersistenceRetirementComponent(
                operationId, profileId, operationKind
        );
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
