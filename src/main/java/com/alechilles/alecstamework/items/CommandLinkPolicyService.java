package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.MembershipMode;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;

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
                              boolean requireOwner,
                              Ref<EntityStore> npcRef,
                              NPCEntity npc,
                              Ref<EntityStore> playerRef,
                              UUID playerUuid,
                              String toolId,
                              Store<EntityStore> store) {
        MembershipMode mode = membershipMode != null ? membershipMode : MembershipMode.LinkedOnly;
        boolean linked = isLinkedToTool(npcRef, playerUuid, toolId, store);
        boolean owner = isOwnedByPlayer(npcRef, playerUuid, store);
        boolean master = isMasterTargetedToPlayer(npcRef, npc, playerRef, store);
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
        return owner != null ? owner.getOwnerId() : null;
    }

    String resolveRoleId(NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        return selectRoleId(
                npc.getRoleName(),
                npc.getRoleIndex(),
                CommandLinkPolicyService::lookupRegisteredRoleId
        );
    }

    static String selectRoleId(String roleName, int roleIndex, IntFunction<String> registeredRoleLookup) {
        if (roleIndex >= 0 && registeredRoleLookup != null) {
            String registeredRoleId = registeredRoleLookup.apply(roleIndex);
            if (registeredRoleId != null && !registeredRoleId.isBlank()) {
                return registeredRoleId;
            }
        }
        if (roleName != null && !roleName.isBlank()) {
            return roleName;
        }
        return null;
    }

    private static String lookupRegisteredRoleId(int roleIndex) {
        NPCPlugin plugin = NPCPlugin.get();
        return plugin != null ? plugin.getName(roleIndex) : null;
    }

    boolean isRoleAllowed(String roleId, TwCommandItemConfig config) {
        return isRoleAllowed(roleId, config, false);
    }

    boolean isRoleAllowed(String roleId, TwCommandItemConfig config, boolean tamed) {
        if (config == null) {
            return true;
        }
        TwCommandItemConfig.AllowedRoles allowed = config.getAllowedRoles();
        if (allowed == null || allowed.getMode() == null) {
            return true;
        }
        String[] roleCandidates = resolveRoleCandidates(roleId, tamed);
        return switch (allowed.getMode()) {
            case AllowAll -> true;
            case Allowlist -> containsAny(allowed.getAllowlist(), roleCandidates);
            case Denylist -> !containsAny(allowed.getDenylist(), roleCandidates);
        };
    }

    private String[] resolveRoleCandidates(String roleId, boolean tamed) {
        if (roleId == null || roleId.isBlank()) {
            return new String[0];
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String trimmed = roleId.trim();
        candidates.add(trimmed);
        if (!tamed) {
            return candidates.toArray(new String[0]);
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.endsWith("_pet")) {
            candidates.add(trimmed + "_Pet");
        }
        if (!lower.startsWith("tamed_")) {
            candidates.add("Tamed_" + trimmed);
        }
        if (lower.endsWith("_pet")) {
            candidates.add(trimmed.substring(0, trimmed.length() - 4));
        }
        if (lower.startsWith("tamed_") && trimmed.length() > 6) {
            candidates.add(trimmed.substring(6));
        }
        return candidates.toArray(new String[0]);
    }

    boolean isLinkedToTool(Ref<EntityStore> npcRef,
                           UUID playerUuid,
                           String toolId,
                           Store<EntityStore> store) {
        TameworkCommandLinksComponent links = store.getComponent(npcRef, TameworkCommandLinksComponent.getComponentType());
        return isLinkedToTool(resolveOwnerId(npcRef, store), links, playerUuid, toolId);
    }

    static boolean isLinkedToTool(UUID canonicalOwnerId,
                                  TameworkCommandLinksComponent links,
                                  UUID playerUuid,
                                  String toolId) {
        return links != null
                && toolId != null
                && !toolId.isBlank()
                && isLinkAuthorized(
                        canonicalOwnerId,
                        links.getOwnerId(),
                        playerUuid,
                        links.containsToolId(toolId)
                );
    }

    static boolean isLinkAuthorized(UUID canonicalOwnerId,
                                    UUID linkOwnerId,
                                    UUID playerUuid,
                                    boolean containsToolId) {
        return containsToolId
                && canonicalOwnerId != null
                && canonicalOwnerId.equals(playerUuid)
                && canonicalOwnerId.equals(linkOwnerId);
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

    private boolean containsAny(String[] values, String[] expectedValues) {
        if (values == null || values.length == 0 || expectedValues == null || expectedValues.length == 0) {
            return false;
        }
        for (String expected : expectedValues) {
            if (contains(values, expected)) {
                return true;
            }
        }
        return false;
    }

    private boolean isMasterTargetedToPlayer(Ref<EntityStore> npcRef,
                                             NPCEntity npc,
                                             Ref<EntityStore> playerRef,
                                             Store<EntityStore> store) {
        Role role = npc != null ? npc.getRole() : null;
        MarkedEntitySupport markedEntity = role != null
                ? NpcSupportAccess.markedEntity(role, npcRef, store)
                : null;
        if (markedEntity == null) {
            return false;
        }
        Ref<EntityStore> marked = markedEntity.getMarkedEntityRef("MasterTarget");
        return marked != null && marked.isValid() && marked.equals(playerRef);
    }
}
