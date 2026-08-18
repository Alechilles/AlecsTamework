package com.alechilles.alecstamework.runtime.activation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Records downstream runtime capability requests before topology publication.
 *
 * <p>The request set is mutable only during setup. Once frozen, all reads use
 * the immutable snapshot and later mutation attempts fail.</p>
 */
public final class TameworkRuntimeCapabilityRequests {
    private final Map<TameworkRuntimeModule, Set<String>> requested = new LinkedHashMap<>();
    private Map<TameworkRuntimeModule, Set<String>> frozen = Map.of();
    private boolean published;

    /** Records one capability request during setup. */
    public synchronized void request(
            TameworkRuntimeModule module,
            String capabilityId
    ) {
        if (published) {
            throw new IllegalStateException("Tamework runtime topology is already published");
        }
        TameworkRuntimeModule checkedModule = Objects.requireNonNull(module, "Runtime module is required");
        String checkedCapability = normalize(capabilityId);
        requested.computeIfAbsent(checkedModule, ignored -> new LinkedHashSet<>())
                .add(checkedCapability);
    }

    /** Freezes setup requests into an immutable O(1)-lookup snapshot. */
    public synchronized Map<TameworkRuntimeModule, Set<String>> publish() {
        if (!published) {
            Map<TameworkRuntimeModule, Set<String>> copy = new LinkedHashMap<>();
            requested.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> copy.put(
                            entry.getKey(),
                            Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue()))
                    ));
            frozen = Collections.unmodifiableMap(copy);
            published = true;
        }
        return frozen;
    }

    /** Returns the immutable published snapshot. */
    public synchronized Map<TameworkRuntimeModule, Set<String>> snapshot() {
        if (!published) {
            throw new IllegalStateException("Runtime topology is not published");
        }
        return frozen;
    }

    /** Returns whether topology publication has occurred. */
    public synchronized boolean isPublished() {
        return published;
    }

    private static String normalize(String value) {
        String normalized = Objects.requireNonNull(value, "Capability ID is required").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Capability ID is required");
        }
        return normalized;
    }
}
