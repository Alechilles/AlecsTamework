package com.alechilles.alecstamework.npc.progression;

/** Test-only bridge for publishing the package-private internal signal. */
public final class CompanionProgressionSignalTestSupport {
    private CompanionProgressionSignalTestSupport() {
    }

    public static void publish(CompanionProgressionSignalBus signals,
                               CompanionXpTransition transition) {
        signals.publish(transition);
    }
}
