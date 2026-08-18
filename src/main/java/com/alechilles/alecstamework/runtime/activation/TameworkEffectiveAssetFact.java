package com.alechilles.alecstamework.runtime.activation;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Immutable effective-content fact supplied to the activation evidence collector.
 *
 * <p>The fact contains only post-override values. It does not retain a Hytale
 * asset map, registry, or live runtime object.</p>
 */
public final class TameworkEffectiveAssetFact {
    private final TameworkRuntimeModule module;
    private final boolean enabled;
    private final String source;
    private final Set<String> effectiveTargets;
    private final Set<String> configuredItemIds;

    private TameworkEffectiveAssetFact(
            TameworkRuntimeModule module,
            boolean enabled,
            String source,
            Collection<String> effectiveTargets,
            Collection<String> configuredItemIds
    ) {
        this.module = Objects.requireNonNull(module, "Runtime module is required");
        this.enabled = enabled;
        this.source = normalizeSource(source, module);
        this.effectiveTargets = immutableNonBlank(effectiveTargets);
        this.configuredItemIds = immutableNonBlank(configuredItemIds);
    }

    /** Creates one immutable effective-content fact. */
    public static TameworkEffectiveAssetFact of(
            TameworkRuntimeModule module,
            boolean enabled,
            String source,
            Collection<String> effectiveTargets,
            Collection<String> configuredItemIds
    ) {
        return new TameworkEffectiveAssetFact(
                module, enabled, source, effectiveTargets, configuredItemIds
        );
    }

    /** Returns the module directly evidenced by this fact. */
    public TameworkRuntimeModule module() {
        return module;
    }

    /** Returns whether the effective config is enabled. */
    public boolean enabled() {
        return enabled;
    }

    /** Returns the immutable source used in activation diagnostics. */
    public String source() {
        return source;
    }

    /** Returns nonblank effective target IDs. */
    public Set<String> effectiveTargets() {
        return effectiveTargets;
    }

    /** Returns nonblank configured feature item IDs. */
    public Set<String> configuredItemIds() {
        return configuredItemIds;
    }

    /** Returns whether this enabled fact binds to effective production content. */
    public boolean hasEffectiveContent() {
        return enabled && (!effectiveTargets.isEmpty() || !configuredItemIds.isEmpty());
    }

    private static String normalizeSource(String source, TameworkRuntimeModule module) {
        return source == null || source.isBlank() ? module.id() : source.trim();
    }

    private static Set<String> immutableNonBlank(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim());
            }
        }
        if (normalized.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(normalized));
    }
}
