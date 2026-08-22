package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.api.ActivityDomain;
import com.alechilles.alecstamework.api.ActivityHeader;
import com.alechilles.alecstamework.api.ActivityIds;
import com.alechilles.alecstamework.api.CombatContributionView;
import com.alechilles.alecstamework.api.CombatDamageActivityView;
import com.alechilles.alecstamework.api.CombatDefeatActivityView;
import com.alechilles.alecstamework.api.CombatParticipantView;
import com.alechilles.alecstamework.api.CompanionXpOutcomeView;
import com.alechilles.alecstamework.api.internal.LiveActivityFeed;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Publishes compact combat activities after final damage and death results. */
public final class CompanionCombatActivityPublisher {
    private final LiveActivityFeed.Publisher publisher;

    public CompanionCombatActivityPublisher(
            @Nonnull LiveActivityFeed.Publisher publisher
    ) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    /** Returns cached interest before combat producers resolve optional data. */
    public boolean hasInterest(@Nonnull String actionId) {
        return (ActivityIds.COMBAT_DAMAGE.equals(actionId)
                || ActivityIds.COMBAT_DEFEAT.equals(actionId))
                && publisher.hasInterest(ActivityDomain.COMBAT, actionId);
    }

    /** Publishes one final damage packet with up to two XP outcomes. */
    public void publishDamage(
            @Nonnull UUID operationId,
            @Nonnull CombatParticipantView source,
            @Nonnull CombatParticipantView target,
            double finalDamage,
            @Nonnull String damageType,
            @Nullable CompanionXpOutcomeView sourceXpOutcome,
            @Nullable CompanionXpOutcomeView targetXpOutcome,
            long occurredAtMs
    ) {
        if (!hasInterest(ActivityIds.COMBAT_DAMAGE)) {
            return;
        }
        try {
            publisher.publish(new CombatDamageActivityView(
                    new ActivityHeader(
                            operationId,
                            ActivityIds.COMBAT_DAMAGE,
                            Instant.ofEpochMilli(occurredAtMs)),
                    source,
                    target,
                    finalDamage,
                    damageType,
                    sourceXpOutcome,
                    targetXpOutcome));
        } catch (RuntimeException | LinkageError ignored) {
            // Publication cannot undo the final damage or XP results.
        }
    }

    /** Publishes one removed defeat-credit entry. */
    public void publishDefeat(
            @Nonnull UUID operationId,
            @Nonnull CombatParticipantView target,
            @Nullable CombatContributionView finalBlowCredit,
            @Nonnull List<CombatContributionView> contributors,
            @Nullable UUID ownerCredit,
            long occurredAtMs
    ) {
        if (!hasInterest(ActivityIds.COMBAT_DEFEAT)) {
            return;
        }
        try {
            publisher.publish(new CombatDefeatActivityView(
                    new ActivityHeader(
                            operationId,
                            ActivityIds.COMBAT_DEFEAT,
                            Instant.ofEpochMilli(occurredAtMs)),
                    target,
                    finalBlowCredit,
                    contributors,
                    ownerCredit));
        } catch (RuntimeException | LinkageError ignored) {
            // Publication cannot undo the confirmed death.
        }
    }
}
