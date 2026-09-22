package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.ui.CompanionViewBinding;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Item-local presentation writes use the same current-tool authority as command-panel actions. */
final class CommandCompanionViewService {
    static CompanionViewBinding bind(CommandToolInventoryService inventory,
                                     CommandGroupAssignPageService groups,
                                     Supplier<Player> player, BooleanSupplier authority,
                                     String toolId, TwCommandItemConfig config) {
        Supplier<CommandCompanionViewStore.Snapshot> read =
                () -> CommandCompanionViewStore.read(inventory.findToolStack(player.get(), toolId));
        Consumer<UnaryOperator<ItemStack>> mutate = operation -> {
            if (authority.getAsBoolean()) inventory.mutateToolStack(player.get(), toolId, operation);
        };
        return new CompanionViewBinding(
                () -> read.get().current(),
                () -> read.get().views().stream().map(v ->
                        new CompanionViewBinding.View(v.id(), v.name(), v.settings())).toList(),
                () -> read.get().selectedId(),
                settings -> mutate.accept(stack -> CommandCompanionViewStore.writeCurrent(stack, settings)),
                id -> mutate.accept(stack -> CommandCompanionViewStore.choose(stack, id)),
                (name, update) -> mutate.accept(stack -> CommandCompanionViewStore.save(stack, name, update)),
                name -> mutate.accept(stack -> CommandCompanionViewStore.rename(stack, name)),
                () -> mutate.accept(CommandCompanionViewStore::deleteSelected),
                () -> { if (authority.getAsBoolean()) groups.applyMatchingSelection(player.get(), toolId, config); });
    }
}
