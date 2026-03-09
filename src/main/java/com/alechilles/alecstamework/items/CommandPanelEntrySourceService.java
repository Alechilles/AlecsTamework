package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.LinkedNpcTraitIndicator;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Builds command-panel entries using linked and nearby-owned companions.
 */
final class CommandPanelEntrySourceService {
    private final CommandLinkedPanelEntryService linkedPanelEntryService;
    private final CommandPanelPreferenceService panelPreferenceService;
    private final CommandLinkPolicyService linkPolicyService;
    private final CommandNpcNameResolver npcNameResolver;

    CommandPanelEntrySourceService(CommandLinkedPanelEntryService linkedPanelEntryService,
                                   CommandPanelPreferenceService panelPreferenceService,
                                   CommandLinkPolicyService linkPolicyService,
                                   CommandNpcNameResolver npcNameResolver) {
        this.linkedPanelEntryService = linkedPanelEntryService;
        this.panelPreferenceService = panelPreferenceService != null
                ? panelPreferenceService
                : new CommandPanelPreferenceService();
        this.linkPolicyService = linkPolicyService != null ? linkPolicyService : new CommandLinkPolicyService();
        this.npcNameResolver = npcNameResolver != null ? npcNameResolver : new CommandNpcNameResolver();
    }

    List<LinkedNpcEntry> buildEntries(Player player,
                                      Store<EntityStore> store,
                                      ItemStack stack,
                                      TwCommandItemConfig config,
                                      String toolId) {
        List<LinkedNpcEntry> linkedEntries = linkedPanelEntryService.buildEntries(player, store, stack, toolId);
        CommandPanelPreferenceService.PanelMode panelMode =
                panelPreferenceService.resolveEffectivePanelMode(stack, config);
        if (panelMode != CommandPanelPreferenceService.PanelMode.NearbyMode) {
            return linkedEntries;
        }
        if (player == null || store == null) {
            return linkedEntries;
        }
        UUID playerUuid = player.getUuid();
        Ref<EntityStore> playerRef = player.getReference();
        if (playerUuid == null || playerRef == null || !playerRef.isValid()) {
            return linkedEntries;
        }
        TransformComponent playerTransform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (playerTransform == null) {
            return linkedEntries;
        }
        double radius = panelPreferenceService.resolveNearbyRadius(stack, config);
        if (!Double.isFinite(radius) || radius <= 0.0) {
            return linkedEntries;
        }
        double radiusSq = radius * radius;

        ArrayList<LinkedNpcEntry> out = new ArrayList<>(linkedEntries.size() + 16);
        Set<UUID> seen = new HashSet<>();
        for (LinkedNpcEntry entry : linkedEntries) {
            if (entry == null || entry.npcUuid() == null) {
                continue;
            }
            out.add(entry);
            seen.add(entry.npcUuid());
        }

        Vector3d playerPos = new Vector3d(playerTransform.getPosition());
        store.forEachChunk(Query.any(), (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
            for (int i = 0; i < chunk.size(); i++) {
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                if (npc == null || npc.getUuid() == null || seen.contains(npc.getUuid())) {
                    continue;
                }
                Ref<EntityStore> npcRef = chunk.getReferenceTo(i);
                if (npcRef == null || !npcRef.isValid()) {
                    continue;
                }
                if (!linkPolicyService.passesOwnerAndTamed(
                        true,
                        config != null && config.isRequireTamed(),
                        npcRef,
                        playerUuid,
                        store
                )) {
                    continue;
                }
                if (!linkPolicyService.isRoleAllowed(linkPolicyService.resolveRoleId(npc), config)) {
                    continue;
                }
                TransformComponent npcTransform = chunk.getComponent(i, TransformComponent.getComponentType());
                if (npcTransform == null) {
                    continue;
                }
                Vector3d npcPos = npcTransform.getPosition();
                double dx = npcPos.x - playerPos.x;
                double dy = npcPos.y - playerPos.y;
                double dz = npcPos.z - playerPos.z;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq > radiusSq) {
                    continue;
                }
                String roleId = normalize(linkPolicyService.resolveRoleId(npc));
                HealthSnapshot healthSnapshot = readHealthSnapshot(npcRef, store);
                TameworkCommandLinksComponent links =
                        store.getComponent(npcRef, TameworkCommandLinksComponent.getComponentType());
                boolean hasHome = links != null && links.hasHome();
                out.add(new LinkedNpcEntry(
                        npc.getUuid(),
                        npcNameResolver.resolveNpcDisplayName(npcRef, store, npc),
                        healthSnapshot.current,
                        healthSnapshot.max,
                        0,
                        0,
                        null,
                        0,
                        0,
                        0,
                        0,
                        true,
                        hasHome,
                        false,
                        false,
                        0L,
                        null,
                        null,
                        LinkedNpcTraitIndicator.EMPTY,
                        false,
                        false,
                        false,
                        false,
                        false,
                        true,
                        roleId,
                        roleId,
                        null,
                        null,
                        null,
                        false,
                        0L,
                        0.0
                ));
                seen.add(npc.getUuid());
            }
        });
        return out;
    }

    private HealthSnapshot readHealthSnapshot(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return HealthSnapshot.ZERO;
        }
        ComponentType<EntityStore, EntityStatMap> statType = EntityStatMap.getComponentType();
        if (statType == null) {
            return HealthSnapshot.ZERO;
        }
        EntityStatMap statMap = store.getComponent(npcRef, statType);
        if (statMap == null) {
            return HealthSnapshot.ZERO;
        }
        int healthIndex = EntityStatType.getAssetMap().getIndex("Health");
        if (healthIndex < 0) {
            return HealthSnapshot.ZERO;
        }
        EntityStatValue value = statMap.get(healthIndex);
        if (value == null) {
            return HealthSnapshot.ZERO;
        }
        int current = Math.max(0, Math.round(value.get()));
        int max = Math.max(1, Math.round(value.getMax()));
        if (current > max) {
            current = max;
        }
        return new HealthSnapshot(current, max);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private record HealthSnapshot(int current, int max) {
        private static final HealthSnapshot ZERO = new HealthSnapshot(0, 0);
    }
}
