package com.alechilles.alecstamework.runtime.activation;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Immutable explanation for one direct or dependency-driven activation.
 *
 * <p>Reasons are data only. They do not start a provider, inspect an ECS
 * store, or retain a mutable registration object.</p>
 */
public final class TameworkActivationReason implements Comparable<TameworkActivationReason> {
    /** The trusted evidence source that caused a module to be considered. */
    public enum Kind {
        CONTENT,
        PUBLIC_CAPABILITY,
        DURABLE_STATE,
        DEPENDENCY
    }

    private final Kind kind;
    private final String source;
    private final String detail;

    /** Creates one immutable reason. */
    public TameworkActivationReason(Kind kind, String source, String detail) {
        this.kind = Objects.requireNonNull(kind, "Activation reason kind is required");
        this.source = requireText(source, "Activation reason source");
        this.detail = requireText(detail, "Activation reason detail");
    }

    /** Creates content evidence for one module or effective asset source. */
    public static TameworkActivationReason content(String source) {
        return new TameworkActivationReason(Kind.CONTENT, "content", source);
    }

    /** Creates content evidence tied to a module's effective asset source. */
    public static TameworkActivationReason content(
            TameworkRuntimeModule module,
            String source
    ) {
        return new TameworkActivationReason(
                Kind.CONTENT,
                requireModule(module).id(),
                source
        );
    }

    /** Creates public-capability evidence for a capability requirement. */
    public static TameworkActivationReason publicCapability(String capabilityId) {
        return new TameworkActivationReason(
                Kind.PUBLIC_CAPABILITY,
                "capability",
                capabilityId
        );
    }

    /** Creates public-capability evidence with a producer or consumer source. */
    public static TameworkActivationReason publicCapability(
            String capabilityId,
            String source
    ) {
        return new TameworkActivationReason(
                Kind.PUBLIC_CAPABILITY,
                capabilityId,
                source
        );
    }

    /** Creates durable-state or recovery evidence. */
    public static TameworkActivationReason durableState(String stateId) {
        return new TameworkActivationReason(
                Kind.DURABLE_STATE,
                "durable-state",
                stateId
        );
    }

    /** Creates durable-state evidence with an explicit probe source. */
    public static TameworkActivationReason durableState(
            String stateId,
            String source
    ) {
        return new TameworkActivationReason(
                Kind.DURABLE_STATE,
                source,
                stateId
        );
    }

    /** Alias for durable recovery evidence. */
    public static TameworkActivationReason recovery(String recoveryId) {
        return durableState(recoveryId);
    }

    /** Creates the reason recorded when a required external capability is missing. */
    public static TameworkActivationReason missingCapability(String capabilityId) {
        String value = requireText(capabilityId, "Capability ID");
        return new TameworkActivationReason(
                Kind.PUBLIC_CAPABILITY,
                "missing-capability",
                value
        );
    }

    /** Creates a dependency edge reason. */
    public static TameworkActivationReason dependency(
            TameworkRuntimeModule dependent,
            TameworkRuntimeModule dependency
    ) {
        TameworkRuntimeModule parent = requireModule(dependent);
        TameworkRuntimeModule child = requireModule(dependency);
        return new TameworkActivationReason(
                Kind.DEPENDENCY,
                parent.id(),
                parent.id() + " -> " + child.id()
        );
    }

    /** Creates a dependency reason for a complete path from direct evidence. */
    public static TameworkActivationReason dependency(
            List<TameworkRuntimeModule> path
    ) {
        if (path == null || path.size() < 2 || path.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("A dependency path with two modules is required");
        }
        String ids = path.stream()
                .map(TameworkRuntimeModule::id)
                .collect(Collectors.joining(" -> "));
        return new TameworkActivationReason(
                Kind.DEPENDENCY,
                path.get(0).id(),
                ids
        );
    }

    public Kind kind() {
        return kind;
    }

    public String source() {
        return source;
    }

    public String detail() {
        return detail;
    }

    /** Returns a stable rendering for diagnostics and deterministic ordering. */
    public String stableKey() {
        return kind.name() + "|" + source + "|" + detail;
    }

    @Override
    public int compareTo(TameworkActivationReason other) {
        return stableKey().compareTo(other.stableKey());
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof TameworkActivationReason reason
                && kind == reason.kind
                && source.equals(reason.source)
                && detail.equals(reason.detail);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, source, detail);
    }

    @Override
    public String toString() {
        return stableKey();
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
