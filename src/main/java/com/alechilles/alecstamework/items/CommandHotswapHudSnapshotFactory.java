package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSnapshot;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Creates detached hotswap HUD snapshots from the existing service model. */
final class CommandHotswapHudSnapshotFactory {
    @Nonnull
    CommandHotswapHudSnapshot create(@Nonnull CommandHotswapHudViewModel model) {
        Objects.requireNonNull(model, "model");
        return new CommandHotswapHudSnapshot(
                copySlot(model.primary()),
                copySlot(model.secondary()),
                copySlot(model.q()),
                copySlot(model.e()),
                copySlot(model.r()),
                copyGroupStatus(model.groupStatus())
        );
    }

    @Nonnull
    private static CommandHotswapHudSnapshot.Slot copySlot(
            @Nonnull CommandHotswapHudViewModel.Slot slot
    ) {
        return new CommandHotswapHudSnapshot.Slot(
                slot.visible(),
                slot.bindingLabel(),
                slot.iconTexturePath(),
                slot.fallbackGlyph()
        );
    }

    @Nonnull
    private static CommandHotswapHudSnapshot.GroupStatus copyGroupStatus(
            @Nonnull CommandHotswapHudViewModel.GroupStatus status
    ) {
        return new CommandHotswapHudSnapshot.GroupStatus(
                status.visible(), status.label(), status.colorHex());
    }
}
