package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.ui.TameworkCommandSelectionPage;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds linked-companion panel entries for command-item UI.
 *
 * <p>This service isolates panel-oriented data assembly (loaded/dead/captured status, display names,
 * health snapshots, and home flags) from command orchestration flows.
 */
final class CommandLinkedPanelEntryService {
    private final CommandLinkedNpcRecordStore linkedNpcRecordStore;
    private final CommandLinkedNpcDeathService deathService;
    private final CommandLinkedNpcCaptureService captureService;
    private final CommandNpcNameResolver npcNameResolver;

    CommandLinkedPanelEntryService(CommandLinkedNpcRecordStore linkedNpcRecordStore,
                                   CommandLinkedNpcDeathService deathService,
                                   CommandLinkedNpcCaptureService captureService,
                                   CommandNpcNameResolver npcNameResolver) {
        this.linkedNpcRecordStore = linkedNpcRecordStore;
        this.deathService = deathService;
        this.captureService = captureService;
        this.npcNameResolver = npcNameResolver;
    }

    List<TameworkCommandSelectionPage.LinkedNpcEntry> buildEntries(Player player,
                                                                   Store<EntityStore> store,
                                                                   ItemStack stack,
                                                                   String toolId) {
        if (player == null || store == null || stack == null || stack.isEmpty()) {
            return List.of();
        }
        List<LinkedNpcRecord> records = linkedNpcRecordStore.read(stack);
        if (records.isEmpty()) {
            return List.of();
        }
        TwGlobalConfig globalConfig = TwGlobalConfig.resolveActive();
        boolean deadRespawnEnabled = globalConfig != null && globalConfig.isCommandDeadRespawnEnabled();
        World world = player.getWorld();
        ArrayList<TameworkCommandSelectionPage.LinkedNpcEntry> entries = new ArrayList<>(records.size());
        for (LinkedNpcRecord record : records) {
            if (record == null || record.npcUuid == null) {
                continue;
            }
            boolean loaded = false;
            boolean dead = false;
            boolean captured = false;
            long deadRespawnRemainingMs = 0L;
            boolean hasHome = record.homePosition != null;
            String displayName = npcNameResolver.resolveCachedUnloadedDisplayName(record);
            if (displayName == null || displayName.isBlank()) {
                displayName = "Unloaded companion (" + abbreviateUuid(record.npcUuid) + ")";
            }
            int health = 0;
            int maxHealth = 0;
            if (world != null) {
                Ref<EntityStore> npcRef = world.getEntityRef(record.npcUuid);
                if (npcRef != null && npcRef.isValid()) {
                    NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
                    if (npc != null) {
                        loaded = true;
                        displayName = npcNameResolver.resolveNpcDisplayName(npcRef, store, npc);
                        TameworkCommandLinksComponent links =
                                store.getComponent(npcRef, TameworkCommandLinksComponent.getComponentType());
                        if (links != null && links.hasHome()) {
                            hasHome = true;
                        }
                        HealthSnapshot snapshot = readNpcHealthSnapshot(npcRef, store);
                        if (snapshot != null) {
                            health = snapshot.current;
                            maxHealth = snapshot.max;
                        }
                    }
                }
            }
            if (!loaded && deathService != null) {
                CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot deadSnapshot = deathService.getDeadSnapshotForTool(
                        record.npcUuid,
                        toolId,
                        player.getUuid()
                );
                if (deadSnapshot != null) {
                    dead = true;
                    String deadName = deadSnapshot.displayName();
                    if (deadName != null && !deadName.isBlank()) {
                        displayName = deadName;
                    }
                    if (deadRespawnEnabled) {
                        deadRespawnRemainingMs = Math.max(0L, deadSnapshot.respawnAvailableAtMs() - System.currentTimeMillis());
                    } else {
                        deadRespawnRemainingMs = -1L;
                    }
                }
            }
            if (!loaded && !dead && captureService != null) {
                CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot capturedSnapshot =
                        captureService.getCapturedSnapshotForTool(record.npcUuid, toolId, player.getUuid());
                if (capturedSnapshot != null) {
                    captured = true;
                    String capturedName = capturedSnapshot.displayName();
                    if (capturedName != null && !capturedName.isBlank()) {
                        displayName = capturedName;
                    }
                }
            }
            entries.add(new TameworkCommandSelectionPage.LinkedNpcEntry(
                    record.npcUuid,
                    displayName,
                    health,
                    maxHealth,
                    loaded,
                    hasHome,
                    dead,
                    captured,
                    deadRespawnRemainingMs
            ));
        }
        return entries;
    }

    private HealthSnapshot readNpcHealthSnapshot(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        ComponentType<EntityStore, EntityStatMap> statType = EntityStatMap.getComponentType();
        if (statType == null) {
            return null;
        }
        EntityStatMap statMap = store.getComponent(npcRef, statType);
        if (statMap == null) {
            return null;
        }
        int healthIndex = EntityStatType.getAssetMap().getIndex("Health");
        if (healthIndex < 0) {
            return null;
        }
        EntityStatValue value = statMap.get(healthIndex);
        if (value == null) {
            return null;
        }
        int current = Math.max(0, Math.round(value.get()));
        int max = Math.max(1, Math.round(value.getMax()));
        if (current > max) {
            current = max;
        }
        return new HealthSnapshot(current, max);
    }

    private String abbreviateUuid(UUID uuid) {
        if (uuid == null) {
            return "unknown";
        }
        String raw = uuid.toString();
        return raw.length() >= 8 ? raw.substring(0, 8) : raw;
    }

    private static final class HealthSnapshot {
        private final int current;
        private final int max;

        private HealthSnapshot(int current, int max) {
            this.current = current;
            this.max = max;
        }
    }
}
