package com.alechilles.alecstamework.runtime.activation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Immutable startup evidence snapshot consumed by the activation planner.
 *
 * <p>The builder is the only mutable object. A built snapshot copies every
 * map, set, and reason collection, so provider readiness reads are constant
 * time lookups and cannot observe later collector mutations.</p>
 */
public final class TameworkActivationEvidence {
    private static final TameworkActivationEvidence EMPTY = new TameworkActivationEvidence(
            Map.of(),
            Map.of(),
            Set.of()
    );

    private final Map<TameworkRuntimeModule, Set<TameworkActivationReason>> directReasons;
    private final Map<TameworkRuntimeModule, Set<String>> requiredCapabilities;
    private final Set<String> availableCapabilities;

    private TameworkActivationEvidence(
            Map<TameworkRuntimeModule, Set<TameworkActivationReason>> directReasons,
            Map<TameworkRuntimeModule, Set<String>> requiredCapabilities,
            Set<String> availableCapabilities
    ) {
        this.directReasons = immutableReasonMap(directReasons);
        this.requiredCapabilities = immutableCapabilityMap(requiredCapabilities);
        this.availableCapabilities = Set.copyOf(availableCapabilities);
    }

    /** Returns the canonical empty snapshot. */
    public static TameworkActivationEvidence empty() {
        return EMPTY;
    }

    /** Starts a mutable builder that produces an immutable evidence snapshot. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns one immutable reason set per directly evidenced module. */
    public Map<TameworkRuntimeModule, Set<TameworkActivationReason>> directReasons() {
        return directReasons;
    }

    /** Returns the directly evidenced modules in stable ID order. */
    public Set<TameworkRuntimeModule> directModules() {
        return directReasons.keySet();
    }

    /** Returns direct reasons for a module, or an empty set when none exist. */
    public Set<TameworkActivationReason> reasonsFor(TameworkRuntimeModule module) {
        return directReasons.getOrDefault(module, Set.of());
    }

    /** Returns required capabilities recorded by public consumers for a module. */
    public Set<String> requiredCapabilitiesFor(TameworkRuntimeModule module) {
        return requiredCapabilities.getOrDefault(module, Set.of());
    }

    /** Returns immutable required-capability facts keyed by module. */
    public Map<TameworkRuntimeModule, Set<String>> requiredCapabilities() {
        return requiredCapabilities;
    }

    /** O(1) readiness lookup on this immutable provider snapshot. */
    public boolean hasCapability(String capabilityId) {
        return capabilityId != null && availableCapabilities.contains(capabilityId.trim());
    }

    /** Alias for {@link #hasCapability(String)}. */
    public boolean isCapabilityAvailable(String capabilityId) {
        return hasCapability(capabilityId);
    }

    /** Returns the immutable provider capability set. */
    public Set<String> availableCapabilities() {
        return availableCapabilities;
    }

    /** Mutable assembly object; call {@link #build()} before sharing evidence. */
    public static final class Builder {
        private final Map<TameworkRuntimeModule, Set<TameworkActivationReason>> directReasons =
                new LinkedHashMap<>();
        private final Map<TameworkRuntimeModule, Set<String>> requiredCapabilities =
                new LinkedHashMap<>();
        private final Set<String> availableCapabilities = new LinkedHashSet<>();

        /** Adds effective production-content evidence. */
        public Builder content(TameworkRuntimeModule module, String source) {
            return activate(module, TameworkActivationReason.content(module, source));
        }

        /** Adds content evidence with the module ID as its source. */
        public Builder content(TameworkRuntimeModule module) {
            return content(module, module == null ? null : module.id());
        }

        /** Adds a public-capability requirement and its direct activation reason. */
        public Builder publicCapability(
                TameworkRuntimeModule module,
                String capabilityId,
                String source
        ) {
            TameworkRuntimeModule checkedModule = requireModule(module);
            String checkedCapability = requireText(capabilityId, "Capability ID");
            addRequiredCapability(checkedModule, checkedCapability);
            return activate(
                    checkedModule,
                    TameworkActivationReason.publicCapability(checkedCapability, source)
            );
        }

        /** Adds a public-capability requirement using the capability as its source. */
        public Builder publicCapability(
                TameworkRuntimeModule module,
                String capabilityId
        ) {
            return publicCapability(module, capabilityId, capabilityId);
        }

        /** Alias for {@link #publicCapability(TameworkRuntimeModule, String, String)}. */
        public Builder requireCapability(
                TameworkRuntimeModule module,
                String capabilityId,
                String source
        ) {
            return publicCapability(module, capabilityId, source);
        }

        /** Adds durable state or recovery evidence. */
        public Builder durableState(TameworkRuntimeModule module, String stateId) {
            return activate(module, TameworkActivationReason.durableState(stateId));
        }

        /** Adds durable recovery evidence with an explicit probe source. */
        public Builder durableState(
                TameworkRuntimeModule module,
                String stateId,
                String source
        ) {
            return activate(module, TameworkActivationReason.durableState(stateId, source));
        }

        /** Alias for durable state evidence. */
        public Builder recovery(TameworkRuntimeModule module, String recoveryId) {
            return durableState(module, recoveryId);
        }

        /** Marks an external provider capability as ready without activating its bridge. */
        public Builder availableCapability(String capabilityId) {
            availableCapabilities.add(requireText(capabilityId, "Capability ID"));
            return this;
        }

        /** Alias for {@link #availableCapability(String)}. */
        public Builder providerReady(String capabilityId) {
            return availableCapability(capabilityId);
        }

        /** Adds an arbitrary immutable reason for a module. */
        public Builder activate(
                TameworkRuntimeModule module,
                TameworkActivationReason reason
        ) {
            TameworkRuntimeModule checkedModule = requireModule(module);
            directReasons.computeIfAbsent(checkedModule, ignored -> new TreeSet<>())
                    .add(Objects.requireNonNull(reason, "Activation reason is required"));
            return this;
        }

        /** Adds a capability requirement without adding a second direct reason. */
        public Builder addRequiredCapability(
                TameworkRuntimeModule module,
                String capabilityId
        ) {
            TameworkRuntimeModule checkedModule = requireModule(module);
            requiredCapabilities.computeIfAbsent(checkedModule, ignored -> new TreeSet<>())
                    .add(requireText(capabilityId, "Capability ID"));
            return this;
        }

        /** Freezes the builder into an immutable startup evidence snapshot. */
        public TameworkActivationEvidence build() {
            return new TameworkActivationEvidence(
                    directReasons,
                    requiredCapabilities,
                    availableCapabilities
            );
        }
    }

    private static Map<TameworkRuntimeModule, Set<TameworkActivationReason>> immutableReasonMap(
            Map<TameworkRuntimeModule, Set<TameworkActivationReason>> values
    ) {
        Map<TameworkRuntimeModule, Set<TameworkActivationReason>> copy = new LinkedHashMap<>();
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> copy.put(
                        requireModule(entry.getKey()),
                        Set.copyOf(new TreeSet<>(entry.getValue()))
                ));
        return Collections.unmodifiableMap(copy);
    }

    private static Map<TameworkRuntimeModule, Set<String>> immutableCapabilityMap(
            Map<TameworkRuntimeModule, Set<String>> values
    ) {
        Map<TameworkRuntimeModule, Set<String>> copy = new LinkedHashMap<>();
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> copy.put(
                        requireModule(entry.getKey()),
                        Set.copyOf(new TreeSet<>(entry.getValue()))
                ));
        return Collections.unmodifiableMap(copy);
    }

    private static TameworkRuntimeModule requireModule(TameworkRuntimeModule module) {
        return Objects.requireNonNull(module, "Runtime module is required");
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
