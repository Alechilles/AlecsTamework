package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandTargetHudChangeSet;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudSnapshot;
import java.util.EnumSet;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Produces focused presentation hints between target HUD snapshots. */
final class CommandTargetHudSnapshotDiffer {
    private CommandTargetHudSnapshotDiffer() {
    }

    /** Returns a full hint when no previous target snapshot exists. */
    @Nonnull
    static CommandTargetHudChangeSet diff(
            @Nullable CommandTargetHudSnapshot previous,
            @Nonnull CommandTargetHudSnapshot current
    ) {
        Objects.requireNonNull(current, "current");
        if (previous == null) {
            return CommandTargetHudChangeSet.full();
        }
        EnumSet<CommandTargetHudChangeSet.Section> sections =
                EnumSet.noneOf(CommandTargetHudChangeSet.Section.class);
        if (!Objects.equals(previous.targetUuid(), current.targetUuid())
                || !Objects.equals(previous.targetKey(), current.targetKey())
                || !Objects.equals(previous.displayName(), current.displayName())
                || !Objects.equals(previous.speciesId(), current.speciesId())
                || !Objects.equals(previous.speciesLabel(), current.speciesLabel())
                || !Objects.equals(previous.gender(), current.gender())
                || !Objects.equals(previous.lifecycleStatus(), current.lifecycleStatus())) {
            sections.add(CommandTargetHudChangeSet.Section.IDENTITY);
        }
        if (!Objects.equals(previous.vitals(), current.vitals())) {
            sections.add(CommandTargetHudChangeSet.Section.VITALS);
        }
        if (!Objects.equals(previous.cooldowns(), current.cooldowns())) {
            sections.add(CommandTargetHudChangeSet.Section.COOLDOWNS);
        }
        if (!Objects.equals(previous.favoriteFood(), current.favoriteFood())
                || !Objects.equals(previous.compatibleFoods(), current.compatibleFoods())) {
            sections.add(CommandTargetHudChangeSet.Section.FOOD);
        }
        if (!Objects.equals(previous.attachments(), current.attachments())) {
            sections.add(CommandTargetHudChangeSet.Section.ATTACHMENTS);
        }
        if (!Objects.equals(previous.tameRequirement(), current.tameRequirement())) {
            sections.add(CommandTargetHudChangeSet.Section.TAME_REQUIREMENTS);
        }
        if (!Objects.equals(previous.progression(), current.progression())) {
            sections.add(CommandTargetHudChangeSet.Section.PROGRESSION);
        }
        if (!Objects.equals(previous.traits(), current.traits())) {
            sections.add(CommandTargetHudChangeSet.Section.TRAITS);
        }
        if (!Objects.equals(previous.ownerDisplayName(), current.ownerDisplayName())) {
            sections.add(CommandTargetHudChangeSet.Section.OWNER);
        }
        return CommandTargetHudChangeSet.of(sections);
    }

    /** Alias for callers that describe the operation as a comparison. */
    @Nonnull
    static CommandTargetHudChangeSet between(
            @Nullable CommandTargetHudSnapshot previous,
            @Nonnull CommandTargetHudSnapshot current
    ) {
        return diff(previous, current);
    }
}
