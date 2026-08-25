package com.alechilles.alecstamework.api.commandui;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable presentation capabilities for one contributor registration.
 *
 * <p>The descriptor describes the namespaces, action scopes, and custom flow
 * types that a contributor can produce for one registration generation.</p>
 */
public final class CommandUiContributorDescriptor {
    private static final Pattern NAMESPACE = Pattern.compile(
            "[a-z0-9][a-z0-9_.-]*");
    private static final Pattern NAMESPACED_ID = Pattern.compile(
            "[a-z0-9][a-z0-9_.-]*:[a-z0-9][a-z0-9_./-]*");

    private final boolean unrestricted;
    private final Set<String> pageDataNamespaces;
    private final Set<String> rowDataNamespaces;
    private final Set<CommandUiContributorAction.Scope> actionScopes;
    private final Set<String> customFlowTypes;

    /** Creates an unrestricted descriptor for compatibility adapters. */
    public CommandUiContributorDescriptor() {
        this(true, Set.of(), Set.of(), Set.of(), Set.of());
    }

    /**
     * Creates a descriptor with explicit presentation capabilities.
     *
     * <p>Data namespace entries may be bare namespaces or namespaced IDs.
     * Custom flow types must be namespaced IDs.</p>
     */
    public CommandUiContributorDescriptor(
            @Nullable Set<?> pageDataNamespaces,
            @Nullable Set<?> rowDataNamespaces,
            @Nullable Set<CommandUiContributorAction.Scope> actionScopes,
            @Nullable Set<String> customFlowTypes
    ) {
        this(false,
                copyNamespaces(pageDataNamespaces, "page data namespace"),
                copyNamespaces(rowDataNamespaces, "row data namespace"),
                copyActionScopes(actionScopes),
                copyFlowTypes(customFlowTypes));
    }

    private CommandUiContributorDescriptor(
            boolean unrestricted,
            @Nonnull Set<String> pageDataNamespaces,
            @Nonnull Set<String> rowDataNamespaces,
            @Nonnull Set<CommandUiContributorAction.Scope> actionScopes,
            @Nonnull Set<String> customFlowTypes
    ) {
        this.unrestricted = unrestricted;
        this.pageDataNamespaces = pageDataNamespaces;
        this.rowDataNamespaces = rowDataNamespaces;
        this.actionScopes = actionScopes;
        this.customFlowTypes = customFlowTypes;
    }

    /** Returns an unrestricted descriptor for legacy registrations. */
    @Nonnull
    public static CommandUiContributorDescriptor unrestricted() {
        return new CommandUiContributorDescriptor();
    }

    /** Returns whether this descriptor accepts every known capability. */
    public boolean isUnrestricted() {
        return unrestricted;
    }

    /** Returns immutable page-level data namespaces. */
    @Nonnull
    public Set<String> pageDataNamespaces() {
        return pageDataNamespaces;
    }

    /** Returns immutable row-level data namespaces. */
    @Nonnull
    public Set<String> rowDataNamespaces() {
        return rowDataNamespaces;
    }

    /** Returns immutable contributor action scopes. */
    @Nonnull
    public Set<CommandUiContributorAction.Scope> actionScopes() {
        return actionScopes;
    }

    /** Returns immutable namespaced custom flow types. */
    @Nonnull
    public Set<String> customFlowTypes() {
        return customFlowTypes;
    }

    /** Alias for callers that use the flow-type terminology. */
    @Nonnull
    public Set<String> flowTypes() {
        return customFlowTypes;
    }

    /** Returns whether the descriptor declares one action scope. */
    public boolean supportsActionScope(
            @Nonnull CommandUiContributorAction.Scope scope
    ) {
        return unrestricted || actionScopes.contains(
                java.util.Objects.requireNonNull(scope, "scope"));
    }

    @Nonnull
    private static Set<String> copyNamespaces(
            @Nullable Set<?> source,
            @Nonnull String field
    ) {
        if (source == null || source.isEmpty()) return Set.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Object value : source) {
            String normalized = value == null
                    ? "" : value.toString().trim().toLowerCase(Locale.ROOT);
            if (!NAMESPACE.matcher(normalized).matches()
                    && !NAMESPACED_ID.matcher(normalized).matches()) {
                throw new IllegalArgumentException(field
                        + " must be a namespace or namespaced ID: " + value);
            }
            values.add(normalized);
        }
        return immutable(values);
    }

    @Nonnull
    private static Set<CommandUiContributorAction.Scope> copyActionScopes(
            @Nullable Set<CommandUiContributorAction.Scope> source
    ) {
        if (source == null || source.isEmpty()) return Set.of();
        EnumSet<CommandUiContributorAction.Scope> values = EnumSet.noneOf(
                CommandUiContributorAction.Scope.class);
        for (CommandUiContributorAction.Scope value : source) {
            values.add(java.util.Objects.requireNonNull(value, "action scope"));
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(values));
    }

    @Nonnull
    private static Set<String> copyFlowTypes(@Nullable Set<String> source) {
        if (source == null || source.isEmpty()) return Set.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String value : source) {
            String normalized = value == null
                    ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (!NAMESPACED_ID.matcher(normalized).matches()) {
                throw new IllegalArgumentException(
                        "flow type must be a namespaced ID: " + value);
            }
            values.add(normalized);
        }
        return immutable(values);
    }

    @Nonnull
    private static Set<String> immutable(@Nonnull LinkedHashSet<String> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }
}
