package com.alechilles.alecstamework.runtime.activation;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/** Pure adapters that turn effective config records into activation facts. */
public final class TameworkAssetActivationEvidenceAdapter {
    private TameworkAssetActivationEvidenceAdapter() {
    }

    /** Builds evidence for enabled records whose content predicate is true. */
    public static <T> TameworkEffectiveAssetFact enabledConfigs(
            TameworkRuntimeModule module,
            String source,
            Iterable<? extends T> configs,
            Predicate<? super T> enabled,
            Predicate<? super T> hasContent
    ) {
        Objects.requireNonNull(enabled, "enabled predicate is required");
        Objects.requireNonNull(hasContent, "content predicate is required");
        boolean active = false;
        if (configs != null) {
            for (T config : configs) {
                if (config != null && enabled.test(config) && hasContent.test(config)) {
                    active = true;
                    break;
                }
            }
        }
        return TameworkEffectiveAssetFact.of(
                module, active, source, active ? List.of(source) : List.of(), List.of()
        );
    }

    /**
     * Builds role evidence from enabled records with at least one loaded role.
     * Empty role arrays do not activate a role-targeted family.
     */
    public static <T> TameworkEffectiveAssetFact roleConfigs(
            TameworkRuntimeModule module,
            String source,
            Iterable<? extends T> configs,
            Predicate<? super T> enabled,
            Function<? super T, ? extends Collection<String>> roleIds,
            Predicate<String> roleExists
    ) {
        Objects.requireNonNull(enabled, "enabled predicate is required");
        Objects.requireNonNull(roleIds, "role ID adapter is required");
        Objects.requireNonNull(roleExists, "role existence predicate is required");
        LinkedHashSet<String> roles = new LinkedHashSet<>();
        if (configs != null) {
            for (T config : configs) {
                if (config == null || !enabled.test(config)) {
                    continue;
                }
                Collection<String> values = roleIds.apply(config);
                if (values == null) {
                    continue;
                }
                for (String role : values) {
                    if (role != null && !role.isBlank() && roleExists.test(role.trim())) {
                        roles.add(role.trim());
                    }
                }
            }
        }
        return TameworkEffectiveAssetFact.of(
                module, !roles.isEmpty(), source, roles, List.of()
        );
    }

    /** Builds item evidence from enabled records whose configured items exist. */
    public static <T> TameworkEffectiveAssetFact itemConfigs(
            TameworkRuntimeModule module,
            String source,
            Iterable<? extends T> configs,
            Predicate<? super T> enabled,
            Function<? super T, ? extends Collection<String>> itemIds,
            Predicate<String> itemExists
    ) {
        Objects.requireNonNull(enabled, "enabled predicate is required");
        Objects.requireNonNull(itemIds, "item ID adapter is required");
        Objects.requireNonNull(itemExists, "item existence predicate is required");
        LinkedHashSet<String> items = new LinkedHashSet<>();
        if (configs != null) {
            for (T config : configs) {
                if (config == null || !enabled.test(config)) {
                    continue;
                }
                Collection<String> values = itemIds.apply(config);
                if (values == null) {
                    continue;
                }
                for (String item : values) {
                    if (item != null && !item.isBlank() && itemExists.test(item.trim())) {
                        items.add(item.trim());
                    }
                }
            }
        }
        return TameworkEffectiveAssetFact.of(
                module, !items.isEmpty(), source, List.of(), items
        );
    }

    /** Builds evidence for an enabled item configuration with one item ID. */
    public static TameworkEffectiveAssetFact itemConfig(
            TameworkRuntimeModule module,
            String source,
            boolean enabled,
            String itemId,
            Predicate<String> itemExists
    ) {
        List<String> values = itemId == null ? List.of() : List.of(itemId);
        return TameworkEffectiveAssetFact.of(
                module,
                enabled,
                source,
                List.of(),
                values.stream().filter(value -> value != null && !value.isBlank())
                        .map(String::trim).filter(itemExists).toList()
        );
    }
}
