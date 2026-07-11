package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Restores durable NPC state either into a pre-add entity holder or an existing entity buffer.
 *
 * <p>Snapshot components are deep-copied through the strict snapshot codec before being handed to
 * ECS. This preserves signed timestamps and prevents a stored snapshot from sharing mutable arrays
 * or component instances with the spawned entity. Health, runtime display name, and model-backed
 * attachment effects are returned as explicit post-add work because they require a live entity.</p>
 */
public final class CoopResidentStateRestorer {
    private final CoopResidentStateSnapshotCodec snapshotCodec;

    public CoopResidentStateRestorer() {
        this(new CoopResidentStateSnapshotCodec());
    }

    CoopResidentStateRestorer(@Nonnull CoopResidentStateSnapshotCodec snapshotCodec) {
        this.snapshotCodec = snapshotCodec;
    }

    /**
     * Installs every durable component before {@code Store.addEntity} and returns deferred effects.
     */
    @Nonnull
    public PostAddWork restoreToHolder(
            @Nonnull Holder<EntityStore> holder,
            @Nonnull CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot,
            @Nullable TameworkProjectionIdentityComponent projectionMarker) {
        return restore((slot, component) -> putOnHolder(holder, slot, component), snapshot, projectionMarker);
    }

    /** Restores the same component set onto an existing entity through its command buffer. */
    @Nonnull
    public PostAddWork restoreToCommandBuffer(
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> reference,
            @Nonnull CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot) {
        return restore(
                (slot, component) -> putOnCommandBuffer(commandBuffer, reference, slot, component),
                snapshot,
                null
        );
    }

    /**
     * Pure/injectable restoration boundary used by spawn planners and deterministic tests.
     */
    @Nonnull
    public PostAddWork restore(
            @Nonnull ComponentWriter writer,
            @Nonnull CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot,
            @Nullable TameworkProjectionIdentityComponent projectionMarker) {
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot copy = snapshotCodec.copy(snapshot);
        write(writer, ComponentSlot.COMMAND_LINKS, copy.commandLinks());
        write(writer, ComponentSlot.OWNER, copy.owner());
        write(writer, ComponentSlot.TAMED, copy.tamed());
        write(writer, ComponentSlot.NPC_NAME, copy.npcName());
        write(writer, ComponentSlot.HAPPINESS, copy.happiness());
        write(writer, ComponentSlot.NEEDS, copy.needs());
        write(writer, ComponentSlot.BREEDING, copy.breeding());
        write(writer, ComponentSlot.LEVELING, copy.leveling());
        write(writer, ComponentSlot.TRAITS, copy.traits());
        write(writer, ComponentSlot.TALENTS, copy.talents());
        write(writer, ComponentSlot.LIFE_STAGE, copy.lifeStage());
        write(writer, ComponentSlot.ATTACHMENTS, copy.attachments());
        if (projectionMarker != null) {
            writer.put(ComponentSlot.PROJECTION_IDENTITY, projectionMarker.clone());
        }
        String displayName = copy.npcName() != null ? copy.npcName().getName() : null;
        TameworkAttachmentsComponent attachments = copy.attachments() != null
                ? new TameworkAttachmentsComponent(
                        copy.attachments().getConfigId(),
                        copy.attachments().getAttachmentIds()
                )
                : null;
        return new PostAddWork(displayName, copy.healthPercent(), attachments);
    }

    private void write(@Nonnull ComponentWriter writer,
                       @Nonnull ComponentSlot slot,
                       @Nullable Component<EntityStore> component) {
        if (component != null) {
            writer.put(slot, component);
        }
    }

    private void putOnHolder(@Nonnull Holder<EntityStore> holder,
                             @Nonnull ComponentSlot slot,
                             @Nonnull Component<EntityStore> component) {
        putUnchecked(holder, resolveType(slot), component);
    }

    private void putOnCommandBuffer(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                                    @Nonnull Ref<EntityStore> reference,
                                    @Nonnull ComponentSlot slot,
                                    @Nonnull Component<EntityStore> component) {
        putUnchecked(commandBuffer, reference, resolveType(slot), component);
    }

    @Nonnull
    private ComponentType<EntityStore, ? extends Component<EntityStore>> resolveType(@Nonnull ComponentSlot slot) {
        ComponentType<EntityStore, ? extends Component<EntityStore>> type = switch (slot) {
            case COMMAND_LINKS -> TameworkCommandLinksComponent.getComponentType();
            case OWNER -> TameworkOwnerComponent.getComponentType();
            case TAMED -> TameworkTamedComponent.getComponentType();
            case NPC_NAME -> TameworkNpcNameComponent.getComponentType();
            case HAPPINESS -> TameworkHappinessComponent.getComponentType();
            case NEEDS -> TameworkNeedsComponent.getComponentType();
            case BREEDING -> TameworkBreedingComponent.getComponentType();
            case LEVELING -> TameworkLevelingComponent.getComponentType();
            case TRAITS -> TameworkTraitsComponent.getComponentType();
            case TALENTS -> TameworkTalentsComponent.getComponentType();
            case LIFE_STAGE -> TameworkLifeStageComponent.getComponentType();
            case ATTACHMENTS -> TameworkAttachmentsComponent.getComponentType();
            case PROJECTION_IDENTITY -> TameworkProjectionIdentityComponent.getComponentType();
        };
        if (type == null) {
            throw new IllegalStateException("Tamework component type is not registered: " + slot);
        }
        return type;
    }

    /**
     * The slot-to-component mapping above guarantees matching types; this helper only bridges the
     * wildcard produced by a heterogeneous restoration plan into Hytale's generic ECS API.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void putUnchecked(@Nonnull Holder<EntityStore> holder,
                              @Nonnull ComponentType<EntityStore, ? extends Component<EntityStore>> type,
                              @Nonnull Component<EntityStore> component) {
        holder.putComponent((ComponentType) type, component);
    }

    /** See {@link #putUnchecked(Holder, ComponentType, Component)}. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void putUnchecked(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                              @Nonnull Ref<EntityStore> reference,
                              @Nonnull ComponentType<EntityStore, ? extends Component<EntityStore>> type,
                              @Nonnull Component<EntityStore> component) {
        commandBuffer.putComponent(reference, (ComponentType) type, component);
    }

    /** Stable component identities for pure pre-add planning and tests. */
    public enum ComponentSlot {
        COMMAND_LINKS,
        OWNER,
        TAMED,
        NPC_NAME,
        HAPPINESS,
        NEEDS,
        BREEDING,
        LEVELING,
        TRAITS,
        TALENTS,
        LIFE_STAGE,
        ATTACHMENTS,
        PROJECTION_IDENTITY
    }

    /** Injectable sink that keeps holder construction out of unit tests. */
    @FunctionalInterface
    public interface ComponentWriter {
        void put(@Nonnull ComponentSlot slot, @Nonnull Component<EntityStore> component);
    }

    /**
     * Effects that cannot safely run until the new entity has been added to its store.
     */
    public record PostAddWork(@Nullable String displayName,
                              @Nullable Double healthPercent,
                              @Nullable TameworkAttachmentsComponent attachments) {
        public boolean hasDisplayNameWork() {
            return displayName != null && !displayName.isBlank();
        }

        public boolean hasHealthWork() {
            return healthPercent != null;
        }

        public boolean hasAttachmentWork() {
            return attachments != null && !attachments.getAttachmentIds().isEmpty();
        }
    }
}
