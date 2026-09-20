package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
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
                player, store, stack, config, toolId, null, null
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
        if (player != null && config != null
                && config.usesBondedCompanionRoster()
                && bondedEntrySource != null) {
            CommandPanelSnapshot durable = bondedEntrySource.buildSnapshot(
                    player, store, config.getBondedRosterId());
            return new CommandPanelSnapshot(
                    applyFiltersAndSort(durable.entries(), stack),
                    durable.featurePresentations(), durable.emptyStateKey());
        }
        CommandRosterPanelRecordSource.PanelSnapshot rosterSnapshot =
                resolveRosterSnapshot(player, config);
        CommandLinkedPanelEntryService.ResolvedEntries rosterEntries =
                resolveRosterEntries(
                        player, store, stack, config, toolId, rosterSnapshot
                );
        List<LinkedNpcEntry> entries = buildEntries(
                player, store, stack, config, toolId, rosterSnapshot,
                rosterEntries
        );
        if (rosterSnapshot == null || featurePresentations == null
                || player == null || config == null) {
            var features = new java.util.HashMap<UUID, CommandPanelFeaturePresentation>();
            if (ownedRecordSource != null && player != null) {
                features.putAll(ownedRecordSource.managedFeatures(player.getUuid(), linkedRecordStore.read(stack)));
                var ownedIds = ownedRecordSource.recordsFor(player.getUuid(), linkedRecordStore.read(stack))
                        .stream().map(record -> record.npcUuid).collect(java.util.stream.Collectors.toSet());
                for (var entry : entries) {
                    if (entry.captured() && !ownedIds.contains(entry.npcUuid()))
                        features.put(entry.npcUuid(), CommandPanelFeaturePresentation.readOnlyManaged());
                }
            }
            return new CommandPanelSnapshot(entries, features);
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

    private List<LinkedNpcEntry> buildEntries(Player player,
                                               Store<EntityStore> store,
                                               ItemStack stack,
                                               TwCommandItemConfig config,
                                               String toolId,
                                               @Nullable CommandRosterPanelRecordSource.PanelSnapshot rosterSnapshot,
                                               @Nullable CommandLinkedPanelEntryService.ResolvedEntries rosterEntries) {
        List<LinkedNpcEntry> linkedEntries = config != null
                && config.usesOwnerCommandFamilyRoster()
                ? buildRosterEntries(
                        player, store, stack, config, toolId, rosterSnapshot,
                        rosterEntries
                )
                : linkedPanelEntryService.buildEntries(
                        player, store, stack, toolId
                );
        CommandPanelPreferenceService.PanelMode panelMode =
                panelPreferenceService.resolveEffectivePanelMode(stack, config);
        boolean ownedMode = config != null && !config.usesOwnerCommandFamilyRoster() && !config.usesBondedCompanionRoster();
        if (ownedMode) CommandCompanionGroups.importLegacy(player, stack);
        if (ownedMode) {
            Map<UUID, LinkedNpcEntry> linkedById = new java.util.HashMap<>();
            for (LinkedNpcEntry entry : linkedEntries) linkedById.put(entry.npcUuid(), entry);
            List<LinkedNpcRecord> linkedRecords = rosterSnapshot == null
                    ? linkedRecordStore.read(stack) : rosterSnapshot.records();
            List<LinkedNpcRecord> ownedRecords = ownedRecordSource == null || player == null
                    ? List.of() : ownedRecordSource.recordsFor(player.getUuid(), linkedRecords);
            if (ownedRecordSource != null) {
                var visibleRecords = new ArrayList<>(ownedRecords);
                visibleRecords.addAll(ownedRecordSource.capturedRecordsFor(linkedRecords, carriedCaptureProfiles(player, store)));
                ownedRecords = visibleRecords;
            }
            List<LinkedNpcEntry> ownedEntries = linkedPanelEntryService.resolveOwnedEntriesFromRecords(
                    player, store, stack, toolId, ownedRecords, linkedById.keySet()).entries();
            linkedEntries = new ArrayList<>(ownedEntries.size());
            for (LinkedNpcEntry entry : ownedEntries) {
                linkedEntries.add(entry.withOwnedActions());
            }
        }
        if (!ownedMode && panelMode != CommandPanelPreferenceService.PanelMode.NearbyMode) {
            return applyFiltersAndSort(decorate(player, store, stack, config, linkedEntries), stack);
        }
        if (player == null || store == null) {
            return applyFiltersAndSort(decorate(player, store, stack, config, linkedEntries), stack);
        }
        UUID playerUuid = player.getUuid();
        Ref<EntityStore> playerRef = player.getReference();
        if (playerUuid == null || playerRef == null || !playerRef.isValid()) {
            return applyFiltersAndSort(decorate(player, store, stack, config, linkedEntries), stack);
        }
        TransformComponent playerTransform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (!ownedMode && playerTransform == null) {
            return applyFiltersAndSort(decorate(player, store, stack, config, linkedEntries), stack);
        }
        double radius = panelPreferenceService.resolveNearbyRadius(stack, config);
        if (!Double.isFinite(radius) || radius <= 0.0) {
            return applyFiltersAndSort(decorate(player, store, stack, config, linkedEntries), stack);
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
        List<LinkedNpcRecord> selectionRecords = ownedMode ? linkedRecordStore.read(stack) : List.of();
        store.forEachChunk(Query.any(), (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
            for (int i = 0; i < chunk.size(); i++) {
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                if (npc == null || npc.getUuid() == null || seen.contains(npc.getUuid())) {
                    continue;
                }
                Ref<EntityStore> npcRef = chunk.getReferenceTo(i);
                if (npcRef == null || !npcRef.isValid()) {
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
                TransformComponent npcTransform = chunk.getComponent(i, TransformComponent.getComponentType());
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
        });
        return applyFiltersAndSort(decorate(player, store, stack, config, out), stack);
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
            @Nullable String emptyStateKey
    ) {
        CommandPanelSnapshot {
            entries = List.copyOf(entries);
            featurePresentations = Map.copyOf(featurePresentations);
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

    private List<LinkedNpcEntry> decorate(Player player, Store<EntityStore> store, ItemStack stack,
                                         TwCommandItemConfig config, List<LinkedNpcEntry> entries) {
        if (player == null || config == null || config.usesOwnerCommandFamilyRoster() || config.usesBondedCompanionRoster()) return entries;
        var groups = new CommandGroupService().readGroups(player, stack);
        var profiledKeys = new java.util.HashMap<UUID, String>();
        var savedRoles = new java.util.HashMap<UUID, String>();
        var ownedRecords = ownedRecordSource == null ? linkedRecordStore.read(stack)
                : ownedRecordSource.recordsFor(player.getUuid(), linkedRecordStore.read(stack));
        for (var record : ownedRecords) savedRoles.put(record.npcUuid, record.cachedRoleId);
        for (var record : linkedRecordStore.read(stack)) {
            if (record.profileId != null) profiledKeys.put(record.npcUuid, CommandCompanionGroups.profileKey(record.profileId));
        }
        var world = player.getWorld();
        var viewer = world == null ? null : world.getEntityRef(player.getUuid());
        var origin = viewer == null || !viewer.isValid() ? null : store.getComponent(viewer, TransformComponent.getComponentType());
        double radius = panelPreferenceService.resolveNearbyRadius(stack, config);
        List<LinkedNpcEntry> decorated = new ArrayList<>(entries.size());
        for (var entry : entries) {
            String key = ownedRecordSource == null ? CommandCompanionGroups.entityKey(entry.npcUuid())
                    : ownedRecordSource.profileForRow(player.getUuid(), entry.npcUuid())
                    .map(id -> CommandCompanionGroups.profileKey(id.toString()))
                    .orElse(CommandCompanionGroups.entityKey(entry.npcUuid()));
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
