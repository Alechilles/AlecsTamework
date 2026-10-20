package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.support.DisplayNameSupport;
import java.util.UUID;

/**
 * Applies and resolves owner/tamed/name state on NPC entities during spawner flows.
 */
final class SpawnerNpcStateService {

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
        DisplayNameSupport.setDisplayName(npcRef, name, store);
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
        Store<EntityStore> store = world.getEntityStore().getStore();
        TameworkOwnerComponent owner = type != null ? store.getComponent(targetRef, type) : null;
        if (owner != null && owner.getOwnerId() != null) {
            return owner.getOwnerId();
        }
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType = TameworkCommandLinksComponent.getComponentType();
        TameworkCommandLinksComponent links = linksType != null ? store.getComponent(targetRef, linksType) : null;
        return links != null ? links.getOwnerId() : null;
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
