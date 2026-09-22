package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.locate.CapturedItemTracker;
import com.alechilles.alecstamework.items.locate.CapturedItemLocationIndex;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.ui.TameworkLinkedNpcLocationFormatter;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwDynamicIconConfig;
import com.alechilles.alecstamework.localization.RoleNameResolver;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.progression.HappinessConfigResolver;
import com.alechilles.alecstamework.npc.progression.NeedsConfigResolver;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.LinkedNpcTraitIndicator;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Builds linked-companion panel entries for command-item UI.
 *
 * <p>This service isolates panel-oriented data assembly (loaded/dead/captured status, display names,
 * health snapshots, and home flags) from command orchestration flows.
 */
final class CommandLinkedPanelEntryService {
    private final CommandLinkedNpcRecordStore linkedNpcRecordStore;
    private final CommandNpcRelocationService relocationService;
    private final CommandNpcNameResolver npcNameResolver;
    private final CommandLinkedNpcStateSnapshotService stateSnapshotService;
    private final CommandLinkedPanelUnloadedNameService unloadedNameService;
    private final CommandLinkPolicyService linkPolicyService;
    private final CommandGroupService groupService;
    private final CommandLinkedPanelProgressionPresentationService progressionPresentationService;
    private final CommandLinkedPanelCooldownSnapshotService cooldownSnapshotService;
    private final CommandLoadedNpcStatusSnapshotService loadedSnapshotService;
    private final CommandLinkedPanelLiveTargetResolver liveTargetResolver;
    private final CommandPersistenceView persistenceView;
    private CapturedItemTracker itemTracker;

    void configureItemLocations(CapturedItemTracker tracker) {
        itemTracker = tracker;
    }

    CommandLinkedPanelEntryService(CommandLinkedNpcRecordStore linkedNpcRecordStore,
                                   CommandNpcRelocationService relocationService,
                                   CommandNpcNameResolver npcNameResolver,
                                   @Nullable CommandLinkedNpcStateSnapshotService stateSnapshotService,
                                   @Nullable CommandPersistenceView persistenceView,
                                   CommandLinkPolicyService linkPolicyService,
                                   CommandGroupService groupService,
                                   @Nullable CommandNpcProfileActionResolver profileActionResolver) {
        this.linkedNpcRecordStore = linkedNpcRecordStore;
        this.relocationService = relocationService;
        this.npcNameResolver = npcNameResolver;
        this.stateSnapshotService = stateSnapshotService;
        this.unloadedNameService = new CommandLinkedPanelUnloadedNameService(
                this.npcNameResolver, stateSnapshotService, persistenceView
        );
        this.persistenceView = persistenceView;
        this.linkPolicyService = linkPolicyService != null ? linkPolicyService : new CommandLinkPolicyService();
        this.groupService = groupService != null ? groupService : new CommandGroupService();
        this.progressionPresentationService = new CommandLinkedPanelProgressionPresentationService();
        this.cooldownSnapshotService = new CommandLinkedPanelCooldownSnapshotService();
        this.loadedSnapshotService = new CommandLoadedNpcStatusSnapshotService(
                this.npcNameResolver,
                this.linkPolicyService,
                this.progressionPresentationService,
                this.cooldownSnapshotService
        );
        this.liveTargetResolver = profileActionResolver == null
                ? null
                : new CommandLinkedPanelLiveTargetResolver(profileActionResolver);
    }

    List<LinkedNpcEntry> buildEntries(Player player,
                                      Store<EntityStore> store,
                                      ItemStack stack,
                                      String toolId) {
        if (player == null || store == null || stack == null || stack.isEmpty()) {
            return List.of();
        }
        return buildEntries(player, store, stack, toolId, linkedNpcRecordStore.read(stack));
    }

    List<LinkedNpcEntry> buildEntries(Player player, Store<EntityStore> store, ItemStack stack,
                                     String toolId, List<LinkedNpcRecord> records) {
        if (player == null || store == null || stack == null || stack.isEmpty()) return List.of();
        if (persistenceView != null) {
            records = persistenceView.linkedRecordsForTool(records, toolId);
        }
        return buildEntriesFromRecords(player, store, stack, toolId, records);
    }

    /** Selection membership does not require constructing live status cards a second time. */
    java.util.Set<UUID> linkedRecordIdsForTool(List<LinkedNpcRecord> records, String toolId) {
        var canonical = persistenceView == null ? records : persistenceView.linkedRecordsForTool(records, toolId);
        var ids = new java.util.HashSet<UUID>();
        for (var record : canonical) ids.add(record.npcUuid);
        return ids;
    }

    /** Builds from canonical records when item metadata is merely a disposable projection. */
    List<LinkedNpcEntry> buildEntriesFromRecords(Player player,
                                                 Store<EntityStore> store,
                                                 ItemStack stack,
                                                 String toolId,
                                                 List<LinkedNpcRecord> records) {
        return resolveEntriesFromRecords(
                player, store, stack, toolId, records
        ).entries();
    }

    /**
     * Builds cards and records the current live UUID for every presentation
     * UUID that had to redirect through canonical profile identity.
     */
    ResolvedEntries resolveEntriesFromRecords(Player player,
                                              Store<EntityStore> store,
                                              ItemStack stack,
                                              String toolId,
                                              List<LinkedNpcRecord> records) {
        return resolveEntriesFromRecords(player, store, stack, toolId, records, null);
    }

    ResolvedEntries resolveOwnedEntriesFromRecords(Player player, Store<EntityStore> store,
            ItemStack stack, String toolId, List<LinkedNpcRecord> records,
            java.util.Set<UUID> linkedIds) {
        return resolveEntriesFromRecords(player, store, stack, toolId, records, linkedIds);
    }

    /**
     * Produces only the fields needed to filter, sort, and page an owned roster.
     * It deliberately avoids saved-card application and live progression, cooldown,
     * trait, portrait, and happiness presentation work.  The selected records are
     * subsequently resolved through {@link #resolveOwnedEntriesFromRecords}.
     */
    List<LinkedNpcEntry> resolveOwnedEntrySummariesFromRecords(
            Player player, Store<EntityStore> store, ItemStack stack,
            List<LinkedNpcRecord> records, java.util.Set<UUID> linkedIds,
            boolean includeCareValues
    ) {
        if (player == null || store == null || stack == null || stack.isEmpty() || records.isEmpty()) {
            return List.of();
        }
        Map<String, CommandGroupService.GroupRecord> groupById = buildGroupLookup(stack);
        World world = player.getWorld();
        ArrayList<LinkedNpcEntry> entries = new ArrayList<>(records.size());
        for (LinkedNpcRecord record : records) {
            if (record == null || record.npcUuid == null) continue;
            Ref<EntityStore> liveRef = world == null ? null : world.getEntityRef(record.npcUuid);
            if (liveRef != null && liveRef.isValid()
                    && (!CommandGenericTargetAuthority.allowsNearbyPresentation(liveRef, store)
                    || !linkPolicyService.passesOwnerAndTamed(true, false, liveRef, player.getUuid(), store))) {
                continue;
            }
            CommandPersistenceView.ProfileSnapshot profile = persistenceView == null
                    ? null : persistenceView.find(record).orElse(null);
            boolean dead = profile != null && profile.dead();
            boolean captured = profile != null && profile.captured();
            boolean inCoop = profile != null && profile.inCoop();
            boolean lost = profile != null && profile.lost();
            NPCEntity liveNpc = liveRef == null || !liveRef.isValid() ? null
                    : safeGetComponent(store, liveRef, NPCEntity.getComponentType());
            String role = firstNonBlank(liveNpc == null ? null : linkPolicyService.resolveRoleId(liveNpc),
                    profile == null ? null : profile.roleId(), firstNonBlank(record.cachedRoleId,
                            RoleNameResolver.extractRoleIdFromNameKey(record.cachedNameKey), null));
            String displayName = firstNonBlank(liveNpc == null ? null : npcNameResolver.resolveNpcDisplayName(liveRef, store, liveNpc),
                    profile == null ? null : profile.customName(), firstNonBlank(
                            profile == null ? null : npcNameResolver.resolveSnapshotDisplayName(
                                    profile.displayName(), record.cachedNameKey, profile.roleId()),
                            unloadedNameService.resolve(record), null));
            String groupId = normalizeOptional(record.groupId);
            CommandGroupService.GroupRecord group = resolveGroup(groupById, groupId);
            String groupName = group == null ? groupId : group.name;
            String groupColor = group == null ? null : group.colorHex;
            boolean loaded = liveNpc != null
                    && !dead && !captured && !inCoop;
            CareValues care = !includeCareValues ? CareValues.EMPTY
                    : loaded ? rawCareValues(liveRef, store) : savedCareValues(record, player, role);
            long deadRemaining = dead && profile != null ? remainingUntil(profile.restorationAvailableAtMs(), System.currentTimeMillis()) : 0L;
            if (dead && !TameworkRuntimeSettings.reviveSystemEnabled(
                    TwCompanionConfig.resolveEffectiveForRole(record.cachedRoleId).isDeadRespawnEnabled())) deadRemaining = -1L;
            entries.add(new LinkedNpcEntry(record.npcUuid, displayName,
                    0, 0, care.happiness, care.maxHappiness, 0, null,
                    care.hunger, care.maxHunger, care.thirst, care.maxThirst,
                    loaded, record.homePosition != null, dead, captured, inCoop, lost, deadRemaining,
                    null, null, null, LinkedNpcTraitIndicator.EMPTY,
                    false, false, false, false, linkedIds.contains(record.npcUuid), record.active,
                    normalize(role), npcNameResolver.resolveRoleDisplayName(role, record.cachedNameKey),
                    groupId, groupName, groupColor, record.breedingEnabled,
                    false, 0L, 0.0, false));
        }
        return entries;
    }

    /** Reads scalar care state for sort ordering without resolving modifiers or nearby populations. */
    private CareValues rawCareValues(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        TameworkHappinessComponent happiness = safeGetComponent(store, npcRef,
                TameworkHappinessComponent.getComponentType());
        TameworkNeedsComponent needs = safeGetComponent(store, npcRef,
                TameworkNeedsComponent.getComponentType());
        TwHappinessConfig happinessConfig = HappinessConfigResolver.resolveConfig(npcRef, store, happiness);
        TwNeedsConfig needsConfig = NeedsConfigResolver.resolveConfig(npcRef, store, needs);
        boolean happinessEnabled = HappinessConfigResolver.isRuntimeEnabled(happinessConfig);
        boolean needsEnabled = needs != null && NeedsConfigResolver.isRuntimeEnabled(needsConfig);
        double happinessMin = happinessEnabled ? Math.min(happinessConfig.getValues().getMin(), happinessConfig.getValues().getMax()) : 0;
        double happinessMax = happinessEnabled ? Math.max(happinessConfig.getValues().getMin(), happinessConfig.getValues().getMax()) : 0;
        double currentHappiness = happiness == null ? Double.NaN : happiness.getValue();
        if (happinessEnabled && !Double.isFinite(currentHappiness)) {
            var breeding = safeGetComponent(store, npcRef,
                    com.alechilles.alecstamework.npc.components.TameworkBreedingComponent.getComponentType());
            currentHappiness = breeding != null && Double.isFinite(breeding.getHappiness())
                    ? breeding.getHappiness() : happinessConfig.getValues().getCurrentDefault();
        }
        int maxHappiness = happinessEnabled ? Math.max(1, Math.round((float) happinessMax)) : 0;
        int maxHunger = needsEnabled ? Math.max(1, Math.round((float) needsConfig.getValues().getHungerMax())) : 0;
        int maxThirst = needsEnabled ? Math.max(1, Math.round((float) needsConfig.getValues().getThirstMax())) : 0;
        return new CareValues(
                clampCare(Math.max(happinessMin, Math.min(happinessMax, currentHappiness)), maxHappiness), maxHappiness,
                clampCare(needsEnabled ? Math.max(needsConfig.getValues().getHungerMin(),
                        Math.min(needsConfig.getValues().getHungerMax(), needs.getHunger())) : 0, maxHunger), maxHunger,
                clampCare(needsEnabled ? Math.max(needsConfig.getValues().getThirstMin(),
                        Math.min(needsConfig.getValues().getThirstMax(), needs.getThirst())) : 0, maxThirst), maxThirst);
    }

    private CareValues savedCareValues(LinkedNpcRecord record, Player player, @Nullable String role) {
        CommandSavedNpcPanelSnapshot saved = persistenceView == null ? null
                : persistenceView.savedPanel(record, player.getUuid());
        CommandSavedNpcPanelSnapshot.CareSnapshot care = saved == null ? null : saved.careSnapshot(role);
        return care == null ? CareValues.EMPTY : new CareValues(care.happiness(), care.maxHappiness(),
                care.hunger(), care.maxHunger(), care.thirst(), care.maxThirst());
    }

    private static int clampCare(double value, int maximum) {
        return maximum <= 0 || !Double.isFinite(value) ? 0
                : Math.max(0, Math.min(maximum, Math.round((float) value)));
    }

    private record CareValues(int happiness, int maxHappiness, int hunger, int maxHunger,
                              int thirst, int maxThirst) {
        private static final CareValues EMPTY = new CareValues(0, 0, 0, 0, 0, 0);
    }

    private ResolvedEntries resolveEntriesFromRecords(Player player, Store<EntityStore> store,
            ItemStack stack, String toolId, List<LinkedNpcRecord> records,
            @Nullable java.util.Set<UUID> ownedViewLinkedIds) {
        if (player == null || store == null || stack == null || stack.isEmpty()) {
            return ResolvedEntries.empty();
        }
        if (records.isEmpty()) {
            return ResolvedEntries.empty();
        }
        Map<String, CommandGroupService.GroupRecord> groupById = buildGroupLookup(stack);
        World world = player.getWorld();
        // Panel assembly runs on the viewer's world thread. Keep only position scalars for its rows.
        Ref<EntityStore> viewerRef = world == null ? null : world.getEntityRef(player.getUuid());
        TransformComponent viewerTransform = viewerRef == null || !viewerRef.isValid() ? null
                : store.getComponent(viewerRef, TransformComponent.getComponentType());
        double viewerX = viewerTransform == null ? Double.NaN : viewerTransform.getPosition().x;
        double viewerZ = viewerTransform == null ? Double.NaN : viewerTransform.getPosition().z;
        ArrayList<LinkedNpcEntry> entries = new ArrayList<>(records.size());
        Map<UUID, UUID> renderedIds = new LinkedHashMap<>();
        for (LinkedNpcRecord record : records) {
            if (record == null || record.npcUuid == null) {
                continue;
            }
            boolean linked = ownedViewLinkedIds == null || ownedViewLinkedIds.contains(record.npcUuid);
            if (ownedViewLinkedIds != null && world != null) {
                Ref<EntityStore> liveRef = world.getEntityRef(record.npcUuid);
                if (liveRef != null && liveRef.isValid()
                        && (!CommandGenericTargetAuthority.allowsNearbyPresentation(liveRef, store)
                        || !linkPolicyService.passesOwnerAndTamed(true, false,
                                liveRef, player.getUuid(), store))) continue;
            }
            boolean loaded = false;
            boolean dead = false;
            boolean captured = false;
            boolean inCoop = false;
            boolean lost = false;
            long deadRespawnRemainingMs = 0L;
            String deathCauseHint = null;
            boolean hasHome = record.homePosition != null;
            boolean active = record.active;
            String groupId = normalizeOptional(record.groupId);
            CommandGroupService.GroupRecord resolvedGroup = resolveGroup(groupById, groupId);
            String groupName = resolvedGroup != null
                    ? resolvedGroup.name
                    : groupId;
            String groupColor = resolvedGroup != null
                    ? resolvedGroup.colorHex
                    : null;
            String displayName = unloadedNameService.resolve(record);
            if (displayName == null || displayName.isBlank()) {
                displayName = "Unloaded companion (" + abbreviateUuid(record.npcUuid) + ")";
            }
            CommandPersistenceView.ProfileSnapshot canonicalProfile =
                    persistenceView == null
                            ? null
                            : persistenceView.find(record).orElse(null);
            if (canonicalProfile != null) {
                dead = canonicalProfile.dead();
                captured = canonicalProfile.captured();
                inCoop = canonicalProfile.inCoop();
                lost = canonicalProfile.lost();
                String canonicalDisplayName =
                        npcNameResolver.resolveSnapshotDisplayName(
                                canonicalProfile.displayName(),
                                record.cachedNameKey,
                                canonicalProfile.roleId()
                        );
                displayName = firstNonBlank(
                        canonicalProfile.customName(),
                        canonicalDisplayName,
                        displayName
                );
            }
            String customName = firstNonBlank(
                    canonicalProfile == null ? null : canonicalProfile.customName(),
                    resolveSnapshotCustomName(record),
                    null
            );
            String gender = null;
            String speciesRoleId = firstNonBlank(
                    canonicalProfile == null
                            ? null : canonicalProfile.roleId(),
                    record.cachedRoleId,
                    RoleNameResolver.extractRoleIdFromNameKey(
                            record.cachedNameKey
                    )
            );
            String speciesId = normalize(speciesRoleId);
            String speciesLabel = npcNameResolver.resolveRoleDisplayName(
                    speciesRoleId, record.cachedNameKey
            );
            if (speciesLabel == null || speciesLabel.isBlank()) {
                speciesLabel = speciesId;
            }
            int health = 0;
            int maxHealth = 0;
            int happiness = 0;
            int maxHappiness = 0;
            int targetHappinessPercent = 0;
            String happinessModifierBreakdown = null;
            int hunger = 0;
            int maxHunger = 0;
            int thirst = 0;
            int maxThirst = 0;
            boolean breedingEnabled = record.breedingEnabled;
            boolean breedingCooldownActive = false;
            long breedingCooldownRemainingMs = 0L;
            double breedingCooldownRatio = 0.0;
            boolean breedingCooldownKnown = false;
            boolean harvestCooldownActive = false;
            long harvestCooldownRemainingMs = 0L;
            double harvestCooldownRatio = 0.0;
            boolean harvestCooldownKnown = false;
            boolean recallPending = false;
            long recallLostRemainingMs = 0L;
            LinkedNpcEntry.FutureStat futureStatA = null;
            LinkedNpcEntry.FutureStat futureStatB = null;
            LinkedNpcTraitIndicator[] traitIndicators = LinkedNpcTraitIndicator.EMPTY;
            boolean talentsActionVisible = false;
            boolean talentsActionEnabled = false;
            if (dead) {
                TwCompanionConfig.EffectiveSettings effectiveSettings =
                        TwCompanionConfig.resolveEffectiveForRole(record.cachedRoleId);
                boolean deadRespawnEnabled = TameworkRuntimeSettings.reviveSystemEnabled(
                        effectiveSettings.isDeadRespawnEnabled()
                );
                if (!deadRespawnEnabled) {
                    deadRespawnRemainingMs = -1L;
                } else if (canonicalProfile != null) {
                    deadRespawnRemainingMs = remainingUntil(
                            canonicalProfile.restorationAvailableAtMs(),
                            System.currentTimeMillis()
                    );
                }
            }
            if (!dead && !captured && !inCoop && world != null) {
                LinkedNpcEntry loadedEntry = buildLoadedEntry(
                        player, world, store, record, displayName, linked, ownedViewLinkedIds != null, active, hasHome,
                        breedingEnabled, groupId, groupName, groupColor
                );
                if (loadedEntry == null && liveTargetResolver != null) {
                    LinkedNpcRecord redirected = liveTargetResolver.resolveRedirect(record);
                    loadedEntry = buildLoadedEntry(
                            player, world, store, redirected, displayName, linked, ownedViewLinkedIds != null, active, hasHome,
                            breedingEnabled, groupId, groupName, groupColor
                    );
                }
                if (loadedEntry != null) {
                    entries.add(loadedEntry);
                    renderedIds.put(record.npcUuid, loadedEntry.npcUuid());
                    continue;
                }
            }
            if (!loaded && !dead && !captured && !inCoop && !lost && relocationService != null) {
                CommandNpcRelocationService.PendingRecallSnapshot pendingRecall =
                        relocationService.getPendingRecallSnapshot(record.npcUuid);
                if (pendingRecall != null) {
                    recallPending = true;
                    recallLostRemainingMs =
                            pendingRecall.remainingUntilDropMs();
                }
            }
            LinkedNpcEntry entry = new LinkedNpcEntry(
                    record.npcUuid,
                    displayName,
                    gender,
                    health,
                    maxHealth,
                    happiness,
                    maxHappiness,
                    targetHappinessPercent,
                    happinessModifierBreakdown,
                    hunger,
                    maxHunger,
                    thirst,
                    maxThirst,
                    loaded,
                    hasHome,
                    dead,
                    captured,
                    inCoop,
                    lost,
                    deadRespawnRemainingMs,
                    deathCauseHint,
                    futureStatA,
                    futureStatB,
                    traitIndicators,
                    false,
                    false,
                    talentsActionVisible,
                    talentsActionEnabled,
                    linked,
                    active,
                    speciesId,
                    speciesLabel,
                    groupId,
                    groupName,
                    groupColor,
                    breedingEnabled,
                    breedingCooldownKnown,
                    breedingCooldownActive,
                    breedingCooldownRemainingMs,
                    breedingCooldownRatio,
                    breedingCooldownKnown,
                    harvestCooldownActive,
                    harvestCooldownRemainingMs,
                    harvestCooldownRatio,
                    harvestCooldownKnown,
                    recallPending,
                    recallLostRemainingMs
            ).withRoleSubtitle(npcNameResolver.resolveRoleSubtitle(
                    customName, speciesRoleId, record.cachedNameKey))
                    .withPortraitIcon(TwDynamicIconConfig.resolveIcon(
                            speciesRoleId, null));
            CommandSavedNpcPanelSnapshot saved = persistenceView == null ? null : persistenceView.savedPanel(record, player.getUuid());
            if (saved != null) {
                // Use the current world's configured clock when this animal belongs to it.
                // Other-world deadlines remain unknown without fetching stores or loading chunks.
                double gameRate = world != null && world.getName().equals(record.lastKnownWorldName)
                        ? BreedingTimeService.resolveCurrentGameSecondsPerRealSecond(store) : Double.NaN;
                entry = saved.apply(entry, player.getPlayerRef() == null ? null : player.getPlayerRef().getLanguage(), gameRate);
            }
            if (!entry.loaded() && !entry.dead() && !entry.lost()) {
                entry = entry.withLocation(location(player, record, entry, saved,
                        world == null ? null : world.getName(), viewerX, viewerZ));
            }
            entries.add(entry);
            renderedIds.put(record.npcUuid, entry.npcUuid());
        }
        return new ResolvedEntries(entries, renderedIds);
    }

    /** Uses saved evidence and observed item holders without querying live inventories. */
    private LinkedNpcEntry.Location location(Player player, LinkedNpcRecord record, LinkedNpcEntry entry,
                                             CommandSavedNpcPanelSnapshot saved,
                                             String viewerWorld, double viewerX, double viewerZ) {
        var stored = saved == null ? null : saved.storedLocation();
        String status;
        String world = "";
        String coordinates = "";
        double targetX = Double.NaN;
        double targetZ = Double.NaN;
        if (entry.captured()) {
            var sighting = stored == null || stored.capture() == null || itemTracker == null ? null
                    : itemTracker.index().find(stored.capture()).orElse(null);
            status = sighting == null
                    ? LocalizedText.resolve(player,
                            "tamework.ui.notifications.command.locate.captureUnknown")
                    : CommandLinkedNpcLocateService.describeSighting(player, sighting);
            if (sighting != null) {
                // These remain advisory observations; cards do not verify distant inventories.
                if (sighting.holder().kind() != CapturedItemLocationIndex.Kind.PLAYER) {
                    world = sighting.holder().worldName();
                    targetX = sighting.holder().x();
                    targetZ = sighting.holder().z();
                    coordinates = TameworkLinkedNpcLocationFormatter.formatCoordinates(
                            sighting.holder().x(), sighting.holder().y(), sighting.holder().z());
                }
            }
        } else if (entry.inCoop()) {
            status = LocalizedText.resolve(player,
                    "tamework.ui.notifications.command.locate.coop");
            if (stored != null && stored.coop() != null) {
                var coop = stored.coop();
                world = coop.worldKey();
                targetX = coop.x();
                targetZ = coop.z();
                coordinates = TameworkLinkedNpcLocationFormatter.formatCoordinates(
                        coop.x(), coop.y(), coop.z());
            }
        } else {
            status = "";
            if (record.lastKnownPosition != null) {
                world = record.lastKnownWorldName;
                targetX = record.lastKnownPosition.x;
                targetZ = record.lastKnownPosition.z;
                coordinates = TameworkLinkedNpcLocationFormatter.formatCoordinates(
                        record.lastKnownPosition.x, record.lastKnownPosition.y, record.lastKnownPosition.z);
            } else {
                status = LocalizedText.format(player,
                        "tamework.ui.notifications.command.locate.noLocation", entry.displayName());
            }
        }
        String relativeDistance = TameworkLinkedNpcLocationFormatter.formatRelativeDistance(
                player.getPlayerRef() == null ? null : player.getPlayerRef().getLanguage(),
                viewerWorld, viewerX, viewerZ, world, targetX, targetZ);
        if (!coordinates.isBlank()) {
            world = TameworkLinkedNpcLocationFormatter.formatDisplayWorldName(world,
                    LocalizedText.resolve(player,
                            "tamework.ui.notifications.command.locate.unknownWorld"));
        }
        return new LinkedNpcEntry.Location(status, world == null ? "" : world, coordinates, relativeDistance);
    }

    record ResolvedEntries(
            List<LinkedNpcEntry> entries,
            Map<UUID, UUID> renderedIds
    ) {
        ResolvedEntries {
            entries = List.copyOf(entries);
            renderedIds = Map.copyOf(renderedIds);
        }

        static ResolvedEntries empty() {
            return new ResolvedEntries(List.of(), Map.of());
        }
    }

    static long remainingUntil(long availableAtMs, long nowMs) {
        if (availableAtMs == 0L || availableAtMs <= nowMs) {
            return 0L;
        }
        long remaining = availableAtMs - nowMs;
        return remaining < 0L ? Long.MAX_VALUE : remaining;
    }

    @Nullable
    private LinkedNpcEntry buildLoadedEntry(Player player,
                                            World world,
                                            Store<EntityStore> store,
                                            @Nullable LinkedNpcRecord record,
                                            String displayName,
                                            boolean linked,
                                            boolean requireOwnedTarget,
                                            boolean active,
                                            boolean hasHome,
                                            boolean breedingEnabled,
                                            String groupId,
                                            String groupName,
                                            String groupColor) {
        if (record == null || record.npcUuid == null) {
            return null;
        }
        Ref<EntityStore> npcRef = world.getEntityRef(record.npcUuid);
        if (npcRef == null || !npcRef.isValid()
                || safeGetComponent(store, npcRef, NPCEntity.getComponentType()) == null) {
            return null;
        }
        if (requireOwnedTarget
                && (!CommandGenericTargetAuthority.allowsNearbyPresentation(npcRef, store)
                || !linkPolicyService.passesOwnerAndTamed(true, false,
                        npcRef, player.getUuid(), store))) {
            return null;
        }
        return loadedSnapshotService.buildLoadedEntry(
                player,
                npcRef,
                store,
                new CommandLoadedNpcStatusSnapshotService.NpcStatusContext(
                        record.npcUuid,
                        displayName,
                        linked,
                        active,
                        hasHome,
                        breedingEnabled,
                        groupId,
                        groupName,
                        groupColor,
                        record.cachedRoleId,
                        record.cachedNameKey
                )
        );
    }

    private String abbreviateUuid(UUID uuid) {
        if (uuid == null) {
            return "unknown";
        }
        String raw = uuid.toString();
        return raw.length() >= 8 ? raw.substring(0, 8) : raw;
    }

    @Nullable
    private String resolveSnapshotCustomName(LinkedNpcRecord record) {
        if (stateSnapshotService == null || record == null || record.npcUuid == null) {
            return null;
        }
        CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot snapshot =
                stateSnapshotService.getSnapshot(record.npcUuid);
        return snapshot == null ? null : snapshot.customName();
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String first, String second, String third) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        if (third != null && !third.isBlank()) {
            return third;
        }
        return null;
    }

    private <T extends Component<EntityStore>> T safeGetComponent(Store<EntityStore> store,
                                                                  Ref<EntityStore> npcRef,
                                                                  ComponentType<EntityStore, T> componentType) {
        if (store == null || npcRef == null || !npcRef.isValid() || componentType == null) {
            return null;
        }
        try {
            return store.getComponent(npcRef, componentType);
        } catch (IndexOutOfBoundsException | IllegalArgumentException ex) {
            return null;
        }
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private Map<String, CommandGroupService.GroupRecord> buildGroupLookup(ItemStack stack) {
        List<CommandGroupService.GroupRecord> groups = groupService.readGroups(stack);
        if (groups == null || groups.isEmpty()) {
            return Map.of();
        }
        HashMap<String, CommandGroupService.GroupRecord> out = new HashMap<>();
        for (CommandGroupService.GroupRecord group : groups) {
            if (group == null || group.groupId == null || group.groupId.isBlank()) {
                continue;
            }
            out.put(normalize(group.groupId), group);
        }
        return out;
    }

    private CommandGroupService.GroupRecord resolveGroup(Map<String, CommandGroupService.GroupRecord> lookup,
                                                         String groupId) {
        if (lookup == null || lookup.isEmpty() || groupId == null || groupId.isBlank()) {
            return null;
        }
        return lookup.get(normalize(groupId));
    }

}
