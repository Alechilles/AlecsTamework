package com.alechilles.alecstamework.api.commandui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Full immutable, detached presentation state for one command UI session.
 *
 * <p>Presentation revision and action generation are intentionally separate.
 * A countdown or indicator refresh can therefore publish a new snapshot
 * without invalidating action handles that remain authoritative.</p>
 */
public final class CommandUiSnapshot {
    private final UUID sessionId;
    private final long presentationRevision;
    private final long actionGeneration;
    @Nullable
    private final CommandUiProviderId providerId;
    @Nullable
    private final String toolId;
    @Nullable
    private final String itemId;
    @Nullable
    private final String configId;
    @Nullable
    private final String rosterMode;
    private final Set<String> enabledCapabilities;
    @Nullable
    private final String selectedCommand;
    private final List<CommandUiCommandOption> commandOptions;
    private final List<CommandUiCompanionRow> companionRows;
    private final CommandUiPanelState panelState;
    private final Map<String, CommandUiActionView> globalActions;
    private final Map<String, CommandUiActionView> commandActions;
    private final Map<String, String> hotswapAssignments;
    private final Map<String, List<CommandUiCommandOption>> hotswapChoices;
    private final Map<String, String> groups;
    private final long serverTimeMillis;
    private final Map<String, Long> deadlines;
    @Nullable
    private final String emptyStateKey;
    @Nullable
    private final String disabledReason;

    /** Small snapshot constructor for pure presentation tests and adapters. */
    public CommandUiSnapshot(
            @Nonnull UUID sessionId,
            long presentationRevision,
            long actionGeneration,
            @Nullable String selectedCommand,
            @Nonnull List<CommandUiCommandOption> commandOptions,
            @Nonnull List<CommandUiCompanionRow> companionRows,
            @Nonnull CommandUiPanelState panelState
    ) {
        this(sessionId, presentationRevision, actionGeneration,
                (CommandUiProviderId) null, null, null,
                null, null, Set.of(), selectedCommand, commandOptions,
                companionRows, panelState, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), 0L, Map.of(), null, null);
    }

    /** Full snapshot constructor used by Tamework assemblers. */
    public CommandUiSnapshot(
            @Nonnull UUID sessionId,
            long presentationRevision,
            long actionGeneration,
            @Nullable String providerId,
            @Nullable String toolId,
            @Nullable String itemId,
            @Nullable String configId,
            @Nullable String rosterMode,
            @Nullable Set<String> enabledCapabilities,
            @Nullable String selectedCommand,
            @Nullable List<CommandUiCommandOption> commandOptions,
            @Nullable List<CommandUiCompanionRow> companionRows,
            @Nullable CommandUiPanelState panelState,
            @Nullable Map<String, CommandUiActionView> globalActions,
            @Nullable Map<String, CommandUiActionView> commandActions,
            long serverTimeMillis,
            @Nullable Map<String, Long> deadlines,
            @Nullable String emptyStateKey,
            @Nullable String disabledReason
    ) {
        this(sessionId, presentationRevision, actionGeneration,
                parseProviderId(providerId), toolId, itemId, configId, rosterMode,
                enabledCapabilities, selectedCommand, commandOptions,
                companionRows, panelState, globalActions, commandActions,
                Map.of(), Map.of(), Map.of(), serverTimeMillis, deadlines,
                emptyStateKey, disabledReason);
    }

    /** Full constructor retaining the compact pre-assignment form. */
    public CommandUiSnapshot(
            @Nonnull UUID sessionId,
            long presentationRevision,
            long actionGeneration,
            @Nullable CommandUiProviderId providerId,
            @Nullable String toolId,
            @Nullable String itemId,
            @Nullable String configId,
            @Nullable String rosterMode,
            @Nullable Set<String> enabledCapabilities,
            @Nullable String selectedCommand,
            @Nullable List<CommandUiCommandOption> commandOptions,
            @Nullable List<CommandUiCompanionRow> companionRows,
            @Nullable CommandUiPanelState panelState,
            @Nullable Map<String, CommandUiActionView> globalActions,
            @Nullable Map<String, CommandUiActionView> commandActions,
            long serverTimeMillis,
            @Nullable Map<String, Long> deadlines,
            @Nullable String emptyStateKey,
            @Nullable String disabledReason
    ) {
        this(sessionId, presentationRevision, actionGeneration, providerId,
                toolId, itemId, configId, rosterMode, enabledCapabilities,
                selectedCommand, commandOptions, companionRows, panelState,
                globalActions, commandActions, Map.of(), Map.of(), Map.of(),
                serverTimeMillis, deadlines, emptyStateKey, disabledReason);
    }

    /** Full constructor accepting the normalized public provider ID type. */
    public CommandUiSnapshot(
            @Nonnull UUID sessionId,
            long presentationRevision,
            long actionGeneration,
            @Nullable CommandUiProviderId providerId,
            @Nullable String toolId,
            @Nullable String itemId,
            @Nullable String configId,
            @Nullable String rosterMode,
            @Nullable Set<String> enabledCapabilities,
            @Nullable String selectedCommand,
            @Nullable List<CommandUiCommandOption> commandOptions,
            @Nullable List<CommandUiCompanionRow> companionRows,
            @Nullable CommandUiPanelState panelState,
            @Nullable Map<String, CommandUiActionView> globalActions,
            @Nullable Map<String, CommandUiActionView> commandActions,
            @Nullable Map<String, String> hotswapAssignments,
            @Nullable Map<String, List<CommandUiCommandOption>> hotswapChoices,
            @Nullable Map<String, String> groups,
            long serverTimeMillis,
            @Nullable Map<String, Long> deadlines,
            @Nullable String emptyStateKey,
            @Nullable String disabledReason
    ) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        if (presentationRevision < 0L) {
            throw new IllegalArgumentException("Presentation revision cannot be negative.");
        }
        if (actionGeneration < 0L) {
            throw new IllegalArgumentException("Action generation cannot be negative.");
        }
        this.presentationRevision = presentationRevision;
        this.actionGeneration = actionGeneration;
        this.providerId = providerId;
        this.toolId = normalize(toolId);
        this.itemId = normalize(itemId);
        this.configId = normalize(configId);
        this.rosterMode = normalize(rosterMode);
        this.enabledCapabilities = copyStrings(enabledCapabilities);
        this.selectedCommand = normalize(selectedCommand);
        this.commandOptions = List.copyOf(commandOptions == null
                ? List.of() : commandOptions);
        this.companionRows = List.copyOf(companionRows == null
                ? List.of() : companionRows);
        this.panelState = panelState == null
                ? new CommandUiPanelState(null) : panelState;
        this.globalActions = copyActions(globalActions);
        this.commandActions = copyActions(commandActions);
        this.hotswapAssignments = copyStringMap(hotswapAssignments);
        this.hotswapChoices = copyChoices(hotswapChoices);
        this.groups = copyStringMap(groups);
        this.serverTimeMillis = serverTimeMillis;
        this.deadlines = copyDeadlines(deadlines);
        this.emptyStateKey = normalize(emptyStateKey);
        this.disabledReason = normalize(disabledReason);
    }

    /** Convenience constructor for a snapshot with no action-generation change. */
    public CommandUiSnapshot(
            @Nonnull UUID sessionId,
            long presentationRevision,
            @Nullable String selectedCommand,
            @Nullable List<CommandUiCommandOption> commandOptions,
            @Nullable List<CommandUiCompanionRow> companionRows,
            @Nullable CommandUiPanelState panelState
    ) {
        this(sessionId, presentationRevision, 0L, selectedCommand,
                commandOptions == null ? List.of() : commandOptions,
                companionRows == null ? List.of() : companionRows,
                panelState == null ? new CommandUiPanelState(null) : panelState);
    }

    @Nonnull
    public UUID sessionId() {
        return sessionId;
    }

    public long presentationRevision() {
        return presentationRevision;
    }

    public long actionGeneration() {
        return actionGeneration;
    }

    @Nullable
    public CommandUiProviderId providerId() {
        return providerId;
    }

    @Nullable
    public String toolId() {
        return toolId;
    }

    @Nullable
    public String itemId() {
        return itemId;
    }

    @Nullable
    public String configId() {
        return configId;
    }

    @Nullable
    public String rosterMode() {
        return rosterMode;
    }

    @Nonnull
    public Set<String> enabledCapabilities() {
        return enabledCapabilities;
    }

    @Nullable
    public String selectedCommand() {
        return selectedCommand;
    }

    @Nonnull
    public List<CommandUiCommandOption> commandOptions() {
        return commandOptions;
    }

    @Nonnull
    public List<CommandUiCompanionRow> companionRows() {
        return companionRows;
    }

    /** Current Q/E/R command assignments keyed by slot name. */
    @Nonnull
    public Map<String, String> hotswapAssignments() {
        return hotswapAssignments;
    }

    /** Detached choices keyed by Q/E/R slot name. */
    @Nonnull
    public Map<String, List<CommandUiCommandOption>> hotswapChoices() {
        return hotswapChoices;
    }

    /** Detached group labels keyed by stable group ID. */
    @Nonnull
    public Map<String, String> groups() {
        return groups;
    }

    @Nonnull
    public CommandUiPanelState panelState() {
        return panelState;
    }

    @Nonnull
    public Map<String, CommandUiActionView> globalActions() {
        return globalActions;
    }

    @Nonnull
    public Map<String, CommandUiActionView> commandActions() {
        return commandActions;
    }

    public long serverTimeMillis() {
        return serverTimeMillis;
    }

    @Nonnull
    public Map<String, Long> deadlines() {
        return deadlines;
    }

    @Nullable
    public String emptyStateKey() {
        return emptyStateKey;
    }

    /**
     * Returns display-ready empty-state text for custom providers.
     *
     * <p>The legacy {@link #emptyStateKey()} name remains for source
     * compatibility. Runtime assemblers resolve configured keys before they
     * cross the public command UI boundary.</p>
     */
    @Nullable
    public String emptyStateText() {
        return emptyStateKey;
    }

    @Nullable
    public String disabledReason() {
        return disabledReason;
    }

    /** Returns the row for a stable presentation ID, or null when absent. */
    @Nullable
    public CommandUiCompanionRow companionRow(@Nullable UUID rowId) {
        if (rowId == null) return null;
        for (CommandUiCompanionRow row : companionRows) {
            if (rowId.equals(row.rowId())) return row;
        }
        return null;
    }

    /** Returns a detached copy with a different presentation revision. */
    @Nonnull
    public CommandUiSnapshot withPresentationRevision(long revision) {
        return copyWithRevision(revision, actionGeneration);
    }

    /** Returns a detached copy with a different authority generation. */
    @Nonnull
    public CommandUiSnapshot withActionGeneration(long generation) {
        return copyWithRevision(presentationRevision, generation);
    }

    /** Returns a copy with display-ready empty-state text. */
    @Nonnull
    public CommandUiSnapshot withEmptyStateText(@Nullable String text) {
        return new CommandUiSnapshot(
                sessionId, presentationRevision, actionGeneration, providerId,
                toolId, itemId, configId, rosterMode, enabledCapabilities,
                selectedCommand, commandOptions, companionRows, panelState,
                globalActions, commandActions, hotswapAssignments,
                hotswapChoices, groups, serverTimeMillis, deadlines, text,
                disabledReason);
    }

    private CommandUiSnapshot copyWithRevision(long revision, long generation) {
        return new CommandUiSnapshot(sessionId, revision, generation, providerId,
                toolId, itemId, configId, rosterMode, enabledCapabilities,
                selectedCommand, commandOptions, companionRows, panelState,
                globalActions, commandActions, hotswapAssignments,
                hotswapChoices, groups, serverTimeMillis, deadlines,
                emptyStateKey, disabledReason);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CommandUiSnapshot that)) return false;
        return presentationRevision == that.presentationRevision
                && actionGeneration == that.actionGeneration
                && serverTimeMillis == that.serverTimeMillis
                && sessionId.equals(that.sessionId)
                && Objects.equals(providerId, that.providerId)
                && Objects.equals(toolId, that.toolId)
                && Objects.equals(itemId, that.itemId)
                && Objects.equals(configId, that.configId)
                && Objects.equals(rosterMode, that.rosterMode)
                && enabledCapabilities.equals(that.enabledCapabilities)
                && Objects.equals(selectedCommand, that.selectedCommand)
                && commandOptions.equals(that.commandOptions)
                && companionRows.equals(that.companionRows)
                && panelState.equals(that.panelState)
                && globalActions.equals(that.globalActions)
                && commandActions.equals(that.commandActions)
                && hotswapAssignments.equals(that.hotswapAssignments)
                && hotswapChoices.equals(that.hotswapChoices)
                && groups.equals(that.groups)
                && deadlines.equals(that.deadlines)
                && Objects.equals(emptyStateKey, that.emptyStateKey)
                && Objects.equals(disabledReason, that.disabledReason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, presentationRevision, actionGeneration,
                providerId, toolId, itemId, configId, rosterMode,
                enabledCapabilities, selectedCommand, commandOptions,
                companionRows, panelState, globalActions, commandActions,
                hotswapAssignments, hotswapChoices, groups, serverTimeMillis,
                deadlines, emptyStateKey, disabledReason);
    }

    @Nullable
    private static CommandUiProviderId parseProviderId(@Nullable String value) {
        return CommandUiProviderId.tryParse(value).orElse(null);
    }

    @Nonnull
    private static Set<String> copyStrings(@Nullable Set<String> source) {
        if (source == null || source.isEmpty()) return Set.of();
        return source.stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Nonnull
    private static Map<String, String> copyStringMap(
            @Nullable Map<String, String> source) {
        if (source == null || source.isEmpty()) return Map.of();
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                copy.put(key.trim(), value.trim());
            }
        });
        return Map.copyOf(copy);
    }

    @Nonnull
    private static Map<String, List<CommandUiCommandOption>> copyChoices(
            @Nullable Map<String, List<CommandUiCommandOption>> source) {
        if (source == null || source.isEmpty()) return Map.of();
        LinkedHashMap<String, List<CommandUiCommandOption>> copy =
                new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                copy.put(key.trim(), List.copyOf(value));
            }
        });
        return Map.copyOf(copy);
    }

    @Nonnull
    private static Map<String, CommandUiActionView> copyActions(
            @Nullable Map<String, CommandUiActionView> source) {
        if (source == null || source.isEmpty()) return Map.of();
        LinkedHashMap<String, CommandUiActionView> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) copy.put(key, value);
        });
        return Map.copyOf(copy);
    }

    @Nonnull
    private static Map<String, Long> copyDeadlines(@Nullable Map<String, Long> source) {
        if (source == null || source.isEmpty()) return Map.of();
        LinkedHashMap<String, Long> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) copy.put(key, value);
        });
        return Map.copyOf(copy);
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
