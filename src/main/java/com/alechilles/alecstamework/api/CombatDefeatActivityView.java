package com.alechilles.alecstamework.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Typed payload for one target defeat and its bounded companion credits. */
public record CombatDefeatActivityView(
        @Nonnull ActivityHeader header,
        @Nonnull CombatParticipantView target,
        @Nullable CombatContributionView finalBlowCredit,
        @Nonnull List<CombatContributionView> contributors,
        @Nullable UUID ownerCredit
) implements ActivityView {
    private static final int MAX_CONTRIBUTORS = 32;

    public CombatDefeatActivityView {
        header = Objects.requireNonNull(header, "header");
        target = Objects.requireNonNull(target, "target");
        contributors = List.copyOf(Objects.requireNonNull(contributors, "contributors"));
        if (contributors.size() > MAX_CONTRIBUTORS) {
            throw new IllegalArgumentException("Too many defeat contributors.");
        }
    }

    public CombatDefeatActivityView(
            @Nonnull ActivityHeader header,
            @Nonnull CombatParticipantView target,
            @Nullable CombatContributionView finalBlowCredit,
            @Nonnull List<CombatContributionView> contributors
    ) {
        this(header, target, finalBlowCredit, contributors, null);
    }

    @Override
    @Nonnull
    public ActivityDomain domain() {
        return ActivityDomain.COMBAT;
    }

    @Override
    @Nonnull
    public CombatDefeatActivityView withHeader(@Nonnull ActivityHeader nextHeader) {
        return new CombatDefeatActivityView(
                nextHeader, target, finalBlowCredit, contributors, ownerCredit);
    }
}
