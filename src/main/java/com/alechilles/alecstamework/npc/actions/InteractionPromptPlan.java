package com.alechilles.alecstamework.npc.actions;

/**
 * Stores pre-classified interaction indexes for prompt selection.
 */
final class InteractionPromptPlan {
    private static final int[] EMPTY_INDEXES = new int[0];

    private final int[] contextualIndexes;
    private final int[] conditionalIndexes;
    private final int[] genericIndexes;

    InteractionPromptPlan(int[] contextualIndexes, int[] conditionalIndexes, int[] genericIndexes) {
        this.contextualIndexes = contextualIndexes == null ? EMPTY_INDEXES : contextualIndexes;
        this.conditionalIndexes = conditionalIndexes == null ? EMPTY_INDEXES : conditionalIndexes;
        this.genericIndexes = genericIndexes == null ? EMPTY_INDEXES : genericIndexes;
    }

    int[] contextualIndexes() {
        return contextualIndexes;
    }

    int[] conditionalIndexes() {
        return conditionalIndexes;
    }

    int[] genericIndexes() {
        return genericIndexes;
    }
}
