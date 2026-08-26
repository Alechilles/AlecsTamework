package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudChangeSet;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSnapshot;
import java.util.EnumSet;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Produces focused hotswap HUD update hints from detached snapshots. */
final class CommandHotswapHudSnapshotDiffer {
    private CommandHotswapHudSnapshotDiffer() {
    }

    @Nonnull
    static CommandHotswapHudChangeSet diff(
            @Nullable CommandHotswapHudSnapshot previous,
            @Nonnull CommandHotswapHudSnapshot current
    ) {
        Objects.requireNonNull(current, "current");
        if (previous == null) {
            return CommandHotswapHudChangeSet.full();
        }
        EnumSet<CommandHotswapHudChangeSet.Slot> changed =
                EnumSet.noneOf(CommandHotswapHudChangeSet.Slot.class);
        if (!Objects.equals(previous.primary(), current.primary())) {
            changed.add(CommandHotswapHudChangeSet.Slot.PRIMARY);
        }
        if (!Objects.equals(previous.secondary(), current.secondary())) {
            changed.add(CommandHotswapHudChangeSet.Slot.SECONDARY);
        }
        if (!Objects.equals(previous.q(), current.q())) {
            changed.add(CommandHotswapHudChangeSet.Slot.Q);
        }
        if (!Objects.equals(previous.e(), current.e())) {
            changed.add(CommandHotswapHudChangeSet.Slot.E);
        }
        if (!Objects.equals(previous.r(), current.r())) {
            changed.add(CommandHotswapHudChangeSet.Slot.R);
        }
        return CommandHotswapHudChangeSet.of(changed,
                !Objects.equals(previous.groupStatus(), current.groupStatus()));
    }
}
