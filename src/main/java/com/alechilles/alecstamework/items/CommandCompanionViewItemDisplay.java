package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.localization.RoleNameResolver;
import com.alechilles.alecstamework.ui.CompanionViewSettings;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** Keeps the physical flute's tooltip aligned with its selected saved view. */
final class CommandCompanionViewItemDisplay {
    private static final String PREFIX = "server.tamework.ui.commandItem.view.";
    private static final String NAME_KEY = PREFIX + "name";
    private static final String FILTER_HEADER = "#F6C453";
    private static final String FILTER_LABEL = "#AFB6B0";
    private static final String FILTER_VALUE = "#F3E7C9";

    private CommandCompanionViewItemDisplay() { }

    static ItemStack apply(@Nullable Player player, @Nullable ItemStack stack) {
        return apply(player, stack, CommandCompanionViews.read(player));
    }

    static ItemStack apply(@Nullable Player player, @Nullable ItemStack stack,
                           @Nullable List<CommandCompanionViews.View> views) {
        if (stack == null || stack.isEmpty()) return stack;
        ItemDisplayMetadata existing = stack.getFromMetadataOrNull(ItemDisplayMetadata.KEYED_CODEC);
        if (existing != null && !owned(existing)) return stack;
        if (views == null) return stack;

        var snapshot = CommandCompanionViewStore.read(stack);
        var selected = CommandCompanionViews.find(views, snapshot.selectedId());
        if (selected == null) {
            return existing == null ? stack : stack.withMetadata(ItemDisplayMetadata.KEYED_CODEC, null);
        }

        Message name = Message.translation(NAME_KEY)
                .param("0", stack.getItem().getTranslationMessage())
                .param("1", Message.raw(selected.name()));
        Message description = Message.join(filters(player, stack, selected.settings()),
                Message.raw("\n\n"), stack.getItem().getDescriptionTranslationMessage());
        return stack.withMetadata(ItemDisplayMetadata.KEYED_CODEC,
                new ItemDisplayMetadata(name, description));
    }

    private static boolean owned(ItemDisplayMetadata display) {
        return display.getName() != null && NAME_KEY.equals(display.getName().getMessageId());
    }

    private static Message filters(@Nullable Player player, ItemStack stack, CompanionViewSettings settings) {
        List<Message> lines = new ArrayList<>();
        String stateKey = switch (settings.state()) {
            case "InWorld" -> "inWorld";
            case "Stored" -> "stored";
            case "LostDead" -> "lostDead";
            default -> null;
        };
        if (stateKey != null) {
            lines.add(line("state", Message.translation("server.tamework.ui.companions." + stateKey)));
        }
        if (settings.nearby()) lines.add(Message.translation(PREFIX + "nearby"));
        if (!settings.speciesIds().isEmpty()) {
            lines.add(line("species", join(settings.speciesIds().stream().map(id -> {
                String key = RoleNameResolver.resolveRoleNameKey(id);
                return key == null ? Message.raw(id) : Message.translation(key);
            }).toList())));
        }
        if (!settings.groupIds().isEmpty()) {
            Map<String, String> groups = new CommandGroupService().readGroups(player, stack).stream()
                    .collect(Collectors.toMap(group -> group.groupId, group -> group.name,
                            (first, ignored) -> first));
            lines.add(line("groups", join(settings.groupIds().stream()
                    .map(id -> Message.raw(groups.getOrDefault(id, id))).toList())));
        }
        if (settings.selectedOnly()) lines.add(Message.translation(PREFIX + "selected"));
        if (!settings.search().isEmpty()) lines.add(line("search", Message.raw(settings.search())));

        if (lines.isEmpty()) {
            return Message.translation(PREFIX + "none").color(FILTER_HEADER).bold(true);
        }
        List<Message> content = new ArrayList<>();
        content.add(Message.translation(PREFIX + "filters").color(FILTER_HEADER).bold(true));
        for (Message line : lines) {
            content.add(Message.raw("\n").color(FILTER_LABEL));
            content.add(line.color(FILTER_LABEL));
        }
        return Message.join(content.toArray(Message[]::new));
    }

    private static Message line(String key, Message value) {
        return Message.translation(PREFIX + key).param("0", value.color(FILTER_VALUE));
    }

    private static Message join(List<Message> values) {
        List<Message> parts = new ArrayList<>();
        for (Message value : values) {
            if (!parts.isEmpty()) parts.add(Message.raw(", "));
            parts.add(value);
        }
        return Message.join(parts.toArray(Message[]::new));
    }
}
