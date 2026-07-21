package com.alechilles.alecstamework.ownership.groups;

import com.alechilles.alecstamework.api.PopulationGroupDefinitionView;
import com.alechilles.alecstamework.config.assets.TwPopulationGroupConfig;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nonnull;

/** Immutable winning-definition and role-membership index for population groups. */
public final class PopulationGroupIndex {
    private static final Comparator<TwPopulationGroupConfig> WINNER_ORDER =
            Comparator.comparingInt(TwPopulationGroupConfig::getPriority).reversed()
                    .thenComparing(TwPopulationGroupConfig::getId, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(TwPopulationGroupConfig::getId);

    private final long revision;
    private final Map<String, PopulationGroupDefinitionView> byGroupId;
    private final Map<String, List<PopulationGroupDefinitionView>> byRole;

    private PopulationGroupIndex(long revision,
                                 Map<String, PopulationGroupDefinitionView> byGroupId,
                                 Map<String, List<PopulationGroupDefinitionView>> byRole) {
        this.revision = revision;
        this.byGroupId = Map.copyOf(byGroupId);
        LinkedHashMap<String, List<PopulationGroupDefinitionView>> immutable = new LinkedHashMap<>();
        byRole.forEach((role, values) -> immutable.put(role, List.copyOf(values)));
        this.byRole = Map.copyOf(immutable);
    }

    public static PopulationGroupIndex compile(@Nonnull Collection<TwPopulationGroupConfig> configs, long revision) {
        Objects.requireNonNull(configs, "configs");
        if (revision < 0L) throw new IllegalArgumentException("Population-group revision cannot be negative.");
        List<TwPopulationGroupConfig> enabled = new ArrayList<>();
        for (TwPopulationGroupConfig config : configs) {
            if (config == null) continue;
            config.validateOrThrow();
            if (config.isEnabled()) enabled.add(config);
        }
        enabled.sort(WINNER_ORDER);
        LinkedHashMap<String, TwPopulationGroupConfig> winners = new LinkedHashMap<>();
        for (TwPopulationGroupConfig candidate : enabled) winners.putIfAbsent(candidate.getGroupId(), candidate);

        LinkedHashMap<String, PopulationGroupDefinitionView> definitions = new LinkedHashMap<>();
        LinkedHashMap<String, List<PopulationGroupDefinitionView>> roles = new LinkedHashMap<>();
        List<String> sortedGroupIds = new ArrayList<>(winners.keySet());
        sortedGroupIds.sort(String::compareTo);
        for (String groupId : sortedGroupIds) {
            PopulationGroupDefinitionView view = winners.get(groupId).toView(revision);
            definitions.put(groupId, view);
            for (String roleId : view.roleIds()) roles.computeIfAbsent(roleId, ignored -> new ArrayList<>()).add(view);
        }
        roles.values().forEach(values -> values.sort(Comparator.comparing(PopulationGroupDefinitionView::groupId)));
        return new PopulationGroupIndex(revision, definitions, roles);
    }

    public static PopulationGroupIndex empty() { return new PopulationGroupIndex(0L, Map.of(), Map.of()); }
    public long revision() { return revision; }
    public Optional<PopulationGroupDefinitionView> getDefinition(String groupId) {
        return groupId == null ? Optional.empty() : Optional.ofNullable(byGroupId.get(groupId));
    }
    public List<PopulationGroupDefinitionView> resolveForRole(String roleId) {
        if (roleId == null) return List.of();
        return byRole.getOrDefault(roleId, List.of());
    }
    public Map<String, PopulationGroupDefinitionView> definitions() { return byGroupId; }
}
