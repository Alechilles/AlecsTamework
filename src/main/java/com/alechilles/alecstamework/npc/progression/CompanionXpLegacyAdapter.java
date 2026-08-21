package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.api.CompanionXpAwardedEvent;
import com.alechilles.alecstamework.api.internal.TameworkEventBus;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Lazily projects internal XP transitions onto the released legacy event contract. */
final class CompanionXpLegacyAdapter implements AutoCloseable {
    private final AutoCloseable subscription;

    CompanionXpLegacyAdapter(@Nonnull CompanionProgressionSignalBus signals,
                              @Nonnull TameworkEventBus legacyEvents) {
        Objects.requireNonNull(signals, "signals");
        TameworkEventBus requiredEvents = Objects.requireNonNull(legacyEvents, "legacyEvents");
        subscription = signals.subscribe(transition -> publishIfInterested(requiredEvents, transition));
    }

    private static void publishIfInterested(@Nonnull TameworkEventBus legacyEvents,
                                            @Nonnull CompanionXpTransition transition) {
        if (!legacyEvents.hasCompanionXpSubscribers()) {
            return;
        }
        legacyEvents.emitCompanionXpAwarded(map(transition));
    }

    @Nonnull
    static CompanionXpAwardedEvent map(@Nonnull CompanionXpTransition transition) {
        Objects.requireNonNull(transition, "transition");
        return new CompanionXpAwardedEvent(
                transition.npcUuid(),
                transition.ownerUuid(),
                transition.toolIds(),
                transition.roleId(),
                transition.levelingConfigId(),
                transition.source(),
                transition.awardedXp(),
                transition.previousLevel(),
                transition.currentLevel(),
                transition.previousTotalXp(),
                transition.currentTotalXp(),
                transition.previousCurrentXp(),
                transition.currentXp(),
                transition.nextLevelXp(),
                transition.maxLevel(),
                transition.atMaxLevel(),
                transition.leveledUp(),
                transition.occurredAtMs(),
                transition.emittedAtMs()
        );
    }

    @Override
    public void close() {
        try {
            subscription.close();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to close the legacy XP adapter.", exception);
        }
    }
}
