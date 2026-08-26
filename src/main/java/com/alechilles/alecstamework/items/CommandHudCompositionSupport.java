package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudContribution;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorId;
import com.alechilles.alecstamework.api.commandhud.CommandHudDirtyScope;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudChangeSet;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSnapshot;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudUpdate;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudView;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudChangeSet;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudSnapshot;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudUpdate;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudView;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

/** Shared generic composition plumbing for the target and hotswap surfaces. */
final class CommandHudCompositionSupport {
    static final SurfaceAdapter<CommandTargetHudSnapshot, CommandTargetHudView,
            CommandTargetHudUpdate> TARGET_ADAPTER = new SurfaceAdapter<>() {
        @Override
        public CommandTargetHudView view(
                CommandTargetHudSnapshot base,
                Map<CommandHudContributorId, CommandHudContribution> contributions
        ) {
            return new CommandTargetHudView(base, contributions);
        }

        @Override
        public CommandTargetHudUpdate update(
                CommandTargetHudView current,
                CommandTargetHudView previous,
                ChangeData data
        ) {
            return new CommandTargetHudUpdate(current, previous, targetChangeSet(data));
        }
    };

    static final SurfaceAdapter<CommandHotswapHudSnapshot, CommandHotswapHudView,
            CommandHotswapHudUpdate> HOTSWAP_ADAPTER = new SurfaceAdapter<>() {
        @Override
        public CommandHotswapHudView view(
                CommandHotswapHudSnapshot base,
                Map<CommandHudContributorId, CommandHudContribution> contributions
        ) {
            return new CommandHotswapHudView(base, contributions);
        }

        @Override
        public CommandHotswapHudUpdate update(
                CommandHotswapHudView current,
                CommandHotswapHudView previous,
                ChangeData data
        ) {
            return new CommandHotswapHudUpdate(current, previous, hotswapChangeSet(data));
        }
    };

    private CommandHudCompositionSupport() {
    }

    @Nonnull
    static Map<CommandHudContributorId, CommandHudContribution> copyContributions(
            @Nonnull Map<CommandHudContributorId, CommandHudContribution> source,
            boolean custom
    ) {
        if (!custom || source.isEmpty()) return Map.of();
        Map<CommandHudContributorId, CommandHudContribution> copy = new LinkedHashMap<>();
        source.forEach((id, contribution) -> {
            if (id == null) return;
            CommandHudContribution value = java.util.Objects.requireNonNull(
                    contribution, "contribution");
            if (!id.equals(value.contributorId())) throw new IllegalArgumentException(
                    "Contribution key must match contributor ID.");
            copy.put(id, value);
        });
        return copy.isEmpty() ? Map.of() : Map.copyOf(copy);
    }

    @Nonnull
    private static CommandTargetHudChangeSet targetChangeSet(@Nonnull ChangeData data) {
        if (data.fullSurface) return CommandTargetHudChangeSet.full();
        return CommandTargetHudChangeSet.contributorScopes(data.paths, data.fullContributors);
    }

    @Nonnull
    private static CommandHotswapHudChangeSet hotswapChangeSet(@Nonnull ChangeData data) {
        if (data.fullSurface) return CommandHotswapHudChangeSet.full();
        return CommandHotswapHudChangeSet.contributorScopes(data.paths,
                data.fullContributors);
    }

    /** Mutable only while composing; dirty paths do not survive this object. */
    static final class ChangeData {
        final boolean fullSurface;
        final Map<CommandHudContributorId, Set<String>> paths = new LinkedHashMap<>();
        final Set<CommandHudContributorId> fullContributors = new HashSet<>();

        ChangeData(boolean fullSurface) {
            this.fullSurface = fullSurface;
        }

        void add(CommandHudContributorId id, CommandHudDirtyScope scope) {
            if (scope.fullRefresh()) {
                fullContributors.add(id);
                paths.remove(id);
                return;
            }
            if (!scope.paths().isEmpty()) paths.put(id, scope.paths());
        }
    }

    interface SurfaceAdapter<B, V, U> {
        @Nonnull
        V view(@Nonnull B base,
               @Nonnull Map<CommandHudContributorId, CommandHudContribution> contributions);

        @Nonnull
        U update(@Nonnull V current, @Nonnull V previous, @Nonnull ChangeData changeData);
    }
}
