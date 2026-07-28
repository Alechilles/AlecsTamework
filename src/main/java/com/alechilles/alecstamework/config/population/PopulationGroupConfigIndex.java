package com.alechilles.alecstamework.config.population;

import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.config.assets.TwPopulationGroupConfig;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nonnull;

/** Immutable deterministic role index compiled from population-group assets. */
public final class PopulationGroupConfigIndex {
    private static final Comparator<TwPopulationGroupConfig> WINNER_ORDER =
            Comparator.comparingInt(
                            TwPopulationGroupConfig::getPriority
                    )
                    .reversed()
                    .thenComparing(
                            TwPopulationGroupConfig::getId,
                            String.CASE_INSENSITIVE_ORDER
                    )
                    .thenComparing(TwPopulationGroupConfig::getId);

    private final long revision;
    private final Map<String, PopulationGroupConfigDefinition> byGroupId;
    private final Map<String, List<PopulationGroupConfigDefinition>> byRole;

    private PopulationGroupConfigIndex(
            long revision,
            Map<String, PopulationGroupConfigDefinition> byGroupId,
            Map<String, List<PopulationGroupConfigDefinition>> byRole
    ) {
        this.revision = revision;
        this.byGroupId = Map.copyOf(byGroupId);
        LinkedHashMap<
                String,
                List<PopulationGroupConfigDefinition>
                > immutableRoles = new LinkedHashMap<>();
        byRole.forEach((roleId, definitions) ->
                immutableRoles.put(roleId, List.copyOf(definitions)));
        this.byRole = Map.copyOf(immutableRoles);
    }

    @Nonnull
    public static PopulationGroupConfigIndex compile(
            @Nonnull Collection<TwPopulationGroupConfig> configs,
            long revision
    ) {
        Objects.requireNonNull(configs, "configs");
        if (revision < 0L) {
            throw new IllegalArgumentException(
                    "Population-group revision cannot be negative"
            );
        }
        List<TwPopulationGroupConfig> enabled = validated(configs);
        enabled.sort(WINNER_ORDER);

        LinkedHashMap<String, TwPopulationGroupConfig> winners =
                new LinkedHashMap<>();
        for (TwPopulationGroupConfig candidate : enabled) {
            winners.putIfAbsent(candidate.getGroupId(), candidate);
        }
        return buildIndex(winners, revision);
    }

    private static List<TwPopulationGroupConfig> validated(
            Collection<TwPopulationGroupConfig> configs
    ) {
        ArrayList<TwPopulationGroupConfig> enabled = new ArrayList<>();
        LinkedHashSet<String> assetIds = new LinkedHashSet<>();
        for (TwPopulationGroupConfig config : configs) {
            if (config == null) {
                continue;
            }
            config.validateOrThrow();
            if (!assetIds.add(config.getId())) {
                throw new IllegalArgumentException(
                        "Duplicate population-group asset ID: "
                                + config.getId()
                );
            }
            if (config.isEnabled()) {
                enabled.add(config);
            }
        }
        return enabled;
    }

    private static PopulationGroupConfigIndex buildIndex(
            Map<String, TwPopulationGroupConfig> winners,
            long revision
    ) {
        LinkedHashMap<
                String,
                PopulationGroupConfigDefinition
                > definitions = new LinkedHashMap<>();
        LinkedHashMap<
                String,
                List<PopulationGroupConfigDefinition>
                > roles = new LinkedHashMap<>();

        ArrayList<String> groupIds = new ArrayList<>(winners.keySet());
        groupIds.sort(String::compareTo);
        for (String groupId : groupIds) {
            PopulationGroupConfigDefinition definition =
                    definition(winners.get(groupId), revision);
            definitions.put(groupId, definition);
            for (String roleId : definition.roleIds()) {
                roles.computeIfAbsent(
                        roleId,
                        ignored -> new ArrayList<>()
                ).add(definition);
            }
        }
        return new PopulationGroupConfigIndex(
                revision,
                definitions,
                roles
        );
    }

    private static PopulationGroupConfigDefinition definition(
            TwPopulationGroupConfig config,
            long revision
    ) {
        LinkedHashSet<String> roles = new LinkedHashSet<>();
        for (String roleId : config.getRoleIds()) {
            roles.add(roleId.trim());
        }
        return new PopulationGroupConfigDefinition(
                config.getId(),
                config.getPriority(),
                roles,
                config.toPolicy(revision)
        );
    }

    public static PopulationGroupConfigIndex empty() {
        return new PopulationGroupConfigIndex(0L, Map.of(), Map.of());
    }

    public long revision() {
        return revision;
    }

    public Optional<PopulationGroupConfigDefinition> getDefinition(
            String groupId
    ) {
        return groupId == null
                ? Optional.empty()
                : Optional.ofNullable(byGroupId.get(groupId));
    }

    public List<PopulationGroupConfigDefinition> resolveForRole(
            String roleId
    ) {
        return roleId == null
                ? List.of()
                : byRole.getOrDefault(roleId, List.of());
    }

    public List<PopulationGroupPolicy> resolvePoliciesForRole(
            String roleId
    ) {
        return resolveForRole(roleId).stream()
                .map(PopulationGroupConfigDefinition::policy)
                .toList();
    }

    public Map<String, PopulationGroupConfigDefinition> definitions() {
        return byGroupId;
    }
}
