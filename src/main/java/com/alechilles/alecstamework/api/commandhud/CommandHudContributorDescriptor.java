package com.alechilles.alecstamework.api.commandhud;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable data capabilities declared by one command HUD contributor. */
public final class CommandHudContributorDescriptor {
    private static final Pattern NAMESPACE = Pattern.compile(
            "[a-z0-9][a-z0-9_.-]*");
    private static final Pattern NAMESPACED_ID = Pattern.compile(
            "[a-z0-9][a-z0-9_.-]*:[a-z0-9][a-z0-9_./-]*");

    private final boolean unrestricted;
    private final Set<String> dataNamespaces;

    /** Creates an unrestricted descriptor for source-compatible registrations. */
    public CommandHudContributorDescriptor() {
        this(true, Set.of());
    }

    /** Creates a descriptor with the data namespaces or IDs this contributor supplies. */
    public CommandHudContributorDescriptor(@Nullable Set<?> dataNamespaces) {
        this(false, copyDataNamespaces(dataNamespaces));
    }

    private CommandHudContributorDescriptor(
            boolean unrestricted,
            @Nonnull Set<String> dataNamespaces
    ) {
        this.unrestricted = unrestricted;
        this.dataNamespaces = dataNamespaces;
    }

    /** Returns an unrestricted descriptor for legacy registrations. */
    @Nonnull
    public static CommandHudContributorDescriptor unrestricted() {
        return new CommandHudContributorDescriptor();
    }

    /** Returns whether this descriptor accepts every data capability. */
    public boolean isUnrestricted() {
        return unrestricted;
    }

    /** Returns immutable data namespaces or IDs supplied by this contributor. */
    @Nonnull
    public Set<String> dataNamespaces() {
        return dataNamespaces;
    }

    /** Alias for callers that use supported-capability terminology. */
    @Nonnull
    public Set<String> supportedDataNamespaces() {
        return dataNamespaces;
    }

    /** Alias for callers that use namespace terminology. */
    @Nonnull
    public Set<String> namespaces() {
        return dataNamespaces;
    }

    /** Returns whether this contributor declares one data namespace or ID. */
    public boolean supportsDataNamespace(@Nonnull String namespace) {
        if (unrestricted) return true;
        String normalized = normalizeNamespaceOrId(namespace, "data namespace");
        if (dataNamespaces.contains(normalized)) return true;
        int separator = normalized.indexOf(':');
        return separator > 0 && dataNamespaces.contains(normalized.substring(0, separator));
    }

    @Nonnull
    private static Set<String> copyDataNamespaces(@Nullable Set<?> source) {
        if (source == null || source.isEmpty()) return Set.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Object value : source) {
            values.add(normalizeNamespaceOrId(value, "data namespace"));
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    @Nonnull
    private static String normalizeNamespaceOrId(
            @Nullable Object rawValue,
            @Nonnull String field
    ) {
        String normalized = rawValue == null
                ? "" : rawValue.toString().trim().toLowerCase(Locale.ROOT);
        if (!NAMESPACE.matcher(normalized).matches()
                && !NAMESPACED_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a namespace or namespaced ID: "
                    + rawValue);
        }
        return normalized;
    }
}
