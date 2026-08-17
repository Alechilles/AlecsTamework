package com.alechilles.alecstamework.runtime.activation;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable declaration of one runtime module's direct topology and external
 * capability requirements.
 */
public final class TameworkRuntimeModuleDescriptor {
    private final TameworkRuntimeModule module;
    private final Set<TameworkRuntimeModule> directDependencies;
    private final Set<String> requiredExternalCapabilities;

    /** Creates a descriptor without external capability requirements. */
    public TameworkRuntimeModuleDescriptor(
            TameworkRuntimeModule module,
            Collection<TameworkRuntimeModule> directDependencies
    ) {
        this(module, directDependencies, List.of());
    }

    /** Creates a descriptor with direct dependencies and required capabilities. */
    public TameworkRuntimeModuleDescriptor(
            TameworkRuntimeModule module,
            Collection<TameworkRuntimeModule> directDependencies,
            Collection<String> requiredExternalCapabilities
    ) {
        this.module = Objects.requireNonNull(module, "Runtime module is required");
        this.directDependencies = immutableModules(
                directDependencies,
                "Direct dependencies"
        );
        this.requiredExternalCapabilities = immutableTextSet(
                requiredExternalCapabilities,
                "Required external capabilities"
        );
    }

    /** Creates a descriptor with no direct dependencies. */
    public static TameworkRuntimeModuleDescriptor of(TameworkRuntimeModule module) {
        return new TameworkRuntimeModuleDescriptor(module, List.of());
    }

    /** Creates a descriptor with the supplied direct dependencies. */
    public static TameworkRuntimeModuleDescriptor of(
            TameworkRuntimeModule module,
            TameworkRuntimeModule... directDependencies
    ) {
        Objects.requireNonNull(directDependencies, "Direct dependencies are required");
        return new TameworkRuntimeModuleDescriptor(
                module,
                Arrays.asList(directDependencies)
        );
    }

    /** Creates a descriptor with required external capabilities. */
    public static TameworkRuntimeModuleDescriptor withRequiredCapabilities(
            TameworkRuntimeModule module,
            Collection<TameworkRuntimeModule> directDependencies,
            Collection<String> requiredExternalCapabilities
    ) {
        return new TameworkRuntimeModuleDescriptor(
                module,
                directDependencies,
                requiredExternalCapabilities
        );
    }

    /** Starts a readable descriptor builder. */
    public static Builder builder(TameworkRuntimeModule module) {
        return new Builder(module);
    }

    public TameworkRuntimeModule module() {
        return module;
    }

    /** Returns this descriptor's direct dependencies only. */
    public Set<TameworkRuntimeModule> directDependencies() {
        return directDependencies;
    }

    /** Alias for {@link #directDependencies()}. */
    public Set<TameworkRuntimeModule> dependencies() {
        return directDependencies;
    }

    public Set<String> requiredExternalCapabilities() {
        return requiredExternalCapabilities;
    }

    /** Alias for {@link #requiredExternalCapabilities()}. */
    public Set<String> requiredCapabilities() {
        return requiredExternalCapabilities;
    }

    private static Set<TameworkRuntimeModule> immutableModules(
            Collection<TameworkRuntimeModule> values,
            String label
    ) {
        if (values == null || values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(label + " cannot be null");
        }
        return Set.copyOf(new LinkedHashSet<>(values));
    }

    private static Set<String> immutableTextSet(
            Collection<String> values,
            String label
    ) {
        if (values == null) {
            throw new IllegalArgumentException(label + " cannot be null");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(label + " cannot contain blank values");
            }
            normalized.add(value.trim());
        }
        return Set.copyOf(normalized);
    }

    /** Mutable only while a descriptor is being assembled; built descriptors are immutable. */
    public static final class Builder {
        private final TameworkRuntimeModule module;
        private final Set<TameworkRuntimeModule> dependencies = new LinkedHashSet<>();
        private final Set<String> capabilities = new LinkedHashSet<>();

        private Builder(TameworkRuntimeModule module) {
            this.module = Objects.requireNonNull(module, "Runtime module is required");
        }

        public Builder dependsOn(TameworkRuntimeModule dependency) {
            dependencies.add(Objects.requireNonNull(dependency, "Dependency is required"));
            return this;
        }

        public Builder requiresCapability(String capabilityId) {
            if (capabilityId == null || capabilityId.isBlank()) {
                throw new IllegalArgumentException("Capability ID is required");
            }
            capabilities.add(capabilityId.trim());
            return this;
        }

        public TameworkRuntimeModuleDescriptor build() {
            return new TameworkRuntimeModuleDescriptor(module, dependencies, capabilities);
        }
    }
}
