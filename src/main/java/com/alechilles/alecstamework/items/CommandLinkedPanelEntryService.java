package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.localization.RoleNameResolver;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
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
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Builds linked-companion panel entries for command-item UI.
 *
 * <p>This service isolates panel-oriented data assembly (loaded/dead/captured status, display names,
 * health snapshots, and home flags) from command orchestration flows.
 */
final class CommandLinkedPanelEntryService {
    private final CommandLinkedNpcRecordStore linkedNpcRecordStore;
    private final CommandLinkedNpcDeathService deathService;
    private final CommandLinkedNpcCaptureService captureService;
    private final CommandLinkedNpcCoopService coopService;
    private final CommandLinkedNpcLostService lostService;
    private final CommandNpcRelocationService relocationService;
    private final CommandNpcNameResolver npcNameResolver;
    private final CommandLinkedPanelUnloadedNameService unloadedNameService;
    private final CommandLinkPolicyService linkPolicyService;
    private final CommandGroupService groupService;
    private final CommandLinkedPanelProgressionPresentationService progressionPresentationService;
    private final CommandLinkedPanelCooldownSnapshotService cooldownSnapshotService;
    private final CommandLoadedNpcStatusSnapshotService loadedSnapshotService;
    private final CommandLinkedPanelLiveTargetResolver liveTargetResolver;

    CommandLinkedPanelEntryService(CommandLinkedNpcRecordStore linkedNpcRecordStore,
                                   CommandLinkedNpcDeathService deathService,
                                   CommandLinkedNpcCaptureService captureService,
                                   CommandLinkedNpcCoopService coopService,
                                   CommandLinkedNpcLostService lostService,
                                   CommandNpcRelocationService relocationService,
                                   CommandNpcNameResolver npcNameResolver,
                                   @Nullable CommandLinkedNpcStateSnapshotService stateSnapshotService,
                                   @Nullable NpcProfileRepository profileRepository,
                                   CommandLinkPolicyService linkPolicyService,
                                   CommandGroupService groupService,
                                   @Nullable CommandNpcProfileActionResolver profileActionResolver) {
        this.linkedNpcRecordStore = linkedNpcRecordStore;
        this.deathService = deathService;
        this.captureService = captureService;
        this.coopService = coopService;
        this.lostService = lostService;
        this.relocationService = relocationService;
        this.npcNameResolver = npcNameResolver;
        this.unloadedNameService = new CommandLinkedPanelUnloadedNameService(
                this.npcNameResolver, stateSnapshotService, profileRepository
        );
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
        List<LinkedNpcRecord> records = linkedNpcRecordStore.read(stack);
        if (records.isEmpty()) {
            return List.of();
        }
        Map<String, CommandGroupService.GroupRecord> groupById = buildGroupLookup(stack);
        World world = player.getWorld();
        String playerLanguage = player.getPlayerRef() != null ? player.getPlayerRef().getLanguage() : null;
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
            if (!loaded && deathService != null) {
                CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot deadSnapshot = deathService.getDeadSnapshotForTool(
                        record.npcUuid,
                        toolId,
                        player.getUuid()
                );
                if (deadSnapshot != null) {
                    dead = true;
                    String deadName = npcNameResolver.resolveSnapshotDisplayName(
                            deadSnapshot.displayName(),
                            record.cachedNameKey,
                            deadSnapshot.roleId()
                    );
                    if (deadName != null && !deadName.isBlank()) {
                        displayName = deadName;
                    }
                    String roleId = deadSnapshot.roleId();
                    if ((roleId == null || roleId.isBlank()) && record.cachedRoleId != null && !record.cachedRoleId.isBlank()) {
                        roleId = record.cachedRoleId;
                    }
                    String normalizedRoleId = normalize(roleId);
                    if (normalizedRoleId != null) {
                        speciesId = normalizedRoleId;
                        speciesLabel = normalizedRoleId;
                    }
                    boolean deadRespawnEnabled = TameworkRuntimeSettings.reviveSystemEnabled(
                            TwCompanionConfig.resolveEffectiveForRole(roleId).isDeadRespawnEnabled()
                    );
                    if (deadRespawnEnabled) {
                        deadRespawnRemainingMs = Math.max(0L, deadSnapshot.respawnAvailableAtMs() - System.currentTimeMillis());
                    } else {
                        deadRespawnRemainingMs = -1L;
                    }
                    deathCauseHint = resolveDeathCauseHint(deadSnapshot, playerLanguage);
                }
            }
            if (!loaded && !dead && captureService != null) {
                CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot capturedSnapshot =
                        captureService.getCapturedSnapshotForToolOrOwner(record.npcUuid, toolId, player.getUuid());
                if (capturedSnapshot != null) {
                    captured = true;
                    String capturedName = npcNameResolver.resolveSnapshotDisplayName(
                            capturedSnapshot.displayName(),
                            record.cachedNameKey,
                            capturedSnapshot.roleId()
                    );
                    if (capturedName != null && !capturedName.isBlank()) {
                        displayName = capturedName;
                    }
                }
            }
            if (!loaded && !dead && !captured && coopService != null) {
                CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot coopSnapshot =
                        coopService.getCoopSnapshotForToolOrOwner(record.npcUuid, toolId, player.getUuid());
                if (coopSnapshot != null) {
                    inCoop = true;
                    String coopName = npcNameResolver.resolveSnapshotDisplayName(
                            coopSnapshot.displayName(),
                            record.cachedNameKey,
                            coopSnapshot.roleId()
                    );
                    if (coopName != null && !coopName.isBlank()) {
                        displayName = coopName;
                    }
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
            if (!loaded && !dead && !captured && !inCoop && lostService != null) {
                CommandLinkedNpcLostService.LostLinkedNpcSnapshot lostSnapshot =
                        lostService.getLostSnapshot(record.npcUuid);
                if (lostSnapshot != null) {
                    lost = true;
                }
            }
            if (!loaded && !dead && !captured && !inCoop && !lost && relocationService != null) {
                CommandNpcRelocationService.PendingRecallSnapshot pendingRecall =
                        relocationService.getPendingRecallSnapshot(record.npcUuid);
                if (pendingRecall != null) {
                    recallPending = true;
                    recallLostRemainingMs = pendingRecall.remainingUntilLostMs();
                }
            }
            entries.add(new LinkedNpcEntry(
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
            ));
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

    @Nullable
    private String resolveDeathCauseHint(@Nullable CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot,
                                         @Nullable String language) {
        if (snapshot == null || snapshot.deathCauseKind() == null) {
            return null;
        }
        return switch (snapshot.deathCauseKind()) {
            case STARVATION -> LocalizedText.resolve(language, "tamework.ui.linkedPanel.deathCause.starvation");
            case DEHYDRATION -> LocalizedText.resolve(language, "tamework.ui.linkedPanel.deathCause.dehydration");
            case STARVATION_AND_DEHYDRATION ->
                    LocalizedText.resolve(language, "tamework.ui.linkedPanel.deathCause.starvationAndDehydration");
            case PLAYER -> LocalizedText.format(
                    language,
                    "tamework.ui.linkedPanel.deathCause.killedByPlayer",
                    fallbackDeathSourceName(snapshot.deathSourceName(), language, true)
            );
            case NPC -> LocalizedText.format(
                    language,
                    "tamework.ui.linkedPanel.deathCause.killedByNpc",
                    fallbackDeathSourceName(snapshot.deathSourceName(), language, false)
            );
            case ENVIRONMENT -> LocalizedText.resolve(language, "tamework.ui.linkedPanel.deathCause.environment");
            case UNKNOWN -> LocalizedText.resolve(language, "tamework.ui.linkedPanel.deathCause.unknown");
        };
    }

    @Nonnull
    private String fallbackDeathSourceName(@Nullable String sourceName, @Nullable String language, boolean player) {
        if (sourceName != null && !sourceName.isBlank()) {
            return sourceName;
        }
        return LocalizedText.resolve(
                language,
                player
                        ? "tamework.ui.linkedPanel.deathCause.killer.playerFallback"
                        : "tamework.ui.linkedPanel.deathCause.killer.npcFallback"
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
