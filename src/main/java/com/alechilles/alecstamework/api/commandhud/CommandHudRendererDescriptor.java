package com.alechilles.alecstamework.api.commandhud;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable contributor capabilities declared by one command HUD renderer. */
public final class CommandHudRendererDescriptor {
    private static final Pattern NAMESPACE = Pattern.compile(
            "[a-z0-9][a-z0-9_.-]*");
    private static final Pattern NAMESPACED_ID = Pattern.compile(
            "[a-z0-9][a-z0-9_.-]*:[a-z0-9][a-z0-9_./-]*");

    private final boolean unrestricted;
    private final Set<String> supportedContributorNamespaces;

    /** Creates an unrestricted descriptor for source-compatible registrations. */
    public CommandHudRendererDescriptor() {
        this(true, Set.of());
    }

    /**
     * Creates a descriptor with the contributor IDs or namespaces it can render.
     * Entries may be bare namespaces such as {@code runeteria} or complete
     * contributor IDs such as {@code runeteria:husbandry}.
     */
    public CommandHudRendererDescriptor(@Nullable Set<?> supportedContributorNamespaces) {
        this(false, copyContributorNamespaces(supportedContributorNamespaces));
    }

    private CommandHudRendererDescriptor(
            boolean unrestricted,
            @Nonnull Set<String> supportedContributorNamespaces
    ) {
        this.unrestricted = unrestricted;
        this.supportedContributorNamespaces = supportedContributorNamespaces;
    }

    /** Returns an unrestricted descriptor for legacy registrations. */
    @Nonnull
    public static CommandHudRendererDescriptor unrestricted() {
        return new CommandHudRendererDescriptor();
    }

    /** Returns whether this descriptor accepts every contributor capability. */
    public boolean isUnrestricted() {
        return unrestricted;
    }

    /** Returns immutable supported contributor namespaces or IDs. */
    @Nonnull
    public Set<String> supportedContributorNamespaces() {
        return supportedContributorNamespaces;
    }

    /** Alias for callers that declare exact contributor IDs. */
    @Nonnull
    public Set<String> supportedContributorIds() {
        return supportedContributorNamespaces;
    }

    /** Returns whether this renderer can represent one contributor declaration. */
    public boolean supports(
            @Nonnull CommandHudContributorId contributorId,
            @Nonnull CommandHudContributorDescriptor contributor
    ) {
        Objects.requireNonNull(contributorId, "contributorId");
        Objects.requireNonNull(contributor, "contributor");
        if (unrestricted) return true;
        if (supportedContributorNamespaces.contains(contributorId.value())) return true;
        if (!supportedContributorNamespaces.contains(contributorId.namespace())) return false;
        if (contributor.isUnrestricted()) return true;
        for (String namespace : contributor.dataNamespaces()) {
            if (!supportsDataNamespace(namespace)) return false;
        }
        return true;
    }

    /** Returns whether this renderer declares support for one contributor ID. */
    public boolean supportsContributor(@Nonnull CommandHudContributorId id) {
        Objects.requireNonNull(id, "id");
        if (unrestricted) return true;
        return supportedContributorNamespaces.contains(id.value())
                || supportedContributorNamespaces.contains(id.namespace());
    }

    /** Returns whether this renderer declares support for one raw contributor ID. */
    public boolean supportsContributor(@Nonnull String id) {
        return CommandHudContributorId.tryParse(id)
                .map(this::supportsContributor)
                .orElse(false);
    }

    /** Returns whether this renderer declares support for one data namespace or ID. */
    public boolean supportsDataNamespace(@Nonnull String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        if (unrestricted) return true;
        String normalized = normalizeNamespaceOrId(namespace, "data namespace");
        if (supportedContributorNamespaces.contains(normalized)) return true;
        int separator = normalized.indexOf(':');
        return separator > 0
                && supportedContributorNamespaces.contains(normalized.substring(0, separator));
    }

    @Nonnull
    private static Set<String> copyContributorNamespaces(@Nullable Set<?> source) {
        if (source == null || source.isEmpty()) return Set.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Object value : source) {
            values.add(normalizeNamespaceOrId(value, "contributor namespace"));
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    @Nonnull
    private static String normalizeNamespaceOrId(
            @Nullable Object rawValue,
            @Nonnull String field
    ) {
        String normalized;
        if (rawValue instanceof CommandHudContributorId contributorId) {
            normalized = contributorId.value();
        } else {
            normalized = rawValue == null
                    ? "" : rawValue.toString().trim().toLowerCase(Locale.ROOT);
        }
        if (!NAMESPACE.matcher(normalized).matches()
                && !NAMESPACED_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a namespace or namespaced ID: "
                    + rawValue);
        }
        return normalized;
    }
}
