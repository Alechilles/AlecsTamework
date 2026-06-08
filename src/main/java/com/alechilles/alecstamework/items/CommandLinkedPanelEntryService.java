package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.localization.RoleNameResolver;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessModifierService;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessService;
import com.alechilles.alecstamework.npc.progression.CompanionGenderService;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionTalentService;
import com.alechilles.alecstamework.npc.progression.NeedsConfigResolver;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.LinkedNpcTraitIndicator;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
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
    private final CommandNpcNameResolver npcNameResolver;
    private final CommandLinkPolicyService linkPolicyService;
    private final CommandGroupService groupService;
    private final CommandLinkedPanelProgressionPresentationService progressionPresentationService;

    CommandLinkedPanelEntryService(CommandLinkedNpcRecordStore linkedNpcRecordStore,
                                   CommandLinkedNpcDeathService deathService,
                                   CommandLinkedNpcCaptureService captureService,
                                   CommandLinkedNpcCoopService coopService,
                                   CommandLinkedNpcLostService lostService,
                                   CommandNpcNameResolver npcNameResolver,
                                   CommandLinkPolicyService linkPolicyService,
                                   CommandGroupService groupService) {
        this.linkedNpcRecordStore = linkedNpcRecordStore;
        this.deathService = deathService;
        this.captureService = captureService;
        this.coopService = coopService;
        this.lostService = lostService;
        this.npcNameResolver = npcNameResolver;
        this.linkPolicyService = linkPolicyService != null ? linkPolicyService : new CommandLinkPolicyService();
        this.groupService = groupService != null ? groupService : new CommandGroupService();
        this.progressionPresentationService = new CommandLinkedPanelProgressionPresentationService();
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
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
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
            String displayName = npcNameResolver.resolveCachedUnloadedDisplayName(record);
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
            LinkedNpcEntry.FutureStat futureStatA = null;
            LinkedNpcEntry.FutureStat futureStatB = null;
            LinkedNpcTraitIndicator[] traitIndicators = LinkedNpcTraitIndicator.EMPTY;
            boolean talentsActionVisible = false;
            boolean talentsActionEnabled = false;
            if (world != null) {
                Ref<EntityStore> npcRef = world.getEntityRef(record.npcUuid);
                if (npcRef != null && npcRef.isValid()) {
                    NPCEntity npc = safeGetComponent(store, npcRef, NPCEntity.getComponentType());
                    if (npc != null) {
                        loaded = true;
                        displayName = npcNameResolver.resolveNpcDisplayName(npcRef, store, npc);
                        String resolvedRoleId = resolveSpeciesRoleId(npc, record.cachedRoleId);
                        if (resolvedRoleId != null) {
                            speciesId = resolvedRoleId;
                            speciesLabel = resolvedRoleId;
                        }
                        gender = CompanionGenderService.resolveGender(npcRef, store, resolvedRoleId, null);
                        TameworkCommandLinksComponent links =
                                safeGetComponent(store, npcRef, TameworkCommandLinksComponent.getComponentType());
                        if (links != null && links.hasHome()) {
                            hasHome = true;
                        }
                        HealthSnapshot snapshot = readNpcHealthSnapshot(npcRef, store);
                        if (snapshot != null) {
                            health = snapshot.current;
                            maxHealth = snapshot.max;
                        }
                        HappinessSnapshot happinessSnapshot = readNpcHappinessSnapshot(
                                npcRef,
                                store,
                                playerLanguage
                        );
                        if (happinessSnapshot != null) {
                            happiness = happinessSnapshot.current;
                            maxHappiness = happinessSnapshot.max;
                            targetHappinessPercent = happinessSnapshot.targetPercent;
                            happinessModifierBreakdown = happinessSnapshot.modifierBreakdown;
                        }
                        NeedsSnapshot needsSnapshot = readNpcNeedsSnapshot(npcRef, store, needsType);
                        if (needsSnapshot != null) {
                            hunger = needsSnapshot.hungerCurrent;
                            maxHunger = needsSnapshot.hungerMax;
                            thirst = needsSnapshot.thirstCurrent;
                            maxThirst = needsSnapshot.thirstMax;
                        }
                        BreedingCooldownSnapshot breedingSnapshot = readBreedingCooldownSnapshot(npcRef, store, speciesId);
                        if (breedingSnapshot != null) {
                            breedingEnabled = breedingSnapshot.enabled;
                            breedingCooldownKnown = breedingSnapshot.known;
                            breedingCooldownActive = breedingSnapshot.active;
                            breedingCooldownRemainingMs = breedingSnapshot.remainingMs;
                            breedingCooldownRatio = breedingSnapshot.ratio;
                        }
                        CompanionLevelingService.LevelingSnapshot levelingSnapshot =
                                CompanionLevelingService.resolveSnapshot(npcRef, store, resolvedRoleId);
                        if (levelingSnapshot != null) {
                            futureStatA = progressionPresentationService.buildLevelFutureStat(
                                    levelingSnapshot,
                                    playerLanguage,
                                    progressionPresentationService.buildModifierTooltip(npcRef, store, npc, playerLanguage)
                            );
                        }
                        TwTalentConfig talentConfig = CompanionTalentService.resolveTalentConfig(npcRef, store);
                        if (talentConfig != null && talentConfig.isEnabled()) {
                            int availableTalentPoints = CompanionTalentService.resolveAvailablePoints(npcRef, store);
                            int totalEarnedTalentPoints = levelingSnapshot != null
                                    ? CompanionLevelingService.resolveEarnedTalentPoints(
                                    levelingSnapshot.level(),
                                    levelingSnapshot.configId()
                            )
                                    : 0;
                            futureStatB = progressionPresentationService.buildTalentPointFutureStat(
                                    availableTalentPoints,
                                    totalEarnedTalentPoints,
                                    playerLanguage
                            );
                            talentsActionVisible = true;
                            talentsActionEnabled = true;
                        }
                        traitIndicators = progressionPresentationService.readLoadedTraitIndicators(npcRef, store, playerLanguage);
                    }
                }
            }
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
            if (!loaded && !dead && !captured && !inCoop && lostService != null) {
                CommandLinkedNpcLostService.LostLinkedNpcSnapshot lostSnapshot =
                        lostService.getLostSnapshot(record.npcUuid);
                if (lostSnapshot != null) {
                    lost = true;
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
                    breedingCooldownActive,
                    breedingCooldownRemainingMs,
                    breedingCooldownRatio,
                    breedingCooldownKnown
            ));
        }
        return entries;
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

    LinkedNpcTraitIndicator[] readLoadedTraitIndicators(Ref<EntityStore> npcRef,
                                                        Store<EntityStore> store) {
        return progressionPresentationService.readLoadedTraitIndicators(npcRef, store);
    }

    LinkedNpcTraitIndicator[] readLoadedTraitIndicators(Ref<EntityStore> npcRef,
                                                        Store<EntityStore> store,
                                                        String language) {
        return progressionPresentationService.readLoadedTraitIndicators(npcRef, store, language);
    }

    private HealthSnapshot readNpcHealthSnapshot(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        ComponentType<EntityStore, EntityStatMap> statType = EntityStatMap.getComponentType();
        if (statType == null) {
            return null;
        }
        EntityStatMap statMap = safeGetComponent(store, npcRef, statType);
        if (statMap == null) {
            return null;
        }
        int healthIndex = EntityStatType.getAssetMap().getIndex("Health");
        if (healthIndex < 0) {
            return null;
        }
        EntityStatValue value = statMap.get(healthIndex);
        if (value == null) {
            return null;
        }
        int current = Math.max(0, Math.round(value.get()));
        int max = Math.max(1, Math.round(value.getMax()));
        if (current > max) {
            current = max;
        }
        return new HealthSnapshot(current, max);
    }

    private HappinessSnapshot readNpcHappinessSnapshot(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            String language) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        CompanionHappinessService.HappinessSnapshot snapshot = CompanionHappinessService.resolveSnapshot(npcRef, store);
        if (snapshot == null) {
            return null;
        }
        double value = clamp(snapshot.value(), snapshot.min(), snapshot.max());
        double max = Math.max(1.0, snapshot.max());
        int roundedMax = Math.max(1, Math.round((float) max));
        int roundedValue = Math.max(0, Math.min(roundedMax, Math.round((float) value)));
        int targetPercent = computePercent(snapshot.target(), snapshot.min(), snapshot.max());
        String modifierBreakdown = buildHappinessModifierBreakdown(snapshot, language);
        return new HappinessSnapshot(roundedValue, roundedMax, targetPercent, modifierBreakdown);
    }

    private String buildHappinessModifierBreakdown(CompanionHappinessService.HappinessSnapshot snapshot) {
        return buildHappinessModifierBreakdown(snapshot, null);
    }

    private String buildHappinessModifierBreakdown(CompanionHappinessService.HappinessSnapshot snapshot,
                                                   String language) {
        if (snapshot == null) {
            return null;
        }
        ArrayList<String> modifierLines = new ArrayList<>();
        for (CompanionHappinessModifierService.ModifierEntry modifier : snapshot.modifiers()) {
            if (modifier == null || !Double.isFinite(modifier.value())) {
                continue;
            }
            if (Math.abs(modifier.value()) <= 0.000001) {
                continue;
            }
            String label = resolveModifierLabel(modifier, language);
            modifierLines.add(label + ": " + formatSigned(modifier.value()));
        }

        ArrayList<String> impulseLines = new ArrayList<>();
        for (CompanionHappinessService.ActiveImpulseSnapshot activeImpulse : snapshot.activeImpulses()) {
            if (activeImpulse == null || !Double.isFinite(activeImpulse.value())) {
                continue;
            }
            if (Math.abs(activeImpulse.value()) <= 0.000001) {
                continue;
            }
            String label = resolveImpulseLabel(activeImpulse, language);
            impulseLines.add(label + ": " + formatSigned(activeImpulse.value()));
        }

        if (modifierLines.isEmpty() && impulseLines.isEmpty()) {
            return null;
        }
        ArrayList<String> lines = new ArrayList<>(modifierLines.size() + impulseLines.size());
        lines.addAll(modifierLines);
        lines.addAll(impulseLines);
        return String.join("\n", lines);
    }

    private String resolveModifierLabel(CompanionHappinessModifierService.ModifierEntry modifier,
                                        String language) {
        if (modifier == null) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.modifier.generic");
        }
        String modifierId = normalize(modifier.id());
        if ("owner_nearby".equals(modifierId)) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.modifier.ownerNearby");
        }
        String label = modifier.label();
        if (label == null || label.isBlank()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.modifier.generic");
        }
        String stripped = stripModifierPrefix(label);
        return LocalizedText.resolveConfigValue(language, stripped, stripped);
    }

    private String resolveImpulseLabel(CompanionHappinessService.ActiveImpulseSnapshot activeImpulse,
                                       String language) {
        String key = normalize(activeImpulse.key());
        if ("feed:hand".equals(key)) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.impulse.handFed");
        }
        if (key != null && key.startsWith("feed:")) {
            String itemName = resolveItemDisplayName(language, activeImpulse.itemId());
            return LocalizedText.format(
                    language,
                    "tamework.ui.linkedPanel.happiness.impulse.ate",
                    itemName
            );
        }
        if ("pet".equals(key)) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.impulse.petted");
        }
        if ("damage".equals(key)) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.impulse.attacked");
        }
        String label = activeImpulse.label();
        if (label == null || label.isBlank()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.impulse.generic");
        }
        String resolvedLabel = LocalizedText.resolveConfigValue(language, label, label);
        String itemId = activeImpulse.itemId();
        if (itemId != null && !itemId.isBlank()) {
            return resolvedLabel + " " + resolveItemDisplayName(language, itemId);
        }
        return resolvedLabel;
    }

    private String resolveItemDisplayName(String language, String itemId) {
        String canonicalItemId = sanitizeItemId(itemId);
        if (canonicalItemId == null || canonicalItemId.isBlank()) {
            return LocalizedText.resolve(language, "tamework.ui.linkedPanel.happiness.food.unknown");
        }
        String itemNameKey = "items." + canonicalItemId + ".name";
        String localizedFromKey = LocalizedText.resolve(language, itemNameKey);
        if (localizedFromKey != null
                && !localizedFromKey.isBlank()
                && !itemNameKey.equals(localizedFromKey)) {
            return localizedFromKey;
        }
        try {
            Item itemAsset = Item.getAssetMap().getAsset(canonicalItemId);
            if (itemAsset != null && itemAsset.getTranslationKey() != null && !itemAsset.getTranslationKey().isBlank()) {
                String translated = LocalizedText.resolve(language, itemAsset.getTranslationKey());
                if (translated != null && !translated.isBlank() && !translated.equals(itemAsset.getTranslationKey())) {
                    return translated;
                }
            }
        } catch (Throwable ignored) {
            // Some tests and degraded startup paths do not initialize Hytale item assets/logging.
        }
        return humanizeItemId(canonicalItemId);
    }

    private String humanizeItemId(String canonicalItemId) {
        if (canonicalItemId == null || canonicalItemId.isBlank()) {
            return LocalizedText.resolve((String) null, "tamework.ui.linkedPanel.happiness.food.unknown");
        }
        String normalized = canonicalItemId;
        if (normalized.startsWith("Tw_")) {
            normalized = normalized.substring(3);
        }
        String[] rawParts = normalized.split("_");
        ArrayList<String> parts = new ArrayList<>(rawParts.length);
        for (String rawPart : rawParts) {
            if (rawPart == null || rawPart.isBlank()) {
                continue;
            }
            parts.add(rawPart);
        }
        if (parts.isEmpty()) {
            return canonicalItemId;
        }
        if (parts.size() > 1 && parts.get(0).equalsIgnoreCase("Feed")) {
            String feed = parts.remove(0);
            parts.add(feed);
        }
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return out.toString();
    }

    private String stripModifierPrefix(String label) {
        if (label == null || label.isBlank()) {
            return LocalizedText.resolve((String) null, "tamework.ui.linkedPanel.happiness.modifier.generic");
        }
        if (label.regionMatches(true, 0, "Hunger:", 0, "Hunger:".length())) {
            return label.substring("Hunger:".length()).trim();
        }
        if (label.regionMatches(true, 0, "Thirst:", 0, "Thirst:".length())) {
            return label.substring("Thirst:".length()).trim();
        }
        if (label.regionMatches(true, 0, "Population:", 0, "Population:".length())) {
            return label.substring("Population:".length()).trim();
        }
        return label.trim();
    }

    private String sanitizeItemId(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        String sanitized = itemId.trim();
        if (sanitized.startsWith("*")) {
            sanitized = sanitized.substring(1);
        }
        int stateIndex = sanitized.indexOf("_State_");
        if (stateIndex > 0) {
            sanitized = sanitized.substring(0, stateIndex);
        }
        return sanitized.isBlank() ? null : sanitized;
    }

    private int computePercent(double value, double min, double max) {
        if (!Double.isFinite(value) || !Double.isFinite(min) || !Double.isFinite(max) || max <= min) {
            return 0;
        }
        double ratio = (clamp(value, min, max) - min) / (max - min);
        return Math.max(0, Math.min(100, Math.round((float) (ratio * 100.0))));
    }

    private NeedsSnapshot readNpcNeedsSnapshot(Ref<EntityStore> npcRef,
                                               Store<EntityStore> store,
                                               ComponentType<EntityStore, TameworkNeedsComponent> needsType) {
        if (npcRef == null || !npcRef.isValid() || store == null || needsType == null) {
            return null;
        }
        TameworkNeedsComponent needs = safeGetComponent(store, npcRef, needsType);
        if (needs == null) {
            return null;
        }
        TwNeedsConfig config = NeedsConfigResolver.resolveConfig(npcRef, store, needs);
        if (config == null || !config.isEnabled()) {
            return null;
        }
        TwNeedsConfig.ValueSettings values = config.getValues();
        double hungerMin = values.getHungerMin();
        double hungerMax = values.getHungerMax();
        double thirstMin = values.getThirstMin();
        double thirstMax = values.getThirstMax();

        int roundedHungerMax = Math.max(1, Math.round((float) hungerMax));
        int roundedThirstMax = Math.max(1, Math.round((float) thirstMax));
        int roundedHunger = Math.max(
                0,
                Math.min(roundedHungerMax, Math.round((float) clamp(needs.getHunger(), hungerMin, hungerMax)))
        );
        int roundedThirst = Math.max(
                0,
                Math.min(roundedThirstMax, Math.round((float) clamp(needs.getThirst(), thirstMin, thirstMax)))
        );
        return new NeedsSnapshot(roundedHunger, roundedHungerMax, roundedThirst, roundedThirstMax);
    }

    private String abbreviateUuid(UUID uuid) {
        if (uuid == null) {
            return "unknown";
        }
        String raw = uuid.toString();
        return raw.length() >= 8 ? raw.substring(0, 8) : raw;
    }

    private double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
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

    private String resolveSpeciesRoleId(NPCEntity npc, String fallbackRoleId) {
        String roleId = firstNonBlank(
                linkPolicyService.resolveRoleId(npc),
                npcNameResolver.resolveNpcRoleId(npc),
                fallbackRoleId
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

    private String formatSigned(double value) {
        if (!Double.isFinite(value)) {
            return "0.00";
        }
        return String.format(Locale.ROOT, "%+.2f", value);
    }

    private BreedingCooldownSnapshot readBreedingCooldownSnapshot(Ref<EntityStore> npcRef,
                                                                  Store<EntityStore> store,
                                                                  String resolvedRoleId) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        if (breedingType == null) {
            return null;
        }
        TameworkBreedingComponent breeding = safeGetComponent(store, npcRef, breedingType);
        if (breeding == null) {
            return new BreedingCooldownSnapshot(false, false, false, 0L, 0.0);
        }
        long now = BreedingTimeService.resolveCurrentTimeMs(store);
        long until = breeding.getCooldownUntilMs();
        boolean active = until != 0L && now < until;
        long remainingGameMs = active ? Math.max(0L, until - now) : 0L;
        long remainingRealMs = active
                ? BreedingTimeService.toEstimatedRealDurationMs(remainingGameMs, store)
                : 0L;
        double ratio = active
                ? resolveBreedingCooldownRatio(breeding, npcRef, store, resolvedRoleId, remainingGameMs)
                : 1.0;
        return new BreedingCooldownSnapshot(true, breeding.isEnabled(), active, remainingRealMs, ratio);
    }

    private double resolveBreedingCooldownRatio(TameworkBreedingComponent breeding,
                                                Ref<EntityStore> npcRef,
                                                Store<EntityStore> store,
                                                String resolvedRoleId,
                                                long remainingMs) {
        long knownDurationMs = 0L;
        if (breeding != null) {
            knownDurationMs = Math.max(0L, breeding.getCooldownDurationMs());
            if (knownDurationMs <= 0L) {
                long startedAtMs = breeding.getCooldownStartedAtMs();
                long untilMs = breeding.getCooldownUntilMs();
                if (startedAtMs > 0L && untilMs > startedAtMs) {
                    knownDurationMs = untilMs - startedAtMs;
                }
            }
        }
        if (knownDurationMs > 0L) {
            double progress = 1.0 - ((double) remainingMs / (double) knownDurationMs);
            return clamp(progress, 0.0, 1.0);
        }

        TwBreedingConfig config = null;
        if (breeding != null && breeding.getConfigId() != null && !breeding.getConfigId().isBlank()) {
            config = TwBreedingConfig.resolveById(breeding.getConfigId());
        }
        if (config == null) {
            String roleId = resolvedRoleId;
            if (roleId == null || roleId.isBlank()) {
                roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
            }
            config = TwBreedingConfig.resolveForRole(roleId);
        }
        String roleId = resolvedRoleId;
        if (roleId == null || roleId.isBlank()) {
            roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        }
        if (config == null || config.resolveCooldowns(roleId) == null || config.resolveTiming(roleId) == null) {
            return 0.0;
        }
        long baseDurationMs = BreedingTimeService.toGameDurationMs(
                config.resolveCooldowns(roleId).getBaseCooldownSeconds(),
                config.resolveTiming(roleId).getTimerBasis(),
                store
        );
        if (baseDurationMs <= 0L) {
            return 0.0;
        }
        double progress = 1.0 - ((double) remainingMs / (double) baseDurationMs);
        return clamp(progress, 0.0, 1.0);
    }

    private static final class HealthSnapshot {
        private final int current;
        private final int max;

        private HealthSnapshot(int current, int max) {
            this.current = current;
            this.max = max;
        }
    }

    private static final class HappinessSnapshot {
        private final int current;
        private final int max;
        private final int targetPercent;
        private final String modifierBreakdown;

        private HappinessSnapshot(int current, int max, int targetPercent, String modifierBreakdown) {
            this.current = current;
            this.max = max;
            this.targetPercent = Math.max(0, Math.min(100, targetPercent));
            this.modifierBreakdown = modifierBreakdown;
        }
    }

    private static final class NeedsSnapshot {
        private final int hungerCurrent;
        private final int hungerMax;
        private final int thirstCurrent;
        private final int thirstMax;

        private NeedsSnapshot(int hungerCurrent, int hungerMax, int thirstCurrent, int thirstMax) {
            this.hungerCurrent = hungerCurrent;
            this.hungerMax = hungerMax;
            this.thirstCurrent = thirstCurrent;
            this.thirstMax = thirstMax;
        }
    }

    private static final class BreedingCooldownSnapshot {
        private final boolean known;
        private final boolean enabled;
        private final boolean active;
        private final long remainingMs;
        private final double ratio;

        private BreedingCooldownSnapshot(boolean known,
                                        boolean enabled,
                                        boolean active,
                                        long remainingMs,
                                        double ratio) {
            this.known = known;
            this.enabled = enabled;
            this.active = active;
            this.remainingMs = Math.max(0L, remainingMs);
            this.ratio = Math.max(0.0, Math.min(1.0, ratio));
        }
    }
}
