package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import java.util.UUID;

/**
 * Applies and resolves owner/tamed/name state on NPC entities during spawner flows.
 */
final class SpawnerNpcStateService {

    void applyOwner(ItemFeatureConfig config,
                    Ref<EntityStore> npcRef,
                    NPCEntity npc,
                    Ref<EntityStore> playerRef,
                    UUID ownerUuid,
                    World world) {
        if (npc == null) {
            return;
        }
        if (world != null && npcRef != null && npcRef.isValid()) {
            Store<EntityStore> store = world.getEntityStore().getStore();
            ComponentType<EntityStore, TameworkOwnerComponent> type = TameworkOwnerComponent.getComponentType();
            if (type != null) {
                String ownerName = null;
                if (ownerUuid != null) {
                    Player ownerPlayer = null;
                    if (playerRef != null) {
                        ownerPlayer = store.getComponent(playerRef, Player.getComponentType());
                    }
                    if (ownerPlayer != null && ownerUuid.equals(ownerPlayer.getUuid())) {
                        ownerName = OwnerNameUtil.resolve(ownerPlayer);
                    } else {
                        Ref<EntityStore> ownerRef = world.getEntityRef(ownerUuid);
                        if (ownerRef != null) {
                            Player resolvedOwner = store.getComponent(ownerRef, Player.getComponentType());
                            if (resolvedOwner != null) {
                                ownerName = OwnerNameUtil.resolve(resolvedOwner);
                            }
                        }
                    }
                }
                store.putComponent(npcRef, type, new TameworkOwnerComponent(ownerUuid, ownerName));
            }
        }
        if (config == null || !config.isSpawnAssignsOwner()) {
            return;
        }
        Role role = npc.getRole();
        if (role == null) {
            return;
        }
        Ref<EntityStore> ownerRef = playerRef;
        if (ownerUuid != null && world != null) {
            Ref<EntityStore> resolved = world.getEntityRef(ownerUuid);
            if (resolved != null) {
                ownerRef = resolved;
            }
        }
        if (ownerRef != null) {
            role.setMarkedTarget("MasterTarget", ownerRef);
        }
    }

    void applyTamed(Ref<EntityStore> npcRef, boolean tamed, World world) {
        if (npcRef == null || !npcRef.isValid() || world == null) {
            return;
        }
        ComponentType<EntityStore, TameworkTamedComponent> type = TameworkTamedComponent.getComponentType();
        if (type == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        store.putComponent(npcRef, type, new TameworkTamedComponent(tamed));
        if (tamed) {
            CompanionProgressionBootstrapService.ensureProgressionComponents(npcRef, store);
        }
    }

    void applyCapturedName(ItemStack itemStack, Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (itemStack == null || npcRef == null || store == null || !npcRef.isValid()) {
            return;
        }
        String name = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.NPC_NAME, Codec.STRING);
        if (name == null || name.isBlank()) {
            return;
        }
        UUID ownerId = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.NPC_NAME_OWNER_UUID, Codec.UUID_STRING);
        Long updatedMs = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.NPC_NAME_UPDATED_MS, Codec.LONG);
        String sourceRaw = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.NPC_NAME_SOURCE, Codec.STRING);
        TameworkNpcNameComponent.NameSource source = parseNameSource(sourceRaw);
        if (source == null) {
            source = TameworkNpcNameComponent.NameSource.Player;
        }
        long resolvedUpdatedMs = (updatedMs != null && updatedMs > 0) ? updatedMs : System.currentTimeMillis();
        ComponentType<EntityStore, TameworkNpcNameComponent> nameType = TameworkNpcNameComponent.getComponentType();
        if (nameType != null) {
            store.putComponent(npcRef, nameType, new TameworkNpcNameComponent(name, ownerId, resolvedUpdatedMs, source));
        }
        EntitySupport.setDisplayName(npcRef, name, store);
    }

    boolean resolveTamedState(Ref<EntityStore> targetRef, World world) {
        if (targetRef == null || world == null || !targetRef.isValid()) {
            return false;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        return TamedStateResolver.isTamed(targetRef, store);
    }

    UUID resolveOwnerFromComponent(Ref<EntityStore> targetRef, World world) {
        if (targetRef == null || world == null || !targetRef.isValid()) {
            return null;
        }
        ComponentType<EntityStore, TameworkOwnerComponent> type = TameworkOwnerComponent.getComponentType();
        if (type == null) {
            return null;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        TameworkOwnerComponent owner = store.getComponent(targetRef, type);
        return owner != null ? owner.getOwnerId() : null;
    }

    String resolveOwnerNameFromComponent(Ref<EntityStore> targetRef, World world) {
        if (targetRef == null || world == null || !targetRef.isValid()) {
            return null;
        }
        ComponentType<EntityStore, TameworkOwnerComponent> type = TameworkOwnerComponent.getComponentType();
        if (type == null) {
            return null;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        TameworkOwnerComponent owner = store.getComponent(targetRef, type);
        if (owner == null || owner.getOwnerName() == null || owner.getOwnerName().isBlank()) {
            return null;
        }
        return owner.getOwnerName();
    }

    private TameworkNpcNameComponent.NameSource parseNameSource(String sourceRaw) {
        if (sourceRaw == null || sourceRaw.isBlank()) {
            return null;
        }
        try {
            return TameworkNpcNameComponent.NameSource.valueOf(sourceRaw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
