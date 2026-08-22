package com.alechilles.alecstamework.config.managed;

import com.alechilles.alecstamework.api.TameworkApiCapability;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Immutable, validated content profile for one generic managed-activity
 * provider.
 *
 * <p>The profile contains content identity and resolution data only. Provider
 * readiness and player progression remain outside Tamework config.</p>
 */
public record ManagedActivityProfile(
        @Nonnull String profileId,
        @Nonnull String providerId,
        int providerContractVersion,
        @Nonnull Set<TameworkApiCapability> requiredCapabilities,
        @Nonnull Map<String, DomainDefinition> domains,
        @Nonnull Map<String, FamilyDefinition> families,
        @Nonnull ActivityMapping activities,
        long configRevision
) {
    public ManagedActivityProfile {
        profileId = requireText(profileId, "profileId");
        providerId = requireText(providerId, "providerId");
        if (providerContractVersion <= 0) {
            throw new IllegalArgumentException(
                    "providerContractVersion must be positive"
            );
        }
        if (requiredCapabilities == null) {
            throw new IllegalArgumentException(
                    "requiredCapabilities are required"
            );
        }
        requiredCapabilities = immutableCapabilities(requiredCapabilities);
        domains = immutableMap(domains, "domains");
        families = immutableMap(families, "families");
        activities = Objects.requireNonNull(activities, "activities");
        if (configRevision < 0L) {
            throw new IllegalArgumentException(
                    "configRevision cannot be negative"
            );
        }
    }

    private static Set<TameworkApiCapability> immutableCapabilities(
            Set<TameworkApiCapability> values
    ) {
        LinkedHashSet<TameworkApiCapability> copy = new LinkedHashSet<>();
        for (TameworkApiCapability value : values) {
            copy.add(Objects.requireNonNull(value, "required capability"));
        }
        return Set.copyOf(copy);
    }

    private static <T> Map<String, T> immutableMap(
            Map<String, T> values,
            String label
    ) {
        if (values == null) {
            throw new IllegalArgumentException(label + " are required");
        }
        LinkedHashMap<String, T> copy = new LinkedHashMap<>();
        for (Map.Entry<String, T> entry : values.entrySet()) {
            String key = requireText(entry.getKey(), label + " key");
            copy.put(key, Objects.requireNonNull(entry.getValue(), label + " value"));
        }
        return Map.copyOf(copy);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    /** A named capacity domain used by an admission provider. */
    public record DomainDefinition(
            @Nonnull String domainId,
            boolean owned,
            boolean deployable
    ) {
        public DomainDefinition {
            domainId = requireText(domainId, "domainId");
            if (!owned && !deployable) {
                throw new IllegalArgumentException(
                        "A domain must apply to owned or deployable capacity"
                );
            }
        }
    }

    /** A gate family backed by one Tamework population group. */
    public record FamilyDefinition(
            @Nonnull String groupId,
            @Nonnull String gateKey,
            int weight,
            @Nonnull Set<String> roleIds
    ) {
        public FamilyDefinition {
            groupId = requireText(groupId, "groupId");
            gateKey = requireText(gateKey, "gateKey");
            if (weight <= 0) {
                throw new IllegalArgumentException("family weight must be positive");
            }
            if (roleIds == null || roleIds.isEmpty()) {
                throw new IllegalArgumentException("family roleIds are required");
            }
            LinkedHashSet<String> copy = new LinkedHashSet<>();
            for (String roleId : roleIds) {
                copy.add(requireText(roleId, "roleId"));
            }
            roleIds = Set.copyOf(copy);
        }
    }

    /** Stable activity IDs and item/context mappings used by generic runtime code. */
    public record ActivityMapping(
            @Nonnull String feed,
            @Nonnull Map<String, String> harvestContexts,
            @Nonnull Map<String, String> pendingOutputItems,
            @Nonnull String breedingSuccess,
            @Nonnull String tameSuccess,
            @Nonnull String needSatisfied
    ) {
        public ActivityMapping {
            feed = requireText(feed, "activities.feed");
            harvestContexts = immutableStringMap(
                    harvestContexts,
                    "activities.harvestContexts"
            );
            pendingOutputItems = immutableStringMap(
                    pendingOutputItems,
                    "activities.pendingOutputItems"
            );
            breedingSuccess = requireText(
                    breedingSuccess,
                    "activities.breedingSuccess"
            );
            tameSuccess = requireText(
                    tameSuccess,
                    "activities.tameSuccess"
            );
            needSatisfied = requireText(
                    needSatisfied,
                    "activities.needSatisfied"
            );
        }

        private static Map<String, String> immutableStringMap(
                Map<String, String> values,
                String label
        ) {
            if (values == null) {
                throw new IllegalArgumentException(label + " are required");
            }
            LinkedHashMap<String, String> copy = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                copy.put(
                        requireText(entry.getKey(), label + " key"),
                        requireText(entry.getValue(), label + " value")
                );
            }
            return Map.copyOf(copy);
        }
    }
}
