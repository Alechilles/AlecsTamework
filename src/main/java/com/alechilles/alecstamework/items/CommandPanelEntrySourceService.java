package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.ownership.live.OwnerPopulationLiveIndex;
import com.alechilles.alecstamework.compat.HytaleSpatialAccess;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.LinkedNpcPanelPageState;
import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Builds command-panel entries using linked and nearby-owned companions.
 */
final class CommandPanelEntrySourceService {
    private final CommandLinkedPanelEntryService linkedPanelEntryService;
    private final CommandPanelPreferenceService panelPreferenceService;
    private final CommandLinkPolicyService linkPolicyService;
    private final CommandNpcNameResolver npcNameResolver;
    private final CommandLoadedNpcStatusSnapshotService loadedSnapshotService;
    @Nullable
    private final CommandRosterPanelRecordSource rosterRecordSource;
    @Nullable
    private final CommandPanelFeaturePresentationSource featurePresentations;
    @Nullable
    private final BondedCompanionPanelEntrySourceService bondedEntrySource;
    @Nullable
    private final CommandOwnedPanelRecordSource ownedRecordSource;
    @Nullable
    private final OwnerPopulationLiveIndex liveOwnerIndex;
    private final CommandLinkedNpcRecordStore linkedRecordStore = new CommandLinkedNpcRecordStore();

    CommandPanelEntrySourceService(CommandLinkedPanelEntryService linkedPanelEntryService,
                                   CommandPanelPreferenceService panelPreferenceService,
                                   CommandLinkPolicyService linkPolicyService,
                                   CommandNpcNameResolver npcNameResolver) {
        this(
                linkedPanelEntryService,
                panelPreferenceService,
                linkPolicyService,
                npcNameResolver,
                null,
                null,
                null
        );
    }

    CommandPanelEntrySourceService(CommandLinkedPanelEntryService linkedPanelEntryService,
                                   CommandPanelPreferenceService panelPreferenceService,
                                   CommandLinkPolicyService linkPolicyService,
                                   CommandNpcNameResolver npcNameResolver,
                                   @Nullable CommandRosterPanelRecordSource rosterRecordSource,
                                   @Nullable CommandPanelFeaturePresentationSource featurePresentations) {
        this(linkedPanelEntryService, panelPreferenceService, linkPolicyService,
                npcNameResolver, rosterRecordSource, featurePresentations,
                null);
    }

    CommandPanelEntrySourceService(CommandLinkedPanelEntryService linkedPanelEntryService,
                                   CommandPanelPreferenceService panelPreferenceService,
                                   CommandLinkPolicyService linkPolicyService,
                                   CommandNpcNameResolver npcNameResolver,
                                   @Nullable CommandRosterPanelRecordSource rosterRecordSource,
                                   @Nullable CommandPanelFeaturePresentationSource featurePresentations,
                                   @Nullable BondedCompanionPanelEntrySourceService bondedEntrySource) {
        this(linkedPanelEntryService, panelPreferenceService, linkPolicyService,
                npcNameResolver, rosterRecordSource, featurePresentations, bondedEntrySource, null);
    }

    CommandPanelEntrySourceService(CommandLinkedPanelEntryService linkedPanelEntryService,
                                   CommandPanelPreferenceService panelPreferenceService,
                                   CommandLinkPolicyService linkPolicyService,
                                   CommandNpcNameResolver npcNameResolver,
                                   @Nullable CommandRosterPanelRecordSource rosterRecordSource,
                                   @Nullable CommandPanelFeaturePresentationSource featurePresentations,
                                   @Nullable BondedCompanionPanelEntrySourceService bondedEntrySource,
                                   @Nullable CommandOwnedPanelRecordSource ownedRecordSource) {
        this(linkedPanelEntryService, panelPreferenceService, linkPolicyService, npcNameResolver,
                rosterRecordSource, featurePresentations, bondedEntrySource, ownedRecordSource,
                Tamework.getInstance() == null ? null : Tamework.getInstance().getOwnerPopulationLiveIndex());
    }

    CommandPanelEntrySourceService(CommandLinkedPanelEntryService linkedPanelEntryService,
                                   CommandPanelPreferenceService panelPreferenceService,
                                   CommandLinkPolicyService linkPolicyService,
                                   CommandNpcNameResolver npcNameResolver,
                                   @Nullable CommandRosterPanelRecordSource rosterRecordSource,
                                   @Nullable CommandPanelFeaturePresentationSource featurePresentations,
                                   @Nullable BondedCompanionPanelEntrySourceService bondedEntrySource,
                                   @Nullable CommandOwnedPanelRecordSource ownedRecordSource,
                                   @Nullable OwnerPopulationLiveIndex liveOwnerIndex) {
        this.liveOwnerIndex = liveOwnerIndex;
        this.ownedRecordSource = ownedRecordSource;
        this.linkedPanelEntryService = linkedPanelEntryService;
        this.panelPreferenceService = panelPreferenceService != null
                ? panelPreferenceService
                : new CommandPanelPreferenceService();
        this.linkPolicyService = linkPolicyService != null ? linkPolicyService : new CommandLinkPolicyService();
        this.npcNameResolver = npcNameResolver != null ? npcNameResolver : new CommandNpcNameResolver();
        this.loadedSnapshotService = new CommandLoadedNpcStatusSnapshotService(
                this.npcNameResolver,
                this.linkPolicyService,
                new CommandLinkedPanelProgressionPresentationService(),
                new CommandLinkedPanelCooldownSnapshotService()
        );
        this.rosterRecordSource = rosterRecordSource;
        this.featurePresentations = featurePresentations;
        this.bondedEntrySource = bondedEntrySource;
    }

    List<LinkedNpcEntry> buildEntries(Player player,
                                      Store<EntityStore> store,
                                      ItemStack stack,
                                      TwCommandItemConfig config,
                                      String toolId) {
        return buildEntries(
                player, store, stack, config, toolId, null, null, refreshInputs(player, store, stack, config)
        );
    }

    /**
     * Builds card data and roster actions from one immutable roster read.
     */
    CommandPanelSnapshot buildSnapshot(Player player,
                                       Store<EntityStore> store,
                                       ItemStack stack,
                                       TwCommandItemConfig config,
                                       String toolId) {
        return buildSnapshot(player, store, stack, config, toolId, null);
    }

    CommandPanelSnapshot buildSnapshot(Player player,
                                       Store<EntityStore> store,
                                       ItemStack stack,
                                       TwCommandItemConfig config,
                                       String toolId,
                                       @Nullable LinkedNpcPanelPageState pagination) {
        if (player != null && config != null
                && config.usesBondedCompanionRoster()
                && bondedEntrySource != null) {
            CommandPanelSnapshot durable = bondedEntrySource.buildSnapshot(
                    player, store, config.getBondedRosterId());
            return new CommandPanelSnapshot(
                    applyFiltersAndSort(durable.entries(), stack),
                    durable.featurePresentations(), durable.emptyStateKey());
        }
        RefreshInputs inputs = refreshInputs(player, store, stack, config);
        if (pagination != null && config != null
                && !config.usesOwnerCommandFamilyRoster() && !config.usesBondedCompanionRoster()) {
            return buildPagedOwnedSnapshot(player, store, stack, config, toolId, inputs, pagination);
        }
        CommandRosterPanelRecordSource.PanelSnapshot rosterSnapshot =
                resolveRosterSnapshot(player, config);
        CommandLinkedPanelEntryService.ResolvedEntries rosterEntries =
                resolveRosterEntries(
                        player, store, stack, config, toolId, rosterSnapshot
                );
        // Group selection describes the full roster even while the cards are text-filtered.
        ItemStack selectionStack = config != null
                && config.getRosterStorage() == TwCommandItemConfig.RosterStorage.ItemMetadata
                ? panelPreferenceService.applySelectedFilterText(stack, "") : stack;
        List<LinkedNpcEntry> selectionEntries = buildEntries(
                player, store, selectionStack, config, toolId, rosterSnapshot,
                rosterEntries, inputs
        );
        List<LinkedNpcEntry> entries = selectionStack == stack ? selectionEntries
                : applyFiltersAndSort(selectionEntries, stack);
        if (rosterSnapshot == null || featurePresentations == null
                || player == null || config == null) {
            var features = new java.util.HashMap<UUID, CommandPanelFeaturePresentation>();
            if (inputs.owned() != null) {
                features.putAll(inputs.owned().managedFeatures());
                var ownedIds = inputs.owned().ownedRecords()
                        .stream().map(record -> record.npcUuid).collect(java.util.stream.Collectors.toSet());
                for (var entry : entries) {
                    if (entry.captured() && !ownedIds.contains(entry.npcUuid()))
                        features.put(entry.npcUuid(), CommandPanelFeaturePresentation.readOnlyManaged());
                }
            }
            return new CommandPanelSnapshot(entries, features, null, selectionEntries);
        }
        String worldName = player.getWorld() == null
                ? null
                : player.getWorld().getName();
        Map<UUID, CommandPanelFeaturePresentation> features =
                featurePresentations.snapshotForMembers(
                        player.getUuid(),
                        worldName,
                        config.getCommandFamilyId(),
                        rosterSnapshot.members()
                );
        return new CommandPanelSnapshot(
                entries, CommandPanelFeatureRemapper.remap(features, rosterEntries)
        );
    }

    @Nullable
    BondedCompanionPanelEntrySourceService bondedReadModel() {
        return bondedEntrySource;
    }

    void warmBondedRoster(@Nullable UUID ownerUuid, @Nullable String rosterId) {
        if (bondedEntrySource != null) bondedEntrySource.warm(ownerUuid, rosterId);
    }

    /** Pages ordinary rosters before the detailed live and saved-card builders run. */
    private CommandPanelSnapshot buildPagedOwnedSnapshot(
            Player player, Store<EntityStore> store, ItemStack stack,
            TwCommandItemConfig config, String toolId, RefreshInputs inputs,
            LinkedNpcPanelPageState pagination
    ) {
        if (player == null || store == null || inputs.owned() == null) {
            pagination.setTotalEntries(0);
            return new CommandPanelSnapshot(List.of(), Map.of(), null, List.of());
        }
        List<LinkedNpcRecord> records = new ArrayList<>(inputs.owned().ownedRecords().size()
                + inputs.owned().capturedRecords().size());
        records.addAll(inputs.owned().ownedRecords());
        records.addAll(inputs.owned().capturedRecords());
        appendFreshOwnedRecords(player, store, records, inputs.linkedRecords());
        Set<UUID> linkedIds = linkedPanelEntryService.linkedRecordIdsForTool(inputs.linkedRecords(), toolId);
        CommandPanelPreferenceService.PanelSort sort = panelPreferenceService.resolveSort(stack);
        boolean includeCareValues = sort == CommandPanelPreferenceService.PanelSort.Happiness
                || sort == CommandPanelPreferenceService.PanelSort.Hunger
                || sort == CommandPanelPreferenceService.PanelSort.Thirst;
        List<LinkedNpcEntry> summaryEntries = linkedPanelEntryService
                .resolveOwnedEntrySummariesFromRecords(player, store, stack, records, linkedIds, includeCareValues);
        Map<UUID, String> profileKeys = profileKeys(records);
        summaryEntries = withOwnedGroups(summaryEntries, profileKeys);
        List<LinkedNpcEntry> selectionEntries = decorate(player, store, stack, config, summaryEntries, inputs);
        List<LinkedNpcEntry> legacyFiltered = applyFiltersAndSort(selectionEntries, stack);
        pagination.setRosterEntries(selectionEntries);
        List<LinkedNpcEntry> filtered = java.util.Arrays.asList(CommandCompanionViewStore.read(stack).current()
                .filter(legacyFiltered.toArray(LinkedNpcEntry[]::new)));
        pagination.setTotalEntries(filtered.size());
        List<LinkedNpcEntry> window = filtered.subList(pagination.startIndex(), pagination.endIndex());
        Set<UUID> pageIds = new HashSet<>(window.size());
        for (LinkedNpcEntry entry : window) pageIds.add(entry.npcUuid());
        List<LinkedNpcRecord> pageRecords = new ArrayList<>(pageIds.size());
        for (LinkedNpcRecord record : records) if (record != null && pageIds.contains(record.npcUuid)) pageRecords.add(record);
        CommandLinkedPanelEntryService.ResolvedEntries resolved = linkedPanelEntryService.resolveOwnedEntriesFromRecords(
                player, store, stack, toolId, pageRecords, linkedIds);
        List<LinkedNpcEntry> detailed = resolved.entries();
        detailed = decorate(player, store, stack, config,
                withOwnedGroups(detailed, profileKeys), inputs);
        // Detail resolution can suppress a stale live target after the lightweight pass.
        detailed = retainPagedOrder(detailed, resolved.renderedIds(), window);
        var features = new java.util.HashMap<UUID, CommandPanelFeaturePresentation>();
        features.putAll(inputs.owned().managedFeatures());
        Set<UUID> ownedIds = inputs.owned().ownedRecords().stream()
                .map(record -> record.npcUuid).collect(java.util.stream.Collectors.toSet());
        for (LinkedNpcEntry entry : detailed) {
            if (entry.captured() && !ownedIds.contains(entry.npcUuid())) {
                features.put(entry.npcUuid(), CommandPanelFeaturePresentation.readOnlyManaged());
            }
        }
        return new CommandPanelSnapshot(detailed, features, null, selectionEntries);
    }

    /** Keeps newly tamed indexed NPCs visible before their durable profile is published. */
    private void appendFreshOwnedRecords(Player player, Store<EntityStore> store,
                                         List<LinkedNpcRecord> records,
                                         List<LinkedNpcRecord> selectionRecords) {
        Set<UUID> known = new HashSet<>();
        for (LinkedNpcRecord record : records) if (record != null && record.npcUuid != null) known.add(record.npcUuid);
        for (Ref<EntityStore> ref : liveCandidates(player, store, true, new Vector3d(), 0.0)) {
            NPCEntity npc = ref == null || !ref.isValid() ? null : store.getComponent(ref, NPCEntity.getComponentType());
            if (npc == null || npc.getUuid() == null || !known.add(npc.getUuid())
                    || !CommandGenericTargetAuthority.allowsNearbyPresentation(ref, store)
                    || !linkPolicyService.passesOwnerAndTamed(true, false, ref, player.getUuid(), store)) continue;
            LinkedNpcRecord selection = linkedRecordStore.find(selectionRecords, npc.getUuid());
            records.add(new LinkedNpcRecord(npc.getUuid(), null, null, null, null,
                    npcNameResolver.resolveNpcDisplayName(ref, store, npc),
                    npcNameResolver.resolveNpcNameKey(npc), linkPolicyService.resolveRoleId(npc), null,
                    selection != null && selection.active,
                    selection != null && selection.breedingEnabled,
                    selection == null ? null : selection.groupId));
        }
    }

    private static Map<UUID, String> profileKeys(List<LinkedNpcRecord> records) {
        Map<UUID, String> keys = new java.util.HashMap<>();
        for (LinkedNpcRecord record : records) {
            if (record != null && record.npcUuid != null && record.profileId != null) {
                keys.put(record.npcUuid, CommandCompanionGroups.profileKey(record.profileId));
            }
        }
        return keys;
    }

    private static List<LinkedNpcEntry> withOwnedGroups(List<LinkedNpcEntry> entries,
                                                          Map<UUID, String> profileKeys) {
        List<LinkedNpcEntry> result = new ArrayList<>(entries.size());
        for (LinkedNpcEntry entry : entries) {
            result.add(entry.withOwnedActions().withCompanionGroups(
                    profileKeys.getOrDefault(entry.npcUuid(), CommandCompanionGroups.entityKey(entry.npcUuid())),
                    entry.groups(), entry.selectionSupported()));
        }
        return result;
    }

    private static List<LinkedNpcEntry> retainPagedOrder(List<LinkedNpcEntry> detailed,
                                                           Map<UUID, UUID> renderedIds,
                                                           List<LinkedNpcEntry> window) {
        Map<UUID, LinkedNpcEntry> byId = new java.util.HashMap<>();
        for (LinkedNpcEntry entry : detailed) byId.put(entry.npcUuid(), entry);
        List<LinkedNpcEntry> result = new ArrayList<>(window.size());
        for (LinkedNpcEntry summary : window) {
            LinkedNpcEntry entry = byId.get(renderedIds.getOrDefault(summary.npcUuid(), summary.npcUuid()));
            if (entry != null) result.add(entry);
        }
        return result;
    }

    private List<LinkedNpcEntry> buildEntries(Player player,
                                               Store<EntityStore> store,
                                               ItemStack stack,
                                               TwCommandItemConfig config,
                                               String toolId,
                                               @Nullable CommandRosterPanelRecordSource.PanelSnapshot rosterSnapshot,
                                               @Nullable CommandLinkedPanelEntryService.ResolvedEntries rosterEntries,
                                               RefreshInputs inputs) {
        boolean ownedMode = config != null && !config.usesOwnerCommandFamilyRoster() && !config.usesBondedCompanionRoster();
        List<LinkedNpcEntry> linkedEntries = ownedMode ? List.of() : config != null
                && config.usesOwnerCommandFamilyRoster()
                ? buildRosterEntries(
                        player, store, stack, config, toolId, rosterSnapshot,
                        rosterEntries
                )
                : linkedPanelEntryService.buildEntries(
                        player, store, stack, toolId, inputs.linkedRecords()
                );
        CommandPanelPreferenceService.PanelMode panelMode =
                panelPreferenceService.resolveEffectivePanelMode(stack, config);
        if (ownedMode) CommandCompanionGroups.importLegacy(player, stack);
        if (ownedMode) {
            Set<UUID> linkedIds = linkedPanelEntryService.linkedRecordIdsForTool(inputs.linkedRecords(), toolId);
            List<LinkedNpcRecord> ownedRecords = new ArrayList<>();
            if (inputs.owned() != null) {
                ownedRecords.addAll(inputs.owned().ownedRecords());
                ownedRecords.addAll(inputs.owned().capturedRecords());
            }
            List<LinkedNpcEntry> ownedEntries = linkedPanelEntryService.resolveOwnedEntriesFromRecords(
                    player, store, stack, toolId, ownedRecords, linkedIds).entries();
            Map<UUID, String> profileKeys = new java.util.HashMap<>();
            for (var record : ownedRecords) {
                if (record.profileId != null) profileKeys.put(record.npcUuid,
                        CommandCompanionGroups.profileKey(record.profileId));
            }
            linkedEntries = new ArrayList<>(ownedEntries.size());
            for (LinkedNpcEntry entry : ownedEntries) {
                linkedEntries.add(entry.withOwnedActions().withCompanionGroups(
                        profileKeys.getOrDefault(entry.npcUuid(), CommandCompanionGroups.entityKey(entry.npcUuid())),
                        entry.groups(), entry.selectionSupported()));
            }
        }
        if (!ownedMode && panelMode != CommandPanelPreferenceService.PanelMode.NearbyMode) {
            return applyFiltersAndSort(decorate(player, store, stack, config, linkedEntries, inputs), stack);
        }
        if (player == null || store == null) {
            return applyFiltersAndSort(decorate(player, store, stack, config, linkedEntries, inputs), stack);
        }
        UUID playerUuid = player.getUuid();
        Ref<EntityStore> playerRef = player.getReference();
        if (playerUuid == null || playerRef == null || !playerRef.isValid()) {
            return applyFiltersAndSort(decorate(player, store, stack, config, linkedEntries, inputs), stack);
        }
        TransformComponent playerTransform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (!ownedMode && playerTransform == null) {
            return applyFiltersAndSort(decorate(player, store, stack, config, linkedEntries, inputs), stack);
        }
        double radius = panelPreferenceService.resolveNearbyRadius(stack, config);
        if (!Double.isFinite(radius) || radius <= 0.0) {
            return applyFiltersAndSort(decorate(player, store, stack, config, linkedEntries, inputs), stack);
        }
        double radiusSq = ownedMode ? Double.POSITIVE_INFINITY : radius * radius;

        ArrayList<LinkedNpcEntry> out = new ArrayList<>(linkedEntries.size() + 16);
        Set<UUID> seen = new HashSet<>();
        for (LinkedNpcEntry entry : linkedEntries) {
            if (entry == null || entry.npcUuid() == null) {
                continue;
            }
            out.add(entry);
            seen.add(entry.npcUuid());
        }

        Vector3d playerPos = playerTransform == null ? new Vector3d() : new Vector3d(playerTransform.getPosition());
        boolean requireOwner = ownedMode || resolveLinkingRequireOwner();
        List<LinkedNpcRecord> selectionRecords = ownedMode ? inputs.linkedRecords() : List.of();
        for (Ref<EntityStore> npcRef : liveCandidates(player, store, ownedMode, playerPos, radius)) {
            if (npcRef == null || !npcRef.isValid()) continue;
            NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
            if (npc == null || npc.getUuid() == null || seen.contains(npc.getUuid())) {
                continue;
            }
            if (!CommandGenericTargetAuthority.allowsNearbyPresentation(
                    npcRef, store
            )) {
                continue;
            }
            if (!linkPolicyService.passesOwnerAndTamed(
                    requireOwner,
                    !ownedMode && config != null && config.isRequireTamed(),
                    npcRef,
                    playerUuid,
                    store
            )) {
                continue;
            }
            if (!ownedMode && !linkPolicyService.isRoleAllowed(linkPolicyService.resolveRoleId(npc), config)) {
                continue;
            }
            TransformComponent npcTransform = store.getComponent(npcRef, TransformComponent.getComponentType());
            if (npcTransform == null) {
                continue;
            }
            Vector3d npcPos = npcTransform.getPosition();
            double dx = npcPos.x - playerPos.x;
            double dy = npcPos.y - playerPos.y;
            double dz = npcPos.z - playerPos.z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > radiusSq) {
                continue;
            }
            String roleId = normalize(linkPolicyService.resolveRoleId(npc));
            // Newly tamed companions may not have an owned profile projection yet.
            LinkedNpcRecord selection = linkedRecordStore.find(selectionRecords, npc.getUuid());
            boolean linkedToTool = ownedMode ? selection != null : linkPolicyService.isLinkedToTool(
                    npcRef,
                    playerUuid,
                    toolId,
                    store
            );
            LinkedNpcEntry loadedEntry = loadedSnapshotService.buildLoadedEntry(
                    player,
                    npcRef,
                    store,
                    new CommandLoadedNpcStatusSnapshotService.NpcStatusContext(
                            npc.getUuid(),
                            npcNameResolver.resolveNpcDisplayName(npcRef, store, npc),
                            linkedToTool,
                            !ownedMode || selection != null && selection.active,
                            false,
                            false,
                            null,
                            null,
                            null,
                            roleId,
                            null
                    )
            );
            if (loadedEntry != null) {
                out.add(ownedMode ? loadedEntry.withOwnedActions() : loadedEntry);
                seen.add(npc.getUuid());
            }
        }
        return applyFiltersAndSort(decorate(player, store, stack, config, out, inputs), stack);
    }

    /** Candidates are resolved and consumed entirely on the viewer's world thread. */
    private List<Ref<EntityStore>> liveCandidates(Player player, Store<EntityStore> store,
                                                  boolean ownedMode, Vector3d center, double radius) {
        var result = new ArrayList<Ref<EntityStore>>();
        if (ownedMode) {
            var world = store.getExternalData().getWorld();
            if (liveOwnerIndex == null || world == null) return result;
            // The live owner index includes freshly tamed NPCs before durable profile publication.
            for (UUID id : liveOwnerIndex.ownedNpcIds(player.getUuid(), world.getName())) {
                var ref = world.getEntityRef(id);
                if (ref != null && ref.isValid()) result.add(ref);
            }
        } else {
            var spatial = store.getResource(EntityModule.get().getEntitySpatialResourceType());
            if (spatial != null) {
                // Own the list: card calculations can perform nested spatial queries on this thread.
                HytaleSpatialAccess.collect(spatial.getSpatialStructure(), center, radius, result);
            }
        }
        return result;
    }

    @Nullable
    private CommandRosterPanelRecordSource.PanelSnapshot resolveRosterSnapshot(
            @Nullable Player player,
            @Nullable TwCommandItemConfig config
    ) {
        if (player == null || config == null
                || !config.usesOwnerCommandFamilyRoster()
                || rosterRecordSource == null) {
            return null;
        }
        return rosterRecordSource.snapshotFor(
                player.getUuid(), config.getCommandFamilyId()
        );
    }

    private List<LinkedNpcEntry> buildRosterEntries(
            Player player,
            Store<EntityStore> store,
            ItemStack stack,
            TwCommandItemConfig config,
            String toolId,
            @Nullable CommandRosterPanelRecordSource.PanelSnapshot rosterSnapshot,
            @Nullable CommandLinkedPanelEntryService.ResolvedEntries rosterEntries
    ) {
        if (player == null || rosterRecordSource == null) {
            return List.of();
        }
        List<LinkedNpcRecord> records = rosterSnapshot != null
                ? rosterSnapshot.records()
                : rosterRecordSource.recordsFor(
                        player.getUuid(), config.getCommandFamilyId()
                );
        return rosterEntries != null
                ? rosterEntries.entries()
                : linkedPanelEntryService.buildEntriesFromRecords(
                        player, store, stack, toolId, records
                );
    }

    @Nullable
    private CommandLinkedPanelEntryService.ResolvedEntries resolveRosterEntries(
            @Nullable Player player,
            @Nullable Store<EntityStore> store,
            @Nullable ItemStack stack,
            @Nullable TwCommandItemConfig config,
            @Nullable String toolId,
            @Nullable CommandRosterPanelRecordSource.PanelSnapshot rosterSnapshot
    ) {
        if (player == null || store == null || stack == null
                || config == null || toolId == null || rosterSnapshot == null
                || !config.usesOwnerCommandFamilyRoster()) {
            return null;
        }
        return linkedPanelEntryService.resolveEntriesFromRecords(
                player, store, stack, toolId, rosterSnapshot.records()
        );
    }

    /** Immutable card and action-presentation result for one panel refresh. */
    record CommandPanelSnapshot(
            List<LinkedNpcEntry> entries,
            Map<UUID, CommandPanelFeaturePresentation> featurePresentations,
            @Nullable String emptyStateKey,
            List<LinkedNpcEntry> selectionEntries
    ) {
        CommandPanelSnapshot {
            entries = List.copyOf(entries);
            featurePresentations = Map.copyOf(featurePresentations);
            selectionEntries = List.copyOf(selectionEntries);
        }

        CommandPanelSnapshot(List<LinkedNpcEntry> entries,
                             Map<UUID, CommandPanelFeaturePresentation> featurePresentations,
                             @Nullable String emptyStateKey) {
            this(entries, featurePresentations, emptyStateKey, entries);
        }

        CommandPanelSnapshot(
                List<LinkedNpcEntry> entries,
                Map<UUID, CommandPanelFeaturePresentation> featurePresentations
        ) {
            this(entries, featurePresentations, null);
        }
    }

    @Nullable
    LinkedNpcRecord ownedSelectionRecord(Player player, ItemStack stack, UUID rowId) {
        if (player == null || rowId == null || player.getWorld() == null) return null;
        List<LinkedNpcRecord> records = linkedRecordStore.read(stack);
        LinkedNpcRecord owned = null;
        if (ownedRecordSource != null) {
            if (ownedRecordSource.managedFeatures(player.getUuid(), records).containsKey(rowId)) return null;
            String profile = ownedRecordSource.profileForRow(player.getUuid(), rowId).map(Object::toString).orElse(null);
            owned = ownedRecordSource.recordsFor(player.getUuid(), records).stream()
                    .filter(record -> rowId.equals(record.npcUuid) || profile != null && profile.equals(record.profileId))
                    .findFirst().orElse(null);
            if (owned != null && !rowId.equals(owned.npcUuid)) {
                owned = new LinkedNpcRecord(rowId, owned.profileId, owned.lastKnownPosition, owned.lastKnownWorldName,
                        owned.homePosition, owned.cachedDisplayName, owned.cachedNameKey, owned.cachedRoleId,
                        owned.cachedCommandState, owned.active, owned.breedingEnabled, owned.groupId);
            }
        }
        var world = player.getWorld();
        var ref = world.getEntityRef(rowId);
        if (ref == null || !ref.isValid()) return owned;
        var store = world.getEntityStore().getStore();
        if (!CommandGenericTargetAuthority.allowsGenericTargetMutation(ref, store)
                || !player.getUuid().equals(linkPolicyService.resolveOwnerId(ref, store))) return null;
        var npc = store.getComponent(ref, NPCEntity.getComponentType());
        if (npc == null) return null;
        var position = store.getComponent(ref, TransformComponent.getComponentType());
        var links = store.getComponent(ref, com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent.getComponentType());
        return new LinkedNpcRecord(rowId, owned == null ? null : owned.profileId,
                position == null ? null : new Vector3d(position.getPosition()),
                world.getName(), links == null ? null : links.getHomePosition(),
                npcNameResolver.resolveNpcDisplayNameFromComponents(ref, store),
                npcNameResolver.resolveNpcNameKey(npc), linkPolicyService.resolveRoleId(npc),
                owned == null ? null : owned.cachedCommandState, owned != null && owned.active,
                owned != null && owned.breedingEnabled, owned == null ? null : owned.groupId);
    }

    /** Called during panel refresh on the viewer's world thread; only this player's bounded inventory is read. */
    private static Set<String> carriedCaptureProfiles(Player player, Store<EntityStore> store) {
        Set<String> profiles = new HashSet<>();
        if (player == null || store == null || player.getReference() == null || !player.getReference().isValid()
                || com.hypixel.hytale.server.core.inventory.InventoryComponent.EVERYTHING == null) return profiles;
        for (var type : com.hypixel.hytale.server.core.inventory.InventoryComponent.EVERYTHING) {
            var inventory = store.getComponent(player.getReference(), type);
            if (inventory == null) continue;
            var container = inventory.getInventory();
            for (short slot = 0; slot < container.getCapacity(); slot++) {
                var capture = com.alechilles.alecstamework.items.locate.CapturedItemMetadata.read(container.getItemStack(slot));
                if (capture != null && capture.profileId() != null) profiles.add(capture.profileId());
            }
        }
        return profiles;
    }

    private record RefreshInputs(List<LinkedNpcRecord> linkedRecords,
                                 @Nullable CommandOwnedPanelRecordSource.Snapshot owned) { }

    private RefreshInputs refreshInputs(Player player, Store<EntityStore> store, ItemStack stack,
                                        TwCommandItemConfig config) {
        var records = linkedRecordStore.read(stack);
        var owned = ownedRecordSource != null && player != null && config != null
                && !config.usesOwnerCommandFamilyRoster() && !config.usesBondedCompanionRoster()
                ? ownedRecordSource.snapshot(player.getUuid(), records, carriedCaptureProfiles(player, store)) : null;
        return new RefreshInputs(records, owned);
    }

    private List<LinkedNpcEntry> decorate(Player player, Store<EntityStore> store, ItemStack stack,
                                         TwCommandItemConfig config, List<LinkedNpcEntry> entries, RefreshInputs inputs) {
        if (player == null || config == null || config.usesOwnerCommandFamilyRoster() || config.usesBondedCompanionRoster()) return entries;
        var groups = new CommandGroupService().readGroups(player, stack);
        var profiledKeys = new java.util.HashMap<UUID, String>();
        var savedRoles = new java.util.HashMap<UUID, String>();
        var ownedRecords = inputs.owned() == null ? inputs.linkedRecords() : inputs.owned().ownedRecords();
        for (var record : ownedRecords) savedRoles.put(record.npcUuid, record.cachedRoleId);
        for (var record : inputs.linkedRecords()) {
            if (record.profileId != null) profiledKeys.put(record.npcUuid, CommandCompanionGroups.profileKey(record.profileId));
        }
        var world = player.getWorld();
        var viewer = world == null ? null : world.getEntityRef(player.getUuid());
        var origin = viewer == null || !viewer.isValid() ? null : store.getComponent(viewer, TransformComponent.getComponentType());
        double radius = panelPreferenceService.resolveNearbyRadius(stack, config);
        List<LinkedNpcEntry> decorated = new ArrayList<>(entries.size());
        var profilesByRow = inputs.owned() == null ? Map.<UUID, com.alechilles.alecstamework.companion.identity.ProfileId>of()
                : inputs.owned().profilesByRow();
        for (var entry : entries) {
            var profileId = profilesByRow.get(entry.npcUuid());
            String key = ownedRecordSource == null ? CommandCompanionGroups.entityKey(entry.npcUuid())
                    : profileId == null ? entry.companionKey()
                    : CommandCompanionGroups.profileKey(profileId.toString());
            key = profiledKeys.getOrDefault(entry.npcUuid(), key);
            CommandCompanionGroups.promoteProfile(player, key, entry.npcUuid());
            var memberships = CommandCompanionGroups.groups(player, key, entry.npcUuid());
            var tags = groups.stream().filter(g -> memberships.contains(g.groupId))
                    .map(g -> new LinkedNpcEntry.GroupMembership(g.groupId, g.name, g.colorHex)).toList();
            var ref = world == null ? null : world.getEntityRef(entry.npcUuid());
            var position = ref == null || !ref.isValid() ? null : store.getComponent(ref, TransformComponent.getComponentType());
            boolean nearby = origin != null && position != null && origin.getPosition().distanceSquared(position.getPosition()) <= radius * radius;
            var npc = ref == null || !ref.isValid() ? null : store.getComponent(ref, NPCEntity.getComponentType());
            String roleId = npc == null ? savedRoles.get(entry.npcUuid()) : linkPolicyService.resolveRoleId(npc);
            decorated.add(entry.withCompanionGroups(key, tags, (!entry.captured() || savedRoles.containsKey(entry.npcUuid()))
                    && linkPolicyService.isRoleAllowed(roleId, config, true)).withNearby(nearby));
        }
        return decorated;
    }

    private boolean resolveLinkingRequireOwner() {
        return resolveLinkingRequireOwner(TwGlobalConfig.resolveActive());
    }

    static boolean resolveLinkingRequireOwner(TwGlobalConfig globalConfig) {
        TwGlobalConfig resolved = globalConfig != null ? globalConfig : TwGlobalConfig.defaultConfig();
        return TameworkRuntimeSettings.linkingRequiresOwner(resolved.isOwnershipLinkingRequiresOwner());
    }

    private List<LinkedNpcEntry> applyFiltersAndSort(List<LinkedNpcEntry> input, ItemStack stack) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        String nameFilter = normalize(panelPreferenceService.resolveNameFilter(stack));
        String speciesFilter = normalize(panelPreferenceService.resolveSpeciesFilter(stack));
        String groupFilter = normalize(panelPreferenceService.resolveGroupFilter(stack));
        ArrayList<LinkedNpcEntry> filtered = new ArrayList<>(input.size());
        for (LinkedNpcEntry entry : input) {
            if (entry == null || entry.npcUuid() == null) {
                continue;
            }
            if (nameFilter != null && !matchesContains(entry.displayName(), nameFilter)
                    && !matchesContains(entry.speciesLabel(), nameFilter)
                    && !matchesContains(entry.speciesId(), nameFilter)
                    && !matchesContains(entry.groupName(), nameFilter)) {
                continue;
            }
            if (!matchesContains(firstNonBlank(entry.speciesLabel(), entry.speciesId()), speciesFilter)) {
                continue;
            }
            if (!matchesContains(firstNonBlank(entry.groupName(), entry.groupId()), groupFilter)) {
                continue;
            }
            filtered.add(entry);
        }
        CommandPanelPreferenceService.PanelSort sort = panelPreferenceService.resolveSort(stack);
        if (sort == CommandPanelPreferenceService.PanelSort.Default) {
            filtered.sort(Comparator.comparingInt((LinkedNpcEntry entry) -> entry.active() ? 0 : 1)
                    .thenComparingInt(entry -> entry.dead() ? 5 : entry.lost() ? 4
                            : entry.captured() || entry.inCoop() ? 3 : entry.loaded() ? 0 : 1));
            return filtered;
        }
        Comparator<LinkedNpcEntry> comparator = buildComparator(sort);
        filtered.sort(comparator);
        return filtered;
    }

    static Comparator<LinkedNpcEntry> buildComparator(CommandPanelPreferenceService.PanelSort sort) {
        Comparator<LinkedNpcEntry> base =
                Comparator.comparing((LinkedNpcEntry value) -> value.active() ? 0 : 1);
        Comparator<LinkedNpcEntry> byName = Comparator
                .comparing((LinkedNpcEntry value) -> safe(value.displayName()), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(value -> value.npcUuid().toString());
        if (sort == null) {
            return base.thenComparing(byName);
        }
        // Selected companions remain first for every sort.
        if (sort == CommandPanelPreferenceService.PanelSort.Happiness) {
            return base.thenComparingDouble((LinkedNpcEntry value) ->
                    careRatio(value.currentHappiness(), value.maxHappiness())).thenComparing(byName);
        }
        if (sort == CommandPanelPreferenceService.PanelSort.Hunger) {
            return base.thenComparingDouble((LinkedNpcEntry value) ->
                    careRatio(value.currentHunger(), value.maxHunger())).thenComparing(byName);
        }
        if (sort == CommandPanelPreferenceService.PanelSort.Thirst) {
            return base.thenComparingDouble((LinkedNpcEntry value) ->
                    careRatio(value.currentThirst(), value.maxThirst())).thenComparing(byName);
        }
        if (sort == CommandPanelPreferenceService.PanelSort.Name) {
            return base.thenComparing(byName);
        }
        if (sort == CommandPanelPreferenceService.PanelSort.Species) {
            return base
                    .thenComparing(
                            (LinkedNpcEntry value) -> safe(firstNonBlank(value.speciesLabel(), value.speciesId())),
                            String.CASE_INSENSITIVE_ORDER
                    )
                    .thenComparing(byName);
        }
        return base
                .thenComparing(
                        (LinkedNpcEntry value) -> safe(firstNonBlank(value.groupName(), value.groupId())),
                        String.CASE_INSENSITIVE_ORDER
                )
                .thenComparing(byName);
    }

    private static double careRatio(int current, int maximum) {
        return maximum <= 0 ? Double.POSITIVE_INFINITY
                : Math.max(0.0, Math.min(1.0, (double) current / maximum));
    }

    private List<LinkedNpcEntry> partitionByActive(List<LinkedNpcEntry> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        ArrayList<LinkedNpcEntry> active = new ArrayList<>(input.size());
        ArrayList<LinkedNpcEntry> inactive = new ArrayList<>(input.size());
        for (LinkedNpcEntry entry : input) {
            if (entry == null) {
                continue;
            }
            if (entry.active()) {
                active.add(entry);
            } else {
                inactive.add(entry);
            }
        }
        if (inactive.isEmpty()) {
            return active;
        }
        if (active.isEmpty()) {
            return inactive;
        }
        active.addAll(inactive);
        return active;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean matchesContains(String candidate, String filterNormalized) {
        if (filterNormalized == null || filterNormalized.isBlank()) {
            return true;
        }
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        return candidate.toLowerCase(Locale.ROOT).contains(filterNormalized);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

}
