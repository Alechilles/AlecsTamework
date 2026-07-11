package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.NpcDisplayNameComponentService;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionHealthStateService;
import com.alechilles.alecstamework.npc.progression.CompanionStatModifierService;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Applies coop snapshot fields after owner admission has been claimed and written. */
final class CoopResidentSnapshotApplicationService {
    void applyBuffered(@Nonnull Ref<EntityStore> reference,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot) {
        put(commandBuffer, reference, TameworkCommandLinksComponent.getComponentType(), snapshot.commandLinks());
        put(commandBuffer, reference, TameworkTamedComponent.getComponentType(), snapshot.tamed());
        put(commandBuffer, reference, TameworkNpcNameComponent.getComponentType(), snapshot.npcName());
        put(commandBuffer, reference, TameworkHappinessComponent.getComponentType(), snapshot.happiness());
        put(commandBuffer, reference, TameworkNeedsComponent.getComponentType(), snapshot.needs());
        put(commandBuffer, reference, TameworkBreedingComponent.getComponentType(), snapshot.breeding());
        put(commandBuffer, reference, TameworkLevelingComponent.getComponentType(), snapshot.leveling());
        put(commandBuffer, reference, TameworkTraitsComponent.getComponentType(), snapshot.traits());
        put(commandBuffer, reference, TameworkTalentsComponent.getComponentType(), snapshot.talents());
        put(commandBuffer, reference, TameworkLifeStageComponent.getComponentType(), snapshot.lifeStage());
        put(commandBuffer, reference, TameworkAttachmentsComponent.getComponentType(), snapshot.attachments());
        applyBufferedDisplayName(reference, commandBuffer, snapshot.npcName());
        if (snapshot.healthPercent() != null) {
            commandBuffer.run(bufferStore -> applyHealth(reference, bufferStore, snapshot.healthPercent()));
        }
    }

    void applyDirect(@Nonnull Ref<EntityStore> reference,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot) {
        put(store, reference, TameworkCommandLinksComponent.getComponentType(), snapshot.commandLinks());
        put(store, reference, TameworkTamedComponent.getComponentType(), snapshot.tamed());
        put(store, reference, TameworkNpcNameComponent.getComponentType(), snapshot.npcName());
        put(store, reference, TameworkHappinessComponent.getComponentType(), snapshot.happiness());
        put(store, reference, TameworkNeedsComponent.getComponentType(), snapshot.needs());
        put(store, reference, TameworkBreedingComponent.getComponentType(), snapshot.breeding());
        put(store, reference, TameworkLevelingComponent.getComponentType(), snapshot.leveling());
        put(store, reference, TameworkTraitsComponent.getComponentType(), snapshot.traits());
        put(store, reference, TameworkTalentsComponent.getComponentType(), snapshot.talents());
        put(store, reference, TameworkLifeStageComponent.getComponentType(), snapshot.lifeStage());
        put(store, reference, TameworkAttachmentsComponent.getComponentType(), snapshot.attachments());
        applyDirectDisplayName(reference, store, snapshot.npcName());
        applyHealth(reference, store, snapshot.healthPercent());
    }

    void applyLinkedFallbackDirect(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull Store<EntityStore> store,
            @Nullable CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot snapshot
    ) {
        if (snapshot == null) {
            return;
        }
        String[] toolIds = snapshot.toolIds();
        if (toolIds != null && toolIds.length > 0) {
            put(store, reference, TameworkCommandLinksComponent.getComponentType(),
                    new TameworkCommandLinksComponent(snapshot.ownerId(), toolIds));
        }
        String displayName = snapshot.displayName();
        if (displayName == null || displayName.isBlank()) {
            return;
        }
        put(store, reference, TameworkNpcNameComponent.getComponentType(), new TameworkNpcNameComponent(
                displayName,
                snapshot.ownerId(),
                System.currentTimeMillis(),
                TameworkNpcNameComponent.NameSource.System
        ));
        NpcDisplayNameComponentService.putPersistentAndRuntimeName(store, reference, displayName);
    }

    void applyHealth(@Nullable Ref<EntityStore> reference,
                     @Nullable Store<EntityStore> store,
                     @Nullable Double healthPercent) {
        if (reference == null || !reference.isValid() || store == null || healthPercent == null) {
            return;
        }
        CompanionStatModifierService.applyTraitModifiers(reference, store);
        CompanionHealthStateService.applyStoredHealthPercent(reference, store, healthPercent);
    }

    private static void applyBufferedDisplayName(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nullable TameworkNpcNameComponent npcName
    ) {
        if (npcName != null && npcName.getName() != null && !npcName.getName().isBlank()) {
            NpcDisplayNameComponentService.putPersistentAndRuntimeName(
                    commandBuffer, reference, npcName.getName()
            );
        }
    }

    private static void applyDirectDisplayName(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull Store<EntityStore> store,
            @Nullable TameworkNpcNameComponent npcName
    ) {
        if (npcName != null && npcName.getName() != null && !npcName.getName().isBlank()) {
            NpcDisplayNameComponentService.putPersistentAndRuntimeName(store, reference, npcName.getName());
        }
    }

    private static <T extends Component<EntityStore>> void put(
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> reference,
            @Nullable ComponentType<EntityStore, T> type,
            @Nullable T component
    ) {
        if (type != null && component != null) {
            commandBuffer.putComponent(reference, type, copy(component));
        }
    }

    private static <T extends Component<EntityStore>> void put(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> reference,
            @Nullable ComponentType<EntityStore, T> type,
            @Nullable T component
    ) {
        if (type != null && component != null) {
            store.putComponent(reference, type, copy(component));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Component<EntityStore>> T copy(@Nonnull T component) {
        return (T) component.clone();
    }
}
