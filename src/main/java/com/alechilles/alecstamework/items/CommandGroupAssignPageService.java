package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import java.util.List;
import java.util.UUID;

/**
 * Resolves group options and applies one linked-panel NPC group assignment.
 */
final class CommandGroupAssignPageService {
    private final CommandPanelActionService panelActionService;
    private final CommandToolInventoryService toolInventoryService;
    private final CommandGroupActivationService groupActivationService;

    CommandGroupAssignPageService(CommandPanelActionService panelActionService,
                                  CommandToolInventoryService toolInventoryService,
                                  CommandGroupActivationService groupActivationService) {
        this.panelActionService = panelActionService;
        this.toolInventoryService = toolInventoryService;
        this.groupActivationService = groupActivationService != null
                ? groupActivationService
                : new CommandGroupActivationService(null, null);
    }

    List<DropdownEntryInfo> resolveGroupDropdownEntries(Player player, String toolId) {
        if (toolInventoryService == null) {
            return List.of();
        }
        return toolInventoryService.resolveGroupDropdownEntriesForTool(player, toolId);
    }

    List<DropdownEntryInfo> resolveGroupActivationDropdownEntries(Player player, String toolId) {
        ItemStack stack = toolInventoryService != null ? toolInventoryService.findToolStack(player, toolId) : null;
        return groupActivationService.resolveDropdownEntries(CommandCompanionGroups.view(player, stack), resolveLanguage(player));
    }

    java.util.Map<String, String> resolveGroupColors(Player player, String toolId) {
        ItemStack stack = toolInventoryService != null ? toolInventoryService.findToolStack(player, toolId) : null;
        return groupActivationService.resolveGroupColors(CommandCompanionGroups.view(player, stack));
    }

    String resolveGroupActivationValue(Player player, String toolId, TwCommandItemConfig config) {
        return resolveGroupActivationValue(player, toolId, config,
                () -> toolInventoryService.buildLinkedPanelBaseEntriesForTool(player, toolId, config));
    }

    /** Uses the page's current unfiltered rows instead of rebuilding the roster for each control. */
    String resolveGroupActivationValue(Player player, String toolId, TwCommandItemConfig config,
                                       java.util.function.Supplier<List<LinkedNpcEntry>> entries) {
        ItemStack stack = toolInventoryService != null ? toolInventoryService.findToolStack(player, toolId) : null;
        if (stack == null || config == null
                || config.getRosterStorage() != TwCommandItemConfig.RosterStorage.ItemMetadata) {
            return CommandGroupActivationService.NONE_VALUE;
        }
        return resolveGroupActivationValue(entries.get(), new CommandGroupService().readGroups(player, stack));
    }

    static String resolveGroupActivationValue(List<LinkedNpcEntry> entries,
                                              List<CommandGroupService.GroupRecord> groups) {
        java.util.Set<String> selected = new java.util.HashSet<>();
        java.util.Set<String> selectable = new java.util.HashSet<>();
        for (LinkedNpcEntry entry : entries) {
            if (entry == null || !entry.selectionSupported()) continue;
            String key = entry.companionKey() == null || entry.companionKey().isBlank()
                    ? CommandCompanionGroups.entityKey(entry.npcUuid()) : entry.companionKey();
            selectable.add(key);
            if (entry.active()) selected.add(key);
        }
        if (selected.isEmpty()) return CommandGroupActivationService.NONE_VALUE;
        if (!selectable.isEmpty() && selected.equals(selectable)) {
            return CommandGroupActivationService.ALL_VALUE;
        }
        for (var group : groups) {
            java.util.Set<String> members = new java.util.HashSet<>();
            for (LinkedNpcEntry entry : entries) {
                if (entry == null || !entry.selectionSupported() || !entry.groupIds().contains(group.groupId)) continue;
                members.add(entry.companionKey() == null || entry.companionKey().isBlank()
                        ? CommandCompanionGroups.entityKey(entry.npcUuid()) : entry.companionKey());
            }
            if (!members.isEmpty() && selected.equals(members)) return group.groupId;
        }
        return CommandGroupActivationService.CUSTOM_VALUE;
    }

    void applyGroupActivation(Player player,
                              String toolId,
                              TwCommandItemConfig config,
                              String selectorValue) {
        applyGroupActivation(player, toolId, config, selectorValue, false);
    }

    void applyGroupActivation(Player player, String toolId, TwCommandItemConfig config,
                              String selectorValue, boolean additive) {
        if (!CommandRosterStorageBoundary.allowsGenericRosterActions(config)
                || player == null || toolId == null || toolId.isBlank()
                || toolInventoryService == null) {
            return;
        }
        List<LinkedNpcEntry> entries = toolInventoryService.buildLinkedPanelBaseEntriesForTool(player, toolId, config);
        boolean all = CommandGroupActivationService.ALL_VALUE.equals(selectorValue);
        boolean none = CommandGroupActivationService.NONE_VALUE.equals(selectorValue);
        if (!all && !none && resolveGroupDropdownEntries(player, toolId).stream().noneMatch(g -> g.value().equals(selectorValue))) return;
        // Existing selections get capacity priority when adding a group. Every candidate is revalidated.
        var candidates = selectionCandidates(entries, selectorValue, additive,
                id -> toolInventoryService.resolveOwnedSelectionRecord(player, toolId, config, id));
        var records = new CommandLinkedNpcRecordStore();
        toolInventoryService.mutateToolStack(player, toolId, stack -> {
            var next = new java.util.LinkedHashMap<UUID, LinkedNpcRecord>();
            records.read(stack).forEach(record -> next.put(record.npcUuid, record.withActive(false)));
            int limit = config.getMaxActive();
            int selected = 0;
            for (var record : candidates) {
                if (limit > 0 && selected >= limit) break;
                if (record.profileId != null) next.values().removeIf(previous -> record.profileId.equals(previous.profileId));
                next.put(record.npcUuid, record.withActive(true));
                selected++;
            }
            return records.write(stack, new java.util.ArrayList<>(next.values()));
        });
    }

    /** Replaces recipients with all eligible matches, across every page, up to the tool's capacity. */
    void applyMatchingSelection(Player player, String toolId, TwCommandItemConfig config) {
        if (player == null || config == null
                || config.getRosterStorage() != TwCommandItemConfig.RosterStorage.ItemMetadata) return;
        var entries = toolInventoryService.buildLinkedPanelBaseEntriesForTool(player, toolId, config);
        var records = new CommandLinkedNpcRecordStore();
        toolInventoryService.mutateToolStack(player, toolId, stack -> records.write(stack,
                matchingSelection(records.read(stack), entries, CommandCompanionViewStore.read(stack).current(),
                        config.getMaxActive(), id -> toolInventoryService.resolveOwnedSelectionRecord(player, toolId, config, id))));
    }

    static List<LinkedNpcRecord> matchingSelection(List<LinkedNpcRecord> previous, List<LinkedNpcEntry> entries,
            com.alechilles.alecstamework.ui.CompanionViewSettings settings, int limit,
            java.util.function.Function<UUID, LinkedNpcRecord> resolve) {
        var matching = settings.filter(entries.toArray(LinkedNpcEntry[]::new));
        var candidates = selectionCandidates(java.util.Arrays.asList(matching),
                CommandGroupActivationService.ALL_VALUE, false, resolve);
        // A stale or entirely read-only view must not clear unrelated selections.
        if (candidates.isEmpty()) return previous;
        var next = new java.util.LinkedHashMap<UUID, LinkedNpcRecord>();
        previous.forEach(record -> next.put(record.npcUuid, record.withActive(false)));
        var unique = new java.util.LinkedHashMap<String, LinkedNpcRecord>();
        for (var record : candidates) unique.put(record.profileId == null
                ? "e:" + record.npcUuid : "p:" + record.profileId, record);
        int selected = 0;
        for (var record : unique.values()) {
            if (limit > 0 && selected >= limit) break;
            if (record.profileId != null) next.values().removeIf(old -> record.profileId.equals(old.profileId));
            next.put(record.npcUuid, record.withActive(true));
            selected++;
        }
        return new java.util.ArrayList<>(next.values());
    }

    static List<LinkedNpcRecord> selectionCandidates(List<LinkedNpcEntry> entries, String group,
            boolean additive, java.util.function.Function<UUID, LinkedNpcRecord> resolve) {
        var candidates = new java.util.LinkedHashMap<UUID, LinkedNpcRecord>();
        if (CommandGroupActivationService.NONE_VALUE.equals(group)) return List.of();
        if (additive) {
            for (var entry : entries) {
                if (entry.active() && entry.selectionSupported()) {
                    var record = resolve.apply(entry.npcUuid());
                    if (record != null) candidates.put(record.npcUuid, record);
                }
            }
        }
        for (var entry : entries) {
            if (!entry.selectionSupported() || candidates.containsKey(entry.npcUuid())
                    || !(CommandGroupActivationService.ALL_VALUE.equals(group) || entry.groupIds().contains(group))) continue;
            var record = resolve.apply(entry.npcUuid());
            if (record != null) candidates.put(record.npcUuid, record);
        }
        return List.copyOf(candidates.values());
    }

    void applyGroupAssignment(Player player,
                              String toolId,
                              TwCommandItemConfig config,
                              UUID npcUuid,
                              String groupId) {
        if (!CommandRosterStorageBoundary.allowsGenericRosterActions(config)
                || player == null || toolId == null || toolId.isBlank()
                || npcUuid == null) {
            return;
        }
        if (panelActionService == null) {
            return;
        }
        applyGroupAssignments(player, toolId, config, npcUuid, groupId == null ? List.of() : List.of(groupId));
    }

    void applyGroupAssignments(Player player, String toolId, TwCommandItemConfig config, UUID npcUuid, List<String> groupIds) {
        if (!CommandRosterStorageBoundary.allowsGenericRosterActions(config) || player == null || npcUuid == null || groupIds == null) return;
        var entry = toolInventoryService.buildLinkedPanelBaseEntriesForTool(player, toolId, config).stream()
                .filter(e -> npcUuid.equals(e.npcUuid()) && e.ownedActions()).findFirst().orElse(null);
        // Group tags belong to the viewer. A freshly resolved captured card may be
        // organized even when capture cleared ownership and command selection is unavailable.
        if (entry == null || !entry.captured()
                && toolInventoryService.resolveOwnedSelectionRecord(player, toolId, config, npcUuid) == null) return;
        CommandCompanionGroups.assign(player, toolInventoryService.findToolStack(player, toolId),
                entry.companionKey(), npcUuid, groupIds);
    }

    private String resolveLanguage(Player player) {
        return player != null && player.getPlayerRef() != null ? player.getPlayerRef().getLanguage() : null;
    }
}
