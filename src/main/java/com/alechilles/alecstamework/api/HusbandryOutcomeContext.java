package com.alechilles.alecstamework.api;

import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Immutable action context supplied to a husbandry outcome provider.
 *
 * <p>{@code happiness} and {@code ageStage} describe the animal at the moment of the outcome
 * (capability {@link TameworkApiCapability#HUSBANDRY_OUTCOME_ANIMAL_STATE}). {@code happiness} is the
 * animal's current happiness, the value the happiness inspector shows, or null when the action has no
 * live animal or happiness is disabled for its role. {@code ageStage} is the age that cull rewards read:
 * {@link AnimalAgeStage#JUVENILE} before the animal is grown, else its aging stage, or null when the
 * action has no live animal or the role has aging disabled.</p>
 */
public record HusbandryOutcomeContext(
        HusbandryOutcomeKind kind,
        UUID ownerId,
        UUID actorId,
        UUID companionId,
        String roleId,
        String profileId,
        Set<String> groupIds,
        String productId,
        HusbandryToolContext tool,
        @Nullable Double happiness,
        @Nullable AnimalAgeStage ageStage
) {
    /** Compatibility constructor for providers compiled before animal state existed; the state is unknown. */
    public HusbandryOutcomeContext(HusbandryOutcomeKind kind,
                                  UUID ownerId,
                                  UUID actorId,
                                  UUID companionId,
                                  String roleId,
                                  String profileId,
                                  Set<String> groupIds,
                                  String productId,
                                  HusbandryToolContext tool) {
        this(kind, ownerId, actorId, companionId, roleId, profileId, groupIds, productId, tool, null, null);
    }

    /** Compatibility constructor for providers compiled before tool context existed. */
    public HusbandryOutcomeContext(HusbandryOutcomeKind kind,
                                  UUID ownerId,
                                  UUID companionId,
                                  String roleId,
                                  String profileId,
                                  Set<String> groupIds,
                                  String productId) {
        this(kind, ownerId, null, companionId, roleId, profileId, groupIds, productId, null, null, null);
    }
}
