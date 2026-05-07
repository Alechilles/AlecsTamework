package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.items.CommandNpcRelocationService;
import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.alechilles.alecstamework.items.CommandLinkedNpcLostService;
import com.alechilles.alecstamework.items.CommandLinkedNpcCoopService;
import com.alechilles.alecstamework.items.CommandLinkedNpcStateSnapshotService;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService;
import com.alechilles.alecstamework.items.CoopDebugLogger;
import com.alechilles.alecstamework.npc.progression.CompanionModelAttachmentService;
import com.alechilles.alecstamework.npc.progression.CompanionModelScaleService;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.builtin.adventure.farming.component.CoopResidentComponent;
import com.hypixel.hytale.builtin.adventure.farming.config.FarmingCoopAsset;
import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.reference.PersistentRef;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Attempts queued command relocation requests when NPC entities are added back into the world store.
 */
public final class CommandNpcRelocationOnLoadSystem extends RefSystem<EntityStore> {
    @Nullable
    private static final Field COOP_RESIDENTS_FIELD = resolveCoopResidentsField();
    @Nullable
    private static final Field STATE_INTERACTABLE_PLAYERS_FIELD = resolveStateSupportField("interactablePlayers");
    @Nullable
    private static final Field STATE_INTERACTED_PLAYERS_FIELD = resolveStateSupportField("interactedPlayers");
    @Nullable
    private static final Field STATE_CONTEXTUAL_INTERACTIONS_FIELD = resolveStateSupportField("contextualInteractions");

    private final CommandNpcRelocationService relocationService;
    private final CommandLinkedNpcDeathService deathService;
    private final CommandLinkedNpcLostService lostService;
    private final CommandLinkedNpcStateSnapshotService stateSnapshotService;
    private final CoopResidentStateSnapshotService coopStateSnapshotService;
    @Nullable
    private final CommandLinkedNpcCoopService coopService;

    public CommandNpcRelocationOnLoadSystem(CommandNpcRelocationService relocationService,
                                            CommandLinkedNpcDeathService deathService,
                                            CommandLinkedNpcLostService lostService,
                                            CommandLinkedNpcStateSnapshotService stateSnapshotService,
                                            CoopResidentStateSnapshotService coopStateSnapshotService,
                                            @Nullable CommandLinkedNpcCoopService coopService) {
        this.relocationService = relocationService;
        this.deathService = deathService;
        this.lostService = lostService;
        this.stateSnapshotService = stateSnapshotService;
        this.coopStateSnapshotService = coopStateSnapshotService;
        this.coopService = coopService;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        sanitizeRoleReferencesOnAdd(reference, store);
        if (coopService != null) {
            applyCoopAttachmentPreload(reference, store, commandBuffer);
        }
        if (stateSnapshotService != null) {
            stateSnapshotService.onNpcAdded(reference, store);
        }
        if (relocationService == null) {
            if (deathService != null) {
                deathService.onNpcAdded(reference, store);
            }
            if (lostService != null) {
                lostService.onNpcAdded(reference, store);
            }
            return;
        }
        relocationService.onNpcAdded(reference, store);
        if (deathService != null) {
            deathService.onNpcAdded(reference, store);
        }
        if (lostService != null) {
            lostService.onNpcAdded(reference, store);
        }
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> reference,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (stateSnapshotService != null) {
            stateSnapshotService.onNpcRemoved(reference, reason, store);
        }
        CoopResidentStateSnapshotService.RemovedCoopResidentCapture removedCoopCapture = null;
        if (coopStateSnapshotService != null) {
            removedCoopCapture = coopStateSnapshotService.onNpcRemoved(reference, reason, store);
        }
        if (coopService != null && removedCoopCapture != null) {
            recaptureCoopResidentOnRemove(removedCoopCapture);
        }
        if (relocationService != null) {
            relocationService.onNpcRemoved(reference, reason, store);
        }
        if (deathService != null) {
            deathService.onNpcRemoved(reference, reason, store);
        }
        if (lostService != null) {
            lostService.onNpcRemoved(reference, reason, store);
        }
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    private void applyCoopAttachmentPreload(@Nonnull Ref<EntityStore> reference,
                                            @Nonnull Store<EntityStore> store,
                                            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (coopService == null || reference == null || !reference.isValid()) {
            return;
        }
        ComponentType<EntityStore, CoopResidentComponent> coopResidentType = CoopResidentComponent.getComponentType();
        if (coopResidentType == null) {
            return;
        }
        CoopResidentComponent coopResident = store.getComponent(reference, coopResidentType);
        if (coopResident == null || coopResident.getMarkedForDespawn()) {
            return;
        }
        NPCEntity npc = store.getComponent(reference, NPCEntity.getComponentType());
        if (npc == null) {
            return;
        }
        UUID currentUuid = resolveCurrentNpcUuid(reference, store, npc);
        if (currentUuid == null) {
            return;
        }
        CoopResidentContext context = resolveCoopContext(store, coopResident);
        if (context == null || context.coopId() == null) {
            return;
        }
        int residentSlot = context.resolveResidentSlot(currentUuid);
        if (residentSlot < 0) {
            return;
        }
        CommandLinkedNpcCoopService.CoopSlotContext slotContext = CommandLinkedNpcCoopService.CoopSlotContext.of(
                context.worldName(),
                context.coopId(),
                context.coopLocation().x,
                context.coopLocation().y,
                context.coopLocation().z,
                residentSlot
        );
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot =
                coopService.getStateSnapshotForSlot(slotContext);
        if (snapshot == null || snapshot.attachments() == null || snapshot.attachments().getAttachmentIds().isEmpty()) {
            return;
        }

        ModelComponent modelComponent = store.getComponent(reference, ModelComponent.getComponentType());
        if (modelComponent == null || modelComponent.getModel() == null) {
            return;
        }
        Model model = modelComponent.getModel();
        ModelAsset modelAsset = ModelAsset.getAssetMap().getAsset(model.getModelAssetId());
        if (modelAsset == null) {
            return;
        }
        Map<String, String> filteredSelections = CompanionModelAttachmentService.filterAttachmentSelections(
                snapshot.attachments().getAttachmentIds(),
                CompanionModelAttachmentService.resolveAttachmentOptionIds(modelAsset)
        );
        if (filteredSelections.isEmpty()) {
            return;
        }
        Map<String, String> currentSelections = CompanionModelAttachmentService.resolveCurrentAttachments(reference, store);
        if (currentSelections.equals(filteredSelections)) {
            return;
        }
        Model updatedModel = CompanionModelScaleService.createScaledModel(
                reference,
                store,
                modelAsset,
                model.getScale(),
                filteredSelections
        );
        if (updatedModel == null) {
            return;
        }
        commandBuffer.putComponent(reference, ModelComponent.getComponentType(), new ModelComponent(updatedModel));
    }

    @Nullable
    private UUID resolveCurrentNpcUuid(@Nonnull Ref<EntityStore> reference,
                                       @Nonnull Store<EntityStore> store,
                                       @Nonnull NPCEntity npc) {
        if (npc.getUuid() != null) {
            return npc.getUuid();
        }
        ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
        if (uuidType == null) {
            return null;
        }
        UUIDComponent uuidComponent = store.getComponent(reference, uuidType);
        return uuidComponent != null ? uuidComponent.getUuid() : null;
    }

    @Nullable
    private CoopResidentContext resolveCoopContext(@Nonnull Store<EntityStore> store,
                                                   @Nonnull CoopResidentComponent coopResident) {
        Vector3i coopLocation = coopResident.getCoopLocation();
        if (coopLocation == null) {
            return null;
        }
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        if (world == null || world.getChunkStore() == null) {
            return null;
        }
        WorldChunk worldChunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(coopLocation.x, coopLocation.z));
        if (worldChunk == null) {
            return null;
        }
        Ref<ChunkStore> blockRef = worldChunk.getBlockComponentEntity(coopLocation.x, coopLocation.y, coopLocation.z);
        if (blockRef == null || !blockRef.isValid()) {
            return null;
        }
        Store<ChunkStore> chunkStore = world.getChunkStore().getStore();
        if (chunkStore == null) {
            return null;
        }
        CoopBlock coopBlock = chunkStore.getComponent(blockRef, CoopBlock.getComponentType());
        if (coopBlock == null) {
            return null;
        }
        FarmingCoopAsset coopAsset = coopBlock.getCoopAsset();
        return new CoopResidentContext(
                world.getName(),
                coopAsset != null ? coopAsset.getId() : null,
                coopLocation,
                coopBlock
        );
    }

    private void recaptureCoopResidentOnRemove(@Nonnull CoopResidentStateSnapshotService.RemovedCoopResidentCapture capture) {
        if (coopService == null || capture.snapshot() == null) {
            return;
        }
        UUID npcUuid = capture.npcUuid();
        String coopId = capture.coopId();
        String roleId = capture.roleId();
        Vector3i coopLocation = capture.coopLocation();
        if (npcUuid == null || coopId == null || coopLocation == null) {
            return;
        }
        TameworkCommandLinksComponent links = capture.snapshot().commandLinks();
        UUID ownerId = links != null && links.getOwnerId() != null
                ? links.getOwnerId()
                : capture.snapshot().owner() != null
                ? capture.snapshot().owner().getOwnerId()
                : null;
        String[] toolIds = links != null ? links.getToolIds() : null;
        TameworkNpcNameComponent npcName = capture.snapshot().npcName();
        String displayName = npcName != null ? npcName.getName() : null;

        if (capture.residentSlot() >= 0) {
            coopService.captureResident(
                    npcUuid,
                    roleId,
                    CommandLinkedNpcCoopService.CoopSlotContext.of(
                            capture.worldName(),
                            coopId,
                            coopLocation.x,
                            coopLocation.y,
                            coopLocation.z,
                            capture.residentSlot()
                    ),
                    ownerId,
                    toolIds,
                    displayName,
                    capture.snapshot()
            );
            return;
        }

        boolean recaptured = coopService.recaptureResidentFromReleasedUuid(
                npcUuid,
                roleId,
                capture.worldName(),
                coopId,
                coopLocation.x,
                coopLocation.y,
                coopLocation.z,
                ownerId,
                toolIds,
                displayName,
                capture.snapshot()
        );
        if (!recaptured) {
            CoopDebugLogger.log(
                    "remove recapture miss npc=" + npcUuid
                            + " coop=" + coopId
                            + " coords=" + coopLocation.x + "," + coopLocation.y + "," + coopLocation.z
            );
        }
    }

    private void sanitizeRoleReferencesOnAdd(@Nonnull Ref<EntityStore> reference, @Nonnull Store<EntityStore> store) {
        if (reference == null || !reference.isValid() || store == null) {
            return;
        }
        NPCEntity npc = store.getComponent(reference, NPCEntity.getComponentType());
        if (npc == null) {
            return;
        }
        Role role = npc.getRole();
        if (role == null) {
            return;
        }
        sanitizeMarkedEntitySupport(role, store);
        sanitizeStateSupport(role, store);
    }

    private void sanitizeMarkedEntitySupport(@Nonnull Role role, @Nonnull Store<EntityStore> store) {
        MarkedEntitySupport markedEntitySupport = role.getMarkedEntitySupport();
        if (markedEntitySupport == null) {
            return;
        }
        Ref<EntityStore>[] targets = markedEntitySupport.getEntityTargets();
        if (targets == null || targets.length == 0) {
            return;
        }
        for (int slot = 0; slot < targets.length; slot++) {
            Ref<EntityStore> target = targets[slot];
            if (isRefInCurrentStore(target, store)) {
                continue;
            }
            markedEntitySupport.setMarkedEntity(slot, null);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void sanitizeStateSupport(@Nonnull Role role, @Nonnull Store<EntityStore> store) {
        StateSupport stateSupport = role.getStateSupport();
        if (stateSupport == null) {
            return;
        }
        Ref<EntityStore> iterationTarget = stateSupport.getInteractionIterationTarget();
        if (!isRefInCurrentStore(iterationTarget, store)) {
            stateSupport.setInteractionIterationTarget(null);
        }

        Collection<Ref<EntityStore>> interactablePlayers =
                readFieldValue(stateSupport, STATE_INTERACTABLE_PLAYERS_FIELD, Collection.class);
        pruneRefCollection(interactablePlayers, store);

        Collection<Ref<EntityStore>> interactedPlayers =
                readFieldValue(stateSupport, STATE_INTERACTED_PLAYERS_FIELD, Collection.class);
        pruneRefCollection(interactedPlayers, store);

        Map<Ref<EntityStore>, String> contextualInteractions =
                readFieldValue(stateSupport, STATE_CONTEXTUAL_INTERACTIONS_FIELD, Map.class);
        if (contextualInteractions != null && !contextualInteractions.isEmpty()) {
            Iterator<Map.Entry<Ref<EntityStore>, String>> iterator = contextualInteractions.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Ref<EntityStore>, String> entry = iterator.next();
                if (!isRefInCurrentStore(entry.getKey(), store)) {
                    iterator.remove();
                }
            }
        }
    }

    private void pruneRefCollection(@Nullable Collection<Ref<EntityStore>> refs, @Nonnull Store<EntityStore> store) {
        if (refs == null || refs.isEmpty()) {
            return;
        }
        Iterator<Ref<EntityStore>> iterator = refs.iterator();
        while (iterator.hasNext()) {
            Ref<EntityStore> candidate = iterator.next();
            if (!isRefInCurrentStore(candidate, store)) {
                iterator.remove();
            }
        }
    }

    private boolean isRefInCurrentStore(@Nullable Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        return ref != null && ref.isValid() && ref.getStore() == store;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static <T> T readFieldValue(@Nonnull Object owner, @Nullable Field field, @Nonnull Class<T> type) {
        if (owner == null || field == null || type == null) {
            return null;
        }
        try {
            Object value = field.get(owner);
            if (type.isInstance(value)) {
                return (T) value;
            }
        } catch (IllegalAccessException ignored) {
            return null;
        }
        return null;
    }

    private record CoopResidentContext(@Nullable String worldName,
                                       @Nullable String coopId,
                                       @Nonnull Vector3i coopLocation,
                                       @Nonnull CoopBlock coopBlock) {
        int resolveResidentSlot(@Nullable UUID uuid) {
            return resolveResidentSlotByUuid(coopBlock, uuid);
        }
    }

    private static int resolveResidentSlotByUuid(@Nullable CoopBlock coopBlock, @Nullable UUID npcUuid) {
        if (npcUuid == null) {
            return -1;
        }
        List<?> residents = readCoopResidents(coopBlock);
        if (residents == null || residents.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < residents.size(); i++) {
            PersistentRef persistentRef = resolvePersistentRef(residents.get(i));
            if (persistentRef == null || persistentRef.getUuid() == null) {
                continue;
            }
            if (npcUuid.equals(persistentRef.getUuid())) {
                return i;
            }
        }
        return -1;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static List<?> readCoopResidents(@Nullable CoopBlock coopBlock) {
        if (coopBlock == null || COOP_RESIDENTS_FIELD == null) {
            return null;
        }
        try {
            Object value = COOP_RESIDENTS_FIELD.get(coopBlock);
            if (value instanceof List<?> list) {
                return list;
            }
        } catch (IllegalAccessException ignored) {
            return null;
        }
        return null;
    }

    @Nullable
    private static PersistentRef resolvePersistentRef(@Nullable Object resident) {
        if (!(resident instanceof CoopBlock.CoopResident coopResident)) {
            return null;
        }
        try {
            return coopResident.getPersistentRef();
        } catch (Exception | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    private static Field resolveCoopResidentsField() {
        try {
            Field field = CoopBlock.class.getDeclaredField("residents");
            field.setAccessible(true);
            return field;
        } catch (Exception | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    private static Field resolveStateSupportField(@Nonnull String name) {
        try {
            Field field = StateSupport.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Exception | LinkageError ignored) {
            return null;
        }
    }
}
