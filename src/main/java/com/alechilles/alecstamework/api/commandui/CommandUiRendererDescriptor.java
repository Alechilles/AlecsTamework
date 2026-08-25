package com.alechilles.alecstamework.api.commandui;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable presentation capabilities for one renderer registration.
 *
 * <p>The descriptor is retained with the renderer generation. An unrestricted
 * descriptor is used by the source-compatible registration overload.</p>
 */
public final class CommandUiRendererDescriptor {
    private static final Pattern NAMESPACE = Pattern.compile(
            "[a-z0-9][a-z0-9_.-]*");
    private static final Pattern NAMESPACED_ID = Pattern.compile(
            "[a-z0-9][a-z0-9_.-]*:[a-z0-9][a-z0-9_./-]*");

    private final boolean unrestricted;
    private final Set<String> supportedContributorNamespaces;
    private final Set<String> supportedFlowTypes;

    /** Creates an unrestricted descriptor for compatibility adapters. */
    public CommandUiRendererDescriptor() {
        this(true, Set.of(), Set.of());
    }

    /**
     * Creates a descriptor with explicit contributor namespaces and flow types.
     *
     * <p>Contributor entries may be a bare namespace such as {@code runeteria}
     * or a complete contributor ID such as {@code runeteria:husbandry}.
     * Custom flow types must be namespaced IDs.</p>
     */
    public CommandUiRendererDescriptor(
            @Nullable Set<?> supportedContributorNamespaces,
            @Nullable Set<String> supportedFlowTypes
    ) {
        this(false,
                copyContributorNamespaces(supportedContributorNamespaces),
                copyFlowTypes(supportedFlowTypes));
    }

    private CommandUiRendererDescriptor(
            boolean unrestricted,
            @Nonnull Set<String> supportedContributorNamespaces,
            @Nonnull Set<String> supportedFlowTypes
    ) {
        this.unrestricted = unrestricted;
        this.supportedContributorNamespaces = supportedContributorNamespaces;
        this.supportedFlowTypes = supportedFlowTypes;
    }

    /** Returns an unrestricted descriptor for legacy registrations. */
    @Nonnull
    public static CommandUiRendererDescriptor unrestricted() {
        return new CommandUiRendererDescriptor();
    }

    /** Returns whether this descriptor accepts every known capability. */
    public boolean isUnrestricted() {
        return unrestricted;
    }

    /** Returns immutable supported contributor namespaces or IDs. */
    @Nonnull
    public Set<String> supportedContributorNamespaces() {
        return supportedContributorNamespaces;
    }

    /** Alias that makes exact contributor-ID declarations easy to discover. */
    @Nonnull
    public Set<String> supportedContributorIds() {
        return supportedContributorNamespaces;
    }

    /** Returns immutable supported namespaced custom flow types. */
    @Nonnull
    public Set<String> supportedFlowTypes() {
        return supportedFlowTypes;
    }

    /** Alias for callers that use the custom-flow terminology. */
    @Nonnull
    public Set<String> customFlowTypes() {
        return supportedFlowTypes;
    }

    /** Returns whether this renderer can represent one contributor generation. */
    public boolean supports(
            @Nonnull CommandUiContributorId contributorId,
            @Nonnull CommandUiContributorDescriptor contributor
    ) {
        Objects.requireNonNull(contributorId, "contributorId");
        Objects.requireNonNull(contributor, "contributor");
        if (unrestricted) return true;
        if (!supportsContributor(contributorId)) return false;
        for (String flowType : contributor.customFlowTypes()) {
            if (!supportsFlowType(flowType)) return false;
        }
        return true;
    }

    /** Returns whether this renderer declares support for one contributor ID. */
    public boolean supportsContributor(@Nonnull CommandUiContributorId id) {
        Objects.requireNonNull(id, "id");
        if (unrestricted) return true;
        return supportedContributorNamespaces.contains(id.value())
                || supportedContributorNamespaces.contains(id.namespace());
    }

    /** Returns whether this renderer declares support for one raw contributor ID. */
    public boolean supportsContributor(@Nonnull String id) {
        return CommandUiContributorId.tryParse(id)
                .map(this::supportsContributor)
                .orElse(false);
    }

    /** Returns whether this renderer declares support for one flow type. */
    public boolean supportsFlowType(@Nonnull String flowType) {
        Objects.requireNonNull(flowType, "flowType");
        if (unrestricted) return true;
        String normalized = normalizeNamespacedId(flowType, "flow type");
        return supportedFlowTypes.contains(normalized);
    }

    @Nonnull
    private static Set<String> copyContributorNamespaces(
            @Nullable Set<?> source
    ) {
        if (source == null || source.isEmpty()) return Set.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Object value : source) {
            if (value instanceof CommandUiContributorId contributorId) {
                values.add(contributorId.value());
            } else {
                values.add(normalizeNamespaceOrId(value, "contributor namespace"));
            }
        }
        return immutable(values);
    }

    @Nonnull
    private static Set<String> copyFlowTypes(@Nullable Set<String> source) {
        if (source == null || source.isEmpty()) return Set.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String value : source) {
            values.add(normalizeNamespacedId(value, "flow type"));
        }
        return immutable(values);
    }

    @Nonnull
    private static String normalizeNamespaceOrId(
            @Nullable Object rawValue,
            @Nonnull String field
    ) {
        String normalized = rawValue == null
                ? "" : rawValue.toString().trim().toLowerCase(Locale.ROOT);
        if (NAMESPACE.matcher(normalized).matches()
                || NAMESPACED_ID.matcher(normalized).matches()) {
            return normalized;
        }
        throw new IllegalArgumentException(field + " must be a namespace or namespaced ID: "
                + rawValue);
    }

    @Nonnull
    private static String normalizeNamespacedId(
            @Nullable String rawValue,
            @Nonnull String field
    ) {
        String normalized = rawValue == null
                ? "" : rawValue.trim().toLowerCase(Locale.ROOT);
        if (!NAMESPACED_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a namespaced ID: "
                    + rawValue);
        }
        return normalized;
    }

    @Nonnull
    private static Set<String> immutable(@Nonnull LinkedHashSet<String> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }
}
