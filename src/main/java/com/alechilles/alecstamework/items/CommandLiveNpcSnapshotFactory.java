package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.NpcDisplayNameComponentService;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Arrays;
import java.util.UUID;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Freezes the immutable last-live command and presentation snapshot of one NPC.
 *
 * <p>All ECS components are read only inside {@link #capture}; only scalar values,
 * copied arrays, and copied vectors leave the world-thread boundary.</p>
 */
final class CommandLiveNpcSnapshotFactory {
    @Nullable
    CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot capture(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            NPCEntity npc
    ) {
        if (npcRef == null || !npcRef.isValid() || store == null
                || npc == null || npc.getUuid() == null) {
            return null;
        }
        TameworkCommandLinksComponent links = component(
                npcRef, store, TameworkCommandLinksComponent.getComponentType()
        );
        String[] toolIds = links == null
                ? new String[0] : sanitizeToolIds(links.getToolIds());
        if (toolIds.length == 0) {
            return null;
        }

        TameworkOwnerComponent owner = component(
                npcRef, store, TameworkOwnerComponent.getComponentType()
        );
        UUID ownerId = owner != null && owner.getOwnerId() != null
                ? owner.getOwnerId() : links.getOwnerId();
        String ownerName = owner == null ? null : owner.getOwnerName();

        String roleId = resolveRoleId(npc);
        String customName = resolveCustomName(npcRef, store);
        return new CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot(
                npc.getUuid(),
                ownerId,
                ownerName,
                toolIds,
                roleId,
                TamedStateResolver.isTamed(npcRef, store),
                customName,
                resolveDisplayName(
                        npcRef, store, npc, roleId, customName
                ),
                position(npcRef, store),
                links.hasHome() ? links.getHomePosition() : null
        );
    }

    @Nullable
    private <T extends Component<EntityStore>> T component(
            Ref<EntityStore> reference,
            Store<EntityStore> store,
            @Nullable ComponentType<EntityStore, T> type
    ) {
        return type == null ? null : store.getComponent(reference, type);
    }

    @Nullable
    private Vector3d position(
            Ref<EntityStore> reference,
            Store<EntityStore> store
    ) {
        TransformComponent transform = store.getComponent(
                reference, TransformComponent.getComponentType()
        );
        return transform == null
                ? null : new Vector3d(transform.getPosition());
    }

    @Nullable
    private String resolveRoleId(NPCEntity npc) {
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            return roleName;
        }
        NPCPlugin plugin = NPCPlugin.get();
        return npc.getRoleIndex() < 0 || plugin == null
                ? null : nonBlank(plugin.getName(npc.getRoleIndex()));
    }

    @Nullable
    private String resolveCustomName(
            Ref<EntityStore> reference,
            Store<EntityStore> store
    ) {
        TameworkNpcNameComponent component = component(
                reference, store, TameworkNpcNameComponent.getComponentType()
        );
        return component == null ? null : nonBlank(component.getName());
    }

    private String resolveDisplayName(
            Ref<EntityStore> reference,
            Store<EntityStore> store,
            NPCEntity npc,
            @Nullable String roleId,
            @Nullable String customName
    ) {
        if (customName != null) {
            return customName;
        }
        String componentName = nonBlank(
                NpcDisplayNameComponentService.resolvePersistentOrRuntimeName(
                        reference, store
                )
        );
        if (componentName != null) {
            return componentName;
        }
        String legacy = nonBlank(npc.getLegacyDisplayName());
        if (legacy != null) {
            return legacy;
        }
        return roleId == null ? "Companion" : roleId;
    }

    private String[] sanitizeToolIds(@Nullable String[] toolIds) {
        if (toolIds == null || toolIds.length == 0) {
            return new String[0];
        }
        return Arrays.stream(toolIds)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toArray(String[]::new);
    }

    @Nullable
    private String nonBlank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
