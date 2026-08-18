package com.alechilles.alecstamework.items;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Function;
import javax.annotation.Nullable;

/** Converts the active generic command roster selection into a compact HUD status. */
final class CommandHotswapHudGroupStatusResolver {
    private static final String ALL_COLOR = "#c8d1db";
    private static final String CUSTOM_COLOR = "#c9a653";
    private static final String NONE_COLOR = "#6e7c8b";
    private static final CommandHotswapHudViewModel.GroupStatus ALL_STATUS =
            new CommandHotswapHudViewModel.GroupStatus(true, "All Companions", ALL_COLOR);
    private static final CommandHotswapHudViewModel.GroupStatus NONE_STATUS =
            new CommandHotswapHudViewModel.GroupStatus(true, "No Active Companions", NONE_COLOR);
    private static final CommandHotswapHudViewModel.GroupStatus CUSTOM_STATUS =
            new CommandHotswapHudViewModel.GroupStatus(true, "Custom Selection", CUSTOM_COLOR);

    private final Function<ItemStack, List<LinkedNpcRecord>> linkedNpcReader;
    private final Function<ItemStack, List<CommandGroupService.GroupRecord>> groupReader;
    private final CommandGroupActivationService activationService;
    private final Map<UUID, CachedStatus> statusByPlayer = new WeakHashMap<>();

    CommandHotswapHudGroupStatusResolver(@Nullable CommandLinkedNpcRecordStore linkedNpcRecordStore,
                                         @Nullable CommandGroupService groupService,
                                         @Nullable CommandGroupActivationService activationService) {
        CommandLinkedNpcRecordStore records = linkedNpcRecordStore != null
                ? linkedNpcRecordStore : new CommandLinkedNpcRecordStore();
        CommandGroupService groups = groupService != null ? groupService : new CommandGroupService();
        this.linkedNpcReader = records::read;
        this.groupReader = groups::readGroups;
        this.activationService = activationService != null
                ? activationService : new CommandGroupActivationService(
                        records, groups);
    }

    private CommandHotswapHudGroupStatusResolver(
            Function<ItemStack, List<LinkedNpcRecord>> linkedNpcReader,
            Function<ItemStack, List<CommandGroupService.GroupRecord>> groupReader,
            @Nullable CommandGroupActivationService activationService) {
        this.linkedNpcReader = linkedNpcReader;
        this.groupReader = groupReader;
        this.activationService = activationService != null
                ? activationService : new CommandGroupActivationService(null, null);
    }

    static CommandHotswapHudGroupStatusResolver forReaders(
            Function<ItemStack, List<LinkedNpcRecord>> linkedNpcReader,
            Function<ItemStack, List<CommandGroupService.GroupRecord>> groupReader) {
        return new CommandHotswapHudGroupStatusResolver(linkedNpcReader, groupReader, null);
    }

    CommandHotswapHudViewModel.GroupStatus resolve(@Nullable UUID playerUuid,
                                                   @Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return NONE_STATUS;
        }
        CachedStatus cached = null;
        if (playerUuid != null) {
            synchronized (statusByPlayer) {
                cached = statusByPlayer.get(playerUuid);
            }
        }
        if (cached != null && cached.stack() == stack) {
            return cached.status();
        }
        CommandHotswapHudViewModel.GroupStatus resolved = resolve(
                linkedNpcReader.apply(stack),
                groupReader.apply(stack)
        );
        if (playerUuid != null) {
            synchronized (statusByPlayer) {
                statusByPlayer.put(playerUuid, new CachedStatus(stack, resolved));
            }
        }
        return resolved;
    }

    CommandHotswapHudViewModel.GroupStatus resolve(@Nullable List<LinkedNpcRecord> records,
                                                   @Nullable List<CommandGroupService.GroupRecord> groups) {
        String selection = activationService.resolveSelectionValue(records, groups);
        if (CommandGroupActivationService.ALL_VALUE.equals(selection)) {
            return ALL_STATUS;
        }
        if (CommandGroupActivationService.NONE_VALUE.equals(selection)) {
            return NONE_STATUS;
        }
        if (CommandGroupActivationService.CUSTOM_VALUE.equals(selection)) {
            return CUSTOM_STATUS;
        }
        CommandGroupService.GroupRecord group = findGroup(groups, selection);
        if (group == null) {
            return CUSTOM_STATUS;
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

    private record CachedStatus(ItemStack stack,
                                CommandHotswapHudViewModel.GroupStatus status) {
    }
}
