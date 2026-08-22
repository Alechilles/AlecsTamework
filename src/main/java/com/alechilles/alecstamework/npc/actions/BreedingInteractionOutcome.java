package com.alechilles.alecstamework.npc.actions;

import java.util.Objects;

/** Result of one player-started breeding attempt, including safe feedback. */
record BreedingInteractionOutcome(Status status, int requiredHappiness) {
    private static final String PREFIX = "tamework.ui.notifications.breeding.";

    BreedingInteractionOutcome {
        status = Objects.requireNonNull(status, "status");
        requiredHappiness = Math.max(0, requiredHappiness);
    }

    enum Status {
        PAIRED,
        SUBMITTED,
        WAITING_FOR_MATE,
        NO_OFFSPRING,
        COOLDOWN,
        NOT_TAMED,
        NOT_ADULT,
        STATE_BLOCKED,
        LOW_HAPPINESS,
        CAPACITY_REACHED,
        CLAIM_REQUIRED,
        PROGRESSION_REQUIRED,
        INTEGRATION_UNAVAILABLE,
        BIRTH_PENDING,
        UNAVAILABLE
    }

    static BreedingInteractionOutcome paired() {
        return new BreedingInteractionOutcome(Status.PAIRED, 0);
    }

    static BreedingInteractionOutcome submitted() {
        return new BreedingInteractionOutcome(Status.SUBMITTED, 0);
    }

    static BreedingInteractionOutcome waitingForMate() {
        return new BreedingInteractionOutcome(Status.WAITING_FOR_MATE, 0);
    }

    static BreedingInteractionOutcome noOffspring() {
        return new BreedingInteractionOutcome(Status.NO_OFFSPRING, 0);
    }

    static BreedingInteractionOutcome cooldown() {
        return new BreedingInteractionOutcome(Status.COOLDOWN, 0);
    }

    static BreedingInteractionOutcome notTamed() {
        return new BreedingInteractionOutcome(Status.NOT_TAMED, 0);
    }

    static BreedingInteractionOutcome notAdult() {
        return new BreedingInteractionOutcome(Status.NOT_ADULT, 0);
    }

    static BreedingInteractionOutcome stateBlocked() {
        return new BreedingInteractionOutcome(Status.STATE_BLOCKED, 0);
    }

    static BreedingInteractionOutcome lowHappiness(double threshold) {
        int roundedThreshold = Double.isFinite(threshold)
                ? (int) Math.ceil(Math.max(0.0, threshold)) : 0;
        return new BreedingInteractionOutcome(Status.LOW_HAPPINESS, roundedThreshold);
    }

    static BreedingInteractionOutcome capacityReached() {
        return new BreedingInteractionOutcome(Status.CAPACITY_REACHED, 0);
    }

    static BreedingInteractionOutcome claimRequired() {
        return new BreedingInteractionOutcome(Status.CLAIM_REQUIRED, 0);
    }

    static BreedingInteractionOutcome progressionRequired() {
        return new BreedingInteractionOutcome(Status.PROGRESSION_REQUIRED, 0);
    }

    static BreedingInteractionOutcome integrationUnavailable() {
        return new BreedingInteractionOutcome(Status.INTEGRATION_UNAVAILABLE, 0);
    }

    static BreedingInteractionOutcome birthPending() {
        return new BreedingInteractionOutcome(Status.BIRTH_PENDING, 0);
    }

    static BreedingInteractionOutcome unavailable() {
        return new BreedingInteractionOutcome(Status.UNAVAILABLE, 0);
    }

    boolean accepted() {
        return status == Status.PAIRED || status == Status.SUBMITTED
                || status == Status.WAITING_FOR_MATE
                || status == Status.NO_OFFSPRING;
    }

    boolean completedPair() {
        return status == Status.PAIRED || status == Status.SUBMITTED
                || status == Status.NO_OFFSPRING;
    }

    boolean warning() {
        return status != Status.PAIRED && status != Status.SUBMITTED
                && status != Status.WAITING_FOR_MATE;
    }

    Feedback feedback() {
        return switch (status) {
            case PAIRED -> text("paired");
            case SUBMITTED -> text("submitted");
            case WAITING_FOR_MATE -> text("selectMate");
            case NO_OFFSPRING -> text("noOffspring");
            case COOLDOWN -> text("cooldown");
            case NOT_TAMED -> text("notTamed");
            case NOT_ADULT -> text("notAdult");
            case STATE_BLOCKED -> text("stateBlocked");
            case LOW_HAPPINESS -> text("happinessTooLow", requiredHappiness);
            case CAPACITY_REACHED -> text("capacityReached");
            case CLAIM_REQUIRED -> text("claimRequired");
            case PROGRESSION_REQUIRED -> text("progressionRequired");
            case INTEGRATION_UNAVAILABLE -> text("integrationUnavailable");
            case BIRTH_PENDING -> text("birthPending");
            case UNAVAILABLE -> text("unavailable");
        };
    }

    private static Feedback text(String suffix, Object... arguments) {
        return new Feedback(PREFIX + suffix, arguments);
    }

    record Feedback(String key, Object[] arguments) {
        Feedback {
            key = Objects.requireNonNull(key, "key");
            arguments = arguments == null ? new Object[0] : arguments.clone();
        }

        @Override
        public Object[] arguments() {
            return arguments.clone();
        }
    }
}
