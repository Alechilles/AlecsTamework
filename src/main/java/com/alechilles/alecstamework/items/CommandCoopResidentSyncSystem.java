package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.hypixel.hytale.builtin.adventure.farming.component.CoopResidentComponent;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Syncs linked companions that enter vanilla coop residency into Tamework's coop snapshot store.
 *
 * <p>This covers walk-in coop intake paths that bypass item-driven coop capture handling.
 */
public final class CommandCoopResidentSyncSystem extends TickingSystem<EntityStore> {
    private static final long SWEEP_INTERVAL_MS = 50L;

    private final CommandLinkedNpcCoopService coopService;
    @Nullable
    private final CommandLinkedNpcCaptureService captureService;
    @Nullable
    private final CommandNpcRelocationService relocationService;
    @Nullable
    private final CommandLinkedNpcLostService lostService;
    @Nullable
    private final ComponentType<EntityStore, TameworkCommandLinksComponent> commandLinksType;
    @Nullable
    private final ComponentType<EntityStore, CoopResidentComponent> coopResidentType;
    @Nullable
    private final ComponentType<EntityStore, NPCEntity> npcType;
    @Nullable
    private final ComponentType<EntityStore, UUIDComponent> uuidType;

    private long nextSweepAtMs;

    public CommandCoopResidentSyncSystem(@Nonnull CommandLinkedNpcCoopService coopService,
                                         @Nullable CommandLinkedNpcCaptureService captureService,
                                         @Nullable CommandNpcRelocationService relocationService,
                                         @Nullable CommandLinkedNpcLostService lostService,
                                         @Nullable ComponentType<EntityStore, TameworkCommandLinksComponent> commandLinksType,
                                         @Nullable ComponentType<EntityStore, CoopResidentComponent> coopResidentType,
                                         @Nullable ComponentType<EntityStore, NPCEntity> npcType,
                                         @Nullable ComponentType<EntityStore, UUIDComponent> uuidType) {
        this.coopService = coopService;
        this.captureService = captureService;
        this.relocationService = relocationService;
        this.lostService = lostService;
        this.commandLinksType = commandLinksType;
        this.coopResidentType = coopResidentType;
        this.npcType = npcType;
        this.uuidType = uuidType;
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        long nowMs = System.currentTimeMillis();
        if (nowMs < nextSweepAtMs) {
            return;
        }
        nextSweepAtMs = nowMs + SWEEP_INTERVAL_MS;
        if (commandLinksType == null || coopResidentType == null) {
            return;
        }
        store.forEachChunk(
                Query.and(commandLinksType, coopResidentType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) ->
                        syncChunk(chunk, store)
        );
    }

    private void syncChunk(@Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store) {
        int size = chunk.size();
        for (int i = 0; i < size; i++) {
            Ref<EntityStore> reference = chunk.getReferenceTo(i);
            if (reference == null || !reference.isValid()) {
                continue;
            }
            TameworkCommandLinksComponent links = chunk.getComponent(i, commandLinksType);
            if (links == null) {
                continue;
            }
            if (chunk.getComponent(i, coopResidentType) == null) {
                continue;
            }
            NPCEntity npc = npcType != null ? chunk.getComponent(i, npcType) : null;
            String roleId = null;
            if (npc != null && npc.getRoleName() != null && !npc.getRoleName().isBlank()) {
                roleId = npc.getRoleName().trim().toLowerCase(Locale.ROOT);
            }
            Set<UUID> uuids = resolveNpcUuids(reference, store, npc);
            if (uuids.isEmpty()) {
                continue;
            }
            for (UUID uuid : uuids) {
                coopService.recordCoopSnapshot(
                        new CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot(
                                uuid,
                                links.getOwnerId(),
                                links.getToolIds(),
                                roleId,
                                null,
                                null,
                                System.currentTimeMillis()
                        )
                );
                if (captureService != null) {
                    captureService.clearCapturedSnapshot(uuid);
                }
                if (relocationService != null) {
                    relocationService.cancelPendingRelocation(uuid);
                }
                if (lostService != null) {
                    lostService.clearLostSnapshot(uuid);
                }
            }
        }
    }

    @Nonnull
    private Set<UUID> resolveNpcUuids(@Nonnull Ref<EntityStore> reference,
                                      @Nonnull Store<EntityStore> store,
                                      @Nullable NPCEntity npc) {
        LinkedHashSet<UUID> uuids = new LinkedHashSet<>(2);
        if (uuidType != null) {
            UUIDComponent uuidComponent = store.getComponent(reference, uuidType);
            if (uuidComponent != null && uuidComponent.getUuid() != null) {
                uuids.add(uuidComponent.getUuid());
            }
        }
        if (npc != null && npc.getUuid() != null) {
            uuids.add(npc.getUuid());
        }
        return uuids;
    }
}
