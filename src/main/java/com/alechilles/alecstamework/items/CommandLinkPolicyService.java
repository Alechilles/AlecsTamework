package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.MembershipMode;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Evaluates command-item link ownership and role policy decisions.
 *
 * <p>This keeps membership/ownership/role filtering rules centralized and reusable across command
 * selection, recipient querying, and link-toggle flows.
 */
final class CommandLinkPolicyService {

    String[] mergeToolIds(String[] existing, String requiredToolId) {
        Set<String> out = new HashSet<>();
        if (existing != null) {
            for (String value : existing) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                out.add(value);
            }
        }
        if (requiredToolId != null && !requiredToolId.isBlank()) {
            out.add(requiredToolId);
        }
        return out.toArray(new String[0]);
    }

    boolean matchesMembership(MembershipMode membershipMode,
                              Ref<EntityStore> npcRef,
                              NPCEntity npc,
                              Ref<EntityStore> playerRef,
                              UUID playerUuid,
                              String toolId,
                              Store<EntityStore> store) {
        MembershipMode mode = membershipMode != null ? membershipMode : MembershipMode.LinkedOnly;
        boolean linked = isLinked(npcRef, playerUuid, toolId, store);
        boolean owner = isOwnedByPlayer(npcRef, playerUuid, store);
        boolean master = isMasterTargetedToPlayer(npc, playerRef);
        return switch (mode) {
            case LinkedOnly -> linked;
            case OwnerScope -> owner;
            case MasterTarget -> master;
            case LinkedOrMasterTarget -> linked || master;
        };
    }

    boolean passesOwnerAndTamed(boolean requireOwner,
                                boolean requireTamed,
                                Ref<EntityStore> npcRef,
                                UUID playerUuid,
                                Store<EntityStore> store) {
        UUID ownerId = resolveOwnerId(npcRef, store);
        if (requireOwner && ownerId != null && !ownerId.equals(playerUuid)) {
            return false;
        }
        if (requireOwner && ownerId == null) {
            return false;
        }
        if (requireTamed && !TamedStateResolver.isTamed(npcRef, store)) {
            return false;
        }
        return true;
    }

    UUID resolveOwnerId(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        TameworkOwnerComponent owner = store.getComponent(npcRef, TameworkOwnerComponent.getComponentType());
        if (owner != null && owner.getOwnerId() != null) {
            return owner.getOwnerId();
        }
        TameworkCommandLinksComponent links = store.getComponent(npcRef, TameworkCommandLinksComponent.getComponentType());
        return links != null ? links.getOwnerId() : null;
    }

    String resolveRoleId(NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        if (npc.getRoleName() != null && !npc.getRoleName().isBlank()) {
            return npc.getRoleName();
        }
        if (npc.getRoleIndex() >= 0 && NPCPlugin.get() != null) {
            return NPCPlugin.get().getName(npc.getRoleIndex());
        }
        return null;
    }

    boolean isRoleAllowed(String roleId, TwCommandItemConfig config) {
        if (config == null) {
            return true;
        }
        TwCommandItemConfig.AllowedRoles allowed = config.getAllowedRoles();
        if (allowed == null || allowed.getMode() == null) {
            return true;
        }
        return switch (allowed.getMode()) {
            case AllowAll -> true;
            case Allowlist -> contains(allowed.getAllowlist(), roleId);
            case Denylist -> !contains(allowed.getDenylist(), roleId);
        };
    }

    private boolean isLinked(Ref<EntityStore> npcRef, UUID playerUuid, String toolId, Store<EntityStore> store) {
        TameworkCommandLinksComponent links = store.getComponent(npcRef, TameworkCommandLinksComponent.getComponentType());
        if (links == null || toolId == null || toolId.isBlank()) {
            return false;
        }
        UUID ownerId = links.getOwnerId();
        if (ownerId != null && !ownerId.equals(playerUuid)) {
            return false;
        }
        return links.containsToolId(toolId);
    }

    private boolean isOwnedByPlayer(Ref<EntityStore> npcRef, UUID playerUuid, Store<EntityStore> store) {
        UUID ownerId = resolveOwnerId(npcRef, store);
        return ownerId != null && ownerId.equals(playerUuid);
    }

    private boolean contains(String[] values, String expected) {
        if (values == null || values.length == 0 || expected == null || expected.isBlank()) {
            return false;
        }
        for (String value : values) {
            if (expected.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean isMasterTargetedToPlayer(NPCEntity npc, Ref<EntityStore> playerRef) {
        if (npc == null || npc.getRole() == null || npc.getRole().getMarkedEntitySupport() == null) {
            return false;
        }
        try {
            Method method = npc.getRole().getMarkedEntitySupport().getClass().getMethod("getMarkedEntity", String.class);
            Object value = method.invoke(npc.getRole().getMarkedEntitySupport(), "MasterTarget");
            if (!(value instanceof Ref<?> marked)) {
                return false;
            }
            return marked.isValid() && marked.equals(playerRef);
        } catch (Exception ignored) {
            return false;
        }
    }
}
