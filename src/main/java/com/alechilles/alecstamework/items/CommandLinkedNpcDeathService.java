package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Tracks linked NPC deaths so command tools can distinguish dead companions from unloaded companions.
 */
public final class CommandLinkedNpcDeathService {
    private final ConcurrentHashMap<UUID, DeadLinkedNpcSnapshot> deadByNpc = new ConcurrentHashMap<>();

    public void onNpcAdded(Ref<EntityStore> reference, Store<EntityStore> store) {
        if (reference == null || !reference.isValid() || store == null) {
            return;
        }
        NPCEntity npc = store.getComponent(reference, NPCEntity.getComponentType());
        UUID npcUuid = npc != null ? npc.getUuid() : null;
        if (npcUuid != null) {
            deadByNpc.remove(npcUuid);
        }
    }

    public void onNpcRemoved(Ref<EntityStore> reference, RemoveReason reason, Store<EntityStore> store) {
        if (reference == null || store == null) {
            return;
        }
        NPCEntity npc = store.getComponent(reference, NPCEntity.getComponentType());
        if (npc == null || npc.getUuid() == null) {
            return;
        }
        UUID npcUuid = npc.getUuid();
        if (!wasDeathRemoval(reference, reason, store)) {
            deadByNpc.remove(npcUuid);
            return;
        }
        TameworkCommandLinksComponent links = store.getComponent(reference, TameworkCommandLinksComponent.getComponentType());
        if (links == null || links.getToolIds() == null || links.getToolIds().length == 0) {
            deadByNpc.remove(npcUuid);
            return;
        }

        UUID ownerId = links.getOwnerId();
        TameworkOwnerComponent ownerComponent = store.getComponent(reference, TameworkOwnerComponent.getComponentType());
        if (ownerComponent != null && ownerComponent.getOwnerId() != null) {
            ownerId = ownerComponent.getOwnerId();
        }
        String ownerName = ownerComponent != null ? ownerComponent.getOwnerName() : null;
        boolean tamed = TamedStateResolver.isTamed(reference, store);

        TransformComponent transform = store.getComponent(reference, TransformComponent.getComponentType());
        Vector3d lastKnownPosition = transform != null ? new Vector3d(transform.getPosition()) : null;
        Vector3d homePosition = links.hasHome() ? links.getHomePosition() : null;
        String roleId = resolveRoleId(npc);
        String customName = resolveCustomName(reference, store);
        String displayName = resolveDisplayName(reference, store, npc, roleId, customName);
        long diedAtMs = System.currentTimeMillis();
        long respawnAvailableAtMs = diedAtMs + resolveRespawnCooldownMs();

        deadByNpc.put(
                npcUuid,
                new DeadLinkedNpcSnapshot(
                        npcUuid,
                        ownerId,
                        ownerName,
                        sanitizeToolIds(links.getToolIds()),
                        roleId,
                        tamed,
                        customName,
                        displayName,
                        lastKnownPosition,
                        homePosition,
                        diedAtMs,
                        respawnAvailableAtMs
                )
        );
    }

    @Nullable
    public DeadLinkedNpcSnapshot getDeadSnapshot(UUID npcUuid) {
        if (npcUuid == null) {
            return null;
        }
        return deadByNpc.get(npcUuid);
    }

    @Nullable
    public DeadLinkedNpcSnapshot getDeadSnapshotForTool(UUID npcUuid, String toolId, @Nullable UUID ownerUuid) {
        DeadLinkedNpcSnapshot snapshot = getDeadSnapshot(npcUuid);
        if (snapshot == null) {
            return null;
        }
        if (!snapshot.containsToolId(toolId)) {
            return null;
        }
        if (snapshot.ownerId() != null && ownerUuid != null && !snapshot.ownerId().equals(ownerUuid)) {
            return null;
        }
        return snapshot;
    }

    public void clearDeadSnapshot(UUID npcUuid) {
        if (npcUuid == null) {
            return;
        }
        deadByNpc.remove(npcUuid);
    }

    private boolean wasDeathRemoval(Ref<EntityStore> reference, RemoveReason reason, Store<EntityStore> store) {
        if (reference != null && reference.isValid() && store != null) {
            try {
                if (store.getArchetype(reference).contains(DeathComponent.getComponentType())) {
                    return true;
                }
            } catch (Exception ignored) {
                // Fall through to reason heuristics.
            }
        }
        if (reason == null) {
            return false;
        }
        String reasonText = reason.toString();
        if (reasonText == null || reasonText.isBlank()) {
            return false;
        }
        String normalized = reasonText.toLowerCase(Locale.ROOT);
        return normalized.contains("death") || normalized.contains("killed");
    }

    private long resolveRespawnCooldownMs() {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        long configured = config != null ? config.getCommandDeadRespawnCooldownMs() : 0L;
        return Math.max(0L, configured);
    }

    private String resolveRoleId(NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            return roleName;
        }
        int roleIndex = npc.getRoleIndex();
        if (roleIndex >= 0 && NPCPlugin.get() != null) {
            String name = NPCPlugin.get().getName(roleIndex);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return null;
    }

    private String resolveCustomName(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        ComponentType<EntityStore, TameworkNpcNameComponent> nameType = TameworkNpcNameComponent.getComponentType();
        if (nameType == null) {
            return null;
        }
        TameworkNpcNameComponent component = store.getComponent(npcRef, nameType);
        if (component == null || component.getName() == null || component.getName().isBlank()) {
            return null;
        }
        return component.getName();
    }

    private String resolveDisplayName(Ref<EntityStore> npcRef,
                                      Store<EntityStore> store,
                                      NPCEntity npc,
                                      String roleId,
                                      String customName) {
        if (customName != null && !customName.isBlank()) {
            return customName;
        }
        if (npcRef != null && npcRef.isValid() && store != null) {
            DisplayNameComponent displayName = store.getComponent(npcRef, DisplayNameComponent.getComponentType());
            if (displayName != null && displayName.getDisplayName() != null) {
                String ansi = displayName.getDisplayName().getAnsiMessage();
                if (ansi != null && !ansi.isBlank()) {
                    return ansi;
                }
            }
        }
        if (npc != null) {
            String legacy = npc.getLegacyDisplayName();
            if (legacy != null && !legacy.isBlank()) {
                return legacy;
            }
        }
        if (roleId != null && !roleId.isBlank()) {
            return roleId;
        }
        return "Dead companion";
    }

    private String[] sanitizeToolIds(String[] toolIds) {
        if (toolIds == null || toolIds.length == 0) {
            return new String[0];
        }
        return Arrays.stream(toolIds)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toArray(String[]::new);
    }

    /**
     * Snapshot of a linked companion that died while linked to one or more command tools.
     */
    public record DeadLinkedNpcSnapshot(UUID npcUuid,
                                        @Nullable UUID ownerId,
                                        @Nullable String ownerName,
                                        String[] toolIds,
                                        @Nullable String roleId,
                                        boolean tamed,
                                        @Nullable String customName,
                                        @Nullable String displayName,
                                        @Nullable Vector3d lastKnownPosition,
                                        @Nullable Vector3d homePosition,
                                        long diedAtMs,
                                        long respawnAvailableAtMs) {
        public boolean containsToolId(String toolId) {
            if (toolId == null || toolIds == null || toolIds.length == 0) {
                return false;
            }
            for (String value : toolIds) {
                if (toolId.equals(value)) {
                    return true;
                }
            }
            return false;
        }

        public boolean isRespawnReady() {
            return System.currentTimeMillis() >= respawnAvailableAtMs;
        }
    }
}
