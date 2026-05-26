package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.ownership.LegacyTamedOwnershipBridge;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Handles linked-NPC mutations and command-item linked-record persistence.
 */
final class CommandLinkMutationService {
    private final CommandLinkedNpcRecordStore linkedNpcRecordStore;
    private final CommandLinkPolicyService linkPolicyService;
    private final CommandNpcNameResolver npcNameResolver;
    @Nullable
    private final CommandLinkedNpcStateSnapshotService stateSnapshotService;

    CommandLinkMutationService(CommandLinkedNpcRecordStore linkedNpcRecordStore,
                               CommandLinkPolicyService linkPolicyService,
                               CommandNpcNameResolver npcNameResolver) {
        this(linkedNpcRecordStore, linkPolicyService, npcNameResolver, null);
    }

    CommandLinkMutationService(CommandLinkedNpcRecordStore linkedNpcRecordStore,
                               CommandLinkPolicyService linkPolicyService,
                               CommandNpcNameResolver npcNameResolver,
                               @Nullable CommandLinkedNpcStateSnapshotService stateSnapshotService) {
        this.linkedNpcRecordStore = linkedNpcRecordStore != null ? linkedNpcRecordStore : new CommandLinkedNpcRecordStore();
        this.linkPolicyService = linkPolicyService != null ? linkPolicyService : new CommandLinkPolicyService();
        this.npcNameResolver = npcNameResolver != null ? npcNameResolver : new CommandNpcNameResolver();
        this.stateSnapshotService = stateSnapshotService;
    }

    LinkToggleResult tryToggleLink(Player player,
                                   Store<EntityStore> store,
                                   Ref<EntityStore> targetRef,
                                   String toolId,
                                   TwCommandItemConfig config,
                                   ItemStack workingItem) {
        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        if (npc == null) {
            return LinkToggleResult.notToggled();
        }
        UUID playerId = player.getUuid();
        if (playerId == null) {
            return LinkToggleResult.notToggled();
        }
        boolean requireOwner = resolveLinkingRequireOwner();
        LegacyTamedOwnershipBridge.ClaimResult ownerBridgeResult =
                requireOwner
                        ? LegacyTamedOwnershipBridge.claimForPlayerIfEligible(targetRef, store, player)
                        : LegacyTamedOwnershipBridge.resolveOwner(targetRef, store);
        UUID ownerId = ownerBridgeResult.getOwnerId();
        if (requireOwner && (ownerId == null || !ownerId.equals(playerId))) {
            return LinkToggleResult.notToggled();
        }
        if (config.isRequireTamed() && !TamedStateResolver.isTamed(targetRef, store)) {
            return LinkToggleResult.notToggled();
        }
        boolean tamed = TamedStateResolver.isTamed(targetRef, store);
        if (!linkPolicyService.isRoleAllowed(linkPolicyService.resolveRoleId(npc), config, tamed)) {
            return LinkToggleResult.notToggled();
        }
        TameworkCommandLinksComponent current = store.getComponent(targetRef, TameworkCommandLinksComponent.getComponentType());
        if (current == null) {
            current = new TameworkCommandLinksComponent(playerId, new String[0]);
        }
        UUID linksOwner = current.getOwnerId();
        if (requireOwner && linksOwner != null && !linksOwner.equals(playerId)) {
            return LinkToggleResult.notToggled();
        }
        current.setOwnerId(playerId);
        boolean linked;
        boolean active = false;
        TameworkCommandLinksComponent updated;
        if (current.containsToolId(toolId)) {
            updated = current.withToolIdRemoved(toolId);
            linked = false;
        } else {
            updated = current.withToolIdAdded(toolId);
            linked = true;
            active = shouldActivateOnLink(config, workingItem);
        }
        store.putComponent(targetRef, TameworkCommandLinksComponent.getComponentType(), updated);
        if (stateSnapshotService != null) {
            stateSnapshotService.refreshFromEntity(targetRef, store);
        }
        ItemStack updatedItem = workingItem;
        UUID npcUuid = npc.getUuid();
        if (npcUuid != null && updatedItem != null && !updatedItem.isEmpty()) {
            if (linked) {
                TransformComponent transform = store.getComponent(targetRef, TransformComponent.getComponentType());
                Vector3d lastKnown = transform != null ? new Vector3d(transform.getPosition()) : null;
                String worldName = resolveWorldName(store, player.getWorld());
                Vector3d homePosition = updated.hasHome() ? updated.getHomePosition() : null;
                updatedItem = linkedNpcRecordStore.upsert(
                        updatedItem,
                        npcUuid,
                        lastKnown,
                        worldName,
                        homePosition,
                        npcNameResolver.resolveNpcDisplayNameFromComponents(targetRef, store),
                        npcNameResolver.resolveNpcNameKey(npc),
                        resolveCachedRoleId(npc),
                        active,
                        resolveCachedCommandState(npc)
                );
            } else {
                updatedItem = linkedNpcRecordStore.remove(updatedItem, npcUuid);
            }
        }
        String name = npcNameResolver.resolveNpcDisplayName(targetRef, store, npc);
        return new LinkToggleResult(true, linked, active, name, updatedItem);
    }

    boolean unlinkLoadedNpcFromTool(Player player, UUID npcUuid, String toolId) {
        if (player == null || npcUuid == null || toolId == null || toolId.isBlank()) {
            return false;
        }
        World world = player.getWorld();
        if (world == null) {
            return false;
        }
        Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
        if (npcRef == null || !npcRef.isValid()) {
            return false;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) {
            return false;
        }
        TameworkCommandLinksComponent links = store.getComponent(npcRef, TameworkCommandLinksComponent.getComponentType());
        if (links == null || !links.containsToolId(toolId)) {
            return false;
        }
        UUID owner = links.getOwnerId();
        if (resolveLinkingRequireOwner() && owner != null && !owner.equals(player.getUuid())) {
            return false;
        }
        store.putComponent(npcRef, TameworkCommandLinksComponent.getComponentType(), links.withToolIdRemoved(toolId));
        return true;
    }

    LinkedNpcRecord findLinkedNpcRecord(List<LinkedNpcRecord> records, UUID npcUuid) {
        return linkedNpcRecordStore.find(records, npcUuid);
    }

    ItemStack upsertLinkedNpcRecord(ItemStack stack,
                                    UUID npcUuid,
                                    Vector3d position,
                                    String lastKnownWorldName,
                                    Vector3d homePosition,
                                    String cachedDisplayName,
                                    String cachedNameKey,
                                    String cachedRoleId) {
        return linkedNpcRecordStore.upsert(
                stack,
                npcUuid,
                position,
                lastKnownWorldName,
                homePosition,
                cachedDisplayName,
                cachedNameKey,
                cachedRoleId,
                null,
                null
        );
    }

    ItemStack refreshLinkedNpcPositions(ItemStack stack, List<Candidate> recipients, Store<EntityStore> store) {
        if (stack == null || stack.isEmpty() || recipients == null || recipients.isEmpty() || store == null) {
            return stack;
        }
        ItemStack updated = stack;
        for (Candidate candidate : recipients) {
            if (candidate == null || candidate.ref == null || candidate.npc == null || candidate.npc.getUuid() == null) {
                continue;
            }
            TransformComponent transform = store.getComponent(candidate.ref, TransformComponent.getComponentType());
            Vector3d position = transform != null ? new Vector3d(transform.getPosition()) : null;
            String worldName = resolveWorldName(store, null);
            TameworkCommandLinksComponent links = store.getComponent(candidate.ref, TameworkCommandLinksComponent.getComponentType());
            Vector3d homePosition = links != null && links.hasHome() ? links.getHomePosition() : null;
            updated = linkedNpcRecordStore.upsert(
                    updated,
                    candidate.npc.getUuid(),
                    position,
                    worldName,
                    homePosition,
                    npcNameResolver.resolveNpcDisplayNameFromComponents(candidate.ref, store),
                    npcNameResolver.resolveNpcNameKey(candidate.npc),
                    resolveCachedRoleId(candidate.npc),
                    null,
                    resolveCachedCommandState(candidate.npc)
            );
            if (stateSnapshotService != null) {
                stateSnapshotService.refreshFromEntity(candidate.ref, store);
            }
        }
        return updated;
    }

    String resolveWorldName(Store<EntityStore> store, @Nullable World fallbackWorld) {
        World world = store != null && store.getExternalData() != null
                ? store.getExternalData().getWorld()
                : fallbackWorld;
        if (world == null || world.getName() == null || world.getName().isBlank()) {
            return null;
        }
        return world.getName();
    }

    private String resolveCachedRoleId(NPCEntity npc) {
        String roleId = linkPolicyService.resolveRoleId(npc);
        if (roleId != null && !roleId.isBlank()) {
            return roleId;
        }
        return npcNameResolver.resolveNpcRoleId(npc);
    }

    private String resolveCachedCommandState(NPCEntity npc) {
        if (npc == null || npc.getRole() == null || npc.getRole().getStateSupport() == null) {
            return null;
        }
        String stateName = npc.getRole().getStateSupport().getStateName();
        return (stateName != null && !stateName.isBlank()) ? stateName : null;
    }

    private boolean resolveLinkingRequireOwner() {
        return resolveLinkingRequireOwner(TwGlobalConfig.resolveActive());
    }

    static boolean resolveLinkingRequireOwner(@Nullable TwGlobalConfig globalConfig) {
        TwGlobalConfig resolved = globalConfig != null ? globalConfig : TwGlobalConfig.defaultConfig();
        return resolved.isOwnershipLinkingRequiresOwner();
    }

    ItemStack removeLinkedNpcRecord(ItemStack stack, UUID npcUuid) {
        return linkedNpcRecordStore.remove(stack, npcUuid);
    }

    ActiveToggleResult toggleLinkedNpcActive(ItemStack stack,
                                             UUID npcUuid,
                                             TwCommandItemConfig config) {
        if (stack == null || stack.isEmpty() || npcUuid == null) {
            return ActiveToggleResult.notToggled(stack);
        }
        List<LinkedNpcRecord> records = linkedNpcRecordStore.read(stack);
        LinkedNpcRecord record = linkedNpcRecordStore.find(records, npcUuid);
        if (record == null) {
            return ActiveToggleResult.notToggled(stack);
        }
        boolean nextActive = !record.active;
        if (nextActive && !canActivateLinkedNpc(stack, npcUuid, config)) {
            return ActiveToggleResult.maxActiveReached(stack);
        }
        ItemStack updated = linkedNpcRecordStore.setActive(stack, npcUuid, nextActive);
        if (updated == stack) {
            return ActiveToggleResult.notToggled(stack);
        }
        return new ActiveToggleResult(updated, true, nextActive);
    }

    BreedingToggleResult toggleLinkedNpcBreeding(ItemStack stack,
                                                  UUID npcUuid) {
        if (stack == null || stack.isEmpty() || npcUuid == null) {
            return BreedingToggleResult.notToggled(stack);
        }
        List<LinkedNpcRecord> records = linkedNpcRecordStore.read(stack);
        LinkedNpcRecord record = linkedNpcRecordStore.find(records, npcUuid);
        if (record == null) {
            return BreedingToggleResult.notToggled(stack);
        }
        boolean nextEnabled = !record.breedingEnabled;
        ItemStack updated = linkedNpcRecordStore.setBreedingEnabled(stack, npcUuid, nextEnabled);
        if (updated == stack) {
            return BreedingToggleResult.notToggled(stack);
        }
        return new BreedingToggleResult(updated, true, nextEnabled);
    }

    ItemStack setLinkedNpcGroup(ItemStack stack, UUID npcUuid, String groupId) {
        return linkedNpcRecordStore.setGroup(stack, npcUuid, groupId);
    }

    ItemStack setLinkedNpcBreedingEnabled(ItemStack stack, UUID npcUuid, boolean breedingEnabled) {
        return linkedNpcRecordStore.setBreedingEnabled(stack, npcUuid, breedingEnabled);
    }

    List<LinkedNpcRecord> readLinkedNpcRecords(ItemStack stack) {
        return linkedNpcRecordStore.read(stack);
    }

    ItemStack writeLinkedNpcRecords(ItemStack stack, List<LinkedNpcRecord> records) {
        return linkedNpcRecordStore.write(stack, records);
    }

    private boolean shouldActivateOnLink(TwCommandItemConfig config, ItemStack stack) {
        int maxActive = config != null ? Math.max(0, config.getMaxActive()) : 0;
        if (maxActive <= 0) {
            return true;
        }
        return countActiveLinkedRecords(stack, null) < maxActive;
    }

    private boolean canActivateLinkedNpc(ItemStack stack,
                                         UUID targetNpcUuid,
                                         TwCommandItemConfig config) {
        int maxActive = config != null ? Math.max(0, config.getMaxActive()) : 0;
        if (maxActive <= 0) {
            return true;
        }
        return countActiveLinkedRecords(stack, targetNpcUuid) < maxActive;
    }

    private int countActiveLinkedRecords(ItemStack stack, UUID excludedNpcUuid) {
        List<LinkedNpcRecord> records = linkedNpcRecordStore.read(stack);
        if (records.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (LinkedNpcRecord record : records) {
            if (record == null || record.npcUuid == null || !record.active) {
                continue;
            }
            if (excludedNpcUuid != null && excludedNpcUuid.equals(record.npcUuid)) {
                continue;
            }
            count++;
        }
        return count;
    }

    static final class ActiveToggleResult {
        final ItemStack updatedItem;
        final boolean toggled;
        final boolean active;
        final boolean blockedByMaxActive;

        private ActiveToggleResult(ItemStack updatedItem, boolean toggled, boolean active, boolean blockedByMaxActive) {
            this.updatedItem = updatedItem;
            this.toggled = toggled;
            this.active = active;
            this.blockedByMaxActive = blockedByMaxActive;
        }

        private ActiveToggleResult(ItemStack updatedItem, boolean toggled, boolean active) {
            this(updatedItem, toggled, active, false);
        }

        static ActiveToggleResult notToggled(ItemStack stack) {
            return new ActiveToggleResult(stack, false, false);
        }

        static ActiveToggleResult maxActiveReached(ItemStack stack) {
            return new ActiveToggleResult(stack, false, false, true);
        }
    }

    static final class BreedingToggleResult {
        final ItemStack updatedItem;
        final boolean toggled;
        final boolean breedingEnabled;

        private BreedingToggleResult(ItemStack updatedItem, boolean toggled, boolean breedingEnabled) {
            this.updatedItem = updatedItem;
            this.toggled = toggled;
            this.breedingEnabled = breedingEnabled;
        }

        static BreedingToggleResult notToggled(ItemStack stack) {
            return new BreedingToggleResult(stack, false, false);
        }
    }
}
