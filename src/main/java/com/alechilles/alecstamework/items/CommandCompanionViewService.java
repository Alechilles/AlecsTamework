package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.ui.CompanionViewBinding;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Connects a flute's draft and selection to the player's shared named views. */
final class CommandCompanionViewService {
    static ItemStack restore(ItemStack stack, List<CommandCompanionViews.View> views) {
        var saved = CommandCompanionViewStore.read(stack);
        var named = CommandCompanionViews.find(views, saved.selectedId());
        String id = saved.selectedId();
        if (named == null && !id.isBlank() && !CommandCompanionViewStore.ALL_ID.equals(id)
                && !CommandCompanionViewStore.SELECTED_ID.equals(id)) {
            id = CommandCompanionViewStore.ALL_ID;
        }
        return id.isBlank() ? stack : CommandCompanionViewStore.choose(
                stack, id, named == null ? null : named.settings());
    }

    static CompanionViewBinding bind(CommandToolInventoryService inventory,
                                     CommandGroupAssignPageService groups,
                                     Supplier<Player> player, BooleanSupplier authority,
                                     String toolId, TwCommandItemConfig config) {
        Supplier<CommandCompanionViewStore.Snapshot> item =
                () -> CommandCompanionViewStore.read(inventory.findToolStack(player.get(), toolId));
        Supplier<List<CommandCompanionViews.View>> shared = () -> CommandCompanionViews.read(player.get());
        Consumer<UnaryOperator<ItemStack>> mutate = operation -> {
            if (authority.getAsBoolean()) {
                Player current = player.get();
                inventory.mutateToolStack(current, toolId,
                        stack -> CommandCompanionViewItemDisplay.apply(current, operation.apply(stack)));
            }
        };
        return new CompanionViewBinding(
                () -> item.get().current(),
                () -> {
                    List<CommandCompanionViews.View> views = shared.get();
                    return views == null ? List.of() : views.stream().map(view ->
                            new CompanionViewBinding.View(view.id(), view.name(), view.settings())).toList();
                },
                () -> item.get().selectedId(),
                settings -> mutate.accept(stack -> CommandCompanionViewStore.writeCurrent(stack, settings)),
                id -> {
                    List<CommandCompanionViews.View> views = shared.get();
                    if (views == null) return;
                    CommandCompanionViews.View selected = CommandCompanionViews.find(views, id);
                    if (selected == null && !CommandCompanionViewStore.ALL_ID.equals(id)
                            && !CommandCompanionViewStore.SELECTED_ID.equals(id)) return;
                    mutate.accept(stack -> CommandCompanionViewStore.choose(
                            stack, id, selected == null ? null : selected.settings()));
                },
                (name, update) -> {
                    if (!authority.getAsBoolean()) return;
                    Player current = player.get();
                    ItemStack stack = inventory.findToolStack(current, toolId);
                    if (stack == null) return;
                    var snapshot = CommandCompanionViewStore.read(stack);
                    if (update) {
                        if (!CommandCompanionViews.update(current, snapshot.selectedId(), snapshot.current())) return;
                    } else {
                        String created = CommandCompanionViews.saveNew(current, name, snapshot.current());
                        if (created == null) return;
                        mutate.accept(itemStack -> CommandCompanionViewStore.choose(
                                itemStack, created, snapshot.current()));
                    }
                    inventory.refreshViewDisplays(current);
                },
                name -> {
                    if (!authority.getAsBoolean()) return;
                    Player current = player.get();
                    if (CommandCompanionViews.rename(current, item.get().selectedId(), name)) {
                        inventory.refreshViewDisplays(current);
                    }
                },
                () -> {
                    if (!authority.getAsBoolean()) return;
                    Player current = player.get();
                    if (!CommandCompanionViews.delete(current, item.get().selectedId())) return;
                    mutate.accept(stack -> CommandCompanionViewStore.choose(
                            stack, CommandCompanionViewStore.ALL_ID, null));
                    inventory.refreshViewDisplays(current);
                },
                () -> { if (authority.getAsBoolean()) groups.applyMatchingSelection(player.get(), toolId, config); },
                () -> {
                    Player current = player.get();
                    return CommandFluteIconService.options(
                            inventory.findToolStack(current, toolId), config,
                            current == null || current.getPlayerRef() == null
                                    ? null : current.getPlayerRef().getLanguage());
                },
                () -> {
                    ItemStack stack = inventory.findToolStack(player.get(), toolId);
                    return stack == null ? "" : stack.getItemId();
                },
                itemId -> mutate.accept(stack -> CommandFluteIconService.choose(stack, config, itemId)));
    }
}
