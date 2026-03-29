package com.alechilles.alecstamework.api;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;

public interface ProgressionApi {
    Optional<ProgressionView> getByProfileId(String profileId);

    Optional<ProgressionView> getByNpcUuid(UUID npcUuid);

    ProgressionMutationResult setHappiness(String profileId, double value);

    ProgressionMutationResult setHappiness(UUID npcUuid, double value);

    ProgressionMutationResult applyHappinessDelta(String profileId, double delta);

    ProgressionMutationResult applyHappinessDelta(UUID npcUuid, double delta);

    ProgressionMutationResult setNeeds(String profileId, @Nullable Double hunger, @Nullable Double thirst);

    ProgressionMutationResult setNeeds(UUID npcUuid, @Nullable Double hunger, @Nullable Double thirst);

    ProgressionMutationResult setBreedingReady(String profileId, boolean ready);

    ProgressionMutationResult setBreedingReady(UUID npcUuid, boolean ready);

    ProgressionMutationResult rerollTraits(String profileId);

    ProgressionMutationResult rerollTraits(UUID npcUuid);

    ProgressionMutationResult setTraits(String profileId, Map<String, Double> traitValues);

    ProgressionMutationResult setTraits(UUID npcUuid, Map<String, Double> traitValues);

    ProgressionMutationResult refreshLifeStage(String profileId);

    ProgressionMutationResult refreshLifeStage(UUID npcUuid);

    ProgressionMutationResult setStoredAttachments(String profileId, Map<String, String> attachmentSelections);

    ProgressionMutationResult setStoredAttachments(UUID npcUuid, Map<String, String> attachmentSelections);

    ProgressionMutationResult syncStoredAttachments(String profileId);

    ProgressionMutationResult syncStoredAttachments(UUID npcUuid);
}
