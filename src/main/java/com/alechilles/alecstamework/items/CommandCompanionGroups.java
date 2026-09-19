package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.items.components.TameworkCompanionGroupsComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.*;
import java.util.function.UnaryOperator;

/** Owner-wide organization, accessed only on the player's world thread. Engine player saving owns durability. */
final class CommandCompanionGroups {
    private static final CommandGroupService CODEC = new CommandGroupService();

    private CommandCompanionGroups() { }

    static String entityKey(UUID id) { return "e" + id; }
    static String profileKey(String id) { return "p" + id; }

    static TameworkCompanionGroupsComponent read(Player player) {
        var type = TameworkCompanionGroupsComponent.getComponentType();
        if (player == null || player.getWorld() == null || type == null) return null;
        var world = player.getWorld();
        var ref = world.getEntityRef(player.getUuid());
        if (ref == null || !ref.isValid()) return null;
        return world.getEntityStore().getStore().getComponent(ref, type);
    }

    private static boolean save(Player player, TameworkCompanionGroupsComponent next) {
        var type = TameworkCompanionGroupsComponent.getComponentType();
        if (player == null || player.getWorld() == null || type == null) return false;
        var world = player.getWorld();
        var ref = world.getEntityRef(player.getUuid());
        if (ref == null || !ref.isValid()) return false;
        // Called from menu/item interaction callbacks, never an ECS system iteration.
        world.getEntityStore().getStore().putComponent(ref, type, next);
        return true;
    }

    static ItemStack view(Player player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return stack;
        var state = read(player);
        return state == null ? stack : stack.withMetadata(TameworkMetadataKeys.COMMAND_GROUPS, Codec.STRING, state.definitions());
    }

    static void importLegacy(Player player, ItemStack stack) {
        if (stack == null || stack.isEmpty() || TameworkCompanionGroupsComponent.getComponentType() == null) return;
        String tool = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING);
        if (tool == null || tool.isBlank()) return;
        var old = read(player);
        if (old != null && old.imported(tool)) return;
        var next = importLegacy(old == null ? new TameworkCompanionGroupsComponent() : old, stack, tool);
        if (next != old) save(player, next);
    }

    /** One-way import per physical tool. Conflicting legacy IDs retain both named groups. */
    static TameworkCompanionGroupsComponent importLegacy(TameworkCompanionGroupsComponent old,
                                                          ItemStack stack, String tool) {
        if (old.imported(tool)) return old;
        var next = old.clone();
        ItemStack shared = stack.withMetadata(TameworkMetadataKeys.COMMAND_GROUPS, Codec.STRING, old.definitions());
        Map<String, String> remap = new HashMap<>();
        for (var legacy : CODEC.readGroups(stack)) {
            var match = CODEC.readGroups(shared).stream()
                    .filter(g -> g.name.equals(legacy.name) && g.colorHex.equals(legacy.colorHex)).findFirst().orElse(null);
            if (match == null) {
                var before = CODEC.readGroups(shared);
                shared = CODEC.createGroup(shared, legacy.name, legacy.colorHex);
                var after = CODEC.readGroups(shared);
                if (after.size() == before.size()) {
                    // Preserve the entire legacy item for a later import after the owner frees capacity.
                    return old;
                }
                match = after.get(after.size() - 1);
            }
            remap.put(legacy.groupId, match.groupId);
        }
        for (var record : new CommandLinkedNpcRecordStore().read(stack)) {
            String group = remap.get(record.groupId);
            if (group == null) continue;
            String fallback = entityKey(record.npcUuid);
            String key = record.profileId == null ? fallback : profileKey(record.profileId);
            Set<String> groups = new LinkedHashSet<>(next.groups(key, fallback));
            groups.add(group);
            next.groups(key, fallback, groups);
        }
        next.definitions(shared.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_GROUPS, Codec.STRING));
        next.markImported(tool);
        return next;
    }

    static boolean mutate(Player player, ItemStack stack, UnaryOperator<ItemStack> mutation) {
        if (stack == null || stack.isEmpty()) return false;
        importLegacy(player, stack);
        var old = read(player);
        if (old == null) return false;
        var projected = view(player, stack);
        var updated = mutation.apply(projected);
        if (updated == null || updated == projected) return false;
        var next = old.clone();
        next.definitions(updated.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_GROUPS, Codec.STRING));
        Set<String> valid = new HashSet<>();
        CODEC.readGroups(updated).forEach(g -> valid.add(g.groupId));
        next.retainGroups(valid);
        return save(player, next);
    }

    static List<String> groups(Player player, String key, UUID alias) {
        var state = read(player);
        return state == null ? List.of() : state.groups(key, entityKey(alias));
    }

    /** Promote UUID tags when a canonical profile first becomes known, before its alias can change. */
    static void promoteProfile(Player player, String key, UUID alias) {
        var state = read(player);
        String fallback = entityKey(alias);
        if (state == null || key.equals(fallback) || state.groups(fallback, null).isEmpty()) return;
        var next = state.clone();
        next.groups(key, fallback, state.groups(key, fallback));
        save(player, next);
    }

    static Set<String> members(Player player, String groupId) {
        var state = read(player);
        return state == null ? Set.of() : state.members(groupId);
    }

    static boolean assign(Player player, ItemStack stack, String key, UUID alias, Collection<String> requested) {
        importLegacy(player, stack);
        var state = read(player);
        if (state == null || requested == null || requested.size() > CommandGroupService.MAX_GROUPS) return false;
        var valid = CODEC.readGroups(view(player, stack)).stream().map(g -> g.groupId).toList();
        if (!valid.containsAll(requested)) return false;
        var next = state.clone();
        next.groups(key, entityKey(alias), requested);
        return save(player, next);
    }
}
