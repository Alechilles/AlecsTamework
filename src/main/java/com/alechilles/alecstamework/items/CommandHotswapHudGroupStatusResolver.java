package com.alechilles.alecstamework.items;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.List;
import javax.annotation.Nullable;

/** Converts the active generic command roster selection into a compact HUD status. */
final class CommandHotswapHudGroupStatusResolver {
    private static final String ALL_COLOR = "#c8d1db";
    private static final String CUSTOM_COLOR = "#c9a653";
    private static final String NONE_COLOR = "#6e7c8b";

    private final CommandLinkedNpcRecordStore linkedNpcRecordStore;
    private final CommandGroupService groupService;
    private final CommandGroupActivationService activationService;

    CommandHotswapHudGroupStatusResolver(@Nullable CommandLinkedNpcRecordStore linkedNpcRecordStore,
                                         @Nullable CommandGroupService groupService,
                                         @Nullable CommandGroupActivationService activationService) {
        this.linkedNpcRecordStore = linkedNpcRecordStore != null
                ? linkedNpcRecordStore : new CommandLinkedNpcRecordStore();
        this.groupService = groupService != null ? groupService : new CommandGroupService();
        this.activationService = activationService != null
                ? activationService : new CommandGroupActivationService(
                        this.linkedNpcRecordStore, this.groupService);
    }

    CommandHotswapHudViewModel.GroupStatus resolve(@Nullable ItemStack stack) {
        return resolve(linkedNpcRecordStore.read(stack), groupService.readGroups(stack));
    }

    CommandHotswapHudViewModel.GroupStatus resolve(@Nullable List<LinkedNpcRecord> records,
                                                   @Nullable List<CommandGroupService.GroupRecord> groups) {
        String selection = activationService.resolveSelectionValue(records, groups);
        if (CommandGroupActivationService.ALL_VALUE.equals(selection)) {
            return new CommandHotswapHudViewModel.GroupStatus(true, "All Companions", ALL_COLOR);
        }
        if (CommandGroupActivationService.NONE_VALUE.equals(selection)) {
            return new CommandHotswapHudViewModel.GroupStatus(true, "No Active Companions", NONE_COLOR);
        }
        if (CommandGroupActivationService.CUSTOM_VALUE.equals(selection)) {
            return new CommandHotswapHudViewModel.GroupStatus(true, "Custom Selection", CUSTOM_COLOR);
        }
        CommandGroupService.GroupRecord group = findGroup(groups, selection);
        if (group == null) {
            return new CommandHotswapHudViewModel.GroupStatus(true, "Custom Selection", CUSTOM_COLOR);
        }
        String label = group.name == null || group.name.isBlank() ? group.groupId : group.name.trim();
        return new CommandHotswapHudViewModel.GroupStatus(true, label, safeColor(group.colorHex));
    }

    @Nullable
    private CommandGroupService.GroupRecord findGroup(
            @Nullable List<CommandGroupService.GroupRecord> groups,
            @Nullable String selection) {
        if (groups == null || selection == null || selection.isBlank()) {
            return null;
        }
        for (CommandGroupService.GroupRecord group : groups) {
            if (group != null && group.groupId != null
                    && group.groupId.trim().equalsIgnoreCase(selection.trim())) {
                return group;
            }
        }
        return null;
    }

    private String safeColor(@Nullable String colorHex) {
        if (colorHex == null || !colorHex.matches("#[0-9a-fA-F]{6}")) {
            return ALL_COLOR;
        }
        return colorHex;
    }
}
