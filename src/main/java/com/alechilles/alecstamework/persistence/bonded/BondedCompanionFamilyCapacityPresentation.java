package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionPresentationAttributes;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionPolicy;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nonnull;

/** Produces non-durable capacity evidence for one bonded profile-view family. */
final class BondedCompanionFamilyCapacityPresentation {
    private BondedCompanionFamilyCapacityPresentation() {
    }

    @Nonnull
    static Map<String, String> attributes(
            @Nonnull BondedCompanionPolicy policy,
            int activeCount
    ) {
        int maximumActive = policy.maximumActive();
        if (maximumActive <= 0) {
            return Map.of();
        }
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put(BondedCompanionPresentationAttributes.ACTIVE_CAPACITY_COUNT,
                Integer.toString(Math.max(0, activeCount)));
        attributes.put(BondedCompanionPresentationAttributes.ACTIVE_CAPACITY_LIMIT,
                Integer.toString(maximumActive));
        attributes.put(BondedCompanionPresentationAttributes.ACTIVE_CAPACITY_LABEL,
                humanize(policy.familyId()));
        return Map.copyOf(attributes);
    }

    private static String humanize(String familyId) {
        String value = familyId == null ? "Companions" : familyId.trim();
        int namespace = value.lastIndexOf(':');
        if (namespace >= 0) {
            value = value.substring(namespace + 1);
        }
        StringBuilder result = new StringBuilder();
        for (String word : value.replace('-', '_').split("_")) {
            if (word.isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                result.append(word.substring(1));
            }
        }
        return result.isEmpty() ? "Companions" : result.toString();
    }
}
