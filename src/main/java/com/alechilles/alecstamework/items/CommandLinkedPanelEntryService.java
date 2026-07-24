package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.localization.RoleNameResolver;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.LinkedNpcTraitIndicator;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.HashMap;
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
    private final CommandLinkedPanelUnloadedNameService unloadedNameService;
    private final CommandLinkPolicyService linkPolicyService;
    private final CommandGroupService groupService;
    private final CommandLinkedPanelProgressionPresentationService progressionPresentationService;
    private final CommandLinkedPanelCooldownSnapshotService cooldownSnapshotService;
    private final CommandLoadedNpcStatusSnapshotService loadedSnapshotService;
    private final CommandLinkedPanelLiveTargetResolver liveTargetResolver;
    private final CommandPersistenceView persistenceView;

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
        return buildEntriesFromRecords(player, store, stack, toolId, linkedNpcRecordStore.read(stack));
    }

    /** Builds from canonical records when item metadata is merely a disposable projection. */
    List<LinkedNpcEntry> buildEntriesFromRecords(Player player,
                                                 Store<EntityStore> store,
                                                 ItemStack stack,
                                                 String toolId,
                                                 List<LinkedNpcRecord> records) {
        if (player == null || store == null || stack == null || stack.isEmpty()) {
            return List.of();
        }
        if (records.isEmpty()) {
            return List.of();
        }
        Map<String, CommandGroupService.GroupRecord> groupById = buildGroupLookup(stack);
        World world = player.getWorld();
        ArrayList<LinkedNpcEntry> entries = new ArrayList<>(records.size());
        for (LinkedNpcRecord record : records) {
            if (record == null || record.npcUuid == null) {
                continue;
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
                displayName = firstNonBlank(
                        canonicalProfile.customName(),
                        canonicalProfile.displayName(),
                        displayName
                );
            }
            String gender = null;
            String speciesId = resolveCachedSpeciesId(record);
            String speciesLabel = speciesId;
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
                }
            }
            if (!dead && !captured && !inCoop && world != null) {
                LinkedNpcEntry loadedEntry = buildLoadedEntry(
                        player, world, store, record, displayName, active, hasHome,
                        breedingEnabled, groupId, groupName, groupColor
                );
                if (loadedEntry == null && liveTargetResolver != null) {
                    LinkedNpcRecord redirected = liveTargetResolver.resolveRedirect(record);
                    loadedEntry = buildLoadedEntry(
                            player, world, store, redirected, displayName, active, hasHome,
                            breedingEnabled, groupId, groupName, groupColor
                    );
                }
                if (loadedEntry != null) {
                    entries.add(loadedEntry);
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
                    true,
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
            );
            entries.add(entry);
        }
        return entries;
    }

    @Nullable
    private LinkedNpcEntry buildLoadedEntry(Player player,
                                            World world,
                                            Store<EntityStore> store,
                                            @Nullable LinkedNpcRecord record,
                                            String displayName,
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
        return loadedSnapshotService.buildLoadedEntry(
                player,
                npcRef,
                store,
                new CommandLoadedNpcStatusSnapshotService.NpcStatusContext(
                        record.npcUuid,
                        displayName,
                        true,
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

    private String resolveCachedSpeciesId(LinkedNpcRecord record) {
        if (record == null) {
            return null;
        }
        String roleId = firstNonBlank(
                record.cachedRoleId,
                RoleNameResolver.extractRoleIdFromNameKey(record.cachedNameKey),
                null
        );
        return normalize(roleId);
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
