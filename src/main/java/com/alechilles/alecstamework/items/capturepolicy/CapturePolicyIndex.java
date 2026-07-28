package com.alechilles.alecstamework.items.capturepolicy;

import com.alechilles.alecstamework.api.CapturePolicyConfigView;
import com.alechilles.alecstamework.config.assets.TwCapturePolicyConfig;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nonnull;

/** Immutable deterministic role index compiled from capture-policy assets. */
public final class CapturePolicyIndex {
    private static final Comparator<TwCapturePolicyConfig> WINNER_ORDER =
            Comparator.comparingInt(TwCapturePolicyConfig::getPriority).reversed()
                    .thenComparing(TwCapturePolicyConfig::getId, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(TwCapturePolicyConfig::getId);

    private final long revision;
    private final Map<String, CapturePolicyConfigView> byId;
    private final Map<String, CapturePolicyConfigView> byRole;

    private CapturePolicyIndex(long revision,
                               Map<String, CapturePolicyConfigView> byId,
                               Map<String, CapturePolicyConfigView> byRole) {
        this.revision = revision;
        this.byId = Map.copyOf(byId);
        this.byRole = Map.copyOf(byRole);
    }

    @Nonnull
    public static CapturePolicyIndex compile(@Nonnull Collection<TwCapturePolicyConfig> configs, long revision) {
        Objects.requireNonNull(configs, "configs");
        if (revision < 0L) throw new IllegalArgumentException("Capture-policy revision cannot be negative.");
        List<TwCapturePolicyConfig> enabled = new ArrayList<>();
        Map<String, CapturePolicyConfigView> byId = new LinkedHashMap<>();
        for (TwCapturePolicyConfig config : configs) {
            if (config == null) continue;
            config.validateOrThrow();
            if (!config.isEnabled()) continue;
            CapturePolicyConfigView view = config.toView(revision);
            if (byId.putIfAbsent(view.configId(), view) != null) {
                throw new IllegalArgumentException("Duplicate capture-policy asset ID: " + view.configId());
            }
            enabled.add(config);
        }
        enabled.sort(WINNER_ORDER);
        Map<String, CapturePolicyConfigView> byRole = new LinkedHashMap<>();
        for (TwCapturePolicyConfig config : enabled) {
            CapturePolicyConfigView view = byId.get(config.getId());
            for (String roleId : config.getRoleIds()) byRole.putIfAbsent(roleId, view);
        }
        return new CapturePolicyIndex(revision, byId, byRole);
    }

    public static CapturePolicyIndex empty() {
        return new CapturePolicyIndex(0L, Map.of(), Map.of());
    }

    public long revision() { return revision; }
    public Optional<CapturePolicyConfigView> getById(String configId) {
        return configId == null ? Optional.empty() : Optional.ofNullable(byId.get(configId));
    }
    public Optional<CapturePolicyConfigView> resolveForRole(String roleId) {
        return roleId == null ? Optional.empty() : Optional.ofNullable(byRole.get(roleId));
    }
    public Map<String, CapturePolicyConfigView> definitions() { return byId; }
}
