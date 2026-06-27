package com.alechilles.alecstamework.items;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Result of attempting to auto-link a newly tamed NPC to a command item. */
public record CommandAutoLinkResult(@Nonnull Status status,
                                    @Nullable String animalDisplayName,
                                    @Nullable String commandItemDisplayName,
                                    @Nullable String craftingStationDisplayName) {
    public enum Status {
        LINKED,
        NO_APPLICABLE_TOOL,
        SKIPPED,
        FAILED
    }

    public static CommandAutoLinkResult linked(String animalDisplayName, String commandItemDisplayName) {
        return new CommandAutoLinkResult(Status.LINKED, animalDisplayName, commandItemDisplayName, null);
    }

    public static CommandAutoLinkResult noApplicableTool(String animalDisplayName,
                                                         String commandItemDisplayName,
                                                         String craftingStationDisplayName) {
        return new CommandAutoLinkResult(
                Status.NO_APPLICABLE_TOOL,
                animalDisplayName,
                commandItemDisplayName,
                craftingStationDisplayName
        );
    }

    public static CommandAutoLinkResult skipped(String animalDisplayName) {
        return new CommandAutoLinkResult(Status.SKIPPED, animalDisplayName, null, null);
    }

    public static CommandAutoLinkResult failed(String animalDisplayName) {
        return new CommandAutoLinkResult(Status.FAILED, animalDisplayName, null, null);
    }
}
