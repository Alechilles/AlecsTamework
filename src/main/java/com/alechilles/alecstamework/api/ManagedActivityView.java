package com.alechilles.alecstamework.api;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Typed managed care or production activity payload. */
public record ManagedActivityView(
        @Nonnull ActivityHeader header,
        @Nonnull String profileId,
        @Nonnull Set<String> groupIds,
        @Nonnull List<ActivityParticipantView> participants,
        @Nonnull String mappedActivityId,
        @Nonnull Map<String, Integer> itemQuantities,
        @Nonnull List<UUID> offspringIds,
        @Nullable CompanionXpOutcomeView companionXpOutcome,
        @Nullable CareCreditOutcomeView careCreditOutcome
) implements ActivityView {
    public ManagedActivityView {
        header = Objects.requireNonNull(header, "header");
        profileId = requireText(profileId, "profileId");
        groupIds = immutableTextSet(groupIds, "groupIds");
        participants = immutableParticipants(participants);
        mappedActivityId = ActivityHeader.requireNamespacedText(
                mappedActivityId, "mappedActivityId");
        itemQuantities = immutablePositiveQuantities(itemQuantities);
        offspringIds = immutableIds(offspringIds, "offspringIds");
    }

    @Override
    @Nonnull
    public ActivityDomain domain() {
        return ActivityDomain.MANAGED_CARE_PRODUCTION;
    }

    @Override
    @Nonnull
    public ManagedActivityView withHeader(@Nonnull ActivityHeader nextHeader) {
        return new ManagedActivityView(
                nextHeader, profileId, groupIds, participants,
                mappedActivityId, itemQuantities, offspringIds,
                companionXpOutcome, careCreditOutcome);
    }

    /** Alias retained for consumers that name the optional outcome by type. */
    @Nullable
    public CompanionXpOutcomeView xpOutcome() {
        return companionXpOutcome;
    }

    private static Set<String> immutableTextSet(Set<String> values, String field) {
        Set<String> source = Objects.requireNonNull(values, field);
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : source) {
            String text = requireText(value, field + " entry");
            if (!normalized.add(text)) {
                throw new IllegalArgumentException(field + " contains duplicate identifier.");
            }
        }
        return Set.copyOf(normalized);
    }

    private static Map<String, Integer> immutablePositiveQuantities(
            Map<String, Integer> values
    ) {
        Map<String, Integer> source = Objects.requireNonNull(values, "itemQuantities");
        Map<String, Integer> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            String key = requireText(entry.getKey(), "itemQuantities key");
            Integer quantity = Objects.requireNonNull(entry.getValue(), "itemQuantities value");
            if (quantity <= 0 || normalized.put(key, quantity) != null) {
                throw new IllegalArgumentException("itemQuantities must contain positive unique keys.");
            }
        }
        return Map.copyOf(normalized);
    }

    private static List<ActivityParticipantView> immutableParticipants(
            List<ActivityParticipantView> values
    ) {
        List<ActivityParticipantView> source = Objects.requireNonNull(
                values, "participants");
        if (source.isEmpty()) {
            throw new IllegalArgumentException(
                    "participants must contain at least one participant.");
        }
        for (ActivityParticipantView value : source) {
            Objects.requireNonNull(value, "participants entry");
        }
        return List.copyOf(source);
    }

    private static List<UUID> immutableIds(List<UUID> values, String field) {
        List<UUID> source = Objects.requireNonNull(values, field);
        for (UUID value : source) {
            Objects.requireNonNull(value, field + " entry");
        }
        return List.copyOf(source);
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }
}
