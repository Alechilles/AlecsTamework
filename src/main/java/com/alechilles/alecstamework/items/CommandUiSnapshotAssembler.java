package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionView;
import com.alechilles.alecstamework.api.commandui.CommandUiCommandOption;
import com.alechilles.alecstamework.api.commandui.CommandUiCompanionRow;
import com.alechilles.alecstamework.api.commandui.CommandUiPanelState;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererId;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.ui.BondedCompanionPanelPresentation;
import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds detached public snapshots from existing command-panel read models. */
final class CommandUiSnapshotAssembler {
    CommandUiSnapshotAssembler() {
    }

    /** Returns every stored group, including groups with no assigned row. */
    @Nonnull
    static Map<String, String> groups(@Nullable ItemStack stack) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (CommandGroupService.GroupRecord group
                : new CommandGroupService().readGroups(stack)) {
            if (group == null || group.groupId == null
                    || group.groupId.isBlank()) continue;
            result.putIfAbsent(group.groupId,
                    group.name == null || group.name.isBlank()
                            ? group.groupId : group.name);
        }
        return Map.copyOf(result);
    }

    /**
     * Assembles a complete detached snapshot from one panel read.
     *
     * <p>All legacy row objects are converted to public value objects. The
     * resulting snapshot does not retain the source list, feature map, trait
     * arrays, or any runtime callback.</p>
     */
    @Nonnull
    static CommandUiSnapshot assemble(
            @Nonnull UUID sessionId,
            long presentationRevision,
            long actionGeneration,
            @Nullable String rendererId,
            @Nullable String toolId,
            @Nullable String itemId,
            @Nullable String configId,
            @Nullable String rosterMode,
            @Nullable Set<String> capabilities,
            @Nullable String selectedCommand,
            @Nullable List<CommandUiCommandOption> commandOptions,
            @Nullable List<LinkedNpcEntry> entries,
            @Nullable Map<UUID, CommandPanelFeaturePresentation> features,
            @Nullable CommandUiPanelState panelState,
            @Nullable Map<String, CommandUiActionView> globalActions,
            @Nullable Map<String, CommandUiActionView> commandActions,
            long serverTimeMillis,
            @Nullable Map<String, Long> deadlines,
            @Nullable String emptyStateKey,
            @Nullable String disabledReason
    ) {
        return assemble(sessionId, presentationRevision, actionGeneration,
                rendererId, toolId, itemId, configId, rosterMode, capabilities,
                selectedCommand, commandOptions, entries, features, Map.of(),
                panelState, globalActions, commandActions, serverTimeMillis,
                deadlines, emptyStateKey, disabledReason);
    }

    /** Assembles a snapshot with per-row opaque action views. */
    @Nonnull
    static CommandUiSnapshot assemble(
            @Nonnull UUID sessionId,
            long presentationRevision,
            long actionGeneration,
            @Nullable String rendererId,
            @Nullable String toolId,
            @Nullable String itemId,
            @Nullable String configId,
            @Nullable String rosterMode,
            @Nullable Set<String> capabilities,
            @Nullable String selectedCommand,
            @Nullable List<CommandUiCommandOption> commandOptions,
            @Nullable List<LinkedNpcEntry> entries,
            @Nullable Map<UUID, CommandPanelFeaturePresentation> features,
            @Nullable Map<UUID, Map<String, CommandUiActionView>> rowActions,
            @Nullable CommandUiPanelState panelState,
            @Nullable Map<String, CommandUiActionView> globalActions,
            @Nullable Map<String, CommandUiActionView> commandActions,
            long serverTimeMillis,
            @Nullable Map<String, Long> deadlines,
            @Nullable String emptyStateKey,
            @Nullable String disabledReason
    ) {
        List<CommandUiCompanionRow> rows = new ArrayList<>();
        if (entries != null) {
            for (LinkedNpcEntry entry : entries) {
                if (entry == null || entry.npcUuid() == null) continue;
                rows.add(toRow(entry,
                        features == null ? null : features.get(entry.npcUuid()),
                        rowActions == null ? Map.of()
                                : rowActions.getOrDefault(entry.npcUuid(), Map.of())));
            }
        }
        CommandUiPanelState panel = panelState == null
                ? new CommandUiPanelState(null) : panelState;
        return new CommandUiSnapshot(
                sessionId, presentationRevision, actionGeneration,
                CommandUiRendererId.tryParse(rendererId).orElse(null),
                toolId, itemId, configId, rosterMode, capabilities,
                selectedCommand, commandOptions, rows, panel, globalActions,
                commandActions, serverTimeMillis, deadlines, emptyStateKey,
                disabledReason);
    }

    /** Assembles a snapshot with detached Q/E/R and group presentation values. */
    @Nonnull
    static CommandUiSnapshot assemble(
            @Nonnull UUID sessionId,
            long presentationRevision,
            long actionGeneration,
            @Nullable String rendererId,
            @Nullable String toolId,
            @Nullable String itemId,
            @Nullable String configId,
            @Nullable String rosterMode,
            @Nullable Set<String> capabilities,
            @Nullable String selectedCommand,
            @Nullable List<CommandUiCommandOption> commandOptions,
            @Nullable List<LinkedNpcEntry> entries,
            @Nullable Map<UUID, CommandPanelFeaturePresentation> features,
            @Nullable Map<UUID, Map<String, CommandUiActionView>> rowActions,
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
        List<CommandUiCompanionRow> rows = new ArrayList<>();
        if (entries != null) {
            for (LinkedNpcEntry entry : entries) {
                if (entry == null || entry.npcUuid() == null) continue;
                rows.add(toRow(entry,
                        features == null ? null : features.get(entry.npcUuid()),
                        rowActions == null ? Map.of()
                                : rowActions.getOrDefault(entry.npcUuid(), Map.of())));
            }
        }
        return new CommandUiSnapshot(
                sessionId, presentationRevision, actionGeneration,
                CommandUiRendererId.tryParse(rendererId).orElse(null),
                toolId, itemId, configId, rosterMode, capabilities,
                selectedCommand, commandOptions, rows,
                panelState == null ? new CommandUiPanelState(null) : panelState,
                globalActions, commandActions, hotswapAssignments, hotswapChoices,
                groups, serverTimeMillis, deadlines, emptyStateKey, disabledReason);
    }

    /** Assembles a snapshot directly from the current panel state. */
    @Nonnull
    static CommandUiSnapshot assemble(
            @Nonnull UUID sessionId,
            long presentationRevision,
            long actionGeneration,
            @Nullable String rendererId,
            @Nullable String toolId,
            @Nullable String itemId,
            @Nullable String configId,
            @Nullable String rosterMode,
            @Nullable Set<String> capabilities,
            @Nullable String selectedCommand,
            @Nullable List<CommandUiCommandOption> commandOptions,
            @Nonnull CommandPanelSnapshotState panelSnapshot,
            @Nullable CommandUiPanelState panelState,
            @Nullable Map<String, CommandUiActionView> globalActions,
            @Nullable Map<String, CommandUiActionView> commandActions,
            long serverTimeMillis,
            @Nullable Map<String, Long> deadlines,
            @Nullable String disabledReason
    ) {
        CommandPanelEntrySourceService.CommandPanelSnapshot source =
                panelSnapshot.snapshot();
        return assemble(sessionId, presentationRevision, actionGeneration,
                rendererId, toolId, itemId, configId, rosterMode, capabilities,
                selectedCommand, commandOptions, source.entries(),
                source.featurePresentations(), panelState, globalActions,
                commandActions, serverTimeMillis, deadlines,
                source.emptyStateKey(), disabledReason);
    }

    /** Converts one existing linked-panel row to a detached public row. */
    @Nonnull
    static CommandUiCompanionRow toRow(
            @Nonnull LinkedNpcEntry entry,
            @Nullable CommandPanelFeaturePresentation feature,
            @Nullable Map<String, CommandUiActionView> actions
    ) {
        String profileId = null;
        String lifecycleStatus = lifecycleStatus(entry);
        String role = entry.speciesLabel();
        String species = entry.speciesId();
        UUID rowId = entry.npcUuid();
        Map<String, String> presentation = new java.util.LinkedHashMap<>();
        if (entry.groupId() != null) presentation.put("groupId", entry.groupId());
        if (entry.groupName() != null) presentation.put("groupName", entry.groupName());
        if (entry.groupColorHex() != null) {
            presentation.put("groupColor", entry.groupColorHex());
        }
        if (feature != null) {
            if (feature.roster() != null) {
                profileId = feature.roster().profileId();
                rowId = stableProfileRowId(profileId, rowId);
                lifecycleStatus = feature.roster().state().name();
                presentation.put("commandFamilyId",
                        feature.roster().commandFamilyId());
                presentation.put("rosterRevision",
                        Long.toString(feature.roster().revision()));
                if (feature.roster().blockingReason() != null) {
                    presentation.put("blockingReason",
                            feature.roster().blockingReason());
                }
            }
            BondedCompanionPanelPresentation bonded = feature.bonded();
            if (bonded != null) {
                profileId = bonded.profileId();
                rowId = stableProfileRowId(profileId, rowId);
                role = bonded.rolePresentation();
                species = bonded.species();
                lifecycleStatus = bonded.status().state().name();
                presentation.put("rosterId", bonded.rosterId());
                presentation.put("roleId", bonded.roleId());
                presentation.put("revision", Long.toString(bonded.revision()));
                presentation.putAll(bonded.attributes());
                presentation.putAll(bonded.extensions());
            }
        }
        return new CommandUiCompanionRow(
                rowId, entry.npcUuid(), profileId,
                entry.displayName() == null || entry.displayName().isBlank()
                        ? "Unknown" : entry.displayName(), role,
                species, entry.gender(), lifecycleStatus, entry.linked(),
                entry.active(), entry.loaded(), entry.loaded(),
                entry.hasHealth() ? entry.currentHealth() : null,
                entry.hasHealth() ? entry.maxHealth() : null,
                entry.hasHappiness() ? entry.currentHappiness() : null,
                entry.hasHappiness() ? entry.maxHappiness() : null,
                actions, presentation);
    }

    /** Builds command choices from an asset config and issued action views. */
    @Nonnull
    static List<CommandUiCommandOption> commandOptions(
            @Nullable TwCommandItemConfig config,
            @Nullable String selectedCommand,
            @Nullable Map<String, CommandUiActionView> actions
    ) {
        return commandOptions(config, selectedCommand, actions, null);
    }

    /** Builds display-ready command choices for one player language. */
    @Nonnull
    static List<CommandUiCommandOption> commandOptions(
            @Nullable TwCommandItemConfig config,
            @Nullable String selectedCommand,
            @Nullable Map<String, CommandUiActionView> actions,
            @Nullable String language
    ) {
        if (config == null || config.getCommandList() == null) return List.of();
        List<CommandUiCommandOption> options = new ArrayList<>();
        for (TwCommandItemConfig.CommandEntry command : config.getCommandList()) {
            if (command == null || command.getId() == null
                    || command.getId().isBlank()) continue;
            String id = command.getId().trim();
            String configuredLabel = command.getDisplayName();
            options.add(new CommandUiCommandOption(
                    id,
                    LocalizedText.resolveConfigValue(
                            language, configuredLabel, id),
                    configuredLabel,
                    command.getIcon(),
                    command.isShowInRadial(),
                    id.equals(selectedCommand),
                    actions == null ? null : actions.get(id)));
        }
        return List.copyOf(options);
    }

    @Nonnull
    private static String lifecycleStatus(LinkedNpcEntry entry) {
        if (entry.dead()) return "DEAD";
        if (entry.captured()) return "CAPTURED";
        if (entry.inCoop()) return "IN_COOP";
        if (entry.lost()) return "LOST";
        if (!entry.loaded()) return "UNLOADED";
        return entry.active() ? "ACTIVE" : "INACTIVE";
    }

    /** Uses durable profile identity for bonded rows and current NPC UUID otherwise. */
    @Nonnull
    private static UUID stableProfileRowId(
            @Nullable String profileId,
            @Nonnull UUID fallback
    ) {
        if (profileId == null || profileId.isBlank()) return fallback;
        return UUID.nameUUIDFromBytes(("tamework:command-ui:profile:" +
                profileId.trim()).getBytes(StandardCharsets.UTF_8));
    }
}
