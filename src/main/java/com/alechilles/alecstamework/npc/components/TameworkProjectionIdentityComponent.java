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
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Persists the stable profile and opaque receipt that owns an NPC projection.
 *
 * <p>The entity UUID remains a replaceable projection identifier. This marker lets restart
 * reconciliation associate an already-visible entity with the durable recovery or managed-coop
 * operation or bonded lease that created it without inferring identity from role or display
 * name.</p>
 */
public final class TameworkProjectionIdentityComponent implements Component<EntityStore> {
    public static final String KIND_RECOVERY = "RECOVERY";
    public static final String KIND_CAPTURE_RELEASE = "CAPTURE_RELEASE";
    public static final String KIND_MANAGED_COOP_RELEASE = "MANAGED_COOP_RELEASE";
    public static final String KIND_MANAGED_COOP_CAPTURE_SOURCE = "MANAGED_COOP_CAPTURE_SOURCE";
    public static final String KIND_MANAGED_COOP_IMPORT_ADOPTION = "MANAGED_COOP_IMPORT_ADOPTION";
    public static final String KIND_BREEDING_CHILD = "BREEDING_CHILD";
    public static final String KIND_COMMAND_ROSTER = "COMMAND_ROSTER";
    public static final String KIND_PROVISIONING_ACTIVATION =
            "PROVISIONING_ACTIVATION";
    public static final String KIND_BONDED_COMPANION = "BONDED_COMPANION";

    public static final BuilderCodec<TameworkProjectionIdentityComponent> CODEC = BuilderCodec.builder(
            TameworkProjectionIdentityComponent.class,
            TameworkProjectionIdentityComponent::new
    )
        .append(
            new KeyedCodec<>("ProfileId", Codec.STRING),
            TameworkProjectionIdentityComponent::setProfileId,
            TameworkProjectionIdentityComponent::getProfileId
        )
        .add()
        .append(
            new KeyedCodec<>("OperationId", Codec.STRING),
            TameworkProjectionIdentityComponent::setOperationId,
            TameworkProjectionIdentityComponent::getOperationId
        )
        .add()
        .append(
            new KeyedCodec<>("ProjectionKind", Codec.STRING),
            TameworkProjectionIdentityComponent::setProjectionKind,
            TameworkProjectionIdentityComponent::getProjectionKind
        )
        .add()
        .append(
            new KeyedCodec<>("SlotKey", Codec.STRING),
            TameworkProjectionIdentityComponent::setSlotKey,
            TameworkProjectionIdentityComponent::getSlotKey
        )
        .add()
        .append(
            new KeyedCodec<>("SourceNpcUuid", new UUIDBinaryCodec()),
            TameworkProjectionIdentityComponent::setSourceNpcUuid,
            TameworkProjectionIdentityComponent::getSourceNpcUuid
        )
        .add()
        .append(
            new KeyedCodec<>("Generation", Codec.LONG),
            TameworkProjectionIdentityComponent::setGeneration,
            TameworkProjectionIdentityComponent::getGeneration
        )
        .add()
        .build();

    private String profileId;
    private String operationId;
    private String projectionKind;
    private String slotKey;
    private UUID sourceNpcUuid;
    private long generation;

    public TameworkProjectionIdentityComponent() {
    }

    public TameworkProjectionIdentityComponent(String profileId,
                                                String operationId,
                                                String projectionKind,
                                                @Nullable String slotKey,
                                                @Nullable UUID sourceNpcUuid,
                                                long generation) {
        this.profileId = profileId;
        this.operationId = operationId;
        this.projectionKind = projectionKind;
        this.slotKey = slotKey;
        this.sourceNpcUuid = sourceNpcUuid;
        this.generation = generation;
    }

    /** Creates the exact marker for one disposable bonded projection. */
    @Nonnull
    public static TameworkProjectionIdentityComponent bondedCompanion(
            String profileId,
            String leaseToken
    ) {
        return new TameworkProjectionIdentityComponent(
                required(profileId, "profileId"),
                required(leaseToken, "leaseToken"),
                KIND_BONDED_COMPANION,
                null,
                null,
                0L
        );
    }

    public static ComponentType<EntityStore, TameworkProjectionIdentityComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getProjectionIdentityComponentType() : null;
    }

    public String getProfileId() {
        return profileId;
    }

    public void setProfileId(String profileId) {
        this.profileId = profileId;
    }

    public String getOperationId() {
        return operationId;
    }

    public void setOperationId(String operationId) {
        this.operationId = operationId;
    }

    public String getProjectionKind() {
        return projectionKind;
    }

    public void setProjectionKind(String projectionKind) {
        this.projectionKind = projectionKind;
    }

    @Nullable
    public String getSlotKey() {
        return slotKey;
    }

    public void setSlotKey(@Nullable String slotKey) {
        this.slotKey = slotKey;
    }

    @Nullable
    public UUID getSourceNpcUuid() {
        return sourceNpcUuid;
    }

    public void setSourceNpcUuid(@Nullable UUID sourceNpcUuid) {
        this.sourceNpcUuid = sourceNpcUuid;
    }

    public long getGeneration() {
        return generation;
    }

    /** Returns whether this marker belongs to the isolated bonded lifecycle. */
    public boolean isBondedCompanion() {
        return KIND_BONDED_COMPANION.equals(projectionKind);
    }

    /** Returns the opaque bonded lease token, or {@code null} for other marker kinds. */
    @Nullable
    public String getBondedLeaseToken() {
        return isBondedCompanion() ? operationId : null;
    }

    public void setGeneration(long generation) {
        this.generation = generation;
    }

    public boolean matches(String expectedKind, String expectedOperationId, String expectedProfileId) {
        return expectedKind != null
                && expectedKind.equals(projectionKind)
                && expectedOperationId != null
                && expectedOperationId.equals(operationId)
                && expectedProfileId != null
                && expectedProfileId.equals(profileId);
    }

    @Override
    public TameworkProjectionIdentityComponent clone() {
        return new TameworkProjectionIdentityComponent(
                profileId,
                operationId,
                projectionKind,
                slotKey,
                sourceNpcUuid,
                generation
        );
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
